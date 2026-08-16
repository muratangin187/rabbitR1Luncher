package com.r1.launcher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.r1.launcher.hermes.HermesConnection
import com.r1.launcher.hermes.HermesMessage
import com.r1.launcher.hermes.HermesToolEvent
import com.r1.launcher.messages.SmsConversation
import com.r1.launcher.messages.SmsItem
import com.r1.launcher.notifications.Notification
import com.r1.launcher.openclaw.ChatMessage
import com.r1.launcher.openclaw.SessionEntry
import com.r1.launcher.transcriber.MeetingIndexEntry
import com.r1.launcher.transcriber.TranscriberDetailAction
import com.r1.launcher.translator.ProviderId
import com.r1.launcher.translator.TranslationMessage

enum class Panel { HOME, ONBOARDING, APPS, SETTINGS, SETTINGS_DISPLAY, SETTINGS_SOUND, SETTINGS_DEVICE, SETTINGS_ABOUT, SETTINGS_VOICE, SETTINGS_VOICE_TUNING, SETTINGS_VOICE_SUBSCRIPTION, SETTINGS_LANGUAGE, SETTINGS_CREDENTIALS, NETWORK, WIFI_SCAN, WIFI_PASSWORD, WIFI_SHARE, WIFI_SHARE_EDIT, REMOTE_PANEL, PANEL_PASSCODE, NTFY_CONFIG, BT_SCAN, BRIGHTNESS, VOLUME, UI_VOLUME, FACTORY_CONFIRM, OPENCLAW_QR, OPENCLAW_CHAT, OPENCLAW_CAMERA, OPENCLAW_SETTINGS, OPENCLAW_SESSIONS, MESSAGES, MESSAGES_THREAD, TERMINAL, HERMES_CHAT, HERMES_CONFIG, HERMES_QR, HERMES_CONNECTION_EDIT, TRANSLATOR_ONBOARDING, TRANSLATOR, TRANSLATOR_SETTINGS, TRANSCRIBER_LIST, TRANSCRIBER_RECORDING, TRANSCRIBER_DETAIL, TRANSCRIBER_SETTINGS, NOTIFICATIONS, TESTING, CAMERA, GALLERY, GALLERY_VIEW }

enum class WifiShareEditTarget { SSID, PASSWORD }

/**
 * Lifecycle of one voice-driven AI image edit. The user is told which of these
 * they're in at all times — a silent 20-40s wait is the whole reason this is a
 * staged enum and not a boolean `busy` flag.
 */
enum class AiStage {
    IDLE,
    /** Mic open, side button still held. */
    RECORDING,
    /** Audio uploaded, waiting on the transcript. */
    TRANSCRIBING,
    /** Transcript + photo uploaded, waiting on the generated image. */
    GENERATING,
    /** New image saved to the gallery. */
    DONE,
    FAILED,
}

/** Kind of toast — drives the edge color in [com.r1.launcher.ui.ToastOverlay]. */
enum class ToastKind { INFO, SUCCESS, FAIL }

/** One terminal scrollback line. [id] is a monotonic counter so the output
 *  LazyColumn can key on stable identity even as the FIFO cap shifts indices
 *  (keying on [text] alone would collide on duplicate lines). */
data class TerminalLine(val id: Long, val text: String)

/**
 * Single in-flight toast. [id] makes consecutive identical messages distinct
 * for `LaunchedEffect` re-keying; [expiresAtMs] is the wall-clock dismiss time
 * so the overlay can self-clear via `delay`.
 */
data class ToastEntry(
    val id: Long,
    val text: String,
    val kind: ToastKind,
    val expiresAtMs: Long,
)

/**
 * Single container for all UI state. Activity mutates; Compose reads.
 *
 * Uses mutableStateOf so Compose observes changes directly — no Flow collection,
 * no ViewModel ceremony. The Activity is single-instance with configChanges flags,
 * so we don't need ViewModel lifecycle survival.
 *
 * Panel state machine mirrors the old Java: panels stack conceptually but we only
 * show one at a time (plus scrim + topbar overlays). `back()` unwinds one level.
 */
class LauncherState {
    // --- panel ---
    var panel by mutableStateOf(Panel.HOME)
        private set

    // --- per-panel focus indices ---
    var appsFocus by mutableIntStateOf(0)
    /**
     * Bumped by [activate] when launching from the apps panel via wheel/side
     * button. The focused AppCard observes this and runs the same press
     * animation that the touch path already fires via its `clickable` lambda,
     * so both entry points share one visual feedback.
     */
    var appsPressTrigger by mutableIntStateOf(0)
    /** Onboarding wizard: 0=welcome, 1=network, 2=updates, 3=done. */
    var onboardingStep by mutableIntStateOf(0)
    /** Focus within the current onboarding step's row list. */
    var onboardingFocus by mutableIntStateOf(0)
    /** True while the wizard is active — gates back-routing detours from sub-flows like wifi scan. */
    var isOnboarding by mutableStateOf(false)
    /** Top settings: 0=back, 1=network, 2=display, 3=sound, 4=device, 5=about. */
    var settingsFocus by mutableIntStateOf(0)
    /** Display category: 0=back, 1=brightness. */
    var settingsDisplayFocus by mutableIntStateOf(0)
    /** Sound category: 0=back, 1=system sound toggle, 2=system volume, 3=sound. */
    var settingsSoundFocus by mutableIntStateOf(0)
    /** Device category: 0=back, 1=updates, 2=factory reset. */
    var settingsDeviceFocus by mutableIntStateOf(0)
    /** About category: 0=back. (single info row). */
    var settingsAboutFocus by mutableIntStateOf(0)
    /** Language picker: 0=back, 1..N=LocalePrefs.SUPPORTED[N-1]. */
    var settingsLanguageFocus by mutableIntStateOf(0)
    var networkFocus by mutableIntStateOf(0)
    /** Factory-reset confirmation: 0=back/cancel, 1=confirm wipe. Defaults to 0 so accidental activate is a cancel. */
    var factoryConfirmFocus by mutableIntStateOf(0)
    var wifiScanFocus by mutableIntStateOf(0)
    var wifiConnectedSsid by mutableStateOf("")
    var wifiSelectedSsid by mutableStateOf("")
    var wifiPasswordInput by mutableStateOf("")
    val wifiScanResults = mutableStateListOf<String>()
    // --- bluetooth scan ---
    data class BtDevice(val name: String, val address: String, val bonded: Boolean, val connected: Boolean = false)
    val btDevices = mutableStateListOf<BtDevice>()
    var btScanning by mutableStateOf(false)
    var btScanFocus by mutableIntStateOf(0)
    // --- wifi share (hotspot) ---
    var wifiShareFocus by mutableIntStateOf(0)
    var wifiShareEnabled by mutableStateOf(false)
    var wifiShareSsid by mutableStateOf("")
    var wifiSharePassword by mutableStateOf("")
    val wifiShareConnectedClients = mutableStateListOf<String>()
    /** 0 = off, otherwise minutes until auto-shutoff. Persisted via WifiSharePrefs. */
    var wifiShareTimerMinutes by mutableIntStateOf(0)
    /** Live countdown shown on the enable row while hotspot is on with a timer. */
    var wifiShareTimerRemainingSec by mutableIntStateOf(0)
    /** Which field the WIFI_SHARE_EDIT keyboard is editing. */
    var wifiShareEditTarget by mutableStateOf(WifiShareEditTarget.SSID)
    /** Buffer used by WIFI_SHARE_EDIT; copied into the targeted field on save. */
    var wifiShareEditInput by mutableStateOf("")
    /** Whether the connected-clients row in WIFI_SHARE is expanded to show MACs. */
    var wifiShareClientsExpanded by mutableStateOf(false)
    /** Live brightness 1..255 — pre-seeded from Settings.System on openSettings(). */
    var brightnessLevel by mutableIntStateOf(128)
    /** Live STREAM_MUSIC volume 0..volumeMax. */
    var volumeLevel by mutableIntStateOf(0)
    var volumeMax by mutableIntStateOf(15)
    /** UI-click feedback volume 0..uiVolumeMax. Drives SoundPool gain in
     *  playMovingSound / playUiClickSound; persisted via SoundPrefs. */
    var uiVolumeLevel by mutableIntStateOf(5)
    var uiVolumeMax by mutableIntStateOf(15)
    /** Master on/off for UI-feedback sounds. When false, every click/tick/beep
     *  is suppressed regardless of uiVolumeLevel. Persisted via SoundPrefs. */
    var uiSoundEnabled by mutableStateOf(true)

