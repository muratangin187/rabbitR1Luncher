# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Compose-based HOME launcher for the **Rabbit R1** (480×480 round, MT6765). Ships as a system app inside the user's LineageOS-based **CarrotOS** image, source tree at `/home/khalifa/lineage` (target: `gsi_r1-ap2a-userdebug`). Successor to `../mylauncher/` (Gradle-less Java + XML). Same package (`com.r1.launcher`), platform-signed (see Release compatibility) so the launcher can hold signature-only perms like `ACCESS_MESSAGES_ON_ICC`. Current clean-baseline chain — see `app/build.gradle.kts` for live `versionCode`/`versionName`.

`../mylauncher/CLAUDE.md` has feature-level history; this file covers only the Compose rewrite + OpenClaw chat + OS-image integration.

## Bootstrap & build

**See `DEVELOPING.md` for the host setup and the day-to-day loop.** Short version:

```bash
./bootstrap.sh   # once: JDK/SDK check, fetch platform.keystore, regenerate gradle-wrapper.jar
./r1.sh          # build + install + restart on the connected device
```

`gradle-wrapper.jar` is `.gitignored`, so `./gradlew` does not work on a fresh
clone until `bootstrap.sh` has run.

- **`versionCode` is overridden from the command line, not edited in place.** The `/system/app/R1Launcher/` copy baked into CarrotOS is the install floor and currently sits at **1000**, while HEAD tracks the real release number (15). Pass `-Pr1.versionCode=<n>` to clear the floor; `r1.sh` reads the floor off the device via `dumpsys package` and passes the next number automatically. `INSTALL_FAILED_VERSION_DOWNGRADE` means the ROM was rebuilt higher — `r1.sh` picks that up on its own. (Upstream instead pins the value in the file and hides it with `git update-index --skip-worktree`; this fork dropped that because it silently drops the change from every diff.) To cut a release: bump `versionCode`+`versionName` in `app/build.gradle.kts`, commit, `git tag vX.Y.Z && git push origin main --tags` — CI fires on tags, not main pushes.
- **`am start` won't replace a foreground launcher process.** "Activity not started, intent has been delivered..." means old code is still in memory. Force-stop first (`r1.sh` does).
- **`buildConfig` must stay enabled.** `gradle.properties` sets `android.defaults.buildfeatures.buildconfig=false` globally, but `AppsPanel.kt`'s `carrotOsInfo()` reads `BuildConfig.CARROT_VERSION` / `CARROT_BUILD_ID`. The app module therefore keeps `buildFeatures { buildConfig = true }` + two `buildConfigField("String", ...)` declarations in `defaultConfig` (empty strings by default — they fall through to `ro.lineage.*` / `Build` at runtime). Removing either breaks the build with a misleading "K expected" type error, not "unresolved reference".

### Host setup (this fork)

This fork builds the launcher APK on Arch Linux. There is **no local CarrotOS
image tree** — the ROM is consumed as a flashed device, not rebuilt. Anything in
this file about `/home/khalifa/lineage`, `make systemimage`, or `fastboot flash`
is upstream context, not a workflow available here.

- JDK 17 at `/usr/lib/jvm/java-17-openjdk`, Android SDK at `~/Android/Sdk`. Both exported by `~/.config/android-dev.env`, sourced from `.bashrc`. `android-doctor` health-checks the chain.
- **`platform.keystore`** (gitignored, required by both build types) is the **public AOSP test key**, SHA-256 `c8a2e9bc…192ab8`, PKCS12, store/key pass `android`, alias `platform`. `bootstrap.sh` fetches it from `khalifa007/carrotOS-harness` and verifies the fingerprint before letting the build proceed.
- **`r1.sh`** (tracked in this fork, unlike upstream) wraps the loop: build / install / restart / logcat / screenshot / screenrecord / scrcpy mirror / carroot root shell / adb-over-wifi / doctor.

## Pinned versions — do not drift without testing

- **AGP 8.7.2** + **Gradle 8.9** — AGP 8.7 is the lowest that works with Kotlin 2.0 compose plugin.
- **Kotlin 2.0.21** + `kotlin.plugin.compose` + `kotlin.plugin.serialization` — Kotlin 2.0 requires the separate compose plugin; serialization is for OpenClaw JSON-RPC.
- **Compose BOM 2024.10.01** — pulls compose.ui/foundation/animation/material3 at matched versions.
- **OkHttp 4.12.0**, **kotlinx-serialization-json 1.7.3**, **security-crypto 1.1.0-alpha06**, **bcprov-jdk18on 1.78.1**, **zxing-android-embedded 4.3.0**.
- **minSdk 23**, **targetSdk 33**, **compileSdk 34**, **Java 17**. minSdk/targetSdk match the old project for clean OTA upgrade.

## Architecture

Single Activity (`LauncherActivity`) with one `setContent { R1Theme { LauncherRoot(state, appStore, host) } }`. No fragments, no nav component.

**State** (`LauncherState`): plain Kotlin class with `mutableStateOf`/`mutableIntStateOf`/`mutableStateListOf` fields. No ViewModel — Activity has `configChanges="keyboardHidden|orientation|screenSize|uiMode"` + `launchMode=singleTask`, so it never recreates.

