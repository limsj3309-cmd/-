# 골프예약 매크로 (노크타운 일일타석 자동 예약)

매일 정해진 시각(기본 06:00:00)에 노크타운 앱을 열어
`커뮤니티 시설 예약하기 → 골프 연습장 → 예약하기 → 일일타석 예약 → 새로고침 → 20:00 칸 → 예약`
순서로 자동으로 눌러 주는 개인용 안드로이드 앱입니다. (접근성 서비스 + 정확한 알람 사용)

## 빌드
```
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
```

## 동작 방식
- `Scheduler` : 매일 (오픈 시각 - 미리 시작 초)에 `AlarmManager.setAlarmClock` 으로 `AlarmActivity` 실행
- `AlarmActivity` : 화면 켜기 → 노크타운 실행 → `MacroService.startRun()`
- `MacroService` : 0.12초마다 화면을 판별해 다음 버튼을 누름. 시간표 화면에서는 오픈 시각까지 대기했다가
  `새로고침` → 목표 시간 칸(선택 가능한 첫 타석) → 예약/확인 버튼 → 완료 문구 확인 후 알림
- 앱 화면의 `테스트` 버튼으로 지금 바로 실행해 볼 수 있고(최종 버튼 전 멈춤 가능),
  `10초 뒤 화면 구조 저장` 으로 노크타운 화면 구조를 저장·공유해 설정을 맞출 수 있습니다.
