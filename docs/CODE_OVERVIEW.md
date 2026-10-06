# SpeakTouch code overview

Static assessment: 2026-10-06, baseline `f8bc445`, branch `docs/code-assessment`. Source is the authority. The user reports working speech and some gestures on a real device. No build, tests, device experiment, migration, or production/build changes were performed here. See [capabilities](CURRENT_CAPABILITIES.md) and [flow](ARCHITECTURE_FLOW.md).

## Structure

| Location | Purpose |
| --- | --- |
| app | Android screen reader application: AccessibilityService, no declared activity. |
| test | Separate manual accessibility fixture application, not an instrumentation test module. |
| build-logic/plugins | Included build providing custom version plugin and property-loading helper. |
| gradle | Wrapper and shared version catalog. |
| .github | Build/wrapper workflows and issue/PR templates. |

[settings.gradle.kts](../settings.gradle.kts) includes both Android modules and build-logic. Production Kotlin is under app/src/main/kotlin/com/neo/speaktouch: service, controller, intercepter (actual spelling), di/module, di/entrypoint, model, utils, utils/extension. Resources include service XML, translations, themes, backup templates. Unit tests are in app/src/test/kotlin; manual fixtures in test/src/main/kotlin/com/neo/test and test/src/main/res/layout.

## Service and configuration

[SpeakTouchService](../app/src/main/kotlin/com/neo/speaktouch/service/SpeakTouchService.kt) extends AccessibilityService and uses @AndroidEntryPoint. [Manifest](../app/src/main/AndroidManifest.xml) declares .service.SpeakTouchService, exported with BIND_ACCESSIBILITY_SERVICE permission, the AccessibilityService intent action, spoken/haptic categories, and android.accessibilityservice metadata referencing @xml/accessibility_service_config. It declares VIBRATE, TTS_SERVICE visibility, and SpeakTouchApplication.

| Resource/API | Configuration |
| --- | --- |
| [xml](../app/src/main/res/xml/accessibility_service_config.xml), 22–25 | typeAllMask; spoken/haptic; flagDefault, flagRequestTouchExplorationMode, flagRetrieveInteractiveWindows; canRequestTouchExplorationMode/canRetrieveWindowContent true; notificationTimeout 0. |
| [xml-v26](../app/src/main/res/xml-v26/accessibility_service_config.xml), 26–30 | Above plus flagEnableAccessibilityVolume. |
| [xml-v31](../app/src/main/res/xml-v31/accessibility_service_config.xml), 31+ | Above plus flagRequestMultiFingerGestures and isAccessibilityTool=true. |

onServiceConnected adds FLAG_REQUEST_2_FINGER_PASSTHROUGH on API 31+. No package restriction, settings activity, key-filter request or gesture injection capability is declared. Navigation uses rootInActiveWindow, not traversal across windows. All-event subscription does not mean all events are handled.

## Runtime and important classes

Application initializes Hilt/debug Timber. Service creation installs a weak reference in Controllers. Hilt creates controllers/interceptors/TTS. Successful TTS initialization speaks activation. Window-state changes log title and attempt moveFocusToFirst. Events then pass through speech, focus, haptic, callback, in that order. Hover/input-focus/click can request accessibility focus; the resulting focus event produces speech/vibration. Titles are logged, not directly spoken.

Class paths below are relative to production package com.neo.speaktouch.