**Panel state machine** on `state.panel`: ~35 panels — HOME, ONBOARDING, APPS, SETTINGS+subscreens (DISPLAY/SOUND/DEVICE/ABOUT/VOICE/LANGUAGE…), NETWORK + WIFI_SCAN/PASSWORD/SHARE, BRIGHTNESS, VOLUME, UI_VOLUME, FACTORY_CONFIRM, OPENCLAW_* (QR/CHAT/CAMERA/SETTINGS/SESSIONS), MESSAGES/MESSAGES_THREAD, TERMINAL, CLAUDE, HERMES_CHAT/CONFIG, TRANSCRIBER_* (LIST/RECORDING/DETAIL/SETTINGS). Authoritative list in `LauncherState.Panel`.

Each panel has its own focus int. `back()` unwinds asymmetrically (e.g. BRIGHTNESS → SETTINGS_DISPLAY → SETTINGS → APPS → HOME; WIFI_PASSWORD → WIFI_SCAN → NETWORK; HERMES_CONFIG → HERMES_CHAT if `hermesConfigCameFromChat` else APPS; TRANSCRIBER_RECORDING back also calls `host.transcriberStopRecording`). Full table in `LauncherNav.kt`. Home dock + system-tray sheet removed in 3.16; wheel press on clock jumps straight to apps grid.

**Apps list is typed**: `state.apps : MutableList<AppEntry>`, sealed class — `Real(ResolveInfo)`, `Settings`, `OpenClaw`, `Messages`, `Terminal`, `Claude`, `Hermes`, `Meetings`. `LauncherActivity.loadApps()` appends synthetics after real apps in order: Messages, OpenClaw, Terminal, Claude, Hermes, Meetings, Settings. `launchApp(idx)` dispatches by type with the appropriate runtime-perm check (`WRITE_SETTINGS`, camera, `READ_SMS`, `RECORD_AUDIO`) before calling its `openX()` host method. Adding a synthetic: every `when` branch in `AppsPanel.kt` (key, painter, label, `appKey`, `appContentType`) needs the new case — sealed class enforces it but the icon switch is easy to miss.

**Navigation** lives in `LauncherNav.kt` as extension functions on `LauncherState`: `wheelUp(host)`, `wheelDown(host)`, `activate(host)`, `backPressed(host)`. Pure state mutations; side effects go through the `LauncherHost` interface implemented by `LauncherActivity` (app launch, system intents, brightness/volume, tones, OpenClaw, SMS, web-server, terminal, claude, clipboard).

**Compose tree** (z-stack inside one `Box` in `LauncherRoot.kt`): wallpaper + HomeScreen + every Panel composable, all gated on `state.panel == Panel.X`. Topbar overlays only on HOME. When adding a panel, register both the composable and its `onRowClick` dispatcher in `LauncherRoot.kt`.

**Key dispatch**: `Activity.dispatchKeyEvent` routes the same superset as the old launcher (volume, dpad, page up/down, headsethook, media_play_pause, call, assist, voice_assist) into `state.wheelUp/wheelDown/activate/backPressed`. The isHandled allowlist must stay in sync with the dispatcher. Debug overlay prints `key <code> sc <scan> NAME` on every keydown.

**Side button (BUTTON_1)**: keylayout (`device/rabbit/r1/keylayout/mtk-kpd.kl`) remaps physical KEY_POWER (116) → `BUTTON_1 WAKE` so the launcher sees raw DOWN/UP events. State machine in `dispatchKeyEvent`: single-tap on HOME = `lockScreen()`, single-tap elsewhere = `state.activate(host)`, double-tap = `state.goHome()` (`SIDE_DOUBLE_PRESS_MS=350`), long-press on HOME = `PowerService.openPowerDialog()` (`SIDE_LONG_PRESS_MS=500`), long-press in OPENCLAW_CHAT/TERMINAL/CLAUDE/HERMES_CHAT = push-to-talk via `*RecordStart()`/`*RecordStop()`. State vars: `sideDownAtMs`/`sideLastShortUpMs`/`sideLongFired`/`pendingSideSingle`. **Don't map to HOME or POWER** — HOME collapses DOWN/UP into one `onNewIntent`; POWER is intercepted by `PhoneWindowManager`.

**Animation tokens** (`ui/Common.kt`): `ANIM_OPEN_MS = 220`, `ANIM_CLOSE_MS = 170`, `ANIM_FOCUS_MS = 140`, `FOCUS_SCALE = 1.04f`, `UNFOCUS_ALPHA = 0.55f`. `Modifier.focusAnim(focused)` applies scale+alpha via `animateFloatAsState`.

**Java interop**: `Updater.java`, `AppStore.java`, `PowerService.java`, `ApkProvider.java` are unchanged from the old project — Kotlin calls them directly.

## Topbar SIM/network

`refreshSim()` reads operator + radio type via direct `TelephonyManager`. Requires `READ_PHONE_STATE` (runtime grant via `ensurePhonePerm()`). Mapping: LTE→`"LTE"`, NR→`"5G"`, HSPA*/UMTS/EVDO*→`"3G"`, EDGE/GPRS/CDMA/1xRTT→`"2G"`. Logs `refreshSim: simState=N op='X' dataOn=B radio=N -> 'Y'`.

