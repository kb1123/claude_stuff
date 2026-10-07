# Super Zoom (Android)

A native Camera2 viewfinder with every back lens selectable and zoom up to 200x.
Needs Android 10 or newer. Photos are saved to Pictures/SuperZoom.

- Lens picker lists each back lens by its 35mm-equivalent focal length, including
  lenses that Android hides behind the main camera (ultra-wide, telephoto).
- Zoom is split in two: the camera crops its sensor as far as it allows (real detail),
  and the GPU scales the live preview for the rest. Pinch, drag to pan, slider, or presets.
- Res cycles the preview resolution (2, 5, 12 MP). Lower is faster, higher is sharper.
- Sharpen turns on the camera's own high-quality edge and noise processing.
- Capture saves exactly what you see, using the full-resolution preview frame.

## Build

Every push to `main` builds a debug APK on GitHub Actions and publishes it to a release:

https://github.com/kb1123/claude_stuff/releases/download/latest/SuperZoom.apk

If a build fails, the compiler output is published to
https://github.com/kb1123/claude_stuff/releases/download/buildlog/build.log

To build locally, open this folder in Android Studio and use Build > Build APK(s).
