# Releasing SRCS

## Signing key — back it up

Every SRCS APK must be signed with the same key, or phones refuse the update (the in-app updater
also checks this) and users have to uninstall, losing pairings and chats.

- Key file: `D:/android-build/keys/srcs-release.jks` (PKCS12, alias `srcs`)
- Passwords: `keystore.properties` in the repo root (git-ignored) and a copy in `D:/android-build/keys/`
- Certificate SHA-256: `87:6D:09:42:6E:8C:47:1B:34:70:17:02:94:71:1E:F0:6D:26:52:77:E7:7F:0B:FC:38:C1:CB:67:3E:CC:EB:15`

This is the same key that signed every release up to 0.5.3 (it was this PC's Android debug key),
now in its own file with a strong password. **Copy the `D:/android-build/keys` folder to a
private, safe place (USB stick or a private cloud folder).** Losing it means every user must
reinstall; leaking it lets someone else publish "updates".

Google Play rejects APKs whose certificate says `CN=Android Debug`. Publishing on Play therefore
needs a new key (Play App Signing), and phones with the sideloaded app then install the Play
version fresh once.

## Steps

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts` (the in-app updater compares
   the version name; Android refuses a lower versionCode).
2. `gradlew :core:jvmTest :app:testDebugUnitTest :app:lintDebug`
3. Build:
   - `gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
     (R8-shrunk, not debuggable, signed with the release key). Smoke-test it on a phone before
     the first release built this way: go online, pair, call, walkie-talkie, message, map, DJ.
   - Until then releases used `assembleDebug` (same key, debuggable).
4. Push to `main` and wait for CI (Android + iPhone build + simulator test).
5. `gh release create vX.Y.Z SRCS.apk SRCS-unsigned.ipa` — the APK asset must be named
   `SRCS.apk` and the tag `vX.Y.Z`, or the download link and the in-app updater break. The IPA
   comes from the CI run's `SRCS-unsigned-ipa` artifact.
