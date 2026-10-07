# Super Zoom (Android)

A small Android app that wraps `app/src/main/assets/zoomcam.html` in a WebView
with camera permission, so the page can open your phone's camera.
Saved photos go to Pictures/SuperZoom. Needs Android 10 or newer.

## Build the APK with GitHub (no Android Studio)

1. Create a free GitHub account and a new empty repository.
2. Upload every file in this folder to it, keeping the folder structure.
   Make sure the hidden `.github/workflows/build.yml` file is included.
3. Open the repository's **Actions** tab, pick **Build APK**, and tap **Run workflow**.
4. When the run finishes (about 3 to 5 minutes), open it and download
   the **SuperZoom-apk** artifact. Unzip it to get `app-debug.apk`.
5. Copy the APK to your phone and open it. Android will ask you to allow
   installing from that source (your file manager or browser).

## Build with Android Studio

Open this folder in Android Studio, let it sync, then Build > Build APK(s).
The APK lands in `app/build/outputs/apk/debug/`.
