# SpeakTouch Testing

## 1. Prerequisites

- Windows Command Prompt or PowerShell and the repository's Gradle wrapper.
- An Android development environment with JDK 21 (the modules request an Adoptium toolchain), Android SDK platform 36 and Build Tools 36.0.0. Configure the SDK location through Android Studio or `local.properties`.
- Android SDK platform-tools, with `adb` available on PATH.
- An Android device with USB debugging or wireless debugging enabled and the development computer authorized. The View test app supports API 22+; the Compose test app requires API 23+.
- Enable the installed SpeakTouch accessibility service in Android settings for screen-reader checks. Run comparisons with other screen readers separately.

Commands below run from the repository root. In PowerShell, prefix the wrapper with `.\`, for example `.\gradlew.bat :app:testDebugUnitTest`. Command Prompt accepts `gradlew.bat` directly. Linux/macOS use `./gradlew` instead.

## 2. Repository build commands

Full app unit tests:

```bat
gradlew.bat :app:testDebugUnitTest
```

Build SpeakTouch debug APK:

```bat
gradlew.bat :app:assembleDebug
```

Build accessibility test APK:

```bat
gradlew.bat :test:assembleDebug
```

Build both:

```bat
gradlew.bat :app:assembleDebug :test:assembleDebug
```

Common combined verification:

```bat
gradlew.bat :app:testDebugUnitTest :app:assembleDebug :test:assembleDebug
```

Clean all modules or just the app:

```bat
gradlew.bat clean
gradlew.bat :app:clean
```

For suspicious stale/incremental build behavior, use this stronger diagnostic run. It is not the normal first command:

```bat
gradlew.bat :app:clean :app:testDebugUnitTest --no-build-cache --rerun-tasks -Pkotlin.incremental=false
```

Focused tests can be selected by class, for example:

```bat
gradlew.bat :app:testDebugUnitTest --tests "*ContextMenuControllerTest" --tests "*ContextMenuSessionTest" --tests "*EmbeddedLinksTest"
```

Unit-test reports are under `app\build\reports\tests\testDebugUnitTest`; XML results are under `app\build\test-results\testDebugUnitTest`. Check timestamps before relying on reports from a failed build.

## 3. Installing APKs with ADB

```bat
adb devices
adb install -r app\build\outputs\apk\debug\SpeakTouch-debug.apk
adb install -r test\build\outputs\apk\debug\test-debug.apk
```

`-r` replaces the installed APK while retaining app data where Android permits. The SpeakTouch debug application ID is `com.neo.speaktouch.debug`; the View test app is `com.neo.speaktouch.test`.

With multiple devices, specify the serial listed by `adb devices`:

```bat
adb -s SERIAL install -r <apk>
```

For stale USB/wireless connections, restart the ADB server and check the device list:

```bat
adb kill-server
adb start-server
adb devices
```

## 4. Manual test application

The separate View accessibility test APK has application ID `com.neo.speaktouch.test` and activity namespace `com.neo.test`. Direct ADB launch is supported developer/test infrastructure and avoids relying on launcher discovery. The current manifest also gives MainActivity, TraversalOrderActivity, ProgressActivity and ContextMenuActivity launcher entries.

The main screen contains the existing View-based reading/grouping, state-description, slider and labelFor fixtures:

```bat
adb shell am start -n com.neo.speaktouch.test/com.neo.test.MainActivity
```

Traversal ordering has its own screen, isolating it from the main screen's grouping behavior:

```bat
adb shell am start -n com.neo.speaktouch.test/com.neo.test.TraversalOrderActivity
```

Check forward/backward order: A is First, Second, Third; B (BEFORE) and C (AFTER) are First, Third, Second. Backward navigation should reverse each sequence.

The separate `test-compose` module provides merged-content and traversal fixtures. See [the Compose testing guide](../test-compose/README.md) for its build, case-selection and hierarchy-capture commands.

## 5. Context menu fixture

```bat
adb shell am start -n com.neo.speaktouch.test/com.neo.test.ContextMenuActivity
```

This screen exercises #37 context menus, #98 provider actions, Copy, embedded Links and #90 custom labels.

Open the SpeakTouch menu with **down then right**: one continuous one-finger gesture that changes direction, not two separate swipes. Commands operate on the original retained accessibility target, not the menu row receiving focus. Refocus the target between separate command invocations.

### Basic context-menu checks

- **Normal button:** focus it, open the menu and activate Click. `Clicks: 0` should increment exactly once.
- **Long-clickable button:** activate Long click. `Long clicks: 0` should increment exactly once.
- **Test checkbox:** verify the state-sensitive Check/Uncheck command changes its state once.
- **Paste:** activate `Prepare Paste for filled field` or `Prepare Paste for empty field`. These place `Paste test text` on the clipboard, give that EditText input focus and put its cursor at the end. Then accessibility-focus the field and open the menu. Paste is offered only when the provider advertises ACTION_PASTE and invokes that action. Clipboard contents and input focus matter; opening an arbitrary EditText menu alone is not reliable preparation.
- **Clear:** focus the filled editable field, activate Clear and verify the entire field becomes empty.

### #98 Actions

1. Focus `Action target`, which must be its own accessibility navigation stop, separate from status text.
2. Open the menu and activate Retry.
3. Verify only `Retry: 0` increments.
4. Refocus `Action target`, reopen the menu and activate Archive.
5. Verify only `Archive: 0` increments.

### Removed target safety

Activate `Remove Action target in 15 seconds`, return to `Action target` and open its menu. Wait for removal, then attempt execution. It must fail safely and must never execute against a different node. Relaunch the activity to recreate this target.

### #90 Custom labels

The section `Custom labels: A, B, shared C and D, then no ID` contains unlabeled ImageButtons with separate counters. Focus the button following `Custom label A clicks: 0`:

1. Open Add label; verify an empty editor and usable keyboard. Enter `Share`, Save, then refocus A: expect the custom label plus its existing button role.
2. Activate normally and verify A's counter changes. Edit the label, refocus and verify the replacement. Remove it and verify the role-only announcement returns.
3. Save a label again and restart SpeakTouch: the label should persist. B has a different ID and must remain unaffected. C and D deliberately share an ID, so a label assigned to either applies to both.
4. The no-ID control and password comparison must not offer label commands. `Sensitive label comparison` is present only on API 34+ and must also be excluded.
5. `App provided label` may receive a stored label, but its app-provided content must remain spoken.
6. Cancel must not save. Use `Change custom label A ID in 15 seconds` or `Remove custom label A in 15 seconds`, return to A and open its editor before the delay expires. Save after the change must fail safely. Use `Restore custom label A` to restore the fixture.

See [custom-label behavior and persistence](custom-labels.md) for exact identity rules and limitations.

## 6. Copy testing

Copy is for meaningful own content of noninteractive readable leaf targets, not the full role/state announcement assembled by Reader. The current precedence is nonblank contentDescription, then text. Hints, state, roles, actions, custom labels and grouped descendants are not clipboard sources.

- `Copy sample: café 👋, punctuation!`: Copy should preserve its own Unicode text and punctuation.
- `Description only`: a passive description-only target may offer Copy.
- The target with visible text `Visible text` and description `Preferred description` should copy `Preferred description`.
- Buttons, long-clickable/checkable/editable controls, password/sensitive targets, sliders/progress controls and grouped containers must not acquire Copy merely because they have labels or spoken content.
- The `Transfer` group must not expose Copy for its partial parent content; its Download/Loading descendants must not be copied as an aggregate.
- Deliberately non-copyable targets with no other command may report `No actions available`.

After Copy, activate `Focus verification field for Paste`, accessibility-focus `Verification field`, and use Paste to inspect the result. This helper preserves the clipboard. Do not use the Prepare Paste helpers for this check: they replace the copied text.

## 7. Embedded Links testing

### Single embedded link

Focus `Open help`, open the menu, choose Links and activate its link row. Only `Help links: 0` should increment. Although represented by a URLSpan, this fixture invokes a provider callback rather than opening a browser.

### Multiple embedded links

Focus `Read Help and License`, choose Links and verify separate Help and License rows. Activate each in separate menu invocations. Only the selected link's Help/License counter should increment.

### Links Cancel

Focus `Read Help and License`, open Links and activate Cancel. The menu must close through the same session and restore original focus only when the target remains valid. Menu focus must never replace the original execution target.

### Changed-link stale protection

Activate `Change link text in 15 seconds`. Return to `Read Help and License` and open Links before the delay expires. After the text becomes `Links replaced`, select an old link row. Expect `Action no longer available`; no stale link counter should increment.

### Removed-target stale protection

Activate `Remove link text in 15 seconds`, return to the multi-link target and open Links. After removal, select a link row. Expect `Target no longer available`; no link action should execute.

Use `Restore link text` to restore the original text/spans and reinsert the target when removed.

### Comparison control

`Retry link comparison` is an ordinary clickable button. It should expose Click, increment only `Link comparison retries: 0`, and have no embedded Links submenu.

## 8. Progress fixture

```bat
adb shell am start -n com.neo.speaktouch.test/com.neo.test.ProgressActivity
```

This native fixture verifies #85 reading and focused live progress feedback. `Download progress` starts at 80 of 200 (40%); its companion initially shows `Value: 80 / 200`. Start advances by 20 every three seconds, stopping at 200. Reset restores 80 and cancels pending advancement. Leaving the activity stops advancement.

1. Focus the bar and verify its accessible name, progress-bar role and 40% value.
2. Activate Start, then return accessibility focus to the bar promptly. Start itself does not request accessibility focus; normal activation/navigation can still move focus, so explicitly check which item is focused.
3. While the bar stays focused, verify state-only percentage announcements are throttled to about five seconds, retaining the latest pending value rather than a backlog. Completion at 100% should be prompt.
4. Listen for short tones rising in pitch as progress increases. Tone cadence is separate from speech; unchanged values must not produce duplicate tones.
5. Move focus away while progression continues: further progress speech/tones must stop. Companion value-text events must not drive progress feedback.
6. Reset and refocus: expect 40%. Up-then-down re-read should reflect the current value without moving focus.

The activity contains one progress bar. Unfocused bars, sliders, invalid/indeterminate ranges and unrelated events are covered by automated progress tests; do not assume an additional manual comparison control exists here.

## 9. Gesture regression checklist

Recheck these assignments after gesture-related work:

| Gesture | Expected behavior |
| --- | --- |
| Swipe left | Previous item |
| Swipe right | Next item |
| Swipe up/down | Increase/decrease the actually focused slider; unhandled away from sliders |
| Up then down | Re-read current focused item without moving focus |
| Down then left | Android Back |
| Down then right | SpeakTouch context menu |
| Two-finger single tap | Pause/restart saved announcement on API 30+; restart uses the full saved text |
| Two-finger double tap | Android headset-hook global action on API 31+; phone/media response depends on Android and the active app |

Multi-part swipes are continuous direction-changing gestures. Ordinary framework double-tap activation and double-tap-and-hold/app long-press must remain available. Verify ordinary touch still stops speech and two-finger scrolling remains usable; the service requests two-finger passthrough on API 31+. Do not treat headset-hook dispatch as a guarantee that every media app will toggle playback.

## 10. Multiple ADB devices

Plain ADB commands are ambiguous when several devices are connected. List them and select the intended serial:

```bat
adb devices
adb -s SERIAL shell am start -n com.neo.speaktouch.test/com.neo.test.ContextMenuActivity
adb -s SERIAL install -r test\build\outputs\apk\debug\test-debug.apk
```

Replace SERIAL with an actual value from the device list. `adb -d` selects a USB-connected device only; do not use it for an intended wireless device.

## 11. Automated tests vs manual device testing

Passing unit tests is necessary but insufficient for an accessibility service. Manual Android-device verification is required for accessibility focus, overlays, gesture dispatch, TTS interaction, clickable spans, provider actions, live progress events and OEM-specific behavior.

Sandbox/CI environments can encounter AAPT2 resource-link limitations before tests or packaging run. Report that separately from source/test failures; do not change repository configuration to accommodate an environment-specific linker crash. A normal local Windows Android build and device run remain authoritative for final verification. Real local unit-test failures must still be investigated and corrected; sandbox limitations do not excuse them.

## 12. Before committing

```bat
git status
git diff --check
```

- Run relevant focused tests.
- Run the full app unit suite.
- Build affected APKs.
- Manually verify device behavior when accessibility behavior changed.
- Confirm no accidental `.idea`, generated-file or build-configuration changes.
- Only then commit/push according to the intended review workflow.