    /** Master on/off for haptic feedback (PTT down, long-press, double-press
     *  home). Persisted via HapticPrefs. */
    var hapticsEnabled by mutableStateOf(true)

    // --- clock / date ---
    var clockText by mutableStateOf("00:00")
    var dateText by mutableStateOf("—")

    // --- topbar & network toggles ---
    var wifiOn by mutableStateOf(false)
    var wifiEnabled by mutableStateOf(false)
    var cellularOn by mutableStateOf(false)
    var btOn by mutableStateOf(false)
    /** Topbar update spinner: 0=hidden, 1=half (checking), 2=full + rotate (downloading/installing). */
    var updateIconState by mutableIntStateOf(0)
    var batteryPct by mutableFloatStateOf(1f)
    /** True while a charger (USB/AC/wireless) is connected. Topbar uses this to tint the battery pill green. */
    var batteryCharging by mutableStateOf(false)
    var simPresent by mutableStateOf(false)
    var simOperator by mutableStateOf("")
    var networkType by mutableStateOf("")
    /** 0..4 */
    var signalLevel by mutableIntStateOf(0)

    // --- apps list ---
    val apps = mutableStateListOf<AppEntry>()
    var appsLoaded = false

    // --- openclaw chat panel ---
    val chatMessages = mutableStateListOf<ChatMessage>()
    /** Cap message list so very long sessions don't degrade UI responsiveness. */
    val chatMessagesMax = 500
    /** Live assistant streaming preview — shown as a single bubble at the bottom
     *  while a `delta` is in flight. Replaced by the canonical bubble after the
     *  next `chat.history` refresh on terminal events. */
    var chatStreamingText by mutableStateOf("")
    /** Run IDs initiated by us; used to filter stream events from other operators. */
    val chatPendingRunIds = mutableStateListOf<String>()
    var chatStatus by mutableStateOf("connecting")
    var chatRecording by mutableStateOf(false)
    var chatBusy by mutableStateOf(false)
    var chatScrollIndex by mutableIntStateOf(0)
    /** Live mic peak 0..100, used by the talk-mode input ring. */
    var chatInputLevel by mutableIntStateOf(0)
    /** Running speech-to-text transcript while recording. Cleared on stop. */
    var chatPartialText by mutableStateOf("")
    /** Draft text in the OpenClaw chat input row. Hoisted to state (not panel-
     *  local) so committed voice transcripts can land here for the user to
     *  edit/delete before hitting send, instead of auto-sending. */
    var chatInputText by mutableStateOf("")
    /** True while OpenClaw TTS playback is in-flight (any chunk). Mirrors
     *  the private openClawSpeechPlaying flag in the activity so the voice
     *  page can drive its orb color. */
    var openClawSpeaking by mutableStateOf(false)
    /** Last QR-decode error to surface in the QR panel. Null = no error shown. */
    var qrError by mutableStateOf<String?>(null)
    /** True between "stop recording" and either transcript-back or error. */
    var chatTranscribing by mutableStateOf(false)
    /** Buffer for the Settings → Voice keyboard input field (key entry). */
    var voiceKeyInput by mutableStateOf("")
    /** Focus index for the openclaw settings menu. */
    var openClawSettingsFocus by mutableIntStateOf(0)
    /** Toggle to hide chat messages in the chat panel. */
    var openClawHideChat by mutableStateOf(false)
    /** Chat font size in sp. Adjustable from OpenClaw settings. */
    var chatFontSize by mutableIntStateOf(14)
    // Voice config now lives globally in Settings → Voice (see VoicePrefs).
    // The fields below are populated from VoicePrefs at activity start and on
    // setting changes, so the UI can read them reactively.
    var voiceEnabled by mutableStateOf(false)
    var voiceId by mutableStateOf("21m00Tcm4TlvDq8ikWAM") // Rachel
    var hasVoiceKey by mutableStateOf(false)
    var voiceKeyTail by mutableStateOf("")
    var voiceFocus by mutableIntStateOf(0)
    /** Clock-screen long-press talk target: "none" | "hermes" | "openclaw".
     *  Hydrated from VoicePrefs; mirrors the Settings → Voice row label and is
     *  read by the HOME long-press dispatch. */
    var talkShortcut by mutableStateOf(com.r1.launcher.voice.VoicePrefs.TALK_NONE)
    /** Optional user-supplied voice id (cloned, professional, shared) overriding
     *  the catalog [voiceId] for synthesis. Empty string = unset. */
    var voiceCustomId by mutableStateOf("")
    /** TTS model id (Flash / Turbo / Multilingual). Hydrated from VoicePrefs. */
    var voiceModel by mutableStateOf(com.r1.launcher.voice.VoicePrefs.DEFAULT_MODEL)
    /** voice_settings.stability — 0..1, hydrated from prefs. */
    var voiceStability by mutableStateOf(com.r1.launcher.voice.VoicePrefs.DEFAULT_STABILITY)
    var voiceSimilarity by mutableStateOf(com.r1.launcher.voice.VoicePrefs.DEFAULT_SIMILARITY)
    var voiceStyle by mutableStateOf(com.r1.launcher.voice.VoicePrefs.DEFAULT_STYLE)
    var voiceSpeed by mutableStateOf(com.r1.launcher.voice.VoicePrefs.DEFAULT_SPEED)
    var voiceSpeakerBoost by mutableStateOf(com.r1.launcher.voice.VoicePrefs.DEFAULT_SPEAKER_BOOST)
    /** Focus index for SETTINGS_VOICE_TUNING rows. */
    var voiceTuningFocus by mutableIntStateOf(0)
    /** True while a "test voice" synthesis is in flight (button stays warm). */
    var voiceTestBusy by mutableStateOf(false)
    // ElevenLabs subscription/balance — cached from /v1/user/subscription. The
    // single credit pool covers TTS chars + STT minutes (Meetings, chat STT,
    // terminal STT, claude STT, OpenClaw TTS) so this is the right home for the
    // balance display rather than putting it inside the Meetings panel.
    /** Full subscription snapshot. Null = never fetched. */
    var voiceSubData by mutableStateOf<com.r1.launcher.voice.SubscriptionData?>(null)
    /** True between fetch start and response. */
    var voiceSubLoading by mutableStateOf(false)
    /** When the cached values were fetched (epoch ms). 0 = never. */
    var voiceSubFetchedAtMs by mutableStateOf(0L)
    /** Last error message from the fetch, or null on success / never tried. */
    var voiceSubError by mutableStateOf<String?>(null)
    /** Focus index for the SETTINGS_VOICE_SUBSCRIPTION page rows. */
    var voiceSubFocus by mutableIntStateOf(0)
    // Live partial transcripts during STT recording. chatPartialText already
    // existed (line 126) and is reused for OpenClaw chat. The terminal one
    // below is for the terminal panel's ElevenLabs dictation.
    var terminalPartial by mutableStateOf("")
    /** Available threads from sessions.list. Driven by GatewaySession.onSessions. */
    val chatSessions = mutableStateListOf<SessionEntry>()
    /** Currently active thread key. Persisted across launches via OpenClawPrefs. */
    var selectedSessionKey by mutableStateOf("main")
    /** Server-snapshot main session key from connect response. */
    var mainSessionKey by mutableStateOf("main")
    /** True while a sessions.list refresh is in flight. */
    var sessionsLoading by mutableStateOf(false)
    /** Focus index for the OPENCLAW_SESSIONS panel rows. */
    var openClawSessionsFocus by mutableIntStateOf(0)
    /** Snap-and-ask camera prompt + captured JPEG payload for OpenClaw chat. */
    var openClawCameraPrompt by mutableStateOf("what do you see?")
    var openClawCameraJpegBase64 by mutableStateOf<String?>(null)
    var openClawCameraBusy by mutableStateOf(false)
    var openClawCameraError by mutableStateOf<String?>(null)
    /**
     * Current target angle for the camera stepper motor while the OpenClaw
     * camera panel is open. Range [0, 180]: 0 = FACE (lens at user), 90 = idle,
     * 180 = BACK (lens at scene). Defaults to BACK on panel entry; wheel
     * up/down nudges it in 15° steps so the user can re-aim the lens (e.g.
     * tilt down for a desk shot or up for selfie framing).
     */
    var openClawCameraMotor by mutableIntStateOf(180)