Topbar pill (`ui/Topbar.kt`) shows lowercase operator + orange `LTE`/`5G`/`3G`/`2G` pill when `simPresent && cellularOn && networkType.isNotEmpty()`. Pill hides immediately on cellular off because `toggleCellular(false)` pre-clears `state.networkType`.

`netRx` BroadcastReceiver also calls `refreshSim()` so topbar updates live on connectivity changes.

**Operator name + LTE pill is NOT proof mobile data works.** Both come from `telephony.registry`, which survives even when `com.android.phone` is dead. Real proof: `mDataConnectionState=2` and `ping 8.8.8.8` returns RTT.

## Connectivity toggles

Pattern: optimistic `state.X = enable` → framework API → verify at +400ms → fall through to carroot shell → re-verify at +1500ms and +4000ms. Carroot @ 127.0.0.1:1337.

| Toggle    | Direct API (silent no-op without sys perm) | Carroot fallback                              |
|-----------|--------------------------------------------|------------------------------------------------|
| Wi-Fi     | `WifiManager.setWifiEnabled`               | `cmd wifi set-wifi-enabled enabled\|disabled`  |
| Cellular  | `TelephonyManager.setDataEnabled`          | `settings put global mobile_data 1\|0`         |
| Bluetooth | `BluetoothAdapter.enable/disable`          | `cmd bluetooth_manager enable\|disable`        |

Each toggle logs `toggleX(B) direct applied=A` and `toggleX(B) ok=O (applied=A)`. **Never trust the framework return value** — `setDataEnabled` returns void, `setWifiEnabled` returns true even when rejected. Always re-read state.

`svc data` and `svc wifi` are dead shims on this build — return 0, no effect. Don't fall back to them.

## Factory reset

`Panel.FACTORY_CONFIRM` (`ui/FactoryConfirmPanel.kt`) is the two-row destructive confirm from Settings → "factory reset". `state.openFactoryConfirm()` resets `factoryConfirmFocus = 0` (back row) so a stray activate cancels rather than wipes. On confirm, `factoryReset()` sends `am broadcast -a android.intent.action.FACTORY_RESET --receiver-foreground -p android` via carroot, with `MASTER_CLEAR` legacy fallback. Both broadcasts are protected — must go through carroot.

## OS-image dependency (CarrotOS at /home/khalifa/lineage)

Three cross-tree integrations:

- **APN database**: `device/rabbit/r1/device.mk` PRODUCT_COPY_FILES includes `device/sample/etc/apns-full-conf.xml:system/etc/apns-conf.xml`. Without this, the carriers DB is empty on first boot and no SIM gets data.
- **carroot socket**: `device/rabbit/r1/rootdir/system/etc/init/carroot.rc` runs `nc -L -p 1337 sh` as root with `u:r:su:s0`. `sendToCarroot(cmd: String): Boolean` in `LauncherActivity.kt` is the single entry point; returns true on socket-write success, NOT command effect — re-verify state after every call.
- **Doze whitelist for ntfy**: `device/rabbit/r1/sysconfig/r1-launcher.xml` declares `<allow-in-power-save package="com.r1.launcher" />`. Without this, Doze severs the long-poll TCP and firewalls DNS for the launcher's package — `NtfySubscriber` reconnects fail with `Unable to resolve host "ntfy.sh"` until the next maintenance window. Verify via `adb shell dumpsys deviceidle whitelist | grep com.r1.launcher`.

Rebuild + flash sequence:

```bash
cd /home/khalifa/lineage && source build/envsetup.sh
lunch gsi_r1-ap2a-userdebug             # NOT lineage_r1-userdebug; product name is gsi_r1, release is ap2a
make systemimage -j$(nproc)
adb reboot bootloader
fastboot reboot fastboot                # system is a logical partition inside `super`; need fastbootd
fastboot flash system out/target/product/r1/system.img
fastboot reboot
# `fastboot -w` only required if boot jars / telephony jars changed (ART cache mismatch).
# Sysconfig XML / APN / launcher-only changes preserve userdata fine.
```

## Wi-Fi sharing (hotspot)

Settings → Network → "wifi share" exposes toggle/SSID/password/connected/auto-off rows. Driven via carroot — `cmd wifi start-softap "<ssid>" wpa2 "<pass>"` (one-shot only; the `set-ssid` then `start-softap` two-step is rejected on this build). Verify by parsing `ip link show ap0` for `state UP` + `LOWER_UP`. **Do not** parse `dumpsys wifi` for SAP state — those strings come from `cmd wifi`'s event listener, not from dumpsys.

Connected-client polling: `ip neigh show dev ap0` every 3s → `state.wifiShareConnectedClients`. Auto-off via `Handler.postDelayed` chain in `armWifiShareTimer()`.

Persistence: `wifishare/WifiSharePrefs.kt` — SSID + timer in plain prefs, password in `EncryptedSharedPreferences("wifishare.secure")`. Defaults seeded on first read (random SSID `R1-XXXX`, random 10-char password).

## SMS / Messages

CarrotOS has **no default SMS app**, so the framework's `InboundSmsHandler` silently drops every incoming SMS (Android 4.4+ restriction — only the default app can write to `content://sms`). Workaround:

