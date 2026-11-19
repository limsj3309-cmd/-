package kr.golfmacro.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 노크타운 화면을 읽고 눌러서 골프 연습장 일일타석을 예약한다.
 *
 * 경로: 홈 → "커뮤니티 시설 예약하기" → "골프 연습장" → "예약하기"
 *      → "일일타석 예약" → (오픈 시각까지 대기) → "새로고침" → 목표 시간 칸 → 예약 버튼 → 확인
 *
 * 화면 전환 이벤트에 의존하지 않고 0.12초마다 현재 화면을 판별해서 다음 동작을 한다.
 * 그래서 중간에 화면이 늦게 뜨거나 팝업이 끼어도 계속 진행한다.
 */
public class MacroService extends AccessibilityService {

    static final String PKG = "io.lifeknock.app";
    static volatile MacroService instance;

    // 화면 종류
    private static final String S_HOME = "홈";
    private static final String S_COMMUNITY = "커뮤니티";
    private static final String S_GOLF = "골프연습장 상세";
    private static final String S_SHEET = "서비스 선택창";
    private static final String S_GRID = "타석/시간 선택";
    private static final String S_UNKNOWN = "알 수 없는 화면";

    // 단계
    private static final int PH_PICK = 0;    // 이동 + 시간 고르기
    private static final int PH_CONFIRM = 1; // 시간 고른 뒤 예약/확인 버튼

    private static final String[] CONFIRM_WORDS = {
            "예약하기", "예약 하기", "예약신청", "예약 신청", "신청하기", "예약 완료하기",
            "결제하기", "다음", "확인", "예"
    };
    private static final String[] SUCCESS_WORDS = {
            "예약이 완료", "예약 완료", "예약완료", "예약되었습니다", "완료되었습니다", "신청이 완료", "신청되었습니다"
    };
    private static final String[] FAIL_WORDS = {
            "이미 예약", "마감", "실패", "불가", "초과", "선택할 수 없", "다른 시간"
    };
    private static final String[] POPUP_CLOSE_WORDS = {
            "오늘 하루 보지 않기", "오늘 그만 보기", "다시 보지 않기", "닫기"
    };

    private final Handler h = new Handler(Looper.getMainLooper());

    // 실행 상태
    private boolean running;
    private boolean dry;
    private String label = "";
    private String target = "20:00";
    private long openAt, deadline, startedAt;
    private int clickDelayMs;
    private int phase;
    private long nextActionAt;
    private long lastRefreshAt;
    private int refreshCount;
    private int scrollTries;
    private int confirmClicks;
    private long confirmStartedAt;
    private String lastScreen = "";
    private long screenSince;
    private int sameScreenClicks;
    private boolean stuckDumped;
    private long lastSeenAppAt;
    private int relaunches;
    private boolean waitLogged;
    private String chosenRow = "";
    private final Set<String> tried = new HashSet<>();
    private Set<String> preClickTexts = new HashSet<>();
    private PowerManager.WakeLock wl;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onServiceConnected() {
        instance = this;
        L.i(this, "접근성 서비스 연결됨");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        stopRun();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) { /* 폴링 방식이라 사용 안 함 */ }

    @Override
    public void onInterrupt() { }

    // ---------------------------------------------------------------- public API