    // --- web companion panel ---
    var webServerEnabled by mutableStateOf(false)
    var webServerPort by mutableIntStateOf(8080)
    /** Best-effort local IP of the interface the panel is reachable on. */
    var webServerIp by mutableStateOf("")
    /** Per-device pre-shared token gating the WS handshake plus sensitive
     *  HTTP endpoints (api/state, api/transcriber/...). The user opens the
     *  panel at http://IP:PORT/?t=TOKEN; the SPA captures t from the URL and
     *  includes it on every subsequent request. Populated from
     *  NotifPrefs.panelToken when the server starts. Empty when off. */
    var webServerToken by mutableStateOf("")
    /** 4-digit passcode the user types into the SPA. Mirror of
     *  NotifPrefs.panelPasscode for Compose-driven UI; the SPA exchanges
     *  this via POST /api/auth for the long token above. Always 4 digits. */
    var panelPasscode by mutableStateOf("0000")
    /** Buffer used while editing the passcode in Panel.PANEL_PASSCODE.
     *  Mutated by the on-screen numeric keypad; committed to
     *  NotifPrefs.panelPasscode once exactly 4 digits long. */
    var panelPasscodeDraft by mutableStateOf("")
    /** When false, web RPC `terminal.*` methods refuse with a "disabled" error.
     *  Off by default — the launcher's root shell over LAN is a real risk and
     *  the user must explicitly opt in via Settings → Network → "remote terminal". */
    var webTerminalEnabled by mutableStateOf(false)

    // --- media capture (web companion only) ---
    /** True while a screenrecord is active. Drives the recording-state UI in
     *  the companion (pulsing dot + duration counter). */
    var mediaRecording by mutableStateOf(false)
    /** Epoch ms when the current recording started; 0 when idle. Companion ticks
     *  the elapsed counter against this. */
    var mediaRecordingStartedAt by mutableLongStateOf(0L)
    /** Mirror of [com.r1.launcher.media.MediaCapturePrefs.micEnabled]. When false,
     *  screen recordings skip the mic input. Defaults to true. */
    var captureMicEnabled by mutableStateOf(true)
    /** Mirror of [com.r1.launcher.media.MediaCapturePrefs.playbackEnabled]. When
     *  false, screen recordings skip the REMOTE_SUBMIX (system audio) input.
     *  Defaults to false — see [com.r1.launcher.media.MediaCapturePrefs] for
     *  why enabling this mutes the device speakers on this MTK build. */
    var capturePlaybackEnabled by mutableStateOf(false)

    // --- remote panel settings (Panel.REMOTE_PANEL) ---
    /** Row focus inside the remote-panel-settings page. 0=back. */
    var remotePanelFocus by mutableIntStateOf(0)