1. **`messages/SmsReceiver.kt`** — manifest-registered receiver for the legacy `android.provider.Telephony.SMS_RECEIVED` action (still fires for any holder of `RECEIVE_SMS`). Decodes PDUs via `Telephony.Sms.Intents.getMessagesFromIntent`, concatenates multi-part, persists.
2. **`messages/SmsCache.kt`** — append-only JSON log at `filesDir/sms-cache.json`, capped at 1000 entries.
3. **`messages/SmsLoader.kt`** — `loadConversations(ctx)` and `loadMessagesFor(ctx, addr)` merge three sources: `content://sms`, ICC SIM via reflection on `SmsManager.getAllMessagesFromIcc()` (hidden `@SystemApi`, see `invokeAllMessagesFromIcc()`), and `SmsCache`. Most carriers don't write to (1) or (2) anymore — (3) is the working path.

Live refresh: after each capture, `SmsReceiver` fires a local `com.r1.launcher.NEW_SMS_LOCAL` broadcast that the activity catches.

`MessagesPanel` shows one row per sender. `MessagesThreadPanel` shows the thread as alternating bubbles (incoming = grey left, outgoing = orange right).

## Web companion panel

`web/R1WebServer.kt` (NanoHTTPD + NanoWSD) hosts HTTP+WS on port 8080, serves SPA from `assets/web/`, JSON-RPC at `/api/rpc`:
```
req:   {type:"req",   id, method, params}
res:   {type:"res",   id, ok, payload, error}
event: {type:"event", event, payload}
```

**RPC methods** (`web/WebRpc.kt`) — thin shims over `LauncherHost`/`LauncherState`:
- `state.snapshot` — full live state, also auto-broadcast at 1 Hz
- `sms.list` / `sms.thread`
- `text.send` (target = `openai_key` or `openclaw_chat`)
- `wifi/cellular/bt/hotspot.toggle`, `brightness/volume.set`, `openai.set`
- `terminal.run` / `terminal.clear` / `terminal.history` — gated on `state.webTerminalEnabled`; return `{code:"disabled"}` when off
- `claude.send` / `claude.clear` / `claude.history` — always available

**Broadcast events**: `state.snapshot` (1 Hz), `terminal.output`, `claude.message`, `claude.streaming` (empty `text` = stream done), `claude.busy`, `claude.cleared`. Helpers in `R1WebServer.broadcast*`; `LauncherActivity` calls them from `terminalRun`/`claudeSend` callbacks so on-device + web stay in lockstep.

**Web UI** (`assets/web/`): single-page, mirrors device design (black bg, `#FF6A00`, Jersey 15, 2px-edge tiles, scanline+grain overlays). `index.html`: `view-home` (clock + grid) plus `<section class="view view-app">` per app, `<template id="tpl-app-header">` cloned for back-pill+title+status. `setView(name)` swaps active class; `<` or Esc returns home. `app.js` = WS-RPC client + view router + ~110-LOC markdown renderer (HTML-escapes first).

**Asset routing** (`R1WebServer.serveHttp`): `/`+`/index.html` → `web/index.html`; `/app.js`+`/style.css` → matching files; `/static/<x>` → `web/<x>` (MIME via `guessMime()`); `/api/state` → JSON; else 404. Font canonical at `res/font/jersey_15.ttf`; copy at `assets/web/` is what the server serves.

Server auto-starts in `onCreate`; toggle via Settings → Network → "remote panel" or `am broadcast -a com.r1.launcher.TOGGLE_WEB_SERVER --ez on true`.

**IP discovery** (`discoverLocalIp`): preferred order `ap0` > `wlan*` > `eth*` > else. Skip cellular (`ccmni*`/`rmnet*`/`ppp*`) — those are CGN, not LAN-routable. Without this `ccmni0`'s `10.x.x.x` wins by alphabetical sort.

**NanoHTTPD/NanoWSD gotchas:** default 5s socket timeout breaks WS — use `start(0, false)`. `WebSocket.send()` from main thread throws — route through a single-threaded `sendExecutor`.

## OpenClaw chat panel