    static void launchNoktown(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(PKG);
        if (i == null) {
            L.i(c, "노크타운 앱을 찾을 수 없습니다.");
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        c.startActivity(i);
    }

    boolean isRunning() { return running; }

    /**
     * @param openAt 예약이 열리는 시각(ms). 이 시각 전에는 시간표 화면에서 기다린다.
     * @param dry    true 면 시간 칸 선택까지만 하고 최종 예약 버튼은 누르지 않는다.
     */
    void startRun(long openAt, String target, boolean dry, String label) {
        if (running) {
            L.i(this, "이미 실행 중이라 새 실행은 무시합니다.");
            return;
        }
        long now = System.currentTimeMillis();
        Prefs p = new Prefs(this);
        this.running = true;
        this.dry = dry;
        this.label = label;
        this.target = target;
        this.openAt = openAt;
        this.startedAt = now;
        this.deadline = Math.max(openAt, now) + 60_000L;
        this.clickDelayMs = p.clickDelayMs();
        this.phase = PH_PICK;
        this.nextActionAt = now + 1500; // 앱이 뜰 시간
        this.lastRefreshAt = 0;
        this.refreshCount = 0;
        this.scrollTries = 0;
        this.confirmClicks = 0;
        this.lastScreen = "";
        this.screenSince = now;
        this.sameScreenClicks = 0;
        this.lastSeenAppAt = now;
        this.relaunches = 0;
        this.waitLogged = false;
        this.chosenRow = "";
        this.tried.clear();

        PowerManager pm = getSystemService(PowerManager.class);
        //noinspection deprecation
        wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "golfmacro:run");
        wl.acquire(4 * 60_000L);

        L.i(this, "▶ 시작 [" + label + "] 목표 " + target + (dry ? " (테스트: 최종 버튼은 안 누름)" : "")
                + ", 오픈 " + MainActivity.fmtMs(openAt));
        h.removeCallbacksAndMessages(null);
        h.post(this::tick);
    }

    void stopRun() {
        running = false;
        h.removeCallbacksAndMessages(null);
        if (wl != null && wl.isHeld()) wl.release();
        wl = null;
    }

    /** delayMs 뒤 현재 화면 구조를 파일로 저장한다(설정 맞추기용). */
    void dumpLater(long delayMs) {
        h.postDelayed(() -> {
            saveDump("수동 저장");
            Notifier.show(this, "화면 구조 저장 완료", "골프예약 매크로 앱에서 '문제 보고 공유'를 누르세요.");
        }, delayMs);
    }

    private void saveDump(String why) {
        String d = "(" + why + ")\n" + dumpTree();
        try {
            L.writeFile(new File(getFilesDir(), "dump.txt"), d, false);
            L.i(this, "화면 구조 저장 [" + why + "] (" + d.length() + "자)");
        } catch (Exception e) {
            L.i(this, "화면 구조 저장 실패: " + e);
        }
    }

    // ---------------------------------------------------------------- main loop

    private void tick() {
        if (!running) return;
        try {
            step(System.currentTimeMillis());
        } catch (Exception e) {
            L.i(this, "오류: " + e);
        }
        if (running) h.postDelayed(this::tick, 120);
    }

    private void step(long now) {
        if (now > deadline) {
            finish(false, "시간 초과 — " + target + " 예약을 끝내지 못했습니다.", snapshot());
            return;
        }
        if (now < nextActionAt) return;

        List<N> ns = snapshot();
        if (ns.isEmpty()) { // 노크타운이 앞에 없음
            if (now - lastSeenAppAt > 4000 && relaunches < 5) {
                relaunches++;
                L.i(this, "노크타운이 화면에 없어 다시 실행합니다 (" + relaunches + ")");
                launchNoktown(this);
                lastSeenAppAt = now;
                nextActionAt = now + 1500;
            }
            return;
        }
        lastSeenAppAt = now;

        if (phase == PH_CONFIRM) {
            confirmStep(ns, now);
            return;
        }

        String screen = detect(ns);
        if (!screen.equals(lastScreen)) {
            L.i(this, "화면: " + screen);
            if (screen.equals(S_UNKNOWN)) L.i(this, "  보이는 글자: " + summary(ns, 25));
            lastScreen = screen;
            screenSince = now;
            sameScreenClicks = 0;
            stuckDumped = false;
        }

        if (!stuckDumped && now - screenSince > 5000 && !screen.equals(S_GRID)) {
            stuckDumped = true;
            L.i(this, "⚠ '" + screen + "' 화면에서 5초째 진행 안 됨. 보이는 글자: " + summary(ns, 40));
            saveDump("자동 저장(멈춤)");
        }

        boolean open = now >= openAt + clickDelayMs;
        long gap = open ? 350 : 1200;

        switch (screen) {
            case S_HOME:
                clickText(ns, "커뮤니티 시설 예약하기", false);
                nextActionAt = now + gap;
                break;
            case S_COMMUNITY: {
                N golf = findGolfCard(ns);
                if (golf != null) click(golf);
                nextActionAt = now + gap;
                break;
            }
            case S_GOLF: {
                N btn = findButton(ns, "예약하기");
                if (btn != null) {
                    click(btn);
                } else {
                    android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                    L.i(this, "  '예약하기' 글자를 못 찾아 화면 아래 가운데를 터치");
                    tap(dm.widthPixels / 2, (int) (dm.heightPixels * 0.88));
                }
                nextActionAt = now + gap;
                break;
            }
            case S_SHEET:
                clickText(ns, "일일타석 예약", false);
                nextActionAt = now + gap;
                break;
            case S_GRID:
                if (!open) {
                    if (!waitLogged) {
                        L.i(this, "시간표 화면 도착. " + MainActivity.fmtMs(openAt) + " 까지 대기");
                        waitLogged = true;
                    }
                    return;
                }
                pickTime(ns, now);
                break;
            default:
                if (closePopup(ns)) {
                    nextActionAt = now + 500;
                } else if (now - screenSince > 6000) {
                    L.i(this, "모르는 화면에서 6초 지나 뒤로가기");
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    screenSince = now;
                    nextActionAt = now + 800;
                }
        }
    }

