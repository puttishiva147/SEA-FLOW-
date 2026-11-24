# SEAFLOW

SEAFLOW is an independent Android phone + Android TV / Google TV control project. It does not modify the existing YUKRI project.

## Product priorities
1. Reliable local-network discovery and secure pairing.
2. Phone-side remote controls and voice input.
3. Optional TV-side companion app and microphone input, where Android TV permits it.
4. Device-specific adapters for commands that are not universally exposed.

## Compatibility
There is no single public Android API that lets an ordinary app control power, launch apps, volume, and navigation on every TV brand. SEAFLOW currently targets Android TV Remote v2. Other TV platforms require separate adapters and their own pairing rules.

## Current implementation
- Native Android app foundation using Kotlin and Jetpack Compose.
- Bounded mDNS discovery for `_androidtvremote2._tcp`, with multicast-lock cleanup and manual host fallback.
- Android TV Remote v2 pairing transport over TLS on port 6467.
- Client RSA identity generated and retained by Android Keystore.
- Protobuf framing and pairing-code verification.
- Paired remote-control channel and phone-side remote UI.
- Foreground microphone listening service with wake-phrase-gated voice command routing.
- Live voice/activity status in the app, plus ChatGPT/Gemini web-link shortcuts.
- Protocol unit tests for framing and pairing validation.

## Validation status
The latest source is committed. A successful Android build, unit-test run, APK artifact, and physical-TV pairing test are **not yet verified**. The microphone listener uses Android's SpeechRecognizer provider; it is not an offline, dedicated wake-word engine. Android requires microphone permission and an explicit user-started foreground service. ChatGPT/Gemini shortcuts open links; they do not guarantee control of a native TV app or transfer account sessions.

## Build
GitHub Actions is configured to run protocol unit tests and assemble a debug APK on pushes to `main`. A signed production APK is a separate release step and is not configured yet. Minimum Android SDK: 26.

## Remaining work
- [ ] Verify CI build and unit-test results; publish the debug APK artifact.
- [ ] Test pairing, reconnection, voice commands, and screen-saver behavior on a physical Android TV / Google TV.
- [ ] Harden voice-service reconnection and TV connection lifecycle.
- [ ] Add separate adapters for other TV platforms.
- [ ] Configure signing and produce a signed release APK after testing.

## Security
The client private key is generated and retained in Android Keystore. Never commit TV credentials, API keys, or private signing material. TV certificate trust during pairing is established by the on-screen code verification; production hardening must also pin the paired TV certificate for the remote-control channel.