| Class/file | Responsibility |
| --- | --- |
| service/SpeakTouchService | Lifecycle, window handling, events, legacy onGesture(Int); onInterrupt no-op. |
| intercepter/Interceptors | Fixed event list and separate gesture handler. |
| intercepter/event/SpeechInterceptor | Stop at touch interaction start; speak accessibility-focused source; shutdown TTS on finish. |
| intercepter/event/FocusInterceptor | Choose hover/input-focus/click source or readable ancestor. |
| intercepter/event/HapticInterceptor | Heavy click for clickable focused node, tick otherwise. |
| intercepter/event/CallbackInterceptor | Latest matching scroll continuation on TYPE_VIEW_SCROLLED. |
| intercepter/event/contract/EventInterceptor | handle contract and default no-op finish. |
| intercepter/gesture/GestureInterceptor | Four hardcoded gesture mappings. |
| controller/FocusController | Forward/backward scans, ancestor scrolling, first-focus attempt. |
| controller/ServiceController | Active root, accessibility focus, service/global actions. |
| controller/SpeechController | Node/resource speech, stop/shutdown. |
| controller/VibratorController | API-specific vibration effects and fallback. |
| controller/Controllers | Speech via Hilt entry point and weak service reference. |
| utils/Reader | Content/type/state composition and child aggregation. |
| utils/NodeValidator; model/NodeFilter | Readability/grouping/traversal eligibility. |
| model/Type | Widget/property classification and localized labels. |
| model/Text | Raw/resource text and recursive format argument resolution. |
| model/LegacyVibrationEffect | Heavy/tick patterns. |
| utils/Node | Debug recursive JSON content/class/visibility/importance/actions dump. |
| utils/Installable | Weak reference install/clear. |
| utils/extension/NodeScan | Directional ancestor/descendant traversal; exception terminates scans. |
| utils/extension/AccessibilityNodeInfo | Content/state, ancestors, children, focus action, debug action reflection. |
| Other extensions | Event predicates/log labels, flags, class matching, string/iterator/context/array helpers. |

## Speech/TTS

[ControllerModule](../app/src/main/kotlin/com/neo/speaktouch/di/module/ControllerModule.kt) constructs TextToSpeech(context), without explicit engine. SUCCESS callback obtains Controllers.speech and speaks activation. Audio attributes are USAGE_ASSISTANCE_ACCESSIBILITY and CONTENT_TYPE_SPEECH. It returns SpeechController with Reader(context).

[SpeechController](../app/src/main/kotlin/com/neo/speaktouch/controller/SpeechController.kt) always calls speak with QUEUE_FLUSH, null parameters and null utterance ID. No QUEUE_ADD, application queue, completion listener, initialization buffering/retry/failure message or speak-result handling. Touch interaction stops active speech; destruction shuts down TTS; onInterrupt does not stop it. No app pitch/rate/language/engine/voice/volume settings. Actual engine/defaults cannot be determined from code. Translations do not implement TTS language selection.

## Focus and traversal

[ServiceController](../app/src/main/kotlin/com/neo/speaktouch/controller/ServiceController.kt) queries findFocus(FOCUS_ACCESSIBILITY) on active root; no saved focus history. [FocusController](../app/src/main/kotlin/com/neo/speaktouch/controller/FocusController.kt) targets focus or root.

Next scans descendants in ascending child order, testing before recursion; then climbs ancestors and scans following sibling subtrees. Previous climbs ancestors, scans preceding siblings in reverse order with descendants first, then tests ancestor. This is tree order, not geometry or traversal-before/after metadata.

NodeFilter rejects invisible nodes; accepts mustFocus or readable content without a mustFocus ancestor. NodeValidator requires visible interactive readable content/children for mustFocus. Interaction means clickable, long-clickable, or focusable. Checkable/editable nodes can be readable without text. Collections with children are excluded from readable/grouped content so items are explored separately. Editable nodes otherwise follow general rules; no web-specific policy.

FocusInterceptor accepts mustFocus source, otherwise nearest mustFocus ancestor, otherwise readable source. If no candidate is selected within an ancestor, navigation attempts scrolling, registers continuation on success, and stops. Matching scroll events retry default navigation. No timeout, boundary feedback, wrapping or explicit failure return. performFocus ends scanning even when action returns false.

moveFocusToFirst scans descendants if no focus exists. With current focus it issues ACTION_NEXT_AT_MOVEMENT_GRANULARITY without arguments or result checking, rather than explicitly returning to first node. getRoot is non-null typed without unavailable-root fallback. These are source limitations, not measured device failures.

## Node descriptions

Authority: [Reader](../app/src/main/kotlin/com/neo/speaktouch/utils/Reader.kt), [Type](../app/src/main/kotlin/com/neo/speaktouch/model/Type.kt), [node extensions](../app/src/main/kotlin/com/neo/speaktouch/utils/extension/AccessibilityNodeInfo.kt).

Classified EditField uses text then hintText, ignoring contentDescription. Other nodes prefer contentDescription then text. Missing content causes readable-child recursion. Content/type/state join with comma-space.