    // ---------------------------------------------------------------- grid / confirm

    private void pickTime(List<N> ns, long now) {
        if (refreshCount == 0) {
            refresh(ns, now);
            return;
        }
        List<N> times = findTimeCells(ns);
        List<N> cands = new ArrayList<>();
        for (N x : times) {
            if (x.enabled && clickableOf(x) != null && !tried.contains(cellKey(ns, x))) cands.add(x);
        }
        if (!cands.isEmpty()) {
            N c = cands.get(0);
            String key = cellKey(ns, c);
            tried.add(key);
            chosenRow = key;
            preClickTexts = textSet(ns);
            click(c);
            L.i(this, "✔ " + target + " 칸 누름: " + key + " (오픈 후 " + (now - openAt) + "ms)");
            phase = PH_CONFIRM;
            confirmStartedAt = now;
            confirmClicks = 0;
            nextActionAt = now + 300;
            return;
        }
        if (times.isEmpty() && scrollTries < 8) {
            scrollTries++;
            scrollRight(ns);
            nextActionAt = now + 300;
            return;
        }
        if (now - lastRefreshAt > 900) {
            if (!times.isEmpty()) L.i(this, target + " 칸이 " + times.size() + "개 보이지만 모두 선택 불가 → 새로고침");
            else L.i(this, target + " 칸을 찾지 못함 → 새로고침");
            refresh(ns, now);
        }
    }

    private void refresh(List<N> ns, long now) {
        N r = findContains(ns, "새로고침");
        if (r != null) click(r);
        refreshCount++;
        lastRefreshAt = now;
        scrollTries = 0;
        nextActionAt = now + 400;
        if (refreshCount == 1) L.i(this, "새로고침 (오픈 후 " + (now - openAt) + "ms)");
    }

    private void confirmStep(List<N> ns, long now) {
        Set<String> texts = textSet(ns);
        Set<String> fresh = new HashSet<>(texts);
        fresh.removeAll(preClickTexts);

        for (String t : fresh) {
            if (containsAny(t, SUCCESS_WORDS)) {
                finish(true, "예약 성공! " + target + " (" + chosenRow + ")", ns);
                return;
            }
        }
        for (String t : fresh) {
            if (containsAny(t, FAIL_WORDS)) {
                L.i(this, "실패 안내: " + t.replace('\n', ' '));
                N close = findButton(ns, "확인");
                if (close == null) close = findButton(ns, "닫기");
                if (close != null) click(close);
                phase = PH_PICK;
                nextActionAt = now + 500;
                return;
            }
        }

        N btn = findConfirmButton(ns);
        if (btn != null) {
            if (dry) {
                finish(true, "[테스트 성공] " + target + " 칸 선택 완료 (" + chosenRow + "). 다음 버튼 '"
                        + oneLine(btn.t) + "' 찾음 — 테스트라 누르지 않았습니다.", ns);
                return;
            }
            if (confirmClicks >= 6) {
                finish(false, "버튼을 6번 눌렀지만 완료 문구를 못 찾았습니다. 앱에서 예약 내역을 확인하세요.", ns);
                return;
            }
            preClickTexts = texts;
            click(btn);
            confirmClicks++;
            L.i(this, "버튼 누름: '" + oneLine(btn.t) + "'");
            nextActionAt = now + 700;
            return;
        }

        if (confirmClicks == 0 && now - confirmStartedAt > 1500) {
            L.i(this, "칸을 눌렀는데 반응 없음 → 다른 타석 시도");
            phase = PH_PICK;
            return;
        }
        if (confirmClicks > 0 && now - confirmStartedAt > 10_000) {
            String s = summary(ns, 30);
            boolean likelyDone = !s.contains("새로고침");
            finish(likelyDone, (likelyDone ? "예약된 것 같습니다" : "결과 확인 필요")
                    + " — 앱에서 예약 내역을 확인하세요.", ns);
        }
    }