    // --- terminal panel ---
    /** Current input buffer; submitted on wheel-press, edited via RetroKeyboard. */
    var terminalInput by mutableStateOf("")
    /** Working directory tracked client-side (parsed from `cd ...`); prepended
     *  to every command since each carroot connection gets a fresh shell. */
    var terminalCwd by mutableStateOf("/sdcard")
    /** Output scrollback. Capped at 500 lines (FIFO) to bound memory.
     *  Each line carries a monotonic [TerminalLine.id] so the LazyColumn can
     *  key on stable identity — under the FIFO cap indices shift on every
     *  append, which would otherwise recompose every visible row. */
    val terminalOutput = mutableStateListOf<TerminalLine>()
    val terminalOutputMax = 500
    /** True between submit and command exit. Blocks concurrent submissions. */
    var terminalBusy by mutableStateOf(false)
    var terminalRecording by mutableStateOf(false)
    var terminalTranscribing by mutableStateOf(false)
    /** Wheel-driven scroll offset for the output area (0 = bottom/latest). */
    var terminalScrollIndex by mutableIntStateOf(0)
    /** When false, the on-screen RetroKeyboard collapses so the output area
     *  fills the screen — useful for reading long `npm install` logs. Toggled
     *  by the "kbd" header pill or the keyboard's own "hide" key. */
    var terminalKbVisible by mutableStateOf(true)

    // --- hermes agent (Panel.HERMES_CHAT / HERMES_CONFIG) ---
    /** Per-connection chat scrollback. Compose-observable; switching active
     *  connection swaps which list the chat panel renders via
     *  [hermesActiveHistory]. */
    val hermesHistories = mutableStateMapOf<String, SnapshotStateList<HermesMessage>>()
    val hermesMessagesMax = 500

    /** Observable mirror of HermesPrefs.connections. */
    val hermesConnections = mutableStateListOf<HermesConnection>()

    /** Observable mirror of HermesPrefs.activeId (null when no connections). */
    var hermesActiveId by mutableStateOf<String?>(null)

    /** Returns the message list for the currently-active connection, or null
     *  when there is none. Lazily creates an empty observable list on first
     *  access for an active connection — call from the UI thread. */
    fun hermesActiveHistory(): SnapshotStateList<HermesMessage>? {
        val id = hermesActiveId ?: return null
        return hermesHistories.getOrPut(id) { mutableStateListOf() }
    }
    /** Live assistant streaming preview — populated per SSE delta. Cleared and
     *  replaced by a committed [HermesMessage] when the stream ends. */
    var hermesStreamingText by mutableStateOf("")
    /** Live STT transcript while recording. Cleared on commit. */
    var hermesPartialText by mutableStateOf("")
    /** Draft text in the Hermes chat input row. Hoisted so committed voice
     *  transcripts land here for the user to edit before sending. */
    var hermesInputText by mutableStateOf("")
    /** "idle" | "live" | "streaming" | "error: <msg>". Drives header status dot. */
    var hermesStatus by mutableStateOf("idle")
    var hermesRecording by mutableStateOf(false)
    var hermesBusy by mutableStateOf(false)
    var hermesTranscribing by mutableStateOf(false)
    /** Mirrors TTS playback (one-shot + streaming chunk pipeline). Drives the
     *  speaker icon in the chat panel header. */
    var hermesSpeaking by mutableStateOf(false)
    /** Streaming reasoning buffer for the in-flight turn. Cleared on each
     *  new send / completion. Snapshotted onto the assistant message at
     *  onDone so it persists per-message. */
    var hermesReasoningText by mutableStateOf("")
    /** Tool-progress events for the in-flight turn — one entry per tool call,
     *  upserted by toolCallId so the running→completed transition mutates in
     *  place rather than appending. Cleared on completion. */
    val hermesToolEvents = mutableStateListOf<HermesToolEvent>()
    var hermesScrollIndex by mutableIntStateOf(0)
    var hermesInputLevel by mutableIntStateOf(0)
    /** HERMES_CONFIG row focus: 0=back, 1=url, 2=key, 3=scan QR, 4=speak, 5=hide input, 6=test. */
    var hermesConfigFocus by mutableIntStateOf(0)
    /** Buffer for the URL row's RetroKeyboard. */
    var hermesServerUrlInput by mutableStateOf("")
    /** Buffer for the API-key row's RetroKeyboard. */
    var hermesApiKeyInput by mutableStateOf("")
    /** Display-mirrors of HermesPrefs so config rows can show current values
     *  reactively. Activity hydrates these on start + after each save. */
    var hermesServerUrl by mutableStateOf("")
    var hermesApiKeyTail by mutableStateOf("")
    var hermesModel by mutableStateOf("hermes-agent")
    var hermesFontSize by mutableIntStateOf(14)
    /** When true, the chat panel hides its text input + send pill — voice-only mode. */
    var hermesHideChat by mutableStateOf(false)
    /** Error string shown above the Hermes QR scanner viewport when a scanned
     *  payload didn't decode cleanly. Null while the scanner is healthy. */
    var hermesQrError by mutableStateOf<String?>(null)
    /** True when entering HERMES_CONFIG from HERMES_CHAT; controls back routing. */
    var hermesConfigCameFromChat by mutableStateOf(false)

    /** Connection-edit sub-panel state: id being edited (null = new-mode). */
    var hermesConnectionEditId by mutableStateOf<String?>(null)
    var hermesConnectionEditFocus by mutableIntStateOf(0)
    /** Buffer for the edit panel's URL row. */
    var hermesConnectionEditUrlInput by mutableStateOf("")
    /** Buffer for the edit panel's API key row. */
    var hermesConnectionEditKeyInput by mutableStateOf("")
    /** Timestamp (SystemClock.uptimeMillis) when the "delete connection" row
     *  was first armed. Second activate within 3000 ms confirms. */
    var hermesConnectionEditDeleteArmedAt by mutableStateOf(0L)

