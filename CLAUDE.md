# SRCS — Short Range Communication System

Offline Android calling / push-to-talk / messaging over Wi-Fi Direct. Kotlin, Jetpack Compose.
(The master plan calls it "P.A.A.I. Link"; the user renamed the product to SRCS. Code packages are
still `ai.paai.link` and the applicationId is `ai.paai.link`.)

- Specification: `PAAI_Link_All_Phases_Master_Plan.md` (section 14 = working rules). Do not edit it.
- Current state per phase: `docs/STATUS.md`. Architecture / wire protocol: `docs/ARCHITECTURE.md`.

## Build and test (Windows, PowerShell or Git Bash)
- Always set `GRADLE_USER_HOME=D:\android-build\gradle-home` (C: is nearly full).
- `gradlew :core:test` (56 PC tests incl. multi-node integration), `:app:testDebugUnitTest`, `lintDebug`,
  `assembleDebug`, `connectedDebugAndroidTest` (needs emulator/phone).
- Emulator: AVD `paai36` in `D:\android-build\avd` (set `ANDROID_AVD_HOME`), API 36 x86_64, WHPX.
- Desktop peer for emulator demos: `gradlew :core:demoPeer` + `adb emu redir add udp:47701:47701`.
- RAM is tight with emulator + Gradle running; `gradlew --stop` frees daemons if builds fail to start.

## Rules
- Keep `core/` free of Android dependencies; put logic there and test it on the JVM.
- Never claim hardware behaviour (Wi-Fi Direct, audio quality, range) works without phone tests.
- Never log keys, message bodies or audio. Wire changes must bump `ProtocolLimits.VERSION`.
- Room schema changes need migrations (schemas in `app/schemas/`).