    private void finish(boolean ok, String msg, List<N> ns) {
        L.i(this, (ok ? "■ 완료: " : "■ 실패: ") + msg);
        L.i(this, "  마지막 화면 글자: " + summary(ns, 40));
        L.i(this, "  걸린 시간: " + (System.currentTimeMillis() - startedAt) / 1000 + "초");
        Notifier.show(this, ok ? "골프 예약 결과" : "골프 예약 실패", msg);
        if (!ok) saveDump("자동 저장(실패)");
        stopRun();
    }

    // ---------------------------------------------------------------- screen detection

    private String detect(List<N> ns) {
        if (has(ns, "원하는 타석") || (has(ns, "새로고침") && has(ns, "타석"))) return S_GRID;
        if (has(ns, "일일타석 예약")) return S_SHEET;
        if (has(ns, "스크린 골프") && has(ns, "예약하기")) return S_GOLF;
        if (has(ns, "피트니스") || hasExact(ns, "시설 예약")) {
            if (findGolfCard(ns) != null) return S_COMMUNITY;
        }
        if (has(ns, "커뮤니티 시설 예약하기")) return S_HOME;
        return S_UNKNOWN;
    }

    private N findGolfCard(List<N> ns) {
        for (N x : ns) if (x.t.contains("골프 연습장") && !x.t.contains("스크린")) return x;
        for (N x : ns) if (x.t.replace(" ", "").contains("골프연습장")) return x;
        return null;
    }

    /** 목표 시간("20:00") 칸들. */
    private List<N> findTimeCells(List<N> ns) {
        List<N> out = new ArrayList<>();
        for (N x : ns) {
            String t = x.t;
            if (t.isEmpty() || t.contains("~")) continue;
            boolean hit = t.equals(target)
                    || (t.startsWith(target) && t.length() <= target.length() + 12)
                    || (t.contains(target) && t.length() <= 24 && !t.contains("이용시간"));
            if (!hit) continue;
            // 같은 칸이 부모/자식으로 중복 잡히면 하나만
            boolean dup = false;
            for (N o : out) if (Rect.intersects(o.r, x.r) && o.r.contains(x.r.centerX(), x.r.centerY())) dup = true;
            if (!dup) out.add(x);
        }
        // 위에서 아래(타석 번호 순)로
        out.sort((a, b) -> a.r.top != b.r.top ? Integer.compare(a.r.top, b.r.top) : Integer.compare(a.r.left, b.r.left));
        return out;
    }

    /** 칸 이름: 같은 줄에 있는 "N골프 타석" 글자. */
    private String cellKey(List<N> ns, N cell) {
        int cy = cell.r.centerY();
        for (N x : ns) {
            if (x.t.contains("타석") && x.r.top <= cy && x.r.bottom >= cy && x.r.right <= cell.r.left + 5) {
                return oneLine(x.t) + " " + target;
            }
        }
        return target + "@" + cell.r.toShortString();
    }

    private N findConfirmButton(List<N> ns) {
        int screenH = getResources().getDisplayMetrics().heightPixels;
        for (String w : CONFIRM_WORDS) {
            for (N x : ns) {
                if (!x.enabled) continue;
                if (!oneLine(x.t).replace(" ", "").equals(w.replace(" ", ""))) continue;
                if (x.r.centerY() < screenH * 0.12) continue; // 화면 맨 위 제목 제외
                if (clickableOf(x) == null) continue;
                return x;
            }
        }
        return null;
    }