| Category | Description |
| --- | --- |
| Button | button; includes ImageButton/clickable ImageView. |
| EditText | edit field, text or hint; isEditable alone does not classify EditField. |
| ImageView | Nonclickable: image; grouped child image type omitted. No OCR. |
| Checkbox | checkbox; stateDescription or checked/not checked. |
| Radio | option button; stateDescription or selected/not selected. |
| Switch | switch; stateDescription or enabled/disabled from checked state. |
| Toggle | toggle button; stateDescription or pressed/not pressed. |
| CheckedTextView | No type; state only when checked, stateDescription or selected. |
| Custom checkable | No type; stateDescription or selected/not selected. |
| List/collection | list classification; collections with children normally excluded from grouped focus. No position/counts. |
| AbsSpinner | options. |
| Heading | isHeading fallback says title; earlier widget types win, not additive. |
| Other selected node | stateDescription or selected. Unselected noncheckable stateDescription generally ignored. |
| Unknown | Content without generic role label. |

Class matching uses exact name or Class.forName assignability; unavailable external classes can fail. Checkable/collection/heading property fallbacks exist, but null className exits classification. No roleDescription/link-span parsing, general disabled/range announcement, action hints or password masking policy. isImportantForAccessibility is logged, not explicitly filtered.

Reader.Options defaults mustReadType/mustReadState true. Grouped children read state only for checkables and suppress image type. This internal verbosity mechanism has no user profiles/settings.

## Gestures, editing, web and notifications

See [capability matrices](CURRENT_CAPABILITIES.md). No custom double-tap activation mapping or ACTION_CLICK call in service code; platform activation is separate. Lone movement action is incomplete, not text navigation. No editing cursor/selection commands, typing echo, text-change/selection speech, continuous reading, WebView/HTML/link navigation, notification/toast extraction or announcement speech. Logging event names does not implement them. Child aggregation builds one utterance, not read-all.

## Feedback/preferences

[HapticInterceptor](../app/src/main/kotlin/com/neo/speaktouch/intercepter/event/HapticInterceptor.kt) responds to accessibility focus if hardware exists: clickable heavy click, otherwise tick. VibratorManager used on API 31+. [VibratorController](../app/src/main/kotlin/com/neo/speaktouch/controller/VibratorController.kt) uses predefined effects on API 29+, accessibility vibration attributes on API 33+, legacy patterns below 29: heavy [0,50,50], tick [0,30] ms, repeat -1. Finish cancels. No tones/earcons/audio clicks.

No SharedPreferences, DataStore, PreferenceFragment, SettingsActivity or equivalent persisted configuration/UI found. Backup sharedpref comments are templates. Gestures, queue, reader defaults, feedback and event order are hardcoded; no hidden persisted speech settings.

## Hilt

[Application](../app/src/main/kotlin/com/neo/speaktouch/SpeakTouchApplication.kt) is @HiltAndroidApp; service @AndroidEntryPoint injects Interceptors/FocusController. Interceptors/focus/service controllers use @Inject and @ServiceScoped. [ServiceModule](../app/src/main/kotlin/com/neo/speaktouch/di/module/ServiceModule.kt), in ServiceComponent, binds Service as Context and checks/provides AccessibilityService/SpeakTouchService. ControllerModule provides service-scoped speech/vibration controllers. Reader is constructed explicitly despite injectable constructor.

[SpeechControllerEntryPoint](../app/src/main/kotlin/com/neo/speaktouch/di/entrypoint/SpeechControllerEntryPoint.kt) exposes speech to TTS callback via Controllers. Weak service reference is installed/cleared by lifecycle; no absent-reference recovery. No preference/repository layer.

## Build (unchanged)

