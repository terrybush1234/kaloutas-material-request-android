# Kaloutas Material Request (Android)

Native Android wrapper for the Kaloutas Material Request Google Apps Script
web app. Shows a splash screen with the app logo and form name, then loads
the form in a full-screen WebView.

## 1. Set your Web App URL

In your Google Apps Script project: **Deploy → Manage deployments → Web app
→ URL** (it ends in `/exec`, not `/edit`). Then open
`app/build.gradle.kts` and replace the placeholder:

```kotlin
buildConfigField(
    "String",
    "WEB_APP_URL",
    "\"https://script.google.com/macros/s/REPLACE_WITH_YOUR_DEPLOYMENT_ID/exec\""
)
```

with your real deployment URL.

## 2. Logo / icon / splash

The launcher icon and splash screen use the real Kaloutas logo, generated
into `app/src/main/res/mipmap-*/` and `app/src/main/res/drawable/logo_splash.png`.
To swap in an updated logo later, use Android Studio's **New → Image Asset**
wizard (right-click `res` → New → Image Asset) for the launcher icon, and
replace `logo_splash.png` for the splash screen.

## 3. Rename the app / form name (optional)

Edit `app_name` and `splash_tagline` in
`app/src/main/res/values/strings.xml`.

## 4. Open and run (debug)

Open this project folder in Android Studio (Iguana or newer), let it sync,
then Run on a device/emulator (minSdk 24 / Android 7.0+).

From the command line:

```
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

## 5. Release build for Google Play

Play Store requires a **signed release App Bundle (.aab)**, not the debug
APK used above. The signing key is never committed to this repo.

**One-time setup — generate your own signing key:**

1. In Android Studio: **Build → Generate Signed Bundle / APK…**
2. Choose **Android App Bundle**, click **Next**.
3. Under "Key store path," click **Create new…**
4. Save the `.jks` file **somewhere outside this project folder** (e.g. a
   password manager's secure notes folder, or a dedicated secrets folder
   you back up separately) — never inside the git repo. Fill in a strong
   store password and key password, alias (e.g. `kaloutas-release-key`),
   and validity of at least 25 years (Play requires the cert to stay valid
   past October 2033).
5. **Back this file up somewhere safe outside this repo.** If you lose it,
   you can never publish another update to this app listing again — Google
   cannot recover or reset it for you.
6. Copy `keystore.properties.template` to `keystore.properties` (same
   folder as this README) and fill in the path to your `.jks` file and the
   passwords/alias you just chose. This file is gitignored — don't commit
   it.

**Every release after that**, just run:

```
./gradlew bundleRelease
```

The signed `.aab` is written to `app/build/outputs/bundle/release/`. Upload
that file to **Play Console → your app → Production (or a testing track) →
Create new release**.

Each new version you upload needs a higher `versionCode` in
`app/build.gradle.kts` (`versionName` is the human-readable version, e.g.
`"1.1"`; `versionCode` is an internal integer that must increase every
release, e.g. `2`, `3`, ...).
