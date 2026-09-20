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

## 2. Replace the placeholder logo (optional)

The launcher icon and splash logo are simple generated vector drawables at:

- `app/src/main/res/drawable/ic_launcher_background.xml`
- `app/src/main/res/drawable/ic_launcher_foreground.xml`

To use your real logo instead, use Android Studio's **New → Image Asset**
wizard (right-click `res` → New → Image Asset) and point it at your logo
image — it will regenerate the adaptive icon and all mipmap densities for
you. For the splash screen image, drop a PNG into `res/drawable` and point
`app/src/main/res/layout/activity_splash.xml`'s `splashLogo` `android:src`
at it.

## 3. Rename the app / form name (optional)

Edit `app_name` and `splash_tagline` in
`app/src/main/res/values/strings.xml`.

## 4. Open and run

Open this project folder in Android Studio (Iguana or newer), let it sync,
then Run on a device/emulator (minSdk 24 / Android 7.0+).

From the command line:

```
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.