Built-in client for an [openclaw](https://github.com/openclaw/openclaw) AI gateway. Pair via QR, chat with streaming replies. Voice input + assistant readback go through ElevenLabs (see Voice below). Files in `app/src/main/java/com/r1/launcher/openclaw/`: `SetupCode.kt` (URL-safe Base64 QR decode), `OpenClawPrefs.kt` (EncryptedSharedPreferences `openclaw.secure` for url/bootstrap/deviceToken/token + plain `openclaw.plain` for `node.instanceId`/`chat.hide`/`chat.fontSize`), `DeviceIdentityStore.kt` (Ed25519 via Bouncy Castle, persisted at `filesDir/openclaw/identity/device.json`, pipe-delimited v3 auth payload), `GatewaySession.kt` (OkHttp WS + JSON-RPC), `ChatMessage.kt`.

### Protocol gotchas

- **Role + scopes**: connect as `role: "operator"` with `scopes: ["operator.read", "operator.write", "operator.talk.secrets"]`. `node` role can't access chat methods; `PAIRING_SETUP_BOOTSTRAP_PROFILE` only grants `operator.*`.
- **Server-issued nonce**: wait for `connect.challenge` after opening the socket; use that nonce in the signed payload + `device.nonce`. Don't generate your own.
- **`chat.subscribe` requires admin** unless wrapped: `request("node.event", {event: "chat.subscribe", payloadJSON: "..."})`. `chat.send` and `chat.history` work directly.
- **`client.id` allowlisted** — use `"openclaw-android"`.
- **No URL port mangling** — gateway URL may be plain `wss://host`; OkHttp picks 443/80 from scheme.
- **Audio attachments are NOT auto-transcribed** by the gateway — transcribe client-side before `chat.send` or text-only LLMs return "I didn't receive any text".
- Admin session methods (`sessions.compact/reset/delete/cleanup/restore/patch`) are unreachable for operator clients — use new-thread switch, not `sessions.reset`. See memory `project_openclaw_admin_methods_unreachable.md`.

## Voice (Settings → Voice)

Single source of truth for ElevenLabs key + voice picker + auto-speak toggle. Used by **OpenClaw chat / Terminal / Claude / Hermes** for STT, and by chat panels (OpenClaw / Hermes) for assistant TTS readback. Files in `voice/`: `VoicePrefs.kt` (EncryptedSharedPreferences `voice.secure` for `elevenlabs.key`, plain `voice.plain` for `voice.enabled` + `voice.id`; 4-voice catalog hardcoded — rachel `21m00Tcm4TlvDq8ikWAM` default, adam `pNInz6obpgDQGcFmaJgB`, aria `9BWtsMINqrJLrRacOk9x`, sarah `EXAVITQu4vr4xnSDxMaL`), `StreamingAudioCapture.kt` (mic → 16 kHz mono PCM_16BIT VOICE_RECOGNITION frames, ~80ms chunks, 60s cap), `ElevenLabsRealtimeClient.kt` (Scribe v2 WS `wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=scribe_v2_realtime&audio_format=pcm_16000&language_code=en&commit_strategy=vad`, `xi-api-key` header, PCM base64-in-JSON, emits `partial_transcript`/`committed_transcript`), `ElevenLabsTtsClient.kt` (Flash v2.5 REST `/v1/text-to-speech/{voice_id}/stream?output_format=mp3_22050_32&optimize_streaming_latency=4`, streams MP3 → `cacheDir/openclaw-voice/assistant.mp3` → MediaPlayer, returns `Call` for `cancel()`; `mp3_22050_32` ~3 KB/s, ~4× smaller than 44100_128 and intelligible for speech).

### Voice flow

`LauncherActivity.startVoiceCapture(sink)` is the single entry. Sinks: `CHAT` (renders `state.chatPartialText`, auto-sends via `openClawSendText`), `TERMINAL` (renders `state.terminalPartial`, pastes into `state.terminalInput` — no auto-exec), `CLAUDE` (renders `state.claudePartial`, pastes into `state.claudeInput`), `HERMES_CHAT` (parallel to CHAT). Side-button long-press in those panels calls the relevant `*RecordStart()` host method; release calls `stopVoiceCapture()` which sends `commit:true` and waits for `committed_transcript`.

**Recording cue & mic-open delay**: `startVoiceCapture` plays `playRecordingTone()` (moving.mp3 + `ToneGenerator.TONE_PROP_BEEP` fallback for MTK SoundPool drops), then delays AudioRecord open by 200 ms — opening `VOICE_RECOGNITION` reroutes the audio path and silences in-flight MEDIA samples.

**Pending bubble UX (chat sinks)**: partial-text bubble renders gated on `chatPartialText.isNotBlank()` (NOT `chatRecording` — that flips false on release ~200-500 ms before `committed_transcript` and produces a flicker gap). `handleCommittedTranscript` clears partial AND adds persisted bubble in the same frame. Same pattern for streaming assistant bubble: `chatStreamingText` cleared inside `applyOpenClawHistory` (and Hermes equivalent), not on `onChatTerminal`.

**TTS auto-speak**: `speakLatestAssistantIfNeeded()` fires on assistant arrival, gated on `voiceEnabled && panel == OPENCLAW_CHAT && openClawSpeakNextAssistant` (gate set in `openClawSendText`). `cancelOpenClawSpeech()` aborts in-flight HTTP `Call` + MediaPlayer; called from `startVoiceCapture` (PTT silences playing reply), no-stacking guard, session close, `onDestroy`. Volume: `USAGE_MEDIA` → `STREAM_MUSIC`, controlled by Settings → Sound. Hermes has parallel `speakLatestHermesAssistantIfNeeded()` / `cancelHermesSpeech()`. ElevenLabs locks billing at submission — cancel saves bandwidth + UX, not guaranteed credit refund.

### ElevenLabs key — five ways to set it

1. Settings → Voice → "elevenlabs key" row → RetroKeyboard
2. adb broadcast: `am broadcast -a com.r1.launcher.SET_ELEVENLABS_KEY --es key 'sk_...'` (receiver: `voiceKeyRx`)
3. Clipboard paste — "paste" pill in the keyboard overlay
4. QR scan — Settings → Voice → "scan key from qr" sets `qrScanMode = OPENAI_KEY` (enum name kept for back-compat)
5. Web companion — "send text" tab with target = `voice_key`

Validation: accepts `sk_<29+ chars>` prefix OR 32-char hex (any case).

## Terminal panel (Panel.TERMINAL)

Synthetic app + Compose panel that turns the launcher into a usable shell on the round screen — replaces shipping a separate Termux. Source: `ui/TerminalPanel.kt`.

Layout: back pill + cwd + `hide`/`kbd` toggle + status (`idle`/`rec`/`stt`/`...`); scrollable `LazyColumn` (auto-scrolls unless wheel-scrolled up); input row with `$` prompt + `paste`/`run`/`clr` pills; collapsible `RetroKeyboard` (`state.terminalKbVisible`).

**Execution model**: each command is one carroot connection. cwd tracked client-side in `state.terminalCwd`, prepended as `cd <cwd> && (<cmd>) ; pwd > <pwdFile> ; printf SENTINEL` so `cd /system` then `pwd` works across fresh `sh` instances. Streaming helper: `sendToCarrootStreaming(userCmd, cwd, onLine, onDone)` opens socket, writes wrapped script, `socket.shutdownOutput()`, reads stdout line-by-line on a background thread until EOF or sentinel. Lines flow into `state.terminalOutput` (cap 500, FIFO) AND `webServer?.broadcastTerminalOutput(line, cwd)`.

**No more alpine auto-routing.** The terminal panel runs commands directly against Android's shell via carroot — no chroot, no `r1-alpine` wrapper, no `npm`/`node`/`python` rewrites. Users who need a real Linux env open Termux (separate app, real PTY). `clear`/`cls` still intercepted client-side.

**Voice dictation**: long-press side button while terminal is open → `terminalRecordStart()` → `startVoiceCapture(VoiceSink.TERMINAL)` (ElevenLabs Realtime) → committed transcript appends to `state.terminalInput`. Doesn't auto-submit. Live partial transcripts appear in `state.terminalPartial`. Single ElevenLabs key shared with chat + claude panels — see Voice section.

**Web terminal tab**: companion panel mirrors via `terminal.output` events. Gated on `state.webTerminalEnabled` (Settings → Network → "remote terminal", default off — exposes a root shell over LAN). RPC methods return `{code:"disabled"}` when off.

## Claude Code app (Panel.CLAUDE)

Synthetic app + chat panel that talks to the `claude` CLI via Termux's `RUN_COMMAND` intent API. Source: `ui/ClaudePanel.kt`, `claude/ClaudeMessage.kt`, `claude/TermuxBridge.kt`. Replaces the v1.1.x alpine-chroot path which kept breaking at the toybox↔Linux-userspace boundary (see memory `project_claude_via_termux.md` for the migration story).

**Setup contract** (one-time, inside Termux on the device):

```
echo "allow-external-apps=true" >> ~/.termux/termux.properties
pkg update -y && pkg install -y nodejs
npm install -g @anthropic-ai/claude-code
claude auth login --claudeai
```

`allow-external-apps=true` is the critical line — Termux's RUN_COMMAND permission is `signatureOrSystem`; our launcher (LineageOS platform key) and Termux (its own key) don't share signatures, so the flag bypasses the check. Manifest declaration of `com.termux.permission.RUN_COMMAND` is still required (AndroidManifest.xml).

The ClaudePanel renders a setup hint (`ClaudeSetupHint` composable) whenever `state.claudeAuthed` is false; "open termux" pill `am start`s Termux, "copy" pill copies the 4-line block to the clipboard, "retry" re-probes via `TermuxBridge.probeClaude()`.

**Execution model**: `LauncherActivity.claudeSend(text)` builds `[--print, (-c)?, <text>]` and fires `TermuxBridge.run(path = "$TERMUX_BIN/claude", args, background=true, callback)`. Termux runs the binary in a real PTY in its sandbox; the `PendingIntent` fires once with `result` bundle (`stdout`/`stderr`/`exitCode`/`err`/`errmsg`) when the process exits. Single assistant bubble appended on completion — no per-token streaming (RUN_COMMAND result is buffered). First turn omits `-c`; turn 2+ uses `-c` to continue the most recent claude session. `clr` pill resets `claudeMessages` + flips `claudeFirstTurn = true`.

**state.claudeAuthed** semantics changed: now means "Termux installed AND `claude --version` exits 0". Auth state (logged in / not) is not separately tracked — failed turns surface their own "Please run /login" message and the setup banner re-opens automatically (see `app.js` heuristic in `appendClaudeMessage`).

**Web RPCs**: `claude.send`, `claude.clear`, `claude.history`, `claude.status` (returns `{ready: bool}`). The old `claude.auth.*` and `claude.setup.*` family is gone — setup happens inside Termux on the device, not from a browser.

**Limitations vs the previous chroot path**: no per-token streaming animation; output is buffered until `claude --print` exits. For interactive features (`/resume`, slash commands, plan mode), open Termux directly — `claude` works there with a full TTY.

## Hermes Agent app (Panel.HERMES_CHAT / HERMES_CONFIG)

See memory `project_hermes_agent_app.md` for the full implementation map. One-line recap: synthetic app that talks to a self-hosted **Hermes Agent** (`github.com/NousResearch/hermes-agent`) via its OpenAI-compatible HTTP gateway (`POST /v1/chat/completions?stream=true`, SSE deltas, `Authorization: Bearer` + `X-Hermes-Session-Id` headers). Files: `hermes/HermesPrefs.kt` / `HermesMessage.kt` / `HermesClient.kt`, panels at `ui/HermesChatPanel.kt` / `ui/HermesConfigPanel.kt`. Voice integration adds `VoiceSink.HERMES_CHAT` + `speakLatestHermesAssistantIfNeeded()` + `cancelHermesSpeech()`, parallel to the OpenClaw plumbing. Wire is **REST+SSE only**, no WebSocket — don't try to reuse `GatewaySession`. Config rows accept typed URL + bearer token; no QR pairing this round. Theme color `AppThemes.Hermes = #FFB300` (amber).

## Resources

Copied from `../mylauncher/res/` except layouts:

- `drawable/` — vectors loaded via `painterResource(R.drawable.ic_xxx)`. Synthetic apps reuse: Settings → `ic_settings`, OpenClaw → `ic_wifi_arc`, Messages → `ic_messages`.
- `assets/web/` — `index.html` + `app.js` + `style.css`. Vanilla JS, no build step.
- `font/jersey_15.ttf` — wired into `LocalR1Type.appCard` (24sp), `clock`/`date` styles in `Theme.kt`.
- `values/colors.xml`, `values/strings.xml`, `xml/accessibility_service.xml`, `xml/network_security_config.xml` — unchanged.
- `values/themes.xml` — declares `@style/Theme.R1Launcher` for windowing (fullscreen, no action bar). Compose draws everything else.

## Release compatibility

- `applicationId = "com.r1.launcher"` — same as old project.
- **Signed with the LineageOS platform key** — `platform.keystore` (PKCS12, password `android`, alias `platform`) is converted from `~/lineage/build/make/target/product/security/platform.{pk8,x509.pem}`. Both `debug` and `release` build types use it. The prebuilt at `~/lineage/device/rabbit/r1/prebuilt/app/R1Launcher/Android.mk` declares `LOCAL_CERTIFICATE := platform` so Soong re-signs at OS-build time with the same key — sigs match, `adb install -r app-debug.apk` works in-place over the system app. Required because `ACCESS_MESSAGES_ON_ICC` (signature-only) gates `SmsManager.getAllMessagesFromIcc()`. SHA-256 cert fingerprint `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`.
- **One-time migration cost from `debug.keystore` (pre-v3.28.1) to platform key (v3.28.1+):** existing `/data/app/` installs signed with `debug.keystore` will reject upgrade with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. After flashing the new system image, either `fastboot -w` (cleanest) or `adb uninstall com.r1.launcher` first, then reinstall.
- **Release cuts on tag push, not main push.** `.github/workflows/release.yml` triggers on `v*` tags; building the APK uses whatever `versionCode`/`versionName` is at HEAD. Workflow: unset skip-worktree on `app/build.gradle.kts`, bump both fields, commit, `git tag vX.Y.Z && git push origin main --tags`. Re-pin debug and re-skip after.
- **`versionCode` chain** restarted at `1` for the v1.0.x clean baseline (last legacy was 213+). Increment both fields together for every release; same `versionCode` + new `versionName` won't allow OTA upgrade.

## Do not waste time re-attempting

Build/release (most build/version traps are in **Bootstrap & build** + **Release compatibility** — read those first):
- **Going Gradle-less / checking in `gradle-wrapper.jar` / AGP < 8.6 with Kotlin 2.0.** Keep Gradle, run `bootstrap.sh`, keep AGP ≥ 8.7.
- **Material3 components with default styling.** Material3 is in only so `MaterialTheme` populates CompositionLocals. Use raw `Box`/`Row`/`Column`/`Text`, not `Button`/`Card`.
- **Skipping `state.back()` after `host.*` side-effect calls.** Self-closing panels expect the state machine to unwind.

System / OS:
- **Stripping `com.android.phone`/`TeleService.apk`/`com.android.providers.telephony`/`mediatek-telephony-*`.** `DataNetworkController` lives in `com.android.phone`; removing leaves operator+pill working (from `telephony.registry`) while `mDataConnectionState=-1`. Keep them.
- **Flashing without `fastboot -w` when telephony boot jars changed.** `oat_primary/` mismatch ANRs `com.android.phone` ("Boot image chunk count mismatch"). Recover: `rm -rf /data/user_de/0/com.android.phone/cache/oat_primary/ /data/dalvik-cache/*phone*` + reboot. Sanity: `service list | grep -E "phone|iphonesubinfo|isub"` shows all three.
- **`am start` to "relaunch" after install.** Only foregrounds existing process. Force-stop via carroot first.
- **Trusting `am broadcast` results.** `result=0` only means `am` exited cleanly. Verify via `dumpsys activity broadcasts`.
- **Mapping side button to HOME or POWER.** HOME collapses DOWN/UP into one `onNewIntent`; POWER intercepted. Use `BUTTON_1` — tradeoff: dead inside third-party apps.

Audio / Voice:
- **`AudioTrack` raw PCM for short clips.** Silently dropped. Wrap in WAV + `MediaPlayer.setDataSource(path)`.
- **`MediaPlayer.setDataSource(FileInputStream(file).use { it.fd })`.** `.use` closes FD before `prepare()`. Pass the path string.
- **Opening `AudioRecord` (esp. `VOICE_RECOGNITION`) immediately after a UI sound.** AudioRecord steals the audio path; in-flight samples clipped. Delay mic open ~200 ms after any cue.
- **`SoundPool.play(...)` with `rate != 1.0f` on MTK.** Silently dropped. Keep `rate=1f`; layer `ToneGenerator.startTone(...)` if a beep is mandatory.
- **ElevenLabs `committed_transcript` not arriving while `partial_transcript` works.** Error frame slipped past `message_type` switch. Catch-all in `onMessage` must route frames with `error`/`exceed` to `onError`.
- **Buffering full TTS MP3 before playback.** Use `/stream?output_format=mp3_22050_32&optimize_streaming_latency=4`, hold OkHttp `Call` for `cancel()`.
- **Force-setting `STREAM_MUSIC` to MAX before TTS.** Overrides user's volume slider.

Networking / SMS / Wi-Fi share:
- **`adb shell cmd clipboard set`.** Not implemented. Use `com.r1.launcher.SET_OPENAI_KEY` broadcast.
- **Delivering SMS via `content://sms` without being default SMS app.** Use legacy `SMS_RECEIVED` + own JSON cache.
- **`SmsManager.getAllMessagesFromIcc()` direct call.** Hidden `@SystemApi`; reflect + `runCatching`.
- **`cmd wifi soft-ap-set-ssid` + `start-softap` two-step.** Only one-shot `cmd wifi start-softap "<ssid>" wpa2 "<pass>"` works. Verify via `ip link show ap0` `state UP` + `LOWER_UP` (retry ~6s on MTK), NOT `dumpsys wifi`. Poll clients via `ip neigh show dev ap0` (interface is `ap0`, not `wlan1`).
- **NanoHTTPD default 5s socket-read timeout.** Tears down WS. Use `start(0, false)`. `WebSocket.send()` from main thread throws — route through `sendExecutor`.
- **First non-loopback interface for panel IP.** `ccmni0` (10.x CGN) sorts before `wlan0`. Use `discoverLocalIp()`.

OpenClaw / chat UX:
- **OpenClaw role `node` for chat / direct `chat.subscribe` / self-generated connect nonce / `:18789` port suffix.** Use `operator` scopes; wrap subscribe in `node.event`; wait for `connect.challenge`; let OkHttp pick port from scheme.
- **Auto-transcribing audio attachments via `chat.send`.** Gateway forwards as multimodal, no STT. Transcribe client-side first.
- **Clearing streaming bubble on `onChatTerminal`.** Clear inside `applyOpenClawHistory` after persisted messages land.
- **Gating partial-transcript bubble on `chatRecording`.** Gate on `chatPartialText.isNotBlank()` alone.

Misc:
- **`*/` inside Kotlin KDoc.** Closes the block.
- **Bumping `multiplatform-markdown-renderer-m3`.** Every version calls a `drawLine` signature missing from compose.ui 1.7.x — hard-crash on `>` blockquote. Strip `^[ \t]*>[ \t]?` per line before `Markdown(...)`. Real fix needs Compose BOM 2025.x.
- **`pgrep -af "<pattern>"`.** Returns nothing (toybox quirk). Use `ps -ef | grep -E "..." | grep -v grep`.
- **Forking Termux for UI restyling.** GPLv3 contagion + 50k LOC of native PTY / bootstrap installer / terminal-view. Use `RUN_COMMAND` intent as a service (current path) instead.

## Testing loop

**There are no automated tests** — no `app/src/test`, no `androidTest`. Every
change is verified by running it on hardware. `DEVELOPING.md` has the full
rationale and toolkit; the essentials:

```bash
./r1.sh              # build + install + restart
./r1.sh mirror       # scrcpy — the round panel in a desktop window, touch works
./r1.sh log          # launcher tags + AndroidRuntime:E + ActivityManager:E
./r1.sh root <cmd>   # root shell through carroot on 127.0.0.1:1337
./r1.sh doctor       # version floor, HOME resolution, carroot liveness
```

The emulator is **not** usable on this host — no `vmx` in `/proc/cpuinfo`, so
VT-x is off in firmware and the emulator would fall back to full CPU emulation.
Even with it enabled, an AVD has no carroot socket, no side button, no scroll
wheel and no camera motor, so the interesting panels need hardware anyway.

Drive the UI headlessly with D-pad keycodes (`./r1.sh keys`). The **side
button** is the exception: it's `BUTTON_1` via the ROM keylayout and its
tap / double-tap (350 ms) / long-press (500 ms) machine reads real DOWN/UP
timing, which `input keyevent` doesn't reproduce — test it by hand.

For OpenClaw work: real R1 + running openclaw gateway on a reachable host. After install:

```bash
adb shell pm grant com.r1.launcher android.permission.CAMERA
adb shell pm grant com.r1.launcher android.permission.RECORD_AUDIO
adb shell "am force-stop com.r1.launcher; am start -n com.r1.launcher/.LauncherActivity"
adb logcat -s GatewaySession R1Motor AudioTrack MediaPlayer
```
