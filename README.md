# SEAFLOW

SEAFLOW is an independent Android phone + Android TV / Google TV control project. It does not modify the existing YUKRI project.

## Product priorities
1. Reliable local-network discovery and pairing.
2. Phone-side remote controls and voice input.
3. Optional TV-side companion app and microphone input, where Android TV permits it.
4. Device-specific adapters for commands that are not universally exposed.

## Important compatibility truth
There is no single public Android API that lets an ordinary app control power, launch apps, volume, and navigation on every TV brand. SEAFLOW will use capability-based adapters and report unsupported actions instead of pretending they worked. Local control does not require SEAFLOW's own cloud backend. Voice recognition and AI interpretation can use on-device/system services or user-authorized existing apps; direct ChatGPT/Gemini account control is not assumed.

## Current implementation
- Native Android phone app foundation (Kotlin + Jetpack Compose).
- Local-network service discovery for Android TV Remote and Google Cast advertisements.
- Manual device entry is planned as a fallback when discovery is unavailable.

## Build
Open this repository in Android Studio and build the `app` configuration. Minimum Android SDK: 26.

## Roadmap
- [x] Establish independent repository and Android project.
- [x] Add phone app shell and local service discovery.
- [ ] Implement and test a compatible TV pairing/control transport.
- [ ] Add voice command routing and honest capability checks.
- [ ] Add TV companion app and cross-brand adapter tests.
- [ ] Produce signed release APK after real-device testing.

## Security
Pairing credentials must be stored using Android Keystore-backed encryption. Never commit TV credentials, API keys, or private signing material.
