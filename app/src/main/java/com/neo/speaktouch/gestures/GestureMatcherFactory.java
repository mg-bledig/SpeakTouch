/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/* Modified for SpeakTouch, 2026: Android gesture configuration only. */
package com.neo.speaktouch.gestures;

import static android.accessibilityservice.AccessibilityService.*;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/** Configuration adapted from BackTalk; the underlying matchers are unchanged algorithms. */
final class GestureMatcherFactory {
  static List<GestureMatcher> getGestureMatcherList(
      Context c,
      List<String> unused,
      GestureMatcher.StateChangeListener l,
      GestureManifold.GestureConfigProvider config,
      GestureMatcher.AnalyticsEventLogger logger) {
    List<GestureMatcher> r = new ArrayList<>();
    r.add(new MultiTap(c, 2, GESTURE_DOUBLE_TAP, l, config, logger));
    r.add(new MultiTapAndHold(c, 2, GESTURE_DOUBLE_TAP_AND_HOLD, l, config, logger));
    r.add(new SecondFingerTap(c, 1, GestureManifold.GESTURE_FAKED_SPLIT_TYPING, l, logger));
    int[] directions = {Swipe.UP, Swipe.DOWN, Swipe.LEFT, Swipe.RIGHT};
    int[] singles = {GESTURE_SWIPE_UP, GESTURE_SWIPE_DOWN, GESTURE_SWIPE_LEFT, GESTURE_SWIPE_RIGHT};
    for (int i = 0; i < 4; i++) r.add(new Swipe(c, directions[i], singles[i], l, config, logger));
    int[][] turns = {
      {Swipe.LEFT, Swipe.RIGHT, GESTURE_SWIPE_LEFT_AND_RIGHT},
      {Swipe.LEFT, Swipe.UP, GESTURE_SWIPE_LEFT_AND_UP},
      {Swipe.LEFT, Swipe.DOWN, GESTURE_SWIPE_LEFT_AND_DOWN},
      {Swipe.RIGHT, Swipe.LEFT, GESTURE_SWIPE_RIGHT_AND_LEFT},
      {Swipe.RIGHT, Swipe.UP, GESTURE_SWIPE_RIGHT_AND_UP},
      {Swipe.RIGHT, Swipe.DOWN, GESTURE_SWIPE_RIGHT_AND_DOWN},
      {Swipe.UP, Swipe.DOWN, GESTURE_SWIPE_UP_AND_DOWN},
      {Swipe.UP, Swipe.LEFT, GESTURE_SWIPE_UP_AND_LEFT},
      {Swipe.UP, Swipe.RIGHT, GESTURE_SWIPE_UP_AND_RIGHT},
      {Swipe.DOWN, Swipe.UP, GESTURE_SWIPE_DOWN_AND_UP},
      {Swipe.DOWN, Swipe.LEFT, GESTURE_SWIPE_DOWN_AND_LEFT},
      {Swipe.DOWN, Swipe.RIGHT, GESTURE_SWIPE_DOWN_AND_RIGHT}
    };
    for (int[] t : turns) r.add(new Swipe(c, t[0], t[1], t[2], l, config, logger));
    int[][] taps = {
      {2, 1, GESTURE_2_FINGER_SINGLE_TAP},
      {2, 2, GESTURE_2_FINGER_DOUBLE_TAP},
      {2, 3, GESTURE_2_FINGER_TRIPLE_TAP},
      {3, 1, GESTURE_3_FINGER_SINGLE_TAP},
      {3, 2, GESTURE_3_FINGER_DOUBLE_TAP},
      {3, 3, GESTURE_3_FINGER_TRIPLE_TAP},
      {4, 1, GESTURE_4_FINGER_SINGLE_TAP},
      {4, 2, GESTURE_4_FINGER_DOUBLE_TAP},
      {4, 3, GESTURE_4_FINGER_TRIPLE_TAP}
    };
    for (int[] t : taps) r.add(new MultiFingerMultiTap(c, t[0], t[1], t[2], l, logger));
    int[][] holds = {
      {2, 2, GESTURE_2_FINGER_DOUBLE_TAP_AND_HOLD},
      {2, 3, GESTURE_2_FINGER_TRIPLE_TAP_AND_HOLD},
      {3, 1, GESTURE_3_FINGER_SINGLE_TAP_AND_HOLD},
      {3, 2, GESTURE_3_FINGER_DOUBLE_TAP_AND_HOLD},
      {3, 3, GESTURE_3_FINGER_TRIPLE_TAP_AND_HOLD},
      {4, 2, GESTURE_4_FINGER_DOUBLE_TAP_AND_HOLD}
    };
    for (int[] t : holds) r.add(new MultiFingerMultiTapAndHold(c, t[0], t[1], t[2], l, logger));
    int[][] swipes = {
      {
        2,
        GESTURE_2_FINGER_SWIPE_UP,
        GESTURE_2_FINGER_SWIPE_DOWN,
        GESTURE_2_FINGER_SWIPE_LEFT,
        GESTURE_2_FINGER_SWIPE_RIGHT
      },
      {
        3,
        GESTURE_3_FINGER_SWIPE_UP,
        GESTURE_3_FINGER_SWIPE_DOWN,
        GESTURE_3_FINGER_SWIPE_LEFT,
        GESTURE_3_FINGER_SWIPE_RIGHT
      },
      {
        4,
        GESTURE_4_FINGER_SWIPE_UP,
        GESTURE_4_FINGER_SWIPE_DOWN,
        GESTURE_4_FINGER_SWIPE_LEFT,
        GESTURE_4_FINGER_SWIPE_RIGHT
      }
    };
    for (int[] w : swipes)
      for (int i = 0; i < 4; i++)
        r.add(new MultiFingerSwipe(c, w[0], directions[i], w[i + 1], l, logger));
    return r;
  }
}