    // --- translator (Panel.TRANSLATOR / TRANSLATOR_SETTINGS) ---
    /** Conversation log. Each entry is one source→target pair. Persisted to
     *  filesDir/translator-history.json (capped at 100 in TranslationHistoryStore)
     *  so it survives launcher restarts; useful for re-showing earlier
     *  translations to the same person. */
    val translatorMessages = mutableStateListOf<TranslationMessage>()
    /** Active source language (ISO 639-1). Mirror of TranslatorPrefs.sourceLang. */
    var translatorSource by mutableStateOf(com.r1.launcher.translator.TranslatorPrefs.DEFAULT_SOURCE)
    /** Active target language (ISO 639-1). Mirror of TranslatorPrefs.targetLang. */
    var translatorTarget by mutableStateOf(com.r1.launcher.translator.TranslatorPrefs.DEFAULT_TARGET)
    /** Active LLM provider. Mirror of TranslatorPrefs.provider. */
    var translatorProvider by mutableStateOf(ProviderId.GEMINI)
    /** Toggle mirrors for the settings page. */
    var translatorAutoDetect by mutableStateOf(true)
    var translatorAutoSpeak by mutableStateOf(true)
    /** When true, the chat-style text input row is hidden (voice-first mode) —
     *  input via the side-button PTT + a compact hold-to-talk pill. */
    var translatorHideInput by mutableStateOf(false)
    /** Per-provider "has key" + masked-tail mirrors. Hydrated in
     *  hydrateTranslatorStateFromPrefs(). */
    var translatorGeminiHasKey by mutableStateOf(false)
    var translatorGeminiKeyTail by mutableStateOf("")
    var translatorOpenAIHasKey by mutableStateOf(false)
    var translatorOpenAIKeyTail by mutableStateOf("")
    var translatorClaudeHasKey by mutableStateOf(false)
    var translatorClaudeKeyTail by mutableStateOf("")
    /** Live STT partial for the source bubble while the mic is open. */
    var translatorPartialText by mutableStateOf("")
    /** Draft text in the input row. Hoisted so committed voice transcripts
     *  can land here when auto-send is off. */
    var translatorInputText by mutableStateOf("")
    /** "ready" | "busy" | "error: …" — drives the status dot on the header. */
    var translatorStatus by mutableStateOf("ready")
    var translatorRecording by mutableStateOf(false)
    /** True between submit and translation reply (or error). */
    var translatorBusy by mutableStateOf(false)
    /** True while a translator TTS playback is in flight. */
    var translatorSpeaking by mutableStateOf(false)
    /** Wheel-driven scroll tick — same protocol as hermesScrollIndex. */
    var translatorScrollIndex by mutableIntStateOf(0)
    /** Settings panel row focus (see TranslatorSettingsPanel row layout). */
    var translatorSettingsFocus by mutableIntStateOf(0)
    /** When non-empty, the shared keyboard overlay on TRANSLATOR_SETTINGS is
     *  open. Values: "gemini" | "openai" | "claude". */
    var translatorEditField by mutableStateOf("")
    var translatorEditInput by mutableStateOf("")
    /** First-run wizard step: 0 = source ("i speak"), 1 = target
     *  ("translate to"), 2 = key ("add a key"). */
    var translatorOnboardingStep by mutableIntStateOf(0)
    /** Wheel focus within the current onboarding step's option list. */
    var translatorOnboardingFocus by mutableIntStateOf(0)
    /** True while the key step is showing the phone-handoff card (panel URL +
     *  passcode, polling for a key to land). */
    var translatorOnboardingWaitingForKey by mutableStateOf(false)

    // --- meetings (transcriber) ---
    /** Index of saved meetings, newest first. Hydrated from MeetingStore on
     *  panel entry; updated in-place after start/stop/transcribe/delete. */
    val meetings = mutableStateListOf<MeetingIndexEntry>()
    /** Focus on TRANSCRIBER_LIST: 0=back pill, 1=settings gear (top-right),
     *  2="+ new recording", 3..N+2=meetings. */
    var transcriberListFocus by mutableIntStateOf(0)
    /** UUID of the currently-selected meeting in detail panel. */
    var currentMeetingUuid by mutableStateOf<String?>(null)
    /** Detail-panel focus when the ⋮ overlay is closed: 0=back pill,
     *  1=⋮ menu icon. While the overlay is open, [transcriberDetailMenuFocus]
     *  drives the wheel instead. */
    var transcriberDetailFocus by mutableIntStateOf(0)
    /** Settings panel focus: 0=back, 1=smtp host, 2=smtp port, 3=smtp user,
     *  4=smtp password, 5=default recipient, 6=clear smtp. */
    var transcriberSettingsFocus by mutableIntStateOf(0)
    /** Live recording state mirrored from the FGS binder. */
    var recordingActive by mutableStateOf(false)
    var recordingElapsedMs by mutableStateOf(0L)
    var recordingPeak by mutableIntStateOf(0)
    /** True between record-stop and Scribe response (or failure). */
    var transcribeBusy by mutableStateOf(false)
    /** Buffer for the recipient typed in the detail panel email keyboard. */
    var transcriberRecipientInput by mutableStateOf("")
    /** Which transcriber-settings field the keyboard is editing.
     *  Empty string = keyboard not visible. */
    var transcriberSettingsEditField by mutableStateOf("")
    var transcriberSettingsEditInput by mutableStateOf("")
    /** Has SMTP creds (cached from prefs to avoid repeated reads on the UI thread). */
    var hasSmtp by mutableStateOf(false)
    var smtpHostDisplay by mutableStateOf("")
    var smtpPortDisplay by mutableIntStateOf(0)
    var smtpUserDisplay by mutableStateOf("")
    var defaultRecipientDisplay by mutableStateOf("")
    /** True while audio is playing back from the detail panel. */
    var detailPlaying by mutableStateOf(false)
    /** When non-empty, shown on the detail panel (e.g. "sent to alice@…", "send failed: …"). */
    var detailStatus by mutableStateOf("")
    /** True while the ⋮ action overlay is open on the detail panel. While open,
     *  the wheel + activate routes through [transcriberDetailMenuFocus] and
     *  back/menu-press first closes the overlay before unwinding the panel. */
    var transcriberDetailMenuOpen by mutableStateOf(false)
    /** Focus index inside the action menu — 0..(N-1) over the dynamic action
     *  set computed from the meeting's status (play/email/retry/delete). */
    var transcriberDetailMenuFocus by mutableIntStateOf(0)
    /** The action set currently shown in the ⋮ menu. Host populates it on
     *  open based on the meeting's status; nav reads it for wheel max + dispatch. */
    val transcriberDetailMenuActions = mutableStateListOf<TranscriberDetailAction>()

    // --- toast overlay ---
    /** Currently visible toast, or null when hidden. Set via [showToast];
     *  cleared either by the overlay's auto-dismiss timer or by [showToast]
     *  replacing it with a newer entry. */
    var toast by mutableStateOf<ToastEntry?>(null)
    private var toastSeq = 0L

    /**
     * Push a toast onto the overlay. New calls preempt any in-flight toast
     * (we never queue — the latest message wins, matching how stock Android
     * Toast behaves with `LENGTH_SHORT`).
     */
    fun showToast(
        text: String,
        kind: ToastKind = ToastKind.INFO,
        durationMs: Long = 3000L,
    ) {
        toastSeq++
        toast = ToastEntry(
            id = toastSeq,
            text = text,
            kind = kind,
            expiresAtMs = System.currentTimeMillis() + durationMs,
        )
    }

