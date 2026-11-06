# Design Decision: Reusing BackTalk Gesture Recognition

## Why we chose to reuse existing code

During the development of SpeakTouch, we decided to reuse and adapt open-source gesture-recognition code from BackTalk rather than create an entirely new gesture-recognition system.

The main reason is simple: **there is no need to reinvent the wheel when a suitable design already exists and is available as open source.**

Gesture recognition is a complex and important part of an Android screen reader. BackTalk builds on Google's open-source accessibility work and provides an existing approach that we could adapt to SpeakTouch's needs. Starting from that work allowed us to concentrate on the features and behaviour that make SpeakTouch useful, rather than duplicating the effort of designing gesture recognition from scratch.

## How we adapted it

We did not set out to reproduce BackTalk as a whole. We reused a specific subsystem and adapted it for SpeakTouch, including changes to integration, callbacks and lifecycle handling, and the removal of features not needed by our project. The aim was to keep SpeakTouch focused, maintainable and reliable.

## Attribution and licensing

We acknowledge the original contributors and respect the licences under which their code was made available. The original source notices are preserved, and our modifications and the relevant source revisions are documented.

For the source attribution and licence details, see `THIRD_PARTY_NOTICES` in this `docs` directory and the Apache License 2.0 text at `app/src/main/assets/licenses/Apache-2.0.txt`.

**This was a deliberate engineering decision:** build on established open-source work where appropriate, and invest our effort in SpeakTouch's own functionality and accessibility.
