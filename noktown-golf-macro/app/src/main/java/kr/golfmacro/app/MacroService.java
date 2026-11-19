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
    private static final String S_TIMESHEET = "이용시간 검색창";
    private static final String S_UNKNOWN = "알 수 없는 화면";

    // 단계
    private static final int PH_PICK = 0;    // 이동 + 시간 고르기
    private static final int PH_CONFIRM = 1; // 시간 고른 뒤 예약/확인 버튼

    private static final String[] SUCCESS_WORDS = {
            "예약을 완료", "완료하였습니다", "예약이 완료", "예약 완료", "예약완료", "예약되었습니다", "완료되었습니다",
            "신청이 완료", "신청되었습니다"
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
    private final java.util.Map<String, Integer> rowTries = new java.util.HashMap<>();
    private long lastPreScrollAt;
    // 이용시간으로 검색 필터
    private boolean filterActive;
    private int filterTries, sheetScrolls, sheetSelects;
    private long lastFilterOpenAt;
    private List<Integer> priority = new ArrayList<>();
    private boolean allowOthers = true;
    private boolean byPosition = true;
    private List<Integer> posPriority = new ArrayList<>();
    private int maxTries = 5;
    private boolean autoClose;
    private int confirmClicks;
    private long confirmStartedAt;
    // 결제 단계 (시간 칸 → [결제 유의사항 팝업 → 확인] → 결제 금액 확인 창 → 결제하기 → 예약 완료)
    private long sheetSeenAt, noticeSeenAt, noticeClosedAt;
    private boolean noticeEverSeen;
    private int payClicks, noticeClicks, afterPayClicks;
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

    static boolean runningNow() {
        MacroService s = instance;
        return s != null && s.running;
    }

    static String runLabel() {
        MacroService s = instance;
        return s != null && s.running ? s.label : "";
    }

    /**
     * @param openAt 예약이 열리는 시각(ms). 이 시각 전에는 시간표 화면에서 기다린다.
     * @param dry    true 면 시간 칸 선택까지만 하고 최종 예약 버튼은 누르지 않는다.
     */
    void startRun(long openAt, String target, boolean dry, boolean scheduled, String label) {
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
        this.priority = Prefs.parsePriority(p.priority());
        this.allowOthers = p.allowOthers();
        this.byPosition = p.byPosition();
        this.posPriority = Prefs.parsePriority(p.posPriority());
        this.maxTries = Math.max(1, p.maxTries());
        this.autoClose = scheduled && p.closeAfter();
        this.phase = PH_PICK;
        this.nextActionAt = now + 1500; // 앱이 뜰 시간
        this.lastRefreshAt = 0;
        this.refreshCount = 0;
        this.scrollTries = 0;
        this.rowTries.clear();
        this.lastPreScrollAt = 0;
        this.filterActive = false;
        this.filterTries = 0;
        this.sheetScrolls = 0;
        this.sheetSelects = 0;
        this.lastFilterOpenAt = 0;
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
                + ", 오픈 " + MainActivity.fmtMs(openAt)
                + ", 최대 " + maxTries + "회"
                + ", " + (byPosition ? "줄 순서 " + posPriority + "번째" : "타석 순서 " + priority)
                + (allowOthers ? " → 그 외" : " (그 외 안 함)"));
        h.removeCallbacksAndMessages(null);
        h.post(this::tick);
        Notifier.updateStatus(this);
    }

    void stopRun() {
        boolean was = running;
        running = false;
        h.removeCallbacksAndMessages(null);
        if (wl != null && wl.isHeld()) wl.release();
        wl = null;
        if (was) Notifier.updateStatus(this);
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
                    openFilter(ns, now, false);
                    return;
                }
                pickTime(ns, now);
                break;
            case S_TIMESHEET:
                timeSheetStep(ns, now);
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
        List<N> labels = rowLabels(ns);
        // 아래쪽 검색 버튼: 필터 전 "이용시간으로 검색", 필터 후 "20:00 ⌄"
        N fb = filterButton(ns);
        filterActive = fb != null && oneLine(fb.t).startsWith(target);
        if (fb != null) {
            final Rect fr = fb.r;
            times.removeIf(x -> x.r.equals(fr)); // 버튼 글자는 시간 칸이 아님
        }
        if (!labels.isEmpty()) times.removeIf(x -> labelOf(labels, x) == null); // 줄에 속한 칸만
        // 1) "이용시간으로 검색"으로 목표 시간에 남은 타석만 보이게
        if (!filterActive && openFilter(ns, now, true)) return;
        // 2) 필터를 못 쓰는 경우에만: 우선순위 줄을 옆으로 끌어서 목표 칸 찾기
        if (!filterActive && scrollToTarget(ns, times, labels, true)) {
            nextActionAt = now + 350;
            return;
        }
        // 2) 보이는 칸 중 우선순위가 가장 높은 것
        N c = null;
        int bestRank = Integer.MAX_VALUE;
        for (N x : times) {
            if (!x.enabled || tried.contains(cellKey(ns, x))) continue;
            int rk = rankOfLabel(labels, labelOf(labels, x));
            if (rk >= 0 && rk < bestRank) {
                bestRank = rk;
                c = x;
            }
        }
        if (c != null) {
            String key = cellKey(ns, c);
            tried.add(key);
            chosenRow = key;
            preClickTexts = textSet(ns);
            click(c);
            L.i(this, "✔ " + target + " 칸 누름: " + key + " (" + (labels.indexOf(labelOf(labels, c)) + 1)
                    + "번째 줄, 보이는 줄 " + labels.size() + "개, 오픈 후 " + (now - openAt) + "ms)");
            phase = PH_CONFIRM;
            confirmStartedAt = now;
            sheetSeenAt = 0;
            noticeSeenAt = 0;
            noticeClosedAt = 0;
            noticeEverSeen = false;
            payClicks = 0;
            noticeClicks = 0;
            afterPayClicks = 0;
            confirmClicks = 0;
            nextActionAt = now + 300;
            return;
        }
        // 3) 우선순위 타석이 모두 안 되면 나머지 타석도 넘겨 본다
        if (!filterActive && allowOthers && scrollToTarget(ns, times, labels, false)) {
            nextActionAt = now + 350;
            return;
        }
        if (now - lastRefreshAt > 900) {
            if (refreshCount >= maxTries) {
                finish(false, maxTries + "회 시도했지만 " + target + " 예약 가능한 칸이 없었습니다. 직접 확인해 보세요.", ns);
                return;
            }
            if (!times.isEmpty()) L.i(this, target + " 칸이 " + times.size() + "개 보이지만 모두 선택 불가 → 새로고침");
            else L.i(this, target + " 칸을 찾지 못함 → 새로고침");
            refresh(ns, now);
        }
    }

    /** "이용시간으로 검색" 버튼을 눌러 시간 목록을 연다. 눌렀으면 true. */
    private boolean openFilter(List<N> ns, long now, boolean log) {
        if (filterTries >= 6 || now - lastFilterOpenAt < 1500) return false;
        N btn = filterButton(ns);
        if (btn != null && oneLine(btn.t).startsWith(target)) {
            filterActive = true; // 이미 목표 시간으로 걸려 있음
            return false;
        }
        if (btn == null) {
            if (filterTries == 0 && log) L.i(this, "'이용시간으로 검색' 버튼을 못 찾음 → 옆으로 끌기 방식 사용");
            filterTries = 6;
            return false;
        }
        filterTries++;
        lastFilterOpenAt = now;
        click(btn);
        L.i(this, "시간 검색 버튼 누름 '" + oneLine(btn.t) + "' (" + filterTries + "번째)");
        nextActionAt = now + 500;
        return true;
    }

    /** 시간 목록 창: 목표 시간 글자를 찾아 누르고, 안 보이면 목록을 아래로 넘긴다. */
    private void timeSheetStep(List<N> ns, long now) {
        if (now - lastFilterOpenAt > 4000) sheetSelects = 0;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        N hit = null;
        for (N x : ns) {
            // 목록 항목은 화면 왼쪽에 붙어 있음 (뒤에 깔린 시간표 칸과 구분)
            if (oneLine(x.t).equals(target) && x.r.left < dm.widthPixels * 0.25) {
                if (hit == null || x.r.top > hit.r.top) hit = x;
            }
        }
        if (hit != null) {
            if (++sheetSelects > 3) {
                // 눌렀는데 창이 안 닫히면 뒤로가기로 닫는다
                L.i(this, "시간 목록 창이 안 닫혀서 뒤로가기");
                performGlobalAction(GLOBAL_ACTION_BACK);
                sheetSelects = 0;
                filterActive = true;
                nextActionAt = now + 600;
                return;
            }
            click(hit);
            filterActive = true;
            sheetScrolls = 0;
            L.i(this, "시간 목록에서 " + target + " 선택");
            nextActionAt = now + 600;
            return;
        }
        if (sheetScrolls >= 8) {
            L.i(this, "시간 목록에서 " + target + " 을 못 찾음 → 닫고 옆으로 끌기 방식 사용");
            filterTries = 6;
            performGlobalAction(GLOBAL_ACTION_BACK);
            nextActionAt = now + 600;
            return;
        }
        sheetScrolls++;
        boolean did = false;
        for (N x : ns) {
            if (x.scrollable && x.r.height() > dm.heightPixels * 0.3 && x.r.left < dm.widthPixels * 0.2) {
                did |= x.n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                if (did) break;
            }
        }
        if (!did) {
            int x = (int) (dm.widthPixels * 0.3);
            swipe(x, (int) (dm.heightPixels * 0.8), x, (int) (dm.heightPixels * 0.35));
        }
        if (sheetScrolls == 1) L.i(this, "시간 목록에 " + target + " 이 안 보여 아래로 넘김");
        nextActionAt = now + 450;
    }

    private void refresh(List<N> ns, long now) {
        N r = findContains(ns, "새로고침");
        if (r != null) click(r);
        refreshCount++;
        lastRefreshAt = now;
        scrollTries = 0;
        rowTries.clear();
        nextActionAt = now + 400;
        L.i(this, "새로고침 " + refreshCount + "/" + maxTries + "회 (오픈 후 " + (now - openAt) + "ms)");
    }

    /** 팝업이 완전히 뜬 뒤 누르기까지 기다리는 시간 */
    private static final long NOTICE_SETTLE_MS = 600;
    /** 결제 창이 뜬 뒤 유의사항 팝업이 나오길 기다리는 최대 시간 */
    private static final long NOTICE_WAIT_MS = 3000;
    /** 팝업을 닫은 뒤 결제하기를 누르기까지 기다리는 시간 */
    private static final long AFTER_NOTICE_MS = 500;

    private void confirmStep(List<N> ns, long now) {
        if (now - confirmStartedAt > 15_000) {
            finish(false, "결제 단계에서 15초 동안 끝나지 않았습니다 — 앱에서 예약 내역을 확인하세요.", ns);
            return;
        }
        // 1) 예약 완료 화면
        if (has(ns, "예약을 완료") || hasExact(ns, "예약 완료") || has(ns, "완료하였습니다")) {
            finish(true, "예약 성공! " + target + " (" + chosenRow + ")", ns);
            return;
        }

        // 2) "결제 유의사항" 팝업 → 잠깐 기다렸다가 [확인]
        boolean notice = has(ns, "결제 유의사항") || (has(ns, "유의사항") && findButton(ns, "확인") != null);
        // 팝업 [확인]을 이미 눌렀다면, 닫힌 팝업 글자가 화면 정보에 남아 있어도 결제하기로 넘어간다.
        // (결제하기를 눌렀는데도 진행이 안 되면 그때 한 번 더 확인을 누름)
        if (notice && noticeClicks > 0) {
            boolean retry = payClicks > noticeClicks - 1 && noticeClicks < 3
                    && now - noticeClosedAt > 1500 && findButton(ns, "결제하기") != null;
            notice = retry;
        }
        if (notice) {
            if (noticeSeenAt == 0) {
                noticeSeenAt = now;
                noticeEverSeen = true;
                L.i(this, "유의사항 팝업 뜸 → " + NOTICE_SETTLE_MS + "ms 뒤 확인");
            }
            if (now - noticeSeenAt < NOTICE_SETTLE_MS) return;
            N ok = findButton(ns, "확인");
            if (ok != null && noticeClicks < 4) {
                click(ok);
                noticeClicks++;
                noticeClosedAt = now;
                noticeSeenAt = 0;
                nextActionAt = now + AFTER_NOTICE_MS;
            }
            return;
        }
        noticeSeenAt = 0;

        // 3) "결제 금액을 확인해 주세요" 창 → 시간 확인 → [결제하기]
        N pay = findButton(ns, "결제하기");
        boolean sheet = has(ns, "결제 금액") || (pay != null && has(ns, "이용시간"));
        if (sheet && pay != null) {
            if (sheetSeenAt == 0) {
                sheetSeenAt = now;
                L.i(this, "결제 확인 창 뜸: " + summary(ns, 12));
                if (sheetShowsOtherTime(ns)) {
                    L.i(this, "⚠ 결제 창 시간이 " + target + " 이 아님 → 닫고 다시 고르기");
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    phase = PH_PICK;
                    nextActionAt = now + 600;
                    return;
                }
            }
            // 유의사항 팝업이 뒤늦게 뜰 수 있으니 잠깐 기다린다
            if (!noticeEverSeen && now - sheetSeenAt < NOTICE_WAIT_MS) return;
            if (noticeClosedAt > 0 && now - noticeClosedAt < AFTER_NOTICE_MS) return;
            if (dry) {
                finish(true, "[테스트 성공] " + target + " 칸 선택 → 유의사항 "
                        + (noticeEverSeen ? "확인" : "(안 뜸)") + " → 결제 창까지 왔습니다 (" + chosenRow
                        + "). 테스트라 '결제하기'는 누르지 않았습니다.", ns);
                return;
            }
            if (payClicks >= 3) {
                finish(false, "'결제하기'를 3번 눌렀지만 완료 화면이 안 나왔습니다. 앱에서 예약 내역을 확인하세요.", ns);
                return;
            }
            click(pay);
            payClicks++;
            L.i(this, "결제하기 누름 (" + payClicks + "번째, 오픈 후 " + (now - openAt) + "ms)");
            nextActionAt = now + 900;
            return;
        }

        // 4) 실패 안내 팝업 (이미 예약됨 등)
        Set<String> texts = textSet(ns);
        Set<String> fresh = new HashSet<>(texts);
        fresh.removeAll(preClickTexts);
        for (String t : fresh) {
            if (containsAny(t, FAIL_WORDS)) {
                L.i(this, "실패 안내: " + t.replace('\n', ' '));
                N close = findButton(ns, "확인");
                if (close == null) close = findButton(ns, "닫기");
                if (close != null) click(close);
                phase = PH_PICK;
                nextActionAt = now + 600;
                return;
            }
        }

        // 4-1) 결제하기 뒤에 "결제하시겠습니까?" 같은 확인 창이 더 뜨면 누른다
        if (payClicks > 0 && afterPayClicks < 3) {
            N ok = findButton(ns, "확인");
            if (ok == null) ok = findButton(ns, "예");
            if (ok == null) ok = findButton(ns, "결제");
            if (ok != null) {
                L.i(this, "결제 후 확인 창: " + summary(ns, 10) + " → '" + oneLine(ok.t) + "' 누름");
                click(ok);
                afterPayClicks++;
                nextActionAt = now + 800;
                return;
            }
        }

        // 5) 아무 창도 안 뜸
        boolean progressed = sheetSeenAt > 0 || noticeEverSeen || payClicks > 0;
        if (!progressed && now - confirmStartedAt > 2000) {
            L.i(this, "칸을 눌렀는데 결제 창이 안 뜸 (예약 불가 칸) → 다음 타석");
            phase = PH_PICK;
            return;
        }
    }

    /** 결제 창에 "HH:MM ~ HH:MM" 이 있는데 목표 시간으로 시작하는 게 하나도 없으면 true */
    private boolean sheetShowsOtherTime(List<N> ns) {
        boolean anyRange = false;
        for (N x : ns) {
            String t = oneLine(x.t);
            if (!t.matches("^\\d{1,2}:\\d{2}\\s*~.*")) continue;
            anyRange = true;
            if (t.startsWith(target)) return false;
        }
        return anyRange;
    }

    private void finish(boolean ok, String msg, List<N> ns) {
        L.i(this, (ok ? "■ 완료: " : "■ 실패: ") + msg);
        L.i(this, "  마지막 화면 글자: " + summary(ns, 40));
        L.i(this, "  걸린 시간: " + (System.currentTimeMillis() - startedAt) / 1000 + "초");
        Notifier.show(this, ok ? "골프 예약 결과" : "골프 예약 실패", msg);
        try {
            android.widget.Toast.makeText(this, (ok ? "⛳ " : "⚠ ") + msg, android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception ignored) { }
        if (!ok) saveDump("자동 저장(실패)");
        boolean close = autoClose;
        stopRun();
        if (close) {
            // 결과 화면을 잠깐 남겼다가 홈으로 나가고 화면을 끈다
            h.postDelayed(() -> {
                performGlobalAction(GLOBAL_ACTION_HOME);
                h.postDelayed(() -> {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
                    L.i(this, "홈으로 나가고 화면 끔");
                }, 800);
            }, 3000);
        }
    }

    // ---------------------------------------------------------------- screen detection

    private String detect(List<N> ns) {
        if (has(ns, "검색할 시간")) return S_TIMESHEET;
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
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 180)).build(), null, null);
    }

    private static final java.util.regex.Pattern ROW_LABEL =
            java.util.regex.Pattern.compile("^\\d+\\s*골프\\s*타석.*");

    /** 타석 번호 → 순위 (작을수록 먼저). 우선순위 목록 밖은 100+번호, 허용 안 하면 -1. */
    private int rank(int rowNo) {
        int i = priority.indexOf(rowNo);
        if (i >= 0) return i;
        if (!allowOthers) return -1;
        return 100 + Math.max(0, rowNo);
    }

    /** 화면 아래 가운데의 시간 검색 버튼 ("이용시간으로 검색" 또는 "20:00 ⌄") */
    private N filterButton(List<N> ns) {
        N f = findContains(ns, "이용시간으로 검색");
        if (f != null) return f;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        N best = null;
        for (N x : ns) {
            String t = oneLine(x.t);
            if (!t.matches("^\\d{1,2}:\\d{2}.{0,3}$")) continue;
            if (x.r.centerY() < dm.heightPixels * 0.75) continue;
            if (x.r.left < dm.widthPixels * 0.2 || x.r.right > dm.widthPixels * 0.85) continue;
            if (best == null || x.r.top > best.r.top) best = x;
        }
        return best;
    }

    /** 시간 칸과 같은 줄의 타석 이름 */
    private static N labelOf(List<N> labels, N cell) {
        int cy = cell.r.centerY();
        for (N lb : labels) {
            if (Math.abs(lb.r.centerY() - cy) < Math.max(60, lb.r.height())) return lb;
        }
        return null;
    }

    /** 줄의 순위 (작을수록 먼저). 위치 모드면 위에서 몇 번째 줄인지, 아니면 타석 번호로. 제외면 -1 */
    private int rankOfLabel(List<N> labels, N lb) {
        if (lb == null) return allowOthers ? 999 : -1;
        if (!byPosition) return rank(rowNo(lb));
        int pos = labels.indexOf(lb) + 1; // labels 는 위→아래 정렬
        int i = posPriority.indexOf(pos);
        if (i >= 0) return i;
        return allowOthers ? 100 + pos : -1;
    }

    /** "6골프 타석" → 6, 못 읽으면 -1 */
    private static int rowNo(N label) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)").matcher(oneLine(label.t));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** 시간 칸이 속한 줄의 타석 번호, 못 찾으면 -1 */
    private static int rowNoOf(List<N> labels, N cell) {
        int cy = cell.r.centerY();
        for (N lb : labels) {
            if (Math.abs(lb.r.centerY() - cy) < Math.max(60, lb.r.height())) return rowNo(lb);
        }
        return -1;
    }

    /** 화면에 보이는 타석 이름들 ("1골프 타석", "9골프 타석 (레슨타석)" …). */
    private List<N> rowLabels(List<N> ns) {
        List<N> out = new ArrayList<>();
        int w = getResources().getDisplayMetrics().widthPixels;
        for (N x : ns) {
            String t = oneLine(x.t);
            if (t.length() > 25 || x.r.left > w * 0.4) continue;
            if (!ROW_LABEL.matcher(t).matches()) continue;
            boolean dup = false;
            for (N o : out) if (Math.abs(o.r.centerY() - x.r.centerY()) < 20) dup = true;
            if (!dup) out.add(x);
        }
        out.sort((a, b) -> Integer.compare(a.r.top, b.r.top));
        return out;
    }

    /**
     * 목표 시간 칸이 아직 안 보이는 타석 줄을 손가락으로 왼쪽으로 끌어서(드래그) 넘긴다.
     * 한 번에 한 줄씩, 줄마다 최대 4번. 넘길 게 있으면 true.
     */
    private boolean scrollToTarget(List<N> ns, List<N> times, List<N> labels, boolean priorityOnly) {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        if (labels.isEmpty()) {
            // 타석 이름을 못 찾으면 예전 방식: 칸이 하나도 안 보일 때만 첫 줄 근처를 넘김
            if (!priorityOnly || !times.isEmpty() || scrollTries >= 8) return false;
            scrollTries++;
            N any = findContains(ns, "타석");
            int y = any != null ? any.r.centerY() : (int) (dm.heightPixels * 0.3);
            swipe((int) (dm.widthPixels * 0.72), y, (int) (dm.widthPixels * 0.05), y);
            if (scrollTries == 1) L.i(this, target + " 칸이 안 보여서 옆으로 끌기 (타석 이름 못 찾음)");
            return true;
        }
        List<N> order = new ArrayList<>();
        for (N lb : labels) {
            int rk = rankOfLabel(labels, lb);
            boolean isPri = rk >= 0 && rk < 100;
            if (priorityOnly ? isPri : (rk >= 100)) order.add(lb);
        }
        order.sort((a, b) -> Integer.compare(rankOfLabel(labels, a), rankOfLabel(labels, b)));
        for (N lb : order) {
            int cy = lb.r.centerY();
            boolean hasTarget = false;
            for (N c : times) if (Math.abs(c.r.centerY() - cy) < Math.max(60, lb.r.height())) hasTarget = true;
            if (hasTarget) continue;
            String key = oneLine(lb.t);
            int n = rowTries.getOrDefault(key, 0);
            if (n >= 4) continue;
            rowTries.put(key, n + 1);
            // 시간 칸 영역(타석 이름 오른쪽)에서 왼쪽으로 빠르게 끌기
            int x1 = (int) (dm.widthPixels * 0.72);
            int x2 = Math.max(lb.r.right + 10, (int) (dm.widthPixels * 0.05));
            if (x2 >= x1) x2 = (int) (dm.widthPixels * 0.05);
            swipe(x1, cy, x2, cy);
            if (n == 0) L.i(this, "  " + key + ": " + target + " 칸이 안 보여서 왼쪽으로 끌기");
            return true;
        }
        return false;
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
