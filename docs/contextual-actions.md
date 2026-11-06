# Contextual Actions navigation (Stage 3)

This implementation adds Actions to the existing navigation modes on Android 13/API 33
and later, after touch-controller registration succeeds. Older Android versions retain
their previous framework gesture handling and navigation modes.

- Three-finger down/up selects the next/previous available mode.
- In Actions, one-finger down/up cycles the focused node's supported actions and Activate.
- Double-tap executes the selected action, or performs normal Android activation for Activate.
- Double-tap-and-hold always goes to Android's `performLongClickAndStartDrag()`, including
  continued finger movement and release. It does not execute a selected custom action.
- Slider up/down adjustments keep precedence. Left/right sequential navigation is unchanged.
- Focus/window changes discard the selected action. Mode changes restore normal activation.
- Nodes without supported contextual actions use effective Default navigation.
- Changed, removed, stale or disabled actions are revalidated before execution. A rejected
  selected action gives failure feedback; that same tap never falls through to ordinary click.

## Source reuse and ownership

The isolated `com.neo.speaktouch.gestures` package adapts BackTalk recognizers and touch
state handling. It does not import BackTalk speech, navigation, menus, rotor, analytics or
typing settings. Attribution, source revision and Apache licence are in THIRD_PARTY_NOTICES
and `app/src/main/assets/licenses`, which are also packaged in the APK.

As in BackTalk's modern path, service double-tap ownership stays enabled while the callback
session is active. Leaving Actions switches dispatch back to Android's normal click without
resetting the active touch stream. Teardown unregisters callbacks, cancels pending work and
restores the flag's prior state. Initial displays are registered at service connection.

## Verification before device installation

Use the existing Java 21 toolchain and run:

```bat
gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Automated tests cover selection, stale actions, API guards, registration cleanup, recognition,
and requests to Android for click/hold/drag. They cannot prove real app drag movement/release
or physical touch-exploration behaviour. Do not consider Stage 3 device-verified until those
checks pass on the Oppo CPH2197. Keep BackTalk installed and available for recovery.

On the Oppo, check:

1. Ordinary touch exploration, double-tap activation and native edit-field activation.
2. Context-menu test activity: focus Action target; select Actions; select Retry or Archive;
   double-tap and verify that only the corresponding counter changes.
3. Cycle to Activate and verify ordinary activation. Move to a different node and confirm
   the previous custom action cannot execute. Check a node with no custom actions.
4. Double-tap-and-hold a fixture control with a long-click counter; verify one long-click.
5. On a draggable app item, hold and move without lifting, return to a safe location and
   release. Verify no continued drag after release. This is a manual framework/device check.
6. Check Characters/Words/Lines, Headings forward/backward and boundaries, sliders, two-finger
   scrolling, pause/restart, re-read, Screen Curtain, launcher page/focus recovery and ordinary
   sequential navigation. Repeat activation outside Actions and after reconnecting SpeakTouch.

No APK is installed or service enabled by the build. Installation and activation require
Mike's approval. Do not use the Samsung S22 for development testing.
