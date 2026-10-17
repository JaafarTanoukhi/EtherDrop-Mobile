# EtherDrop Mobile

The Android subset of EtherDrop Desktop 2.0. It reuses the desktop Wi-Fi theme, icons, screen names, transfer options, approval flow, progress metrics, and diagnostics, with layouts sized for phones.

The app sends and receives files and folders directly between Android and Windows, or between Android devices, on the same Wi-Fi network or hotspot. There is no Ethernet option or mobile update checker. iPhone support remains on hold.

## Install on your Android phone

The Android APK built successfully on October 17, 2026. [Download the APK](https://expo.dev/artifacts/eas/scajZTQyJAjxsp6mc-ylkyWAGhFuidBRyU2jrK5uBk4.apk), or open the [Android build page](https://expo.dev/accounts/jaafar-tanoukhis-team/projects/jaafar-tanoukhi/builds/6713baf6-2be7-4643-9b35-00d046a0c4da).

1. Open the APK download link on your Android phone and download the file.
2. Open the downloaded APK. If Android asks, allow **Install unknown apps** for the browser or file manager you used, then tap **Install**.
3. If the ringtone example is installed, choose **Update**. This app uses the same package and signing key.
4. Open **EtherDrop** and allow notifications when asked so you can see background transfer status.

The app uses a local Android module and needs its own APK; it cannot run inside standard Expo Go. Physical-device checks are still needed for Android's document picker, Wi-Fi discovery, notifications, and locked-screen transfers.

No Android Studio, Android SDK, emulator, or Google Play developer account is needed for this cloud-build workflow. The standalone APK works without Expo or a development server. The local Wi-Fi network does not need internet access.

## Live testing on Android

The development APK includes Expo's development client and the same native EtherDrop transfer module. Install it once, then load the JavaScript from your PC over Wi-Fi.

The development build completed on October 17, 2026. [Download the development APK (171.2 MB)](https://expo.dev/artifacts/eas/Zn5Zal37hEVEa7ii6zd_CmozSR3vPZNKUrb62xvqnhQ.apk), or open its [build page](https://expo.dev/accounts/jaafar-tanoukhis-team/projects/jaafar-tanoukhi/builds/4ebaab7c-f051-49d9-9b56-b05eec1220ad).

1. Install the development APK on your phone, choosing **Update** if EtherDrop is already installed.
2. Connect the phone and PC to the same Wi-Fi network or hotspot.
3. Open PowerShell in this folder and run `npm start`. Use `npm run start:offline` when internet access is unavailable.
4. Open EtherDrop on the phone and select the development server. If it is not listed, enter the `http://<PC Wi-Fi address>:8081` URL shown by Expo.
5. Keep the development server running. Saving changes to React screens, styles, or JavaScript updates the app through Fast Refresh over the local network.

If a VPN causes Expo to advertise the wrong interface, set `$env:REACT_NATIVE_PACKAGER_HOSTNAME = '<PC Wi-Fi address>'` in the same PowerShell window before starting the server. Allow Node.js through Windows Firewall on your private network if Windows prompts.

Java/Kotlin changes, native dependencies, permissions, or other native configuration changes require another development APK. Build it with `$env:EAS_NO_VCS = '1'` followed by `npm run android:build:dev`. Development APKs need the PC's server to load the current app; use the standalone APK for normal use without a server.

## Transfer files

1. Join the same Wi-Fi network or hotspot on every device. On Windows, choose **Wi-Fi** in EtherDrop.
2. Choose **Send files** on one device and **Receive files** on the others.
3. Use **Add files…** or **Add folder…**, then **Send**. Every discovered receiver receives its own offer.
4. Each receiver chooses a destination folder and accepts independently. Android uses its system folder picker.
5. Review progress under **Overview** or diagnostics under **Details**. Tap **Done** after the transfer finishes.

**Transfer options** includes **Wrap transfer in a folder** (on by default) and **Verify files with SHA-256** (off by default). Conflicting destination file names offer **Keep both files**, **Replace existing file**, or **Skip this file**.

Transfers continue when switching apps or locking the screen, using an Android foreground service with a status notification. A temporary connection interruption preserves completed files and the last completed 8 MiB block; the same transfer resumes after reconnecting on the same local address. Keep both apps running. Changing networks or addresses requires a new connection setup. Closing or force-stopping an app does not preserve a resumable session across launches.

**Cancel transfer** removes new receiver output. Existing files that you explicitly chose to replace are not restored. **Cancel all** stops all recipients; declining or failing on one receiver does not stop the others.

The native engine uses the desktop V3 protocol: UDP broadcast on **45670**, direct TCP on **45671**, length-prefixed UTF-8 JSON control frames, streamed file bytes, per-file acknowledgements, optional SHA-256, heartbeats, and reconnect checkpoints. Guest-network client isolation can block discovery and transfers.

EAS has free build quotas and a queue. Uploading the project and downloading an APK use internet data. The npm cache and installed dependencies are excluded from the upload.

## Build an updated APK

The project is linked to Expo project `b97e20ad-a770-4094-950d-40c48973bb05`, owned by `jaafar-tanoukhis-team`. Its Android signing keystore was generated and is managed by EAS.

1. Open PowerShell in this folder and run `npx eas login` if you are signed out.
2. Run `$env:EAS_NO_VCS = '1'` so the project can build without a Git repository or a commit.
3. Run `npm run android:build`.
4. When the build finishes, open its new download link on your phone to install the updated APK.

## iPhone support

An iPhone version can share these React screens and the V3 protocol. It needs an iOS networking/storage implementation inside this project. EAS internal distribution to a real iPhone requires a paid Apple Developer membership and registered test devices. No iOS app is currently implemented or built.

## Checks

- `npm run check` checks dependency compatibility.
- `npm run export:android` validates the Android JavaScript bundle. This creates a JavaScript export, not an APK, and does not compile the native module.
- `npx expo-modules-autolinking resolve --platform android` checks native-module discovery.
- `./scripts/test-protocol.ps1` compiles the production Java transfer engine with the installed JDK and tests it against the unchanged desktop Python engine. It uses the desktop `.venv`; set `-JavaHome` if your JDK path differs. Its small JSON test dependency is downloaded into the ignored `.expo/test-deps` folder on first use.

The nine integration checks cover Windows-to-mobile and mobile-to-Windows transfers, folders and Unicode names, SHA-256, multiple independent recipients, mobile-to-mobile transfers, approval, cancellation cleanup, reconnects in both directions, unsafe-path rejection, discovery packets, and recovery after the Wi-Fi binding returns.

## UI development preview

Run `npx expo start --web --localhost`. The browser renders the actual React Native screens using clearly labelled preview fixtures; it does not transfer files. The Android bundle uses the real native engine and excludes those fixtures. Browser preview dependencies are development-only. The app's native entry point is `modules/etherdrop/etherdrop-module.js`.