    // --- messages (SMS) ---
    val smsConversations = mutableStateListOf<SmsConversation>()
    /** True while loadConversations() is running on a background thread. */
    var smsLoading by mutableStateOf(false)
    /** Set when READ_SMS is denied or content provider returned no rows. */
    var smsError by mutableStateOf<String?>(null)
    var messagesFocus by mutableIntStateOf(0)
    /** Open thread address; drives MESSAGES_THREAD title + body list. */
    var smsThreadAddress by mutableStateOf("")
    var smsThreadName by mutableStateOf("")
    val smsThreadMessages = mutableStateListOf<SmsItem>()
    var smsThreadLoading by mutableStateOf(false)
    var smsThreadFocus by mutableIntStateOf(0)

    // --- credentials panel (global API keys) ---
    /** Focus index in SETTINGS_CREDENTIALS. Row layout matches the panel:
     *  0=back, 1=elevenlabs, 2=hermes, 3=ntfy topic, 4=webhook token
     *  (view+regenerate), 5=control secret (view+regenerate). Single keyboard
     *  overlay opened per-row via [credentialsEditField] / [credentialsEditInput]. */
    var credentialsFocus by mutableIntStateOf(0)
    /** When non-empty, the credentials keyboard overlay is open for this
     *  field. Values: "anthropic" | "elevenlabs" | "hermes" | "ntfy_topic". */
    var credentialsEditField by mutableStateOf("")
    var credentialsEditInput by mutableStateOf("")
    /** Display mirrors for the credentials panel. Hydrated in onCreate +
     *  refreshed on every successful save. Keys themselves stay in their
     *  per-app *Prefs objects — these are read-only snapshots for UI. */
    var hasHermesKey by mutableStateOf(false)
    var hasOpenAiKey by mutableStateOf(false)
    var openAiKeyTail by mutableStateOf("")
    var hermesKeyTail by mutableStateOf("")
    /** Webhook bearer token — read-only on this surface; regenerate button
     *  triggers [LauncherHost.regenerateWebhookToken]. */
    var webhookTokenDisplay by mutableStateOf("")
    /** Per-device control secret required on the SET_ELEVENLABS_KEY /
     *  SET_HERMES_CONFIG / TOGGLE_WEB_SERVER adb broadcasts. Shown in full
     *  (it's short) so the user can copy it into their adb snippets; tap the
     *  row to regenerate. */
    var controlSecretDisplay by mutableStateOf("")

    // --- ntfy.sh subscriber ---
    /** Focus index in NTFY_CONFIG. Row layout:
     *  0=back, 1=enable toggle, 2=topic, 3=status (info row). */
    var ntfyConfigFocus by mutableIntStateOf(0)
    /** Buffer for the NTFY_CONFIG topic keyboard. Empty when keyboard closed. */
    var ntfyTopicInput by mutableStateOf("")
    /** Display mirror of NtfyPrefs.topic. */
    var ntfyTopic by mutableStateOf("")
    /** Display mirror of NtfyPrefs.enabled — also drives the Network panel
     *  row toggle. */
    var ntfySubscriberEnabled by mutableStateOf(false)
    /** Live subscriber status. Values: "disabled" | "connecting" |
     *  "live" | "retry…" | "error". Drives the Network row pill + the
     *  config page status display. */
    var ntfyStatus by mutableStateOf("disabled")

    // --- notifications ---
    /** Newest-first list of notifications. Hydrated from NotificationStore on
     *  activity start; mutated by NotificationCenter.add/markRead/dismiss/clear
     *  in lockstep with the on-disk JSON. */
    val notifications = mutableStateListOf<Notification>()
    /** Cached unread count — kept in sync by NotificationCenter so the HOME
     *  badge doesn't have to recompute every recomposition. */
    var notificationsUnread by mutableIntStateOf(0)
    /** Wheel focus inside the NOTIFICATIONS panel. 0 = back, 1 = header clear
     *  (only present when [notifications] isn't empty), 2..N+1 = items in
     *  reverse-chronological order. */
    var notificationsFocus by mutableIntStateOf(0)
    /** Transient banner shown on HOME for ~4s when a notification arrives
     *  while the clock screen is visible. Null = nothing being shown. */
    var notificationBanner by mutableStateOf<Notification?>(null)
    /** Master gate for the chime that fires when a notification lands. Mirrors
     *  NotifPrefs.soundEnabled; surfaced as the "notifications" row in
     *  Settings → Sound. When false the badge/panel still update — only the
     *  audio cue is suppressed. */
    var notificationSoundEnabled by mutableStateOf(true)

    fun openNotifications() {
        notificationsFocus = 0
        panel = Panel.NOTIFICATIONS
    }

    fun openSettingsCredentials() {
        credentialsFocus = 0
        credentialsEditField = ""
        credentialsEditInput = ""
        panel = Panel.SETTINGS_CREDENTIALS
    }

    fun openNtfyConfig() {
        ntfyConfigFocus = 0
        ntfyTopicInput = ntfyTopic
        panel = Panel.NTFY_CONFIG
    }

    // --- state transitions ---

    fun openApps() {
        appsFocus = 0
        panel = Panel.APPS
    }

    fun openOnboarding() {
        onboardingStep = 0
        onboardingFocus = 0
        isOnboarding = true
        panel = Panel.ONBOARDING
    }

    fun advanceOnboarding() {
        onboardingStep++
        onboardingFocus = 0
        panel = Panel.ONBOARDING
    }

    fun openSettings() {
        settingsFocus = 0
        panel = Panel.SETTINGS
    }

    fun openSettingsDisplay() {
        settingsDisplayFocus = 0
        panel = Panel.SETTINGS_DISPLAY
    }

    fun openSettingsSound() {
        settingsSoundFocus = 0
        panel = Panel.SETTINGS_SOUND
    }

    fun openSettingsDevice() {
        settingsDeviceFocus = 0
        panel = Panel.SETTINGS_DEVICE
    }

    fun openSettingsAbout() {
        settingsAboutFocus = 0
        panel = Panel.SETTINGS_ABOUT
    }

    fun openNetwork() {
        networkFocus = 0
        panel = Panel.NETWORK
    }

    fun openWifiScan() {
        wifiScanFocus = 0
        panel = Panel.WIFI_SCAN
    }

    fun openBtScan() {
        btScanFocus = 0
        panel = Panel.BT_SCAN
    }

    fun openWifiPassword(ssid: String) {
        wifiSelectedSsid = ssid
        wifiPasswordInput = ""
        panel = Panel.WIFI_PASSWORD
    }

    fun openWifiShare() {
        wifiShareFocus = 0
        wifiShareClientsExpanded = false
        panel = Panel.WIFI_SHARE
    }

    fun openWifiShareEdit(target: WifiShareEditTarget) {
        wifiShareEditTarget = target
        wifiShareEditInput = when (target) {
            WifiShareEditTarget.SSID -> wifiShareSsid
            WifiShareEditTarget.PASSWORD -> wifiSharePassword
        }
        panel = Panel.WIFI_SHARE_EDIT
    }

