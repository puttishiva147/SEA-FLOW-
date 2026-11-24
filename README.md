# SEAFLOW

SEAFLOW is an independent Android phone + Android TV / Google TV control project. It does not modify the existing YUKRI project.

## Product priorities
1. Reliable local-network discovery and secure pairing.
2. Phone-side remote controls and voice input.
3. Optional TV-side companion app and microphone input, where Android TV permits it.
4. Device-specific adapters for commands that are not universally exposed.

## Compatibility
There is no single public Android API that lets an ordinary app control power, launch apps, volume, and navigation on every TV brand. SEAFLOW uses capability-based adapters and must report unsupported actions instead of pretending they worked. The current pairing implementation targets Android TV Remote v2. Other TV platforms require separate adapters and their own pairing rules.

Local control does not require a SEAFLOW cloud backend. Voice recognition and AI interpretation can use on-device/system services or user-authorized existing apps; direct ChatGPT/Gemini account control is not assumed.

## Current implementation
- Native Android app foundation using Kotlin and Jetpack Compose.
- Bounded mDNS discovery for `_androidtvremote2._tcp`, with a multicast lock and cleanup.
- Android TV Remote v2 pairing transport over TLS on port 6467.
- Client RSA identity generated and retained by Android Keystore.
- Protobuf varint framing and pairing request/options/configuration messages.
- Six-character hexadecimal TV code verification using both RSA public keys and SHA-256.
- Unit tests for protobuf varint framing and nested field parsing.

**Validation status:** source changes are committed, but a successful CI build and a pairing test against a physical TV have not yet been confirmed. Discovery and pairing support only work on TVs that expose Android TV Remote v2; the resolved mDNS port is not used as the pairing port.

## Build
The GitHub Actions workflow runs protocol unit tests and assembles a debug APK. A signed production APK is a separate release step and is not configured yet. Minimum Android SDK: 26.

## Roadmap
- [x] Establish independent repository and Android project.
- [x] Add phone app shell and local Android TV Remote v2 discovery.
- [x] Implement the TLS pairing handshake and on-screen code verification.
- [ ] Verify CI build and unit-test results.
- [ ] Test pairing on a physical Android TV / Google TV device.
- [ ] Implement the paired remote-control channel and command handling.
- [ ] Add voice command routing and honest capability checks.
- [ ] Add separate TV-platform adapters and cross-brand tests.
- [ ] Produce a signed release APK after real-device testing.

## Security
The client private key is generated and retained in Android Keystore. Never commit TV credentials, API keys, or private signing material. TV certificate trust during pairing is established by the on-screen code verification; production hardening must also pin the paired TV certificate for the remote-control channel.