| Setting | Value |
| --- | --- |
| app min/target/compile SDK | 22 / 35 / 36 |
| test min/target/compile SDK | 22 / 36 / 36 |
| Build tools | 36.0.0 |
| JVM toolchain | Adoptium JDK 21 in app/test/build-logic/plugins; no separate explicit compileOptions/jvmTarget. |
| Gradle / AGP | 9.0.0 / 8.12.0 |
| Kotlin / KSP | 2.2.0 / 2.2.0-2.0.2 |
| Hilt | 2.57, KSP compiler |
| AndroidX | core-ktx 1.16.0, appcompat 1.7.1, test constraintlayout 2.2.1 |
| Material / Timber | 1.12.0 / 5.0.1 |
| JUnit / Robolectric | 4.13.2 / 4.15.1; test Android resources enabled |
| Foojay resolver | 0.9.0 |
| App version | Custom plugin 1.0.0-dev, code 100 |
| Variants | Debug .debug suffix; minification disabled; release signing conditional on keystore.properties readability. |
| Locales | en, pl, pt |

Sources: [app build](../app/build.gradle.kts), [test build](../test/build.gradle.kts), [catalog](../gradle/libs.versions.toml), [wrapper](../gradle/wrapper/gradle-wrapper.properties), [plugin build](../build-logic/plugins/build.gradle.kts), [VersionConfig](../build-logic/plugins/src/main/kotlin/VersionConfig.kt).

README JDK 17 conflicts with build/CI JDK 21. Module targets differ. Deprecated integer gesture callback, legacy vibration and reflective debug lookup merit future review. Versions are recorded, not claimed latest; upgrade/compatibility research is outside scope. CI runs ./gradlew build for main/develop push/PR and validates wrapper. Artifact glob app/build/outputs/apk*.apk merits checking against actual output layout; CI success not inspected.

## Tests and unfinished work

[TypeTest](../app/src/test/kotlin/com/neo/speaktouch/model/TypeTest.kt) is sole automated suite: 16 Robolectric/JUnit tests for ImageButton, clickable/nonclickable ImageView, EditText, CheckedTextView, Switch, ToggleButton, RadioButton, CheckBox, custom checkable, Button, RecyclerView, ListView, custom collection, Spinner and heading. CustomList/CustomCheckable are fixtures. No instrumentation source set.

Manual fragments cover exploration, interactive parents, child grouping/images, two-item RecyclerView and checkable states. [activity_main.xml](../test/src/main/res/layout/activity_main.xml) launches only ReadChildrenFragment; no runtime chooser for ExplorationFragment/MustFocusFragment/SwitchesFragment. Two fixtures labeled invisible set importantForAccessibility=no, not visibility. Comments are not runtime proof.

No automated tests found for Reader output, validator/grouping, focus/scroll/action failure, TTS lifecycle, gestures/global actions, haptic API branches, missing roots or window changes. Tests were read, not run.

TODOs: NodeValidator.hasTextToRead consolidation with getContent; editable exception after issue #88; Type.get isEditable consideration; Class.forName caching; data_extraction_rules backup template. No FIXME, TODO() or NotImplemented stub found. onInterrupt/default finish are no-ops; unmatched branches deliberately do nothing. Backup/ProGuard commented examples (including WebView) are templates, not features. No substantial disabled production feature block found.

No call sites found for getLog, Context.getText(Boolean), ifEmptyOrNull, IntArray.toLongArray, CustomPlugin private Project.android. Unlaunched fragments/internal Options noted above. Missing action-result handling, scroll timeout/cleanup override, root recovery and TTS readiness handling are visible gaps. MustFocusFragment/SwitchesFragment do not clear view bindings on destruction; ReadChildrenFragment does. None modified.

## Future placement (proposals only)

| Feature | Logical location |
| --- | --- |
| Character/word/line/paragraph | New text-navigation controller beside FocusController; supported granularity arguments, cursor/focus distinction, traversed-text event handling; GestureInterceptor dispatch. |
| Sentence | Same layer with defined segmentation/selection policy, not assumed platform sentence support. |
| Editing/selection/echo | Dedicated editing event interceptor feeding speech and tracking editing state. |
| Continuous reading | Service-scoped reading controller plus new utterance IDs/completion callbacks in SpeechController; touch/window/lifecycle cancellation. |
| Gesture configuration | Inject preference-backed command mapping into GestureInterceptor. |
| Settings UI | New activity and configuration repository; optional accessibility settingsActivity link. |
| Verbosity | Extend Reader.Options/Type/state formatting and tests; separate settings from eligibility. |
| Heading/control/link | Extend NodeFilter and commands; links need new semantics. |

Focused tests for current description/focus rules would help later review. No proposed feature was implemented.
