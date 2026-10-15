# SpeakTouch

SpeakTouch is an open-source screen reader for Android.

The project is under active development and is not yet feature-complete. This fork is being used to continue testing and improving SpeakTouch on real Android devices.

## About this fork

This fork continues development and testing of the original SpeakTouch project.

The aim is to preserve the original idea of a simple, practical, open-source Android screen reader while gradually improving its accessibility support, reliability and everyday usability.

Development here is being tested on real Android devices. The work is kept public so that the original developer, contributors and anyone interested in SpeakTouch can see and try the current state of the project.

## Current development status

Recent work includes:

- improved accessibility focus and navigation
- support for state descriptions, labels, traversal order and screen-reader focusable content
- slider support
- progress feedback and percentage updates
- re-read current focus
- pause and resume speech
- support for accessibility actions
- initial context menu support for focused elements
- improved handling of unlabeled interactive elements
- a fix for crashes when the active accessibility root is temporarily unavailable
- additional validation and regression tests

Some navigation edge cases, including wrap-around behavior in certain virtualized app grids, are still being investigated.

## Build
In development, choose the *debug build type*, because for the *release build type*, it's necessary to create a [keystore](https://developer.android.com/training/articles/keystore) and specify its properties in the `keystore.properties` file (use `keystore.properties.example` as a base).

- Download and install JDK 17, we recommend the [Adoptium OpenJDK 17](https://adoptium.net/temurin/releases/?version=17).

### Android Studio

1. Download and install [the latest version of Android Studio](https://developer.android.com/studio).
2. Clone the repository to your local machine.
3. Open the project in the Android Studio.
4. Run the **app** module.

### IntelliJ IDEA

1. Download and install [the latest version of IntelliJ IDEA](https://www.jetbrains.com/idea/).
2. Clone the repository to your local machine.
3. Open the project in the IntelliJ IDEA.
4. Install Android SDK using SDK Setup of IntelliJ (Setting/Languages & Framework/Android SDK).
5. Run the **app** module.

### Command line
1. Download and install [Android Command Line Tools](https://developer.android.com/studio#command-tools).
2. Set Android SDK path on `ANDROID_HOME` environment variable.
3. Clone the repository to your local machine.
4. Open the project in the terminal with `cd path/SpeakTouch`.
5. Run `./gradlew assembleDebug` to build the debug version.
6. The apk will be generated in `app/build/outputs/apk/debug`.

## License
[![GNU GPLv3 logo](https://www.gnu.org/graphics/gplv3-127x51.png)](https://www.gnu.org/licenses/gpl-3.0.html)

This project is licensed under the [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html), a copyleft open-source license. You are permitted to copy, modify, and redistribute the code, provided that the redistribution is accompanied by the corresponding source code under the same license in order to maintain its open-source nature. You can find a copy of the license in the project's [LICENSE file](/LICENSE) or the original version at https://www.gnu.org/licenses/gpl-3.0.html.