    private boolean closePopup(List<N> ns) {
        for (String w : POPUP_CLOSE_WORDS) {
            N x = findButton(ns, w);
            if (x != null) {
                L.i(this, "팝업 닫기: " + w);
                click(x);
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- node helpers

    private static final class N {
        AccessibilityNodeInfo n;
        String t;
        final Rect r = new Rect();
        boolean clickable, enabled, scrollable;
        String cls;
    }

    private List<N> snapshot() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && r.getPackageName() != null && PKG.contentEquals(r.getPackageName())) roots.add(r);
            }
        } catch (Exception ignored) { }
        if (roots.isEmpty()) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && r.getPackageName() != null && PKG.contentEquals(r.getPackageName())) roots.add(r);
        }
        List<N> out = new ArrayList<>();
        for (AccessibilityNodeInfo r : roots) walk(r, out, 0);
        return out;
    }

    private void walk(AccessibilityNodeInfo node, List<N> out, int depth) {
        if (node == null || depth > 80) return;
        N x = new N();
        x.n = node;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        StringBuilder sb = new StringBuilder();
        if (t != null) sb.append(t);
        if (d != null && (t == null || !d.toString().equals(t.toString()))) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(d);
        }
        x.t = sb.toString().trim();
        node.getBoundsInScreen(x.r);
        boolean visible = node.isVisibleToUser() && x.r.width() > 0 && x.r.height() > 0
                && x.r.bottom > 0 && x.r.top < getResources().getDisplayMetrics().heightPixels + 200;
        x.clickable = node.isClickable();
        x.enabled = node.isEnabled();
        x.scrollable = node.isScrollable();
        x.cls = node.getClassName() == null ? "" : node.getClassName().toString();
        if (visible) out.add(x);
        for (int i = 0; i < node.getChildCount(); i++) walk(node.getChild(i), out, depth + 1);
    }

    private static boolean has(List<N> ns, String s) {
        for (N x : ns) if (x.t.contains(s)) return true;
        return false;
    }

    private static boolean hasExact(List<N> ns, String s) {
        for (N x : ns) if (oneLine(x.t).equals(s)) return true;
        return false;
    }

    private static N findContains(List<N> ns, String s) {
        for (N x : ns) if (x.t.contains(s)) return x;
        return null;
    }

    /** 글자가 정확히 s 인 요소. 누를 수 있는 것 우선, 그다음 화면 아래쪽(버튼) 우선. */
    private N findButton(List<N> ns, String s) {
        N best = null;
        boolean bestClickable = false;
        for (N x : ns) {
            if (!oneLine(x.t).replace(" ", "").equals(s.replace(" ", ""))) continue;
            if (x.r.width() <= 0 || x.r.height() <= 0) continue;
            boolean c = clickableOf(x) != null;
            if (best == null || (c && !bestClickable) || (c == bestClickable && x.r.top > best.r.top)) {
                best = x;
                bestClickable = c;
            }
        }
        return best;
    }

    private boolean clickText(List<N> ns, String s, boolean exact) {
        for (N x : ns) {
            if (exact ? oneLine(x.t).equals(s) : x.t.contains(s)) {
                click(x);
                return true;
            }
        }
        return false;
    }

    /** 자기 자신이나 가까운 부모 중 누를 수 있는 것(너무 큰 부모는 제외). */
    private static AccessibilityNodeInfo clickableOf(N x) {
        if (x.clickable) return x.n;
        AccessibilityNodeInfo p = x.n.getParent();
        long area = Math.max(1L, (long) x.r.width() * x.r.height());
        Rect pr = new Rect();
        for (int i = 0; i < 4 && p != null; i++) {
            p.getBoundsInScreen(pr);
            if ((long) pr.width() * pr.height() > area * 12) return null;
            if (p.isClickable()) return p;
            p = p.getParent();
        }
        return null;
    }

    /**
     * 같은 화면에서 누르기가 반복되면(=앞의 누르기가 안 먹힘) 방식을 번갈아 쓴다:
     * 0,2,4…번째는 접근성 클릭 명령, 1,3,5…번째는 손가락 터치(제스처).
     */
    private void click(N x) {
        int k = sameScreenClicks++;
        String how;
        if (k % 2 == 1) {
            tap(x.r.centerX(), x.r.centerY());
            how = "터치";
        } else {
            AccessibilityNodeInfo c = clickableOf(x);
            boolean ok = c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (!ok) tap(x.r.centerX(), x.r.centerY());
            how = ok ? "클릭" : "터치";
        }
        if (k < 6) L.i(this, "  누름(" + how + "): '" + oneLine(x.t) + "' " + x.r.toShortString());
    }

    private void tap(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }

    private void swipe(int x1, int y1, int x2, int y2) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 250)).build(), null, null);
    }

    /** 시간 칸이 옆으로 숨어 있을 때 오른쪽으로 넘긴다. */
    private void scrollRight(List<N> ns) {
        boolean did = false;
        for (N x : ns) {
            if (x.scrollable && x.r.width() > x.r.height() * 2) {
                did |= x.n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            }
        }
        if (!did) {
            int w = getResources().getDisplayMetrics().widthPixels;
            for (N x : ns) {
                if (x.t.endsWith("타석") || x.t.contains("골프 타석")) {
                    int y = x.r.centerY();
                    swipe((int) (w * 0.9), y, (int) (w * 0.4), y);
                    did = true;
                    break;
                }
            }
        }
        if (scrollTries == 1) L.i(this, target + " 칸이 안 보여서 옆으로 넘김" + (did ? "" : " (넘길 곳 못 찾음)"));
    }

    private static Set<String> textSet(List<N> ns) {
        Set<String> s = new HashSet<>();
        for (N x : ns) if (!x.t.isEmpty()) s.add(x.t);
        return s;
    }

    private static boolean containsAny(String t, String[] words) {
        for (String w : words) if (t.contains(w)) return true;
        return false;
    }

    private static String oneLine(String s) {
        return s.replace('\n', ' ').trim();
    }

    private static String summary(List<N> ns, int max) {
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new HashSet<>();
        int n = 0;
        for (N x : ns) {
            String t = oneLine(x.t);
            if (t.isEmpty() || !seen.add(t)) continue;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(t.length() > 30 ? t.substring(0, 30) + "…" : t);
            if (++n >= max) break;
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- dump

    private String dumpTree() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 화면 구조 ").append(MainActivity.fmtMs(System.currentTimeMillis())).append('\n');
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                sb.append("## window type=").append(w.getType()).append(" pkg=").append(r.getPackageName()).append('\n');
                if (r.getPackageName() == null || !PKG.contentEquals(r.getPackageName())) {
                    sb.append("  (노크타운 아님 — 생략)\n");
                    continue;
                }
                dumpNode(r, sb, 0);
            }
        } catch (Exception e) {
            sb.append("error: ").append(e).append('\n');
        }
        return sb.toString();
    }

    private void dumpNode(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 80) return;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        for (int i = 0; i < depth; i++) sb.append("  ");
        String cls = n.getClassName() == null ? "?" : n.getClassName().toString();
        sb.append(cls.substring(cls.lastIndexOf('.') + 1));
        if (n.getText() != null) sb.append(" t=\"").append(oneLine(n.getText().toString())).append('"');
        if (n.getContentDescription() != null)
            sb.append(" d=\"").append(oneLine(n.getContentDescription().toString())).append('"');
        if (n.getViewIdResourceName() != null) sb.append(" id=").append(n.getViewIdResourceName());
        if (n.isClickable()) sb.append(" CLICK");
        if (!n.isEnabled()) sb.append(" DISABLED");
        if (n.isSelected()) sb.append(" SELECTED");
        if (n.isScrollable()) sb.append(" SCROLL");
        if (!n.isVisibleToUser()) sb.append(" HIDDEN");
        sb.append(' ').append(r.toShortString()).append('\n');
        for (int i = 0; i < n.getChildCount(); i++) dumpNode(n.getChild(i), sb, depth + 1);
    }
}
