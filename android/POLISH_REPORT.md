# Android widget and Garmin completion — November 19, 2026

The reported empty result was correct for November 19. Both authenticated backend
endpoints returned the same November 18 activity, ID 25119642770, named `Koper Бег`
by Garmin, and zero activities on November 19. Android additionally ignored the
existing date selector for Garmin and allowed older-day cache fallback; those bugs
are fixed. The phone's backend/token fingerprint matches the development backend.

November 18 now downloads, parses, persists and displays on the physical phone:
Running — Koper Бег; 8.30 km; 39:12; 4:43/km. The original Garmin service is reused.
The app and widget never substitute yesterday for today. The backend chooses today's
calendar date in GARMIN_TIMEZONE, including DST. Explicit historical dates remain supported.

Refresh Data independently downloads Garmin even when Health Connect is unavailable.
Typed network/auth/backend/invalid-response failures retain cached results. Successful
empty responses replace the selected day's result. Diagnostics contain no tokens.

The widget uses 👣 and 🌙, hides Garmin entirely without today's workouts, centers
its compact content, preserves dark styling/rounding, and limits visible workouts by
available size/font scaling. Tiny widgets summarize workouts with a tap hint.
The physical widget's current size was verified without clipping or empty Garmin UI.

Validation: 46 Android tests passed, including one opt-in live download/disk-cache
round-trip test; 13 backend tests; 8 React tests; React lint/build; final debug APK
and Android lint (0 errors, 18 warnings). Final APK installed without clearing data.
Health Connect retained 3,822 steps and 7h 10m sleep. See TESTING.md for reproduction.

Remaining limits: offline restart and other launcher sizes/multiple-workout visual
layouts were not physically verified. Failure retention, multiple workouts, malformed
responses, authentication and DST were tested with fixtures. Launcher redraw timing
is controlled by Android; an existing widget cannot automatically shrink.

Files changed in this task (paths relative to repository root):

- README.md
- android/README.md, android/TESTING.md, android/POLISH_REPORT.md
- android/app/src/main/java/com/stridehub/android/StrideHubApplication.kt
- android/app/src/main/java/com/stridehub/android/data/GarminClient.kt
- android/app/src/main/java/com/stridehub/android/data/GarminData.kt
- android/app/src/main/java/com/stridehub/android/data/GarminRepository.kt
- android/app/src/main/java/com/stridehub/android/ui/HomeScreen.kt
- android/app/src/main/java/com/stridehub/android/ui/HomeViewModel.kt
- android/app/src/main/java/com/stridehub/android/ui/SettingsScreen.kt
- android/app/src/main/java/com/stridehub/android/widget/StrideHubWidget.kt
- android/app/src/main/res/layout/widget_preview.xml
- android/app/src/main/res/values/strings.xml
- android/app/src/test/java/com/stridehub/android/data/GarminTest.kt
- android/app/src/test/java/com/stridehub/android/data/GarminLiveTest.kt
- server/src/routes/garmin.routes.js
- server/src/services/garminDaily.service.js
- server/test/api.test.js

Pre-existing workspace changes were preserved; no database schema or Garmin service
rewrite, new dependency, or new background worker was introduced.
