JARVIS Audio Sync — Android Studio source project

WHAT IT DOES
- Full-screen Iron-Man-inspired animated face.
- Microphone mode: reacts to sound captured by the phone microphone.
- Other-app audio mode: requests Android's system audio-capture permission and attempts to capture supported media/game audio on Android 10+.
- The mouth animation reacts to audio level; it is not speech-to-text.

IMPORTANT LIMITATIONS
- This ZIP is an Android Studio source project, not a precompiled APK.
- Some apps prevent playback capture, and Android may restrict capture depending on app policy/device.
- Protected audio, voice/video-call audio, and some live-stream apps may not be capturable.
- Media playback capture requires the user to approve the Android system dialog.
- If capture is blocked by a source app, use microphone mode (speaker audio can be picked up; headphones usually won't be).
- Build using Android Studio with JDK 17 and Android SDK 35. Open this folder as a project and use Build > Build APK(s).
