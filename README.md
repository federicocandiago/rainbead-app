# Skypane for Android

An Android app for [Skypane](../cyberdesk), the desktop in the browser. It wraps the web desktop of any
Skypane instance in a native window. It is a twin of [kalban-app](https://github.com/federicocandiago/kalban-app).

- **First launch:** asks only for the address, in Italian or English to match the phone's language. It checks
  the address and reads the instance's web manifest to find the start page and the instance's paths. It never
  asks again. To switch instances, clear the app's data.
- **System bars** take the colour of the page, so they follow light and dark mode and the theme.
- **Links** inside the instance stay in the app. Anything else opens in the browser or the matching app.
- File uploads, downloads with the session cookies, a back button that walks the page history, and predictive back.
- Debug builds let the page be inspected (`chrome://inspect`) and driven by the end-to-end tests.
- Targets Android 17 (API 37). Needs Android 12 (API 31) or later.

## Build

Requirements: JDK 17 and the Android SDK with platform `android-37.0`. Set the SDK path in `local.properties`
(`sdk.dir=…`) or with `ANDROID_HOME`.

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # build and install on a connected device
```

## Try it against a local server, over USB

```sh
adb reverse tcp:8000 tcp:8000  # the phone's localhost:8000 is the computer's
```

Then type `http://localhost:8000` on first launch. Being "localhost", the page is a secure context: the service
worker and the installable app work without https, and nothing is exposed to the network.

Release builds are signed when a properties file is found at `~/.android/skypane-release.properties` (or at the
path in `SKYPANE_SIGNING`), with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.

## License

[GNU General Public License v3.0](LICENSE).
