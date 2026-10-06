# Current capabilities

Source assessment: 2026-10-06, `f8bc445`, branch `docs/code-assessment`. Implemented means a concrete code path exists, not guaranteed success in every app/device. User reports working speech/some gestures. No runtime testing performed here. See [overview](CODE_OVERVIEW.md) for source references and caveats.

## Fully implemented source paths

- AccessibilityService declaration, all-event subscription, touch exploration request.
- Focused-node descriptions and TTS QUEUE_FLUSH replacement speech.
- Stop active speech on touch interaction start.
- Activation speech after TTS SUCCESS; shutdown on destruction.
- Hover/input-focus/click-driven focus selection; basic previous/next tree navigation.
- Label/widget/checkable/selected descriptions and readable-child grouping.
- Focus vibration with API-specific effects/fallbacks.
- Four gesture mappings below, subject to API conditions.
- English, Polish and Portuguese description/activation resources.

Authority: SpeakTouchService, Interceptors, event interceptors, FocusController, Reader, Type, SpeechController, VibratorController and service XML. Links in [overview](CODE_OVERVIEW.md).

## Partial or limited

| Capability | What exists | Limit |
| --- | --- | --- |
| Focus navigation | Tree scans, ancestor scrolling/event continuation | Failed focus still ends scan; no boundary/wrap/timeout/root recovery. |
| First focus on window change | Descendant scan when unfocused | Existing focus gets argument-free movement action, not explicit first-node relocation. |
| Editing | Read focused EditText text/hint | No cursor/edit/selection commands or typing feedback. |
| Verbosity | Internal Reader.Options type/state switches | No user profiles/preferences. |
| Roles/states | Common widgets/checkables/collection/heading fallback | No general roleDescription, additive heading, range, links or disabled state. |
| Collections | Child grouping exception and generic traversal/scroll | No positions/counts or dedicated granularity. |
| TTS lifecycle | Construction, success speech, stop/shutdown | No readiness buffer, failure feedback, completion or result handling. |

## Every implemented gesture

[GestureInterceptor](../app/src/main/kotlin/com/neo/speaktouch/intercepter/gesture/GestureInterceptor.kt) receives the deprecated service onGesture(Int). Mappings are hardcoded.

| Constant | Gesture | Action | API gate |
| --- | --- | --- | --- |
| GESTURE_SWIPE_LEFT | Swipe left | Previous node | App minimum 22 |
| GESTURE_SWIPE_RIGHT | Swipe right | Next node | App minimum 22 |
| GESTURE_SWIPE_DOWN_AND_LEFT | Down then left | GLOBAL_ACTION_BACK | App minimum 22 |
| GESTURE_2_FINGER_DOUBLE_TAP | Two-finger double tap | GLOBAL_ACTION_KEYCODE_HEADSETHOOK | 31+ |

Mapped gestures return true, including when global action fails; others false. No other mappings, gesture preferences or AccessibilityGestureEvent overload. API 31 XML requests multifinger gestures and service adds two-finger passthrough; actual delivery of that combination was not verified. No explicit double-tap-to-click/ACTION_CLICK implementation; any platform touch-exploration activation is separate.

## Global actions

| Action | Status |
| --- | --- |
| Back | Implemented, down-then-left. |
| Home | Not implemented. |
| Recents | Not implemented. |
| Notifications panel | Not implemented. |
| Quick settings | Not implemented. |
| Media/headset | API 31+ headset-hook global action. Platform/context-dependent result; no dedicated play/pause/next/previous or hardware headset/key listener. |
| Power dialog, lock, screenshot, others | Not implemented. |

ServiceController's generic wrapper does not expose unreferenced actions to users.

## Text editing and granularities

| Operation/granularity | Status | Evidence/limit |
| --- | --- | --- |
| General next/previous node | Implemented | FocusController tree scan. |
| Character | Not implemented as usable feature | No character arguments/state/gesture/handler. |
| Word | Not implemented | No word command/arguments. |
| Line | Not implemented | No line command/arguments. |
| Sentence | Not implemented | No segmentation/command. |
| Paragraph | Not implemented | No paragraph command/arguments. |
| Heading | Description only; navigation absent | Type.Heading says title; no heading filter. |
| Control | Classification only; navigation absent | No control-only mode. |
| Link | Not implemented | No link semantics/navigation. |
| List item/collection | Partial generic exploration | Grouping exception; no named granularity mode. |
| Other named granularities | Not implemented | No selector/mode storage. |
| Read editable field | Basic support | EditText text then hint; isEditable accepted by general focus rules. |
| Move cursor | No user command | Lone movement action does not establish cursor navigation. |
| Edit/set text | Not implemented | No ACTION_SET_TEXT. |
| Select text | Not implemented | No ACTION_SET_SELECTION/extend-selection arguments. |
| Report selection changes | Not implemented | No selection-change handler; selected-node state is not cursor/range reporting. |
| Announce typed characters/words | Not implemented | No text-change speech handler. |

Specific API search found one ACTION_NEXT_AT_MOVEMENT_GRANULARITY in [FocusController.moveFocusToFirst](../app/src/main/kotlin/com/neo/speaktouch/controller/FocusController.kt), called on window-state changes if focus exists. No Bundle/granularity/extend-selection argument, result check or previous-at-granularity counterpart. TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY appears only in a log-name helper. This is a partial API attempt, not character/word/line/paragraph support.

## Not implemented

- Read-from-here/read-all/continuous reading or speech-completion traversal. Child recursion builds one utterance.
- Special WebView/browser/HTML, web actions, links/web heading navigation. Exposed nodes may follow generic rules; browser behavior is not certified by source.
- Notification/toast speech, TYPE_NOTIFICATION_STATE_CHANGED handling or Notification extraction. TYPE_ANNOUNCEMENT also unhandled.
- Settings UI/persisted preferences/configurable gestures/TTS pitch-rate-language-engine-voice-queue controls.
- Earcons/tones/audio clicks/boundary cues/OCR/braille/clipboard editing/hardware key handling.

Subscription to all events and event-name strings are not implementation.

## Implemented but not user-configurable

- Reader.Options switches, used internally during child aggregation.
- Text resolution and internal speech APIs.
- Debug node/action JSON logging and Timber tree.
- Generic global-action wrapper; only Back/headset-hook have gesture callers.
- Manual fixture screens beyond ReadChildrenFragment, without launch-path selector.

No hidden persisted speech settings. Hardcoded defaults/API branches are code policy.

## Likely next targets (not implemented)

New text-navigation controller for character/word/line/paragraph; explicit sentence segmentation; editing event interceptor; reading controller with TTS completion; preference-backed gestures/settings activity; richer Reader verbosity/tests. [Overview](CODE_OVERVIEW.md#future-placement-proposals-only) explains placement. No functional/build changes made.
