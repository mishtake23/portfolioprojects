# BackTouch

Use the cover (back) screen of a foldable as a trackpad for the unfolded inner screen.

## Status
- **Step 1 (done, untested):** setup screen lists all displays and launches a trackpad activity on a chosen display.
- **Step 2 (done, untested):** accessibility service draws a cursor on the inner screen and performs tap / long-press / two-finger scroll at the cursor.
- **Step 3 (todo):** Shizuku/ADB route (real mouse events, wake the cover display), drag, double-tap, sensitivity settings, palm rejection.

Not compiled yet: no Android SDK was available where this was written.

## Build
Open `backtouch/` in Android Studio (it generates the Gradle wrapper), run on the phone.

## Try it
1. Install, open the app, tap "Enable accessibility service" and turn BackTouch on.
2. Unfold the phone. The list shows each display's id and state.
3. Tap "Open trackpad on display N" for the cover display. Move one finger = cursor, tap = click, hold = long press, two fingers = scroll.

Key unknown: whether the cover display accepts touch while the phone is unfolded. If not, Step 3 uses ADB/Shizuku to force it on.