    fun openPanelPasscodeEditor() {
        // Seed the draft with the current passcode so the user sees what
        // they already have. Clearing it forces a from-scratch retype.
        panelPasscodeDraft = panelPasscode
        panel = Panel.PANEL_PASSCODE
    }

    fun openRemotePanel() {
        remotePanelFocus = 0
        panel = Panel.REMOTE_PANEL
    }

    fun openBrightness() {
        panel = Panel.BRIGHTNESS
    }

    fun openVolume() {
        panel = Panel.VOLUME
    }

    fun openUiVolume() {
        panel = Panel.UI_VOLUME
    }

    fun openFactoryConfirm() {
        factoryConfirmFocus = 0
        panel = Panel.FACTORY_CONFIRM
    }

    fun openOpenClawQr() {
        // Don't clear qrError here — auto-recovery from a failed handshake
        // sets the error message and then opens this panel; clearing would
        // erase it before the user sees it. User-initiated entry from the
        // apps grid resets qrError explicitly.
        panel = Panel.OPENCLAW_QR
    }

    fun openOpenClawChat() {
        chatScrollIndex = 0
        chatStatus = "connecting"
        panel = Panel.OPENCLAW_CHAT
    }

    fun openSettingsVoice() {
        voiceFocus = 0
        panel = Panel.SETTINGS_VOICE
    }

    fun openSettingsVoiceTuning() {
        voiceTuningFocus = 0
        panel = Panel.SETTINGS_VOICE_TUNING
    }

    fun openSettingsVoiceSubscription() {
        voiceSubFocus = 0
        panel = Panel.SETTINGS_VOICE_SUBSCRIPTION
    }

    fun openSettingsLanguage() {
        settingsLanguageFocus = 0
        panel = Panel.SETTINGS_LANGUAGE
    }

    fun openOpenClawCamera() {
        openClawCameraPrompt = "what do you see?"
        openClawCameraJpegBase64 = null
        openClawCameraBusy = false
        openClawCameraError = null
        openClawCameraMotor = 180
        panel = Panel.OPENCLAW_CAMERA
    }

    fun openOpenClawSessions() {
        openClawSessionsFocus = 0
        panel = Panel.OPENCLAW_SESSIONS
    }

    fun openMessages() {
        messagesFocus = 0
        panel = Panel.MESSAGES
    }

    fun openTerminal() {
        // Preserve scrollback and cwd so reopening feels session-like.
        terminalInput = ""
        terminalScrollIndex = 0
        terminalRecording = false
        terminalTranscribing = false
        panel = Panel.TERMINAL
    }

    fun openMessagesThread(address: String, displayName: String) {
        smsThreadAddress = address
        smsThreadName = displayName
        smsThreadFocus = 0
        smsThreadMessages.clear()
        smsThreadLoading = true
        panel = Panel.MESSAGES_THREAD
    }

    fun openOpenClawSettings() {
        openClawSettingsFocus = 0
        panel = Panel.OPENCLAW_SETTINGS
    }

    fun openHermesChat() {
        hermesScrollIndex = 0
        panel = Panel.HERMES_CHAT
    }

    fun openHermesConfig(fromChat: Boolean = false) {
        hermesConfigFocus = 0
        hermesConfigCameFromChat = fromChat
        panel = Panel.HERMES_CONFIG
    }

    fun openHermesQr() {
        hermesQrError = null
        panel = Panel.HERMES_QR
    }

    // --- camera app ---
    /** Motor angle the lens is held at while the camera panel is open.
     *  MOTOR_FACE (0) = pointing at the user, MOTOR_BACK (180) = at the scene.
     *  There is one physical sensor; "flipping" rotates it. */
    var cameraFacing by mutableIntStateOf(180)
    val cameraFacingIsFront: Boolean get() = cameraFacing <= 90
    /** True from shutter press until the JPEG lands — freezes the shutter. */
    var cameraCapturing by mutableStateOf(false)
    /** False until the preview's first repeating request is accepted. */
    var cameraReady by mutableStateOf(false)
    var cameraError by mutableStateOf<String?>(null)
    /** Bumped by the host to ask the live preview for a frame. The panel owns
     *  the R1CameraView instance, so this counter is the only way in. */
    var cameraShutterRequest by mutableIntStateOf(0)
    /** Flashes white over the preview for one frame after a capture. */
    var cameraShutterFlash by mutableIntStateOf(0)

    /** Gallery contents, newest first. Rebuilt from disk by the host. */
    val photos = mutableStateListOf<com.r1.launcher.camera.PhotoStore.Photo>()
    /** Focused tile in Panel.GALLERY (0 = back row, 1+ = photos). */
    var galleryFocus by mutableIntStateOf(0)
    /** Index into [photos] shown in Panel.GALLERY_VIEW. */
    var galleryIndex by mutableIntStateOf(0)
    val galleryCurrent: com.r1.launcher.camera.PhotoStore.Photo?
        get() = photos.getOrNull(galleryIndex)

    // --- AI image edit job ---
    var aiStage by mutableStateOf(AiStage.IDLE)
    /** One-line human-readable status shown under the stage label. */
    var aiMessage by mutableStateOf("")
    /** Live partial/final transcript of what the user said. */
    var aiPrompt by mutableStateOf("")
    /** Mic level 0-100 while RECORDING, for the level meter. */
    var aiLevel by mutableIntStateOf(0)
    /** Wall-clock ms when GENERATING started — drives the elapsed counter. */
    var aiStartedAtMs by mutableStateOf(0L)
    /** Path of the photo the in-flight job was started from, so a job that
     *  finishes after the user swipes away still reports against the right one. */
    var aiSourcePath by mutableStateOf("")
    val aiBusy: Boolean
        get() = aiStage == AiStage.RECORDING || aiStage == AiStage.TRANSCRIBING || aiStage == AiStage.GENERATING

    fun openCamera() {
        cameraError = null
        cameraReady = false
        panel = Panel.CAMERA
    }

    fun openGallery() {
        galleryFocus = if (photos.isEmpty()) 0 else 1
        panel = Panel.GALLERY
    }

    fun openGalleryView(index: Int) {
        if (photos.isEmpty()) return
        galleryIndex = index.coerceIn(0, photos.lastIndex)
        panel = Panel.GALLERY_VIEW
    }

    /** Clear a finished job so the status strip collapses. */
    fun aiReset() {
        aiStage = AiStage.IDLE
        aiMessage = ""
        aiPrompt = ""
        aiLevel = 0
        aiStartedAtMs = 0L
        aiSourcePath = ""
    }

