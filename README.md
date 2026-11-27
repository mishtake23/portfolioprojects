# BackTouch

Use the cover (back) screen of a foldable as a trackpad for the unfolded inner screen.

## Install
Download the latest build on the phone:
https://github.com/mishtake23/portfolioprojects/releases/download/debug-latest/app-debug.apk

Every push rebuilds the APK with GitHub Actions (`.github/workflows/build-apk.yml`).

## Setup
1. Open BackTouch and tap **Turn on**. Switch BackTouch on in Accessibility. This is a one-time step;
   Android does not let any app do this by itself.
2. Unfold the phone and tap **Start on cover screen**.

If the switch is greyed out (apps installed outside the Play Store), open App info, tap the menu,
choose "Allow restricted settings", and try again.

Optional: `adb shell pm grant com.example.backtouch android.permission.WRITE_SECURE_SETTINGS`
lets BackTouch switch itself back on after updates and reboots.

## How it works
- Samsung Folds expose one logical display that switches between the inner and cover panel, so the
  trackpad is shown on the cover screen with Jetpack WindowManager's rear-display presentation API.
- An accessibility service draws the pointer on the inner screen and performs taps, long presses,
  drags and scrolls with `dispatchGesture`, and Back / Home / Recents with global actions.

## Gestures
| Gesture | Action |
|---|---|
| Slide 1 finger | Move pointer |
| Tap / tap twice | Click / double click |
| 2-finger tap | Right click (long press) |
| Hold until it buzzes | Long press; slide after the buzz to drag |
| Slide 2 fingers | Scroll (flings on release) |
| 3 fingers up / left / right / down | Home / Back / Recent apps / Notifications |
| 3-finger tap | Pause / resume |
| Bottom bar | Back / Home / Recents |
