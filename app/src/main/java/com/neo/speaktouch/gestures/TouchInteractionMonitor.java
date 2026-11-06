/*
 * Adapted from trypsynth/backtalk TouchInteractionMonitor.java (Apache-2.0).
 * See THIRD_PARTY_NOTICES and app/src/main/assets/licenses/Apache-2.0.txt.
 * Modified for SpeakTouch, 2026: service callbacks/driver; no telemetry, menus, rotor or typing
 * preferences; owned queued events and explicit teardown. Recognition/pointer tracking reused.
 */
package com.neo.speaktouch.gestures;

import static android.accessibilityservice.AccessibilityService.*;
import static android.accessibilityservice.TouchInteractionController.*;
import static android.view.MotionEvent.*;

import android.accessibilityservice.AccessibilityGestureEvent;
import android.accessibilityservice.TouchInteractionController;
import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import androidx.annotation.RequiresApi;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

@RequiresApi(33)
public final class TouchInteractionMonitor
    implements TouchInteractionController.Callback, GestureManifold.Listener {
  public interface Driver {
    int getState();

    int getMaxPointerCount();

    void requestTouchExploration();

    void requestDragging(int pointerId);

    void requestDelegating();

    void performClick();

    void performLongClickAndStartDrag();
  }

  public interface Callbacks {
    boolean onDoubleTap();

    void onGesture(int gestureId);

    void onGestureStarted();
  }

  private static final String LOG_TAG = "SpeakTouchGestures", DEBUG_GESTURE_TAG = LOG_TAG;
  private static final float MAX_DRAGGING_ANGLE_COS = 0.525321989f;
  private static final Set<Integer> TOUCH_EXPLORE_GATE =
      Set.of(
          GESTURE_DOUBLE_TAP,
          GESTURE_DOUBLE_TAP_AND_HOLD,
          GESTURE_SWIPE_UP,
          GESTURE_SWIPE_DOWN,
          GESTURE_SWIPE_LEFT,
          GESTURE_SWIPE_RIGHT,
          GESTURE_SWIPE_LEFT_AND_RIGHT,
          GESTURE_SWIPE_LEFT_AND_UP,
          GESTURE_SWIPE_LEFT_AND_DOWN,
          GESTURE_SWIPE_RIGHT_AND_LEFT,
          GESTURE_SWIPE_RIGHT_AND_UP,
          GESTURE_SWIPE_RIGHT_AND_DOWN,
          GESTURE_SWIPE_UP_AND_DOWN,
          GESTURE_SWIPE_UP_AND_LEFT,
          GESTURE_SWIPE_UP_AND_RIGHT,
          GESTURE_SWIPE_DOWN_AND_UP,
          GESTURE_SWIPE_DOWN_AND_LEFT,
          GESTURE_SWIPE_DOWN_AND_RIGHT);
  private final Context context;
  private final Driver controller;
  private final Callbacks callbacks;
  private final Handler mainHandler;
  private final GestureManifold gestureDetector;
  private final ReceivedPointerTracker receivedPointerTracker;
  private final RequestTouchExplorationDelayed requestTouchExplorationDelayed;
  private final Queue<MotionEvent> queuedMotionEvents = new LinkedList<>();
  private final Set<Integer> touchExploreGate = new HashSet<>();
  private final int passthroughTotalSlop, swipeStartWindowMs;
  private final float edgeSwipeHeightPixels;
  private int state = STATE_CLEAR,
      previousState = STATE_CLEAR,
      draggingPointerId = INVALID_POINTER_ID;
  private boolean active = true,
      gestureStarted,
      stateChangeRequested,
      keepMonitorTouchExplore = true;
  private boolean clearGestureDetectorOnNextDown, waitFirstMotionEvent;
  private final boolean logMotionEvent = false;

  public TouchInteractionMonitor(Context context, Driver driver, Callbacks callbacks) {
    this.context = context;
    this.controller = driver;
    this.callbacks = callbacks;
    mainHandler = new Handler(context.getMainLooper());
    gestureDetector =
        new GestureManifold(
            context,
            this,
            new GestureManifold.GestureConfigProvider() {},
            e -> {},
            0,
            new ArrayList<>());
    gestureDetector.setMultiFingerGesturesEnabled(true);
    gestureDetector.setTwoFingerPassthroughEnabled(true);
    receivedPointerTracker = new ReceivedPointerTracker();
    requestTouchExplorationDelayed = new RequestTouchExplorationDelayed();
    passthroughTotalSlop = ViewConfiguration.get(context).getScaledTouchSlop() * 3;
    edgeSwipeHeightPixels =
        context.getResources().getDisplayMetrics().ydpi / GestureUtils.CM_PER_INCH * 0.25f;
    swipeStartWindowMs =
        (int)
            (context
                    .getResources()
                    .getFloat(com.neo.speaktouch.R.dimen.config_gesture_confirm_distance_cm)
                * context
                    .getResources()
                    .getInteger(
                        com.neo.speaktouch.R.integer.config_max_time_to_start_swipe_ms_per_cm));
    clear();
  }

  public void stop() {
    active = false;
    mainHandler.removeCallbacksAndMessages(null);
    clear();
  }

  public void configurationChanged(Context context) {
    gestureDetector.onConfigurationChanged(context);
  }

  private void clearQueuedEvents() {
    while (!queuedMotionEvents.isEmpty()) queuedMotionEvents.poll().recycle();
  }

  private void clear() {
    gestureStarted = false;
    clearGestureDetectorOnNextDown = false;
    stateChangeRequested = false;
    gestureDetector.clear();
    receivedPointerTracker.clear();
    requestTouchExplorationDelayed.cancel();
    clearQueuedEvents();
    touchExploreGate.clear();
    touchExploreGate.addAll(TOUCH_EXPLORE_GATE);
    keepMonitorTouchExplore = true;
    waitFirstMotionEvent = false;
  }

  @Override
  public void onGestureCompleted(AccessibilityGestureEvent gesture) {
    if (!active
        || state == STATE_DRAGGING
        || state == STATE_DELEGATING
        || state == STATE_CLEAR
            && (previousState == STATE_DRAGGING || previousState == STATE_DELEGATING)) return;
    int id = gesture.getGestureId();
    if (id == GESTURE_DOUBLE_TAP || id == GestureManifold.GESTURE_FAKED_SPLIT_TYPING) {
      if (id == GestureManifold.GESTURE_FAKED_SPLIT_TYPING || !callbacks.onDoubleTap())
        controller.performClick();
    } else if (id == GESTURE_DOUBLE_TAP_AND_HOLD) {
      // Android owns long press, continued finger motion and release. Never emulate with
      // ACTION_LONG_CLICK.
      controller.performLongClickAndStartDrag();
      return;
    } else
      mainHandler.post(
          () -> {
            if (active) callbacks.onGesture(id);
          });
    clear();
    clearGestureDetectorOnNextDown = true;
  }

  private boolean canRequest() {
    return active
        && controller.getState() != STATE_DELEGATING
        && controller.getState() != STATE_TOUCH_EXPLORING;
  }

  private void requestTouchExploration(String caller) {
    if (canRequest()) {
      stateChangeRequested = true;
      controller.requestTouchExploration();
    }
  }

  private void requestDragging(int pointer, String caller) {
    if (canRequest()) {
      stateChangeRequested = true;
      gestureDetector.clear();
      controller.requestDragging(pointer);
    }
  }

  private void requestDelegating(String caller) {
    if (canRequest()) {
      stateChangeRequested = true;
      gestureDetector.clear();
      controller.requestDelegating();
    }
  }

  private final class RequestTouchExplorationDelayed implements Runnable {
    private long postTime;
    private int actualDelay;

    public void cancel() {
      mainHandler.removeCallbacks(this);
    }

    public void post() {
      post(0);
    }

    public void post(int remaining) {
      postTime = SystemClock.uptimeMillis();
      actualDelay = GestureConfiguration.DOUBLE_TAP_TIMEOUT_MS;
      mainHandler.postDelayed(this, actualDelay);
    }

    public boolean isPending() {
      return mainHandler.hasCallbacks(this);
    }

    public int remainingTime() {
      return actualDelay - (int) (SystemClock.uptimeMillis() - postTime);
    }

    public void extendTo(int total) {
      if (total <= actualDelay) return;
      actualDelay = total;
      mainHandler.removeCallbacks(this);
      mainHandler.postDelayed(this, Math.max(0, total - (SystemClock.uptimeMillis() - postTime)));
    }

    @Override
    public void run() {
      if (active) requestTouchExploration("timer");
    }
  }

  @Override
  public void onMotionEvent(MotionEvent event) {
    if (!active || event == null) return;
    if (event.getActionMasked() == ACTION_CANCEL) {
      clear();
      return;
    }
    if (event.getActionMasked() == ACTION_POINTER_DOWN) keepMonitorTouchExplore = false;
    if (stateChangeRequested) {
      queuedMotionEvents.add(MotionEvent.obtain(event));
      return;
    }
    receivedPointerTracker.onMotionEvent(event);
    if (shouldPerformGestureDetection()) {
      if (clearGestureDetectorOnNextDown && event.getActionMasked() == ACTION_DOWN) {
        clearGestureDetectorOnNextDown = false;
        gestureDetector.clear();
      }
      gestureDetector.onMotionEvent(null, event);
    }
    if (!gestureStarted) {
      switch (state) {
        case STATE_TOUCH_INTERACTING -> handleMotionEventStateTouchInteracting(event);
        case STATE_DRAGGING -> handleMotionEventStateDragging(event);
        default -> {}
      }
    }
  }

  public void handleMotionEventStateTouchInteracting(MotionEvent event) {
    switch (event.getActionMasked()) {
      case ACTION_DOWN -> {
        if (waitFirstMotionEvent) {

          waitFirstMotionEvent = false;
        }
        if (requestTouchExplorationDelayed.isPending()) {
          // The touch explore delay timer should be restart each time received Action-down event.
          // For multi-tap gestures, system will detect the ACTION_DOWN events multiple times. If
          // their is already a pending runnable and starting a new one, the older one will be
          // executed earlier than we expected.
          requestTouchExplorationDelayed.cancel();
        }
        requestTouchExplorationDelayed.post();
      }
      case ACTION_MOVE -> {
        switch (event.getPointerCount()) {
          case 1:
            // Do nothing. Touch exploration will fire on a delay.
            break;
          case 2:
            if (gestureDetector.isTwoFingerPassthroughEnabled()) {
              for (int index = 0; index < event.getPointerCount(); ++index) {
                int id = event.getPointerId(index);
                if (!receivedPointerTracker.isReceivedPointerDown(id)) {
                  // Something is wrong with the event stream.
                  GestureLog.e(LOG_TAG, "Invalid pointer id: %d", id);
                  return;
                }
                final float deltaX =
                    receivedPointerTracker.getReceivedPointerDownX(id) - event.getX(index);
                final float deltaY =
                    receivedPointerTracker.getReceivedPointerDownY(id) - event.getY(index);
                final double moveDelta = Math.hypot(deltaX, deltaY);
                if (moveDelta < passthroughTotalSlop) {
                  // For 3 finger swipe gestures which bear the 3 times of touchSlop during the
                  // detection. If the monitor issues state change to drag/delegate before the 3rd
                  // finger down due to the touch-slop over, the 3-finger swipe gesture detector
                  // fails. So we align the moveDelta to 3-times of touch-slop.
                  return;
                }
              }
            }
            if (isDraggingGesture(event)) {
              computeDraggingPointerIdIfNeeded(event);
              requestDragging(draggingPointerId, "handleMotionEventStateTouchInteracting");
            } else {
              requestDelegating("handleMotionEventStateTouchInteracting-2-points");
            }
            break;
          case 3:
            if (allPointersDownOnBottomEdge(event)) {
              requestDelegating("handleMotionEventStateTouchInteracting-3-points");
            }
            break;
          default:
            break;
        }
      }
      case ACTION_POINTER_DOWN -> requestTouchExplorationDelayed.cancel();
      default -> {}
    }
  }

  public void handleMotionEventStateDragging(MotionEvent event) {
    switch (event.getActionMasked()) {
      case ACTION_MOVE -> {
        if (draggingPointerId == INVALID_POINTER_ID) {
          break;
        }
        switch (event.getPointerCount()) {
          case 1:
            // do nothing
            break;
          case 2:
            if (isDraggingGesture(event)) {
              // Do nothing. The system will continue the drag on its own.
            } else {
              // The two pointers are moving either in different directions or
              // no close enough => delegate the gesture to the view hierarchy.
              requestDelegating("handleMotionEventStateDragging-2-points");
            }
            break;
          default:
            if (!gestureDetector.isMultiFingerGesturesEnabled()) {
              requestDelegating("handleMotionEventStateDragging-3-points");
            }
        }
      }
      default -> {}
    }
  }

  public void onStateChanged(int state) {
    if (!active) return;
    GestureLog.v(
        LOG_TAG,
        "%s -> %s",
        TouchInteractionController.stateToString(this.state),
        TouchInteractionController.stateToString(state));

    if (this.state == STATE_CLEAR) {
      // Clear on transition to a new interaction
      clear();
    }
    if (state == STATE_TOUCH_INTERACTING) {
      waitFirstMotionEvent = true;
    } else if (state == STATE_TOUCH_EXPLORING) {
      // Log isDefaultDisplay/gestureId/onGestureDetectedTime. The targetGestureTimeout is the
      // current time minus lastMotionEventTransmissionLatency

      waitFirstMotionEvent = true;
    }

    previousState = this.state;
    this.state = state;
    requestTouchExplorationDelayed.cancel();
    stateChangeRequested = false;
    if (shouldReceiveQueuedMotionEvents()) {
      while (!stateChangeRequested && !queuedMotionEvents.isEmpty()) {
        // When the onMotionEvent involve the request of gesture controller's state change, we stop
        // popping the event from the queue. Otherwise, it may cause infinite loop of onMotionEvent
        // callback.
        MotionEvent queued = queuedMotionEvents.poll();
        try {
          onMotionEvent(queued);
        } finally {
          queued.recycle();
        }
      }
    } else {
      clearQueuedEvents();
    }
  }

  private boolean allPointersDownOnBottomEdge(MotionEvent event) {
    final long screenHeight = context.getResources().getDisplayMetrics().heightPixels;
    for (int i = 0; i < event.getPointerCount(); ++i) {
      final int pointerId = event.getPointerId(i);
      final float pointerDownY = receivedPointerTracker.getReceivedPointerDownY(pointerId);
      if (pointerDownY < (screenHeight - edgeSwipeHeightPixels)) {
        return false;
      }
    }
    return true;
  }

  private void computeDraggingPointerIdIfNeeded(MotionEvent event) {
    if (draggingPointerId != INVALID_POINTER_ID) {
      // If we have a valid pointer ID, we should be good
      final int pointerIndex = event.findPointerIndex(draggingPointerId);
      if (pointerIndex >= 0) {
        return;
      }
    }
    GestureLog.v(LOG_TAG, "computeDraggingPointerIdIfNeeded/MotionEvent:%s", event);
    // Use the pointer that is closest to its closest edge.
    final float firstPtrX = event.getX(0);
    final float firstPtrY = event.getY(0);
    final int firstPtrId = event.getPointerId(0);
    final float secondPtrX = event.getX(1);
    final float secondPtrY = event.getY(1);
    final int secondPtrId = event.getPointerId(1);
    draggingPointerId =
        (getDistanceToClosestEdge(firstPtrX, firstPtrY)
                < getDistanceToClosestEdge(secondPtrX, secondPtrY))
            ? firstPtrId
            : secondPtrId;
    if (draggingPointerId < 0) {
      GestureLog.e(
          LOG_TAG,
          "computeDraggingPointerIdIfNeeded: incorrect dragging pointer of event:%s",
          event);
    }
  }

  private float getDistanceToClosestEdge(float x, float y) {
    final long width = this.context.getResources().getDisplayMetrics().widthPixels;
    final long height = this.context.getResources().getDisplayMetrics().heightPixels;
    float distance = Float.MAX_VALUE;
    if (x < (width - x)) {
      distance = x;
    } else {
      distance = width - x;
    }
    if (distance > y) {
      distance = y;
    }
    if (distance > (height - y)) {
      distance = (height - y);
    }
    return distance;
  }

  private boolean isDraggingGesture(MotionEvent event) {

    final float firstPtrX = event.getX(0);
    final float firstPtrY = event.getY(0);
    final float secondPtrX = event.getX(1);
    final float secondPtrY = event.getY(1);

    final float firstPtrDownX = receivedPointerTracker.getReceivedPointerDownX(0);
    final float firstPtrDownY = receivedPointerTracker.getReceivedPointerDownY(0);
    final float secondPtrDownX = receivedPointerTracker.getReceivedPointerDownX(1);
    final float secondPtrDownY = receivedPointerTracker.getReceivedPointerDownY(1);

    return GestureUtils.isDraggingGesture(
        firstPtrDownX,
        firstPtrDownY,
        secondPtrDownX,
        secondPtrDownY,
        firstPtrX,
        firstPtrY,
        secondPtrX,
        secondPtrY,
        MAX_DRAGGING_ANGLE_COS);
  }

  /** This class tracks where and when a pointer went down. It does not track its movement. */
  class ReceivedPointerTracker {

    private final PointerDownInfo[] mReceivedPointers;

    // Which pointers are down.
    private int mReceivedPointersDown;

    ReceivedPointerTracker() {
      mReceivedPointers = new PointerDownInfo[controller.getMaxPointerCount()];
      clear();
    }

    /** Clears the internals state. */
    public void clear() {
      mReceivedPointersDown = 0;
      for (int i = 0; i < controller.getMaxPointerCount(); ++i) {
        mReceivedPointers[i] = new PointerDownInfo();
      }
    }

    /**
     * Processes a received {@link MotionEvent} event.
     *
     * @param event The event to process.
     */
    public void onMotionEvent(MotionEvent event) {
      final int action = event.getActionMasked();
      switch (action) {
        case MotionEvent.ACTION_DOWN -> handleReceivedPointerDown(event.getActionIndex(), event);
        case MotionEvent.ACTION_POINTER_DOWN ->
            handleReceivedPointerDown(event.getActionIndex(), event);
        case MotionEvent.ACTION_UP -> handleReceivedPointerUp(event.getActionIndex(), event);
        case MotionEvent.ACTION_POINTER_UP ->
            handleReceivedPointerUp(event.getActionIndex(), event);
        default -> {}
      }
    }

    /**
     * @return The number of received pointers that are down.
     */
    public int getReceivedPointerDownCount() {
      return Integer.bitCount(mReceivedPointersDown);
    }

    /**
     * Whether an received pointer is down.
     *
     * @param pointerId The unique pointer id.
     * @return True if the pointer is down.
     */
    public boolean isReceivedPointerDown(int pointerId) {
      final int pointerFlag = (1 << pointerId);
      return (mReceivedPointersDown & pointerFlag) != 0;
    }

    /**
     * @param pointerId The unique pointer id.
     * @return The X coordinate where the pointer went down.
     */
    public float getReceivedPointerDownX(int pointerId) {
      return mReceivedPointers[pointerId].mX;
    }

    /**
     * @param pointerId The unique pointer id.
     * @return The Y coordinate where the pointer went down.
     */
    public float getReceivedPointerDownY(int pointerId) {
      return mReceivedPointers[pointerId].mY;
    }

    /**
     * @param pointerId The unique pointer id.
     * @return The time when the pointer went down.
     */
    public long getReceivedPointerDownTime(int pointerId) {
      return mReceivedPointers[pointerId].mTime;
    }

    /**
     * Handles a received pointer down event.
     *
     * @param pointerIndex The index of the pointer that has changed.
     * @param event The event to be handled.
     */
    private void handleReceivedPointerDown(int pointerIndex, MotionEvent event) {
      final int pointerId = event.getPointerId(pointerIndex);
      final int pointerFlag = (1 << pointerId);
      mReceivedPointersDown |= pointerFlag;
      mReceivedPointers[pointerId].set(
          event.getX(pointerIndex), event.getY(pointerIndex), event.getEventTime());
    }

    /**
     * Handles a received pointer up event.
     *
     * @param pointerIndex The index of the pointer that has changed.
     * @param event The event to be handled.
     */
    private void handleReceivedPointerUp(int pointerIndex, MotionEvent event) {
      final int pointerId = event.getPointerId(pointerIndex);
      final int pointerFlag = (1 << pointerId);
      mReceivedPointersDown &= ~pointerFlag;
      mReceivedPointers[pointerId].clear();
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append("=========================");
      builder.append("\nDown pointers #");
      builder.append(getReceivedPointerDownCount());
      builder.append(" [ ");
      for (int i = 0; i < controller.getMaxPointerCount(); i++) {
        if (isReceivedPointerDown(i)) {
          builder.append(i);
          builder.append(" ");
        }
      }
      builder.append("]");
      builder.append(" ]");
      builder.append("\n=========================");
      return builder.toString();
    }
  }

  static class PointerDownInfo {
    private float mX;
    private float mY;
    private long mTime;

    public void set(float x, float y, long time) {
      mX = x;
      mY = y;
      mTime = time;
    }

    public void clear() {
      mX = 0;
      mY = 0;
      mTime = 0;
    }
  }

  public void onGestureCancelled(int gestureId) {
    if (!active) return;
    if (touchExploreGate.remove(gestureId)) {
      if (keepMonitorTouchExplore
          && touchExploreGate.isEmpty()
          && state == STATE_TOUCH_INTERACTING) {
        keepMonitorTouchExplore = false;
        requestTouchExplorationDelayed.cancel();
        requestTouchExploration("onGestureCancelled");
      }
    }
  }

  public void onGestureProcessing(int gestureId) {
    if (!active) return;
    if (gestureId >= GESTURE_SWIPE_UP && gestureId <= GESTURE_SWIPE_DOWN_AND_RIGHT) {
      // A one-finger swipe moving its way: touch exploration waits until the swipe either starts
      // or runs out of time to, so that a swipe that starts slowly isn't explored.
      if (requestTouchExplorationDelayed.isPending()) {
        requestTouchExplorationDelayed.extendTo(swipeStartWindowMs);
      }
      return;
    }
    if (gestureId != GESTURE_DOUBLE_TAP) {
      return;
    }
    // When the touch exploration timer is pending, extend the timer to the original value to
    // accommodate the double-tap gesture detection; excluding the keyboard keys with lift-to-type.
    if (requestTouchExplorationDelayed.isPending()) {
      int remaining = requestTouchExplorationDelayed.remainingTime();
      requestTouchExplorationDelayed.cancel();
      requestTouchExplorationDelayed.post(remaining);
    }
  }

  public void onGestureStarted(int gestureId) {
    if (!active) return;
    gestureStarted = true;
    requestTouchExplorationDelayed.cancel();
    // The system will NOT send AccessibilityEvent TYPE_GESTURE_DETECTION_START and
    // TYPE_GESTURE_DETECTION_END when using TalkBack gesture detection. So the module has to
    // manually notify the service that gesture detection has been started.
    callbacks.onGestureStarted();
  }

  private boolean shouldPerformGestureDetection() {
    if (this.state == STATE_TOUCH_INTERACTING || this.state == STATE_TOUCH_EXPLORING) {
      return true;
    }
    return false;
  }

  private boolean shouldReceiveQueuedMotionEvents() {
    if (this.state == STATE_TOUCH_INTERACTING || this.state == STATE_DRAGGING) {
      return true;
    } else {
      return false;
    }
  }
}
