# Compose accessibility reproduction track

This application isolates issue #92 from SpeakTouch production and the View test app.
Compose UI/Foundation are pinned to 1.10.4; the compiler matches the existing Kotlin 2.2.0.
No Material dependency is used. The new app's minimum SDK is 23; existing modules retain their SDK settings.

Build: `gradlew.bat :test-compose:assembleDebug :test-compose:assembleDebugAndroidTest`

Use Previous/Next case buttons. Only one fixture is shown at a time. The case heading and navigation buttons are outside the fixture.
You can also launch a specific case using `adb shell am start -S -n com.neo.speaktouch.testcompose/com.neo.testcompose.MainActivity --ei case N` (zero-based).

| Case | Fixture | Expected accessibility navigation |
| --- | --- | --- |
| 1 | Unmerged comparison | Download and Loading are separate stops. |
| 2 | Merged passive children | One stop containing Download and Loading. |
| 3 | Nested passive container | One stop containing Download and Loading. |
| 4 | Parent own semantics text | Compare Transfer plus descendant speech; record each reader's behavior. |
| 5 | Independent actionable child | Download/Loading group, then independent Retry button. Activation increments Retry's label. |
| 6 | Invisible merged group | No Download/Loading stop; both alpha-zero and accessibility-hidden semantics are applied. |
| 7 | Group inside collection | Before group, Download/Loading group, After group. |
| 8 | Explicit traversal order | Source First/Second/Third, indices 0/2/1 inside a traversal group. Expected First/Third/Second. |
| 9 | Traversal comparison | Plain Column with First/Second/Third; expected structural order. |

Run the same sequence with TalkBack and current SpeakTouch separately. Do not treat a merged Compose testing tree as proof of service navigation.
For the basic case, one combined stop with no separate passive children in current SpeakTouch demonstrates that no new production change is needed for that fixture.
To specifically attribute the result to #120, compare against a SpeakTouch build from before #120.

Optional full platform hierarchy capture (install the debug AndroidTest APK too):
`adb shell am instrument -w -e case 1 com.neo.speaktouch.testcompose.test/com.neo.testcompose.HierarchyCapture`

The capture instrumentation opens the selected zero-based fixture, reads nodes/actions without invoking them, and uses DONT_SUPPRESS_ACCESSIBILITY_SERVICES on API 24+.
It includes the screen-reader-focusable flag on API 28+. UIAutomation's view of the provider is evidence about exposure, not a substitute for observing actual screen-reader focus and speech.

## Issue #96 reproduction

The View test app has a separate launcher entry, **Traversal order tests**, with
A (no links), B (BEFORE-only), and C (AFTER-only) button rows. Each row is visually
First/Second/Third. All three compact rows fit without a scroll wrapper; their
container has no accessibility stop. The original ReadChildren screen is unchanged.
Launch directly with:
`adb shell am start -n com.neo.speaktouch.test/com.neo.test.TraversalOrderActivity`

Record forward and backward
order with TalkBack and SpeakTouch separately. A should remain First/Second/Third;
B and C declare First/Third/Second. These are expectations, not measured device results.

Compose case 8 is zero-based `case 7`; comparison case 9 is `case 8`.
Capture case 8 with:
`adb shell am instrument -w -e case 7 com.neo.speaktouch.testcompose.test/com.neo.testcompose.HierarchyCapture`

To capture the View test app already open, without launching another activity:
`adb shell am instrument -w -e current true com.neo.speaktouch.testcompose.test/com.neo.testcompose.HierarchyCapture`

For Compose, use the explicit `case` argument instead: instrumentation may restart
its target Compose process, so preserving an already-open Compose case is not assumed.

The JSON now contains a flat `nodes` array, with snapshot IDs, parent IDs, child
indices and child ID arrays. Each node includes screen bounds and shallow
traversalBefore/traversalAfter references. References outside the collected tree
are marked; traversal links are never recursively followed. Snapshot IDs identify
nodes only within that capture. Capture may wait up to five seconds for idle.