    // --- testing scratch panel ---
    /** 0 = back row, 1 = the button. */
    var testingFocus by mutableIntStateOf(0)
    var testingCount by mutableIntStateOf(0)

    fun openTesting() {
        testingFocus = 0
        panel = Panel.TESTING
    }

    fun openTranslator() {
        translatorScrollIndex = 0
        translatorPartialText = ""
        if (translatorStatus.startsWith("error")) translatorStatus = "ready"
        panel = Panel.TRANSLATOR
    }

    fun openTranslatorOnboarding() {
        translatorOnboardingStep = 0
        translatorOnboardingFocus = 0
        translatorOnboardingWaitingForKey = false
        panel = Panel.TRANSLATOR_ONBOARDING
    }

    fun openTranslatorSettings() {
        translatorSettingsFocus = 0
        translatorEditField = ""
        translatorEditInput = ""
        panel = Panel.TRANSLATOR_SETTINGS
    }

    fun openHermesConnectionEdit(id: String?) {
        hermesConnectionEditId = id
        hermesConnectionEditFocus = 0
        hermesConnectionEditDeleteArmedAt = 0L
        val existing = id?.let { editId -> hermesConnections.firstOrNull { it.id == editId } }
        hermesConnectionEditUrlInput = existing?.url.orEmpty()
        hermesConnectionEditKeyInput = ""
        panel = Panel.HERMES_CONNECTION_EDIT
    }

    fun openTranscriberList() {
        transcriberListFocus = 0
        detailStatus = ""
        panel = Panel.TRANSCRIBER_LIST
    }

    fun openTranscriberRecording() {
        // No focus index — recording panel has a single stop pill driven by
        // the side-button toggle, plus a back row that wheel-up reaches.
        panel = Panel.TRANSCRIBER_RECORDING
    }

    fun openTranscriberDetail(uuid: String) {
        currentMeetingUuid = uuid
        transcriberDetailFocus = 0
        transcriberDetailMenuOpen = false
        transcriberDetailMenuFocus = 0
        transcriberRecipientInput = defaultRecipientDisplay
        detailStatus = ""
        panel = Panel.TRANSCRIBER_DETAIL
    }

    fun openTranscriberSettings() {
        transcriberSettingsFocus = 0
        transcriberSettingsEditField = ""
        transcriberSettingsEditInput = ""
        panel = Panel.TRANSCRIBER_SETTINGS
    }

    fun goHome() {
        panel = Panel.HOME
    }

    fun back() {
        panel = when (panel) {
            Panel.BRIGHTNESS -> Panel.SETTINGS_DISPLAY
            Panel.VOLUME -> Panel.SETTINGS_SOUND
            Panel.UI_VOLUME -> Panel.SETTINGS_SOUND
            Panel.NETWORK -> if (isOnboarding) Panel.ONBOARDING else Panel.SETTINGS
            Panel.FACTORY_CONFIRM -> Panel.SETTINGS_DEVICE
            // Language was promoted out of the root in v3.32 — it now lives
            // under Device alongside reboot/power off/factory reset, so the
            // back arrow needs to drop back into SETTINGS_DEVICE.
            Panel.SETTINGS_LANGUAGE -> Panel.SETTINGS_DEVICE
            Panel.SETTINGS_DISPLAY, Panel.SETTINGS_SOUND, Panel.SETTINGS_DEVICE, Panel.SETTINGS_ABOUT, Panel.SETTINGS_VOICE, Panel.SETTINGS_CREDENTIALS -> Panel.SETTINGS
            Panel.SETTINGS_VOICE_TUNING -> Panel.SETTINGS_VOICE
            Panel.SETTINGS_VOICE_SUBSCRIPTION -> Panel.SETTINGS_VOICE
            Panel.WIFI_SCAN -> if (isOnboarding) Panel.ONBOARDING else Panel.NETWORK
            Panel.WIFI_PASSWORD -> Panel.WIFI_SCAN
            Panel.WIFI_SHARE -> Panel.NETWORK
            Panel.WIFI_SHARE_EDIT -> Panel.WIFI_SHARE
            // Passcode editor is only reachable from the new REMOTE_PANEL
            // settings page; back unwinds to it. Older code used to enter the
            // editor from Settings → Network — that row has since been moved
            // into REMOTE_PANEL so this back path covers every entry point.
            Panel.PANEL_PASSCODE -> Panel.REMOTE_PANEL
            Panel.REMOTE_PANEL -> Panel.NETWORK
            Panel.NTFY_CONFIG -> Panel.NETWORK
            Panel.BT_SCAN -> Panel.NETWORK
            Panel.ONBOARDING -> Panel.ONBOARDING
            Panel.SETTINGS -> Panel.APPS
            Panel.APPS -> Panel.HOME
            Panel.OPENCLAW_QR -> Panel.APPS
            Panel.OPENCLAW_CHAT -> Panel.APPS
            Panel.OPENCLAW_CAMERA -> Panel.OPENCLAW_CHAT
            Panel.OPENCLAW_SETTINGS, Panel.OPENCLAW_SESSIONS -> Panel.OPENCLAW_CHAT
            Panel.MESSAGES -> Panel.APPS
            Panel.MESSAGES_THREAD -> Panel.MESSAGES
            Panel.TERMINAL -> Panel.APPS
            Panel.HERMES_CHAT -> Panel.APPS
            Panel.HERMES_CONFIG -> if (hermesConfigCameFromChat) Panel.HERMES_CHAT else Panel.APPS
            Panel.HERMES_QR -> Panel.HERMES_CONFIG
            Panel.HERMES_CONNECTION_EDIT -> Panel.HERMES_CONFIG
            Panel.TRANSLATOR_ONBOARDING -> Panel.APPS
            Panel.TRANSLATOR -> Panel.APPS
            Panel.TRANSLATOR_SETTINGS -> Panel.TRANSLATOR
            Panel.TRANSCRIBER_LIST -> Panel.APPS
            // Recording-stop side-effect is fired by LauncherActivity's
            // backPressed handling; here we just unwind the panel.
            Panel.TRANSCRIBER_RECORDING -> Panel.TRANSCRIBER_LIST
            Panel.TRANSCRIBER_DETAIL -> Panel.TRANSCRIBER_LIST
            Panel.TRANSCRIBER_SETTINGS -> Panel.TRANSCRIBER_LIST
            // Notifications open from the HOME badge — back returns there.
            Panel.NOTIFICATIONS -> Panel.HOME
            Panel.TESTING -> Panel.APPS
            Panel.CAMERA -> Panel.APPS
            Panel.GALLERY -> Panel.CAMERA
            Panel.GALLERY_VIEW -> Panel.GALLERY
            Panel.HOME -> Panel.HOME
        }
    }
}
