# GenieLock Customer (Android)

A native Android WebView wrapper around the GadgetGenie customer portal
(`https://my.gadgetgenie.org/portal`), built so it can be opened **from the Samsung Knox
Guard lock screen** when a financed phone is locked for missed payments. Chrome is never
launched — the WebView runs inside this app's own process, which is why Knox allows it
through where a Chrome-based (TWA) wrapper is blocked.

This app has **no backend of its own**. Every feature — sign-in, payments, receipts,
vouchers — is served by the existing portal. This project only wraps it natively.

- Package name: `org.gadgetgenie.my.native`
- Kotlin/R-class namespace: `org.gadgetgenie.customer` (kept distinct from the
  applicationId because `native` is a reserved word in Java/Kotlin and can't be a package
  segment for generated code — the two are independent in modern Android Gradle Plugin, and
  the app still installs and is identified as `org.gadgetgenie.my.native`, verified with a
  local build)
- minSdk 26, compileSdk 35, **targetSdk 34** — see the note in `app/build.gradle.kts` on why
  targetSdk is deliberately not 35 yet (Android 15's enforced edge-to-edge layout turns
  `setStatusBarColor`/`setNavigationBarColor` into no-ops, which would break the fixed
  blue-status-bar/white-nav-bar branding this app depends on)

## 1. Install the toolchain

You do **not** need Android Studio. You need a JDK and the Android command-line tools.

### macOS

```bash
brew install openjdk@17
```

Then install the Android SDK command-line tools (skip if `$ANDROID_HOME` already has them):

```bash
mkdir -p ~/Library/Android/sdk/cmdline-tools
cd ~/Library/Android/sdk/cmdline-tools
curl -fsSL -o cmdline-tools.zip \
  "https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip"
unzip cmdline-tools.zip && mv cmdline-tools latest
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

### Linux

Same as macOS, swap the download for
`commandlinetools-linux-11076708_latest.zip` and install the JDK with your package
manager (`apt install openjdk-17-jdk` or similar).

### Windows (PowerShell)

```powershell
# JDK 17 — via winget, or download Temurin 17 manually.
winget install EclipseAdoptium.Temurin.17.JDK

# Android command-line tools
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\sdk"
New-Item -ItemType Directory -Force -Path "$env:ANDROID_HOME\cmdline-tools"
Invoke-WebRequest -Uri "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile cmdline-tools.zip
Expand-Archive cmdline-tools.zip -DestinationPath "$env:ANDROID_HOME\cmdline-tools"
Rename-Item "$env:ANDROID_HOME\cmdline-tools\cmdline-tools" "latest"
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Either way, point Gradle at your SDK by creating `local.properties` in the project root
(this file is gitignored — never commit it, it's machine-specific):

```
sdk.dir=/absolute/path/to/your/Android/sdk
```

## 2. Build

Everything runs through the committed Gradle wrapper — no Gradle install needed:

```bash
./gradlew assembleDebug      # unsigned debug APK, for local testing
./gradlew assembleRelease    # release APK (minified, shrunk)
./gradlew bundleRelease      # release AAB, for Play Store / Knox Guard upload
```

Outputs land in `app/build/outputs/apk/` and `app/build/outputs/bundle/`.

Without a release keystore configured, `assembleRelease` falls back to debug signing so you
can still test the release build type locally (minify/shrink included) — you'll see a
warning in the build log. **Do not distribute a debug-signed release build.**

## 3. Sign a real release

Generate a keystore once (never commit it):

```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias genielock-customer \
  -keyalg RSA -keysize 2048 -validity 10000
```

Create `keystore.properties` in the project root (gitignored) for local release builds:

```
storeFile=/absolute/path/to/release.keystore
storePassword=...
keyAlias=genielock-customer
keyPassword=...
```

Then `./gradlew assembleRelease bundleRelease` will sign with it automatically.

Print the SHA-256 certificate fingerprint Knox Guard needs:

```bash
scripts/print-sha256.sh release.keystore genielock-customer
```

## 4. CI (GitHub Actions)

`.github/workflows/android.yml` builds a signed release APK and AAB on every push to
`main` and uploads both as workflow artifacts. It reads signing material from repo secrets
— set these under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -i release.keystore \| pbcopy` (or `base64 -w0` on Linux) |
| `KEYSTORE_PASSWORD` | your keystore password |
| `KEY_ALIAS` | your key alias |
| `KEY_PASSWORD` | your key password |

The workflow decodes the keystore to a temp file for the build and deletes it afterwards.
**Never commit a keystore, a `.jks`/`.keystore` file, or a `keystore.properties` file** —
`.gitignore` already blocks all three.

## 5. Install on a device

```bash
adb install app/build/outputs/apk/release/app-release.apk
```

## 6. Knox Guard setup

In the Knox Guard console, under **Policies → Pay-As-You-Go Lock Screen**:

1. Add allowed app: package `org.gadgetgenie.my.native`, with the release certificate's
   SHA-256 (from `scripts/print-sha256.sh`).
2. Set **Primary support option** to **Application**.
3. Save.
4. Confirm the **Device-Under-Lock** policy allows network access while locked — EcoCash
   payments need to reach the network from behind the lock screen to complete and for Knox
   to refresh the device's unlock status afterward.

## What's deliberately not here

No analytics, ads, or third-party SDKs. Dependencies are limited to AndroidX
(`core`, `appcompat`, `activity`, `webkit`, `swiperefreshlayout`, `core-splashscreen`) and
Material — ask before adding anything else.

## Fonts & icons

The Manrope font (`app/src/main/res/font/manrope.ttf`, variable weight) is bundled under
the OFL license — see `licenses/manrope-OFL.txt`. The launcher icon is generated from
`genielock-512.png` / `genielock-512-maskable.png` in the Lovable repo's `public/icons/`.

## A few implementation notes worth knowing

- **Downloads land in app-specific storage, not the public Downloads folder, on Android
  8.0–9 (API 26–28).** The app only holds `INTERNET`, `ACCESS_NETWORK_STATE`, and `CAMERA`
  permissions — no `WRITE_EXTERNAL_STORAGE` — so on API 29+ files go into the real
  MediaStore Downloads collection (visible in the Files app, no permission needed), but on
  26–28 they're written to the app's own external files dir and registered with
  `DownloadManager.addCompletedDownload()` so they still show up with a "download complete"
  notification and open normally — they just won't appear inside the system *Downloads* app
  on those three OS versions. If that matters for your fleet of devices, the fix is adding
  `WRITE_EXTERNAL_STORAGE` (API ≤28 only) and asking the user for it at runtime.
- **Third-party cookies are off** (`setAcceptThirdPartyCookies(webView, false)`), per spec.
  If any part of the sign-in or EcoCash flow ever introduces a cross-domain redirect that
  needs a cookie set on that other domain, this will block it — re-check this setting first
  if sign-in state behaves oddly after linking to a payment or auth redirect domain.
