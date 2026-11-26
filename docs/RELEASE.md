# Release signing and versioning

Release builds are minified (R8) and **unsigned until you create a key**. The key proves future
updates come from you: back it up offline; if it is lost, existing installs cannot be updated.

## Create the release key (once)

```powershell
cd D:\pythonProjects\short-range-communication-system
keytool -genkeypair -v -keystore D:\android-build\paai-link-release.jks -alias paai-link `
        -keyalg RSA -keysize 4096 -validity 10000
```

Create `keystore.properties` in the project root (ignored by git — never commit it):

```properties
storeFile=D:/android-build/paai-link-release.jks
storePassword=...
keyAlias=paai-link
keyPassword=...
```

Then `.\gradlew.bat assembleRelease` produces `app\build\outputs\apk\release\app-release.apk`,
signed and installable (`adb install -r ...`).

## Versioning

- `versionName` follows semantic versioning; `versionCode` increases with every build you share.
- Protocol: `ProtocolLimits.VERSION` (currently 1) is checked in the link hello and every
  envelope / media packet. Incompatible wire changes must bump it.
- Database: Room schema is exported to `app/schemas/`. Any schema change needs a `Migration` —
  never destructive fallback, users would lose messages.
