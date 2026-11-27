# BackTouch

Use the cover (back) screen of a foldable as a trackpad for the unfolded inner screen.

## Status
- **Step 1 (done):** setup screen lists all displays and launches the trackpad on a chosen display.
- **Step 2 (done):** accessibility service draws a cursor on the inner screen and taps / scrolls at it.
- **Step 3 (partly done):** drag, pointer acceleration, settings sliders, palm rejection, pause gesture.
- **Step 3 (todo):** Shizuku/ADB route (real mouse pointer, waking the cover display). This depends on the result of the first phone test.

The Kotlin sources compile against the Android 14 framework classes. The full Android build
(manifest, resources, APK) has not been run yet, and nothing has been tested on a device.

## Gestures (on the cover screen)
| Gesture | Action |
|---|---|
| Move one finger | Move cursor (faster swipes go further) |
| Tap | Click (tap twice quickly to double-click) |
| Hold, then move | Drag (cursor turns blue) |
| Hold, release | Long press |
| Two fingers move | Scroll |
| Two-finger tap | Long press ("right click") |
| Three-finger tap | Pause / resume |

Palm rejection: touches that start near an edge (adjustable) or with a large contact area are ignored.

## Build
Open this repository folder in Android Studio (it generates the Gradle wrapper), run on the phone.

## Try it
1. Install, open the app, tap "Enable accessibility service" and turn BackTouch on.
2. Unfold the phone. The list shows each display's id and state.
3. Tap "Open trackpad on display N" for the cover display, then use the gestures above.

Key unknown: whether the cover display accepts touch while the phone is unfolded. If not, Step 3 uses ADB/Shizuku to force it on.

## Repo note
`covid-sql-analysis/` is unrelated to this app. It stays here only until it has moved to its own repository.
