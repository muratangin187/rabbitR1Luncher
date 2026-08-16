package com.r1.launcher

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings

import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.view.KeyEvent
import android.view.WindowManager
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.r1.launcher.openclaw.GatewaySession
import com.r1.launcher.openclaw.OpenClawPrefs
import com.r1.launcher.openclaw.decodeGatewaySetupCode
import com.r1.launcher.transcriber.Meeting
import com.r1.launcher.transcriber.MeetingStatus
import com.r1.launcher.transcriber.MeetingStore
import com.r1.launcher.transcriber.ScribeClient
import com.r1.launcher.transcriber.SmtpSender
import com.r1.launcher.transcriber.TranscriberPrefs
import com.r1.launcher.transcriber.TranscriberRecordingService
import com.r1.launcher.transcriber.TranscriptFormatter
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.serialization.json.put
import com.r1.launcher.ui.LauncherRoot
import com.r1.launcher.ui.MOTOR_BACK
import com.r1.launcher.ui.R1Theme
import com.r1.launcher.ui.setMotorOrientation
import com.r1.launcher.updater.OTAUpdater
import java.io.OutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity shell:
 *   - setContent { R1Theme { LauncherRoot(state, host) } }
 *   - dispatchKeyEvent routes wheel/PTT candidates into state/host
 *   - onResume/onPause register BroadcastReceivers (net, battery, package) and
 *     the telephony signal listener, just like the old Java launcher
 *   - clock tick re-posts aligned to the next second boundary
 *
 * LauncherState is plain Kotlin (not a ViewModel) — the activity is singleTask
 * with configChanges flags, so it never recreates; no survival needed.
 */
class LauncherActivity : ComponentActivity(), LauncherHost {

    companion object {
        // Ignore a second press arriving within this window of an earlier one.
        // Catches accidental double-taps and prevents a "go home + lock" combo
        // when the user double-taps the side button while inside another app.
        private const val SIDE_PRESS_DEBOUNCE_MS = 250L
        private const val REQ_CAMERA_PERM = 4801
        private const val REQ_AUDIO_PERM = 4802
        private const val REQ_PHONE_PERM = 4803
        private const val REQ_SMS_PERM = 4804
        private const val REQ_NOTIF_PERM = 4805
        private const val REQ_AUDIO_PERM_TRANSCRIBER = 4806
        private const val REQ_BT_SCAN_PERM = 4807
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(
            com.r1.launcher.locale.applyLocale(
                newBase,
                com.r1.launcher.locale.LocalePrefs.get(newBase).language,
            )
        )
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        // We intercept locale|layoutDirection in configChanges so the system
        // doesn't auto-recreate when only the system locale changes (we honour
        // the user's per-app pick). User-initiated locale changes call recreate()
        // explicitly via setLanguage().
        super.onConfigurationChanged(newConfig)
    }

    private val state = LauncherState()
    private var tone: ToneGenerator? = null
    private var soundPool: SoundPool? = null
    private var movingSoundId: Int = 0
    private var uiClickSoundId: Int = 0
    private var selectSoundId: Int = 0
    private var recordStartSoundId: Int = 0
    private var recordStopSoundId: Int = 0
    private var audioManager: AudioManager? = null


    private val openClawPrefs by lazy { OpenClawPrefs.get(this) }
    private val hermesPrefs by lazy { com.r1.launcher.hermes.HermesPrefs.get(this) }
    private val hermesClient by lazy { com.r1.launcher.hermes.HermesClient() }
    private val translatorPrefs by lazy { com.r1.launcher.translator.TranslatorPrefs.get(this) }
    private val cameraPrefs by lazy { com.r1.launcher.camera.CameraPrefs(this) }
    private val chatPrefs by lazy { com.r1.launcher.chat.ChatPrefs(this) }
    private val translatorClient by lazy { com.r1.launcher.translator.TranslatorClient() }
    /** In-flight Translator TTS HTTP call — cancellable when a new translation
     *  arrives or the user starts a new mic capture. */
    private var translatorTtsCall: okhttp3.Call? = null
    private var translatorSpeechPlayer: MediaPlayer? = null
    private var translatorSpeechFile: File? = null
    /** Last-played key (messageId) — guards against double-speak when both the
     *  auto-speak path and a tap-to-replay race the same MP3. */
    private var translatorLastSpokenKey = ""
    /** In-flight Hermes TTS download/playback — separate slot from the OpenClaw
     *  TTS pipeline so the two apps don't fight over playback state. */
    private var hermesTtsCall: okhttp3.Call? = null
    private var hermesSpeechPlayer: MediaPlayer? = null
    private var hermesSpeechCurrentFile: File? = null
    private var hermesSpeakNextAssistant = false
    private var hermesLastSpokenKey = ""
    // Hermes streaming-TTS pipeline — mirrors the OpenClaw block above.
    // Cuts first-audio latency to "first sentence boundary in SSE deltas"
    // instead of waiting for the full reply.
    private var hermesStreamingSpokenOffset: Int = 0
    private var hermesStreamingTtsActive: Boolean = false
    private var hermesStreamingTtsTurnId: Long = 0L
    private var hermesSpeechIssuedSeq: Int = 0
    private var hermesSpeechNextToPlay: Int = 1
    private var hermesSpeechPlaying: Boolean = false
    private val hermesSpeechSlots: java.util.TreeMap<Int, File?> = java.util.TreeMap()
    private val hermesTtsChunkCalls: MutableList<okhttp3.Call> = mutableListOf()
    private val soundPrefs by lazy { com.r1.launcher.sound.SoundPrefs.get(this) }
    private val notifPrefs by lazy { com.r1.launcher.notifications.NotifPrefs.get(this) }
    private val hapticPrefs by lazy { com.r1.launcher.haptic.HapticPrefs.get(this) }
    private val haptics by lazy { com.r1.launcher.haptic.Haptics.get(this) }
    private val ntfyPrefs by lazy { com.r1.launcher.notifications.NtfyPrefs.get(this) }
    /** Single ntfy.sh subscriber instance — null when stopped. Created lazily
     *  on first enable to avoid holding a Wi-Fi lock when the feature is
     *  unused. */
    private var ntfySubscriber: com.r1.launcher.notifications.NtfySubscriber? = null
    /** Wall-clock of the last notification chime — rate-limit to one beep per
     *  [NOTIF_SOUND_MIN_GAP_MS] so a burst of webhooks doesn't machine-gun. */
    private var lastNotifSoundAtMs: Long = 0L
    private val NOTIF_SOUND_MIN_GAP_MS = 3000L
    private val wifiSharePrefs by lazy { com.r1.launcher.wifishare.WifiSharePrefs.get(this) }
    private var wifiShareTimerEndMs: Long = 0L
    // True while a user-initiated toggle is mid-verification — suppresses the
    // refreshNetwork() reconcile so a transient ap0 reading can't fight it.
    @Volatile private var wifiShareToggleInFlight = false
    // True while a reconcile check is already running — coalesces the burst of
    // refreshNetwork() calls during softap up/down into one ap0 probe.
    @Volatile private var wifiShareSyncInFlight = false
    private val wifiShareTimerRunnable = Runnable { toggleWifiShare(false) }
    private val wifiShareCountdownRunnable = object : Runnable {
        override fun run() {
            val remaining = ((wifiShareTimerEndMs - System.currentTimeMillis()) / 1000L)
                .toInt().coerceAtLeast(0)
            state.wifiShareTimerRemainingSec = remaining
            if (remaining > 0 && state.wifiShareEnabled) ui.postDelayed(this, 1000L)
        }
    }
    private val wifiShareClientPollRunnable = object : Runnable {
        override fun run() {
            if (!state.wifiShareEnabled) return
            pollWifiShareClients()
            ui.postDelayed(this, 3000L)
        }
    }
    private var openClawSession: GatewaySession? = null
    private var openClawSpeechPlayer: MediaPlayer? = null
    // In-flight TTS HTTP call — cancellable when the user starts a new
    // recording mid-playback (interrupts download + may save credits).
    private var openClawTtsCall: okhttp3.Call? = null
    private var openClawSpeakNextAssistant = false
    private var openClawLastSpokenKey = ""

    // Streaming-TTS pipeline: chunk the assistant reply by sentence as
    // onChatDelta ticks arrive, fire ElevenLabs synth per chunk in parallel,
    // play MP3s sequentially in issuance order. Cuts perceived first-audio
    // latency from "stream end + history round-trip" to "first sentence
    // boundary in streaming text".
    private var openClawStreamingSpokenOffset: Int = 0
    private var openClawStreamingTtsActive: Boolean = false
    private var openClawStreamingTtsTurnId: Long = 0L
    private var openClawSpeechIssuedSeq: Int = 0
    private var openClawSpeechNextToPlay: Int = 1
    private var openClawSpeechPlaying: Boolean = false
    // seq -> File (success) or null (errored/canceled, skip in playback)
    private val openClawSpeechSlots: java.util.TreeMap<Int, File?> = java.util.TreeMap()
    private val openClawTtsChunkCalls: MutableList<okhttp3.Call> = mutableListOf()
    // The file currently feeding openClawSpeechPlayer — tracked so cancel
    // can delete it (it's no longer in the slots map once playback starts).
    private var openClawSpeechCurrentFile: File? = null

    // Voice/STT state — shared across chat / terminal / hermes.
    private val voicePrefs by lazy { com.r1.launcher.voice.VoicePrefs.get(this) }
    private var voiceCapture: com.r1.launcher.voice.StreamingAudioCapture? = null
    private var voiceSession: com.r1.launcher.voice.ElevenLabsRealtimeClient? = null
    /** Which sink to deliver the STT transcript to. */
    private enum class VoiceSink { CHAT, TERMINAL, HERMES_CHAT, TRANSLATOR }
    private var voiceSink: VoiceSink? = null
    /** True when the active capture was triggered by a power-user gesture
     *  (side button hold / wheel-press toggle): committed transcript auto-
     *  sends straight to the chat. False = on-screen mic pill: transcript
     *  lands in the input draft so the user can edit / delete / send. */
    private var voiceAutoSend: Boolean = false
    /** Wall-clock ms when the current voice session's mic actually opened.
     *  Kept for diagnostics; barge-in itself is anchored to TTS-start time
     *  via [voiceSpeakingStartedAtMs] (see that field for why mic-open isn't
     *  a useful anchor in conversation mode). */
    private var voiceMicOpenedAtMs: Long = 0L
    /** Wall-clock ms when TTS playback most recently started. Barge-in uses
     *  this as the grace anchor: AEC needs time to adapt to the TTS reference
     *  signal, and the first ~1s of speaker output bleeds through the mic
     *  before it locks on. Anchoring grace to mic-open instead (the prior
     *  approach) failed in conversation mode because the mic re-opens BEFORE
     *  TTS starts — the grace would expire just as the AI began speaking. */
    private var voiceSpeakingStartedAtMs: Long = 0L

    // --- meetings (transcriber) ---
    private val transcriberPrefs by lazy { TranscriberPrefs.get(this) }
    private val meetingStore by lazy { MeetingStore.get(this) }
    private var transcriberBinder: TranscriberRecordingService.LocalBinder? = null
    private var transcriberServiceBound: Boolean = false
    private var transcriberCurrentMeeting: Meeting? = null
    private var transcriberPlayer: MediaPlayer? = null
    private val transcriberExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    // Single shared worker for cold-start + app-open encrypted-prefs hydration.
    // EncryptedSharedPreferences.create does a MasterKey keystore/TEE round-trip
    // + AES-SIV decrypt (20-100ms each on the A53); doing six of them serially on
    // the main thread before setContent delays first paint, and the same cost
    // recurs on the Hermes/Translator app-open tap (prefs build + history JSON
    // decode of up to 200/100 entries). Read off-thread, post values back into
    // LauncherState (which already carries defaults) a frame or two later.
    private val prefsHydrationExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val transcriberPollRunnable = object : Runnable {
        override fun run() {
            val b = transcriberBinder
            if (b != null && b.isRecording) {
                state.recordingActive = true
                state.recordingElapsedMs = b.elapsedMs
                state.recordingPeak = b.peakLevel
                ui.postDelayed(this, 200L)
            } else {
                state.recordingActive = false
            }
        }
    }
    private val transcriberServiceConn = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            transcriberBinder = service as? TranscriberRecordingService.LocalBinder
            // If we bound to an active recording (e.g. activity recreate after
            // low-mem kill), surface it back into the panel state.
            if (transcriberBinder?.isRecording == true) {
                // Don't auto-jump panels on rebind; the user is wherever they
                // are, and the list page reflects active recording via the
                // pulsing record row.
                state.recordingActive = true
                ui.removeCallbacks(transcriberPollRunnable)
                ui.post(transcriberPollRunnable)
            }
            // Now that the binder is available we can tell a genuinely-live
            // recording apart from a stale one and fail the stale ones.
            reconcileStaleMeetings()
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            transcriberBinder = null
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    // Lazy so Locale.getDefault() resolves AFTER attachBaseContext. The locale
    // helper pins ASCII numerals via the `-u-nu-latn` Unicode extension — see
    // com.r1.launcher.locale.digitFriendlyLocale.
    private val hm by lazy { SimpleDateFormat("h:mm a", com.r1.launcher.locale.digitFriendlyLocale()) }
    private val dt by lazy { SimpleDateFormat("EEEE, MMMM d", com.r1.launcher.locale.digitFriendlyLocale()) }

    private var lastSidePressMs: Long = 0L
    private var lastPauseMs: Long = 0L
    private var lastResumeMs: Long = 0L
    private var openClawPttKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN
    // Set when the clock-screen talk shortcut opens the OpenClaw chat: the
    // socket connects asynchronously, so recording can't start until the
    // session is Live. Flushed in openClawStartSession()'s onState handler;
    // cleared on release / error so a late connect never records after the fact.
    private var pendingChatVoiceArm: Boolean = false

    // Side button (BUTTON_1) press detection: distinguishes short-tap, double-tap,
    // and long-press. Tuned values:
    //   short ≤ 500 ms (DOWN→UP duration)
    //   long  ≥ 500 ms (DOWN held without UP fires before UP arrives, see ACTION_DOWN repeat)
    //   double tap window: two short taps within 350 ms of each other
    private var sideDownAtMs: Long = 0L
    private var sideLastShortUpMs: Long = 0L
    private var sideLongFired: Boolean = false
    private var pendingSideSingle: Runnable? = null
    private val SIDE_DOUBLE_PRESS_MS = 350L
    private val SIDE_LONG_PRESS_MS = 500L

    private var telephony: TelephonyManager? = null
    private val phoneListener = object : PhoneStateListener() {
        override fun onSignalStrengthsChanged(s: SignalStrength?) {
            if (s == null) return
            state.signalLevel = s.level.coerceIn(0, 4)
        }
    }

    private val netRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent?) {
            refreshNetwork()
            refreshBluetooth()
            refreshSim()
        }
    }

    private val packageRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent?) {
            loadApps()
        }
    }

    private val batteryRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent?) {
            i ?: return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return
            state.batteryPct = (level.toFloat() / scale).coerceIn(0.08f, 1f)
            state.batteryCharging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        }
    }

    // adb-installable ElevenLabs key receiver:
    //   adb shell "am broadcast -a com.r1.launcher.SET_ELEVENLABS_KEY \
    //     --es secret <controlSecret> --es key sk_..."
    // Lets the user inject the API key without typing it on a 480x480 round
    // screen. Receiver is exported so adb (uid 2000) can reach it, but gated on
    // the per-device control secret (Settings → Credentials) so it isn't open
    // to every installed app — see controlSecretOk().
    private val voiceKeyRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent?) {
            if (!controlSecretOk(i)) return
            val k = i?.getStringExtra("key")?.trim().orEmpty()
            when {
                k.isEmpty() -> toastFail("--es key missing")
                !isValidElevenKey(k) -> toastFail("not an elevenlabs key")
                else -> {
                    voicePrefs.elevenlabsKey = k
                    refreshVoiceKeyState()
                    toastSuccess("voice key saved")
                }
            }
        }
    }

    // Drive the chat app from adb:
    //   adb shell "am broadcast -a com.r1.launcher.CHAT_SEND \
    //     --es secret <controlSecret> --es text 'hello' [--ez image true]"
    // The on-screen RetroKeyboard is a Compose surface, not an IME, so
    // `input text` can't reach it — this is the only way to script a turn, and
    // it doubles as the hook a companion app would use.
    private val chatSendRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent?) {
            if (!controlSecretOk(i)) return
            val text = i?.getStringExtra("text")?.trim().orEmpty()
            if (text.isEmpty()) { toastFail("--es text missing"); return }
            hydrateChatPrefs()
            if (state.chatId.isBlank()) {
                state.chatId = com.r1.launcher.chat.ChatStore.newId()
                state.chatTitle = "new chat"
                state.chatMsgs.clear()
            }
            state.chatImageMode = i?.getBooleanExtra("image", false) == true
            state.chatInput = text
            if (state.panel != Panel.CHAT) state.openChat()
            chatSend()
        }
    }

    // adb-installable OpenAI key receiver (camera app: transcription + image edit):
    //   adb shell "am broadcast -a com.r1.launcher.SET_OPENAI_KEY \
    //     --es secret <controlSecret> --es key sk-proj-..."
    // Same rationale as the ElevenLabs receiver above — an OpenAI project key
    // is ~160 characters, which is not something anyone is going to enter on
    // the RetroKeyboard. Gated on the control secret.
    private val openAiKeyRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent?) {
            if (!controlSecretOk(i)) return
            val k = i?.getStringExtra("key")?.trim().orEmpty()
            when {
                k.isEmpty() -> toastFail("--es key missing")
                !com.r1.launcher.camera.CameraPrefs.looksValid(k) -> toastFail("not an openai key")
                else -> {
                    cameraPrefs.openAiKey = k
                    refreshCredentialsDisplay()
                    toastSuccess("openai key saved")
                }
            }
        }
    }

    // adb-installable Hermes URL + bearer receiver:
    //   adb shell "am broadcast -a com.r1.launcher.SET_HERMES_CONFIG \
    //     --es secret <controlSecret> --es url 'https://hermes.example/v1' --es key 'sk-r1-...'"
    // url/key optional (pass one or both); secret required (see controlSecretOk).
    // The bearer token is 70 chars and pasting it via the round-screen
    // RetroKeyboard is impractical — without the secret gate, though, any app
    // could repoint Hermes at an attacker and exfiltrate the user's chats.
    private val hermesConfigRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent?) {
            if (!controlSecretOk(i)) return
            val url = i?.getStringExtra("url")?.trim().orEmpty()
            val key = i?.getStringExtra("key")?.trim().orEmpty()
            if (url.isEmpty() && key.isEmpty()) {
                toastFail("hermes: pass --es url and/or --es key")
                return
            }
            if (url.isNotEmpty()) hermesSetServerUrl(url)
            if (key.isNotEmpty()) hermesSetApiKey(key)
            hydrateHermesStateFromPrefs()
            val parts = buildList {
                if (url.isNotEmpty()) add("url")
                if (key.isNotEmpty()) add("key")
            }
            toastSuccess("hermes ${parts.joinToString("+")} saved")
        }
    }

    // SmsReceiver writes incoming SMS to SmsCache and fires this local broadcast
    // so the messages panel can refresh without polling.
    private val smsLocalRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent?) {
            if (state.panel == Panel.MESSAGES) loadSmsConversations()
            else if (state.panel == Panel.MESSAGES_THREAD) {
                // re-pull the active thread to surface the new bubble
                openSmsThread(state.smsThreadAddress, state.smsThreadName)
            }
        }
    }

    // adb-callable web-server toggle:
    //   adb shell "am broadcast -a com.r1.launcher.TOGGLE_WEB_SERVER \
    //     --es secret <controlSecret> --ez on true"
    // Secret-gated (controlSecretOk) — otherwise any app could silently expose
    // the LAN web companion (and, if remote-terminal is on, a root shell).
    private val webToggleRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent?) {
            if (!controlSecretOk(i)) return
            val on = i?.getBooleanExtra("on", !state.webServerEnabled) ?: !state.webServerEnabled
            android.util.Log.i("LauncherActivity", "webToggleRx fired on=$on")
            toggleWebServer(on)
        }
    }

    // Profile proxies to detect which bonded devices are currently connected
    // (A2DP for audio sink, HEADSET for hands-free / mic). Bound in onCreate
    // once and reused across BT panel openings.
    private var btA2dpProxy: android.bluetooth.BluetoothA2dp? = null
    private var btHeadsetProxy: android.bluetooth.BluetoothHeadset? = null

    @Suppress("DEPRECATION")
    private val btProfileListener = object : android.bluetooth.BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
            android.util.Log.i("R1Bt", "onServiceConnected profile=$profile (A2DP=2 HEADSET=1)")
            when (profile) {
                android.bluetooth.BluetoothProfile.A2DP -> btA2dpProxy = proxy as android.bluetooth.BluetoothA2dp
                android.bluetooth.BluetoothProfile.HEADSET -> btHeadsetProxy = proxy as android.bluetooth.BluetoothHeadset
            }
            if (state.panel == Panel.BT_SCAN) ui.post { refreshBtDevices() }
        }
        override fun onServiceDisconnected(profile: Int) {
            android.util.Log.i("R1Bt", "onServiceDisconnected profile=$profile")
            when (profile) {
                android.bluetooth.BluetoothProfile.A2DP -> btA2dpProxy = null
                android.bluetooth.BluetoothProfile.HEADSET -> btHeadsetProxy = null
            }
        }
    }

    /** Try to bind A2DP + HEADSET proxies. Returns true if both already bound. */
    @Suppress("DEPRECATION")
    private fun ensureBtProxiesBound(): Boolean {
        val adp = BluetoothAdapter.getDefaultAdapter() ?: return false
        if (!adp.isEnabled) return false
        var fired = false
        if (btA2dpProxy == null) {
            val ok = runCatching { adp.getProfileProxy(this, btProfileListener, android.bluetooth.BluetoothProfile.A2DP) }.getOrDefault(false)
            android.util.Log.i("R1Bt", "ensureBtProxiesBound A2DP getProfileProxy=$ok")
            fired = true
        }
        if (btHeadsetProxy == null) {
            val ok = runCatching { adp.getProfileProxy(this, btProfileListener, android.bluetooth.BluetoothProfile.HEADSET) }.getOrDefault(false)
            android.util.Log.i("R1Bt", "ensureBtProxiesBound HEADSET getProfileProxy=$ok")
            fired = true
        }
        return !fired && btA2dpProxy != null && btHeadsetProxy != null
    }

    @Suppress("DEPRECATION")
    private fun isBtDeviceConnected(dev: android.bluetooth.BluetoothDevice): Boolean {
        val a2dp = runCatching { btA2dpProxy?.connectedDevices?.any { it.address == dev.address } == true }.getOrDefault(false)
        val hs = runCatching { btHeadsetProxy?.connectedDevices?.any { it.address == dev.address } == true }.getOrDefault(false)
        return a2dp || hs
    }

    /** Rebuild btDevices from current bonded set + connection state, preserving
     *  any non-bonded entries already discovered in this scan session. */
    @Suppress("DEPRECATION")
    private fun refreshBtDevices() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val bondedSet = runCatching { adapter.bondedDevices }.getOrNull() ?: emptySet()
        val nonBonded = state.btDevices.filter { !it.bonded }
        val bondedEntries = bondedSet.map { dev ->
            val name = runCatching { dev.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: dev.address
            LauncherState.BtDevice(name, dev.address, bonded = true, connected = isBtDeviceConnected(dev))
        }.sortedByDescending { it.connected }
        state.btDevices.clear()
        state.btDevices.addAll(bondedEntries)
        state.btDevices.addAll(nonBonded)
    }

    @Suppress("DEPRECATION")
    private val btScanRx = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                android.bluetooth.BluetoothDevice.ACTION_FOUND,
                android.bluetooth.BluetoothDevice.ACTION_NAME_CHANGED -> {
                    val dev = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                    } ?: return
                    // The broadcast carries the freshest name in EXTRA_NAME during
                    // discovery — `dev.name` is often null on first sighting and
                    // only resolves later (or never, for anonymous peripherals).
                    val name = intent.getStringExtra(android.bluetooth.BluetoothDevice.EXTRA_NAME)?.takeIf { it.isNotBlank() }
                        ?: runCatching { dev.name }.getOrNull()?.takeIf { it.isNotBlank() }
                        ?: dev.address
                    val bonded = dev.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED
                    val entry = LauncherState.BtDevice(name, dev.address, bonded, connected = isBtDeviceConnected(dev))
                    ui.post {
                        val existingIdx = state.btDevices.indexOfFirst { it.address == dev.address }
                        if (existingIdx < 0) {
                            state.btDevices.add(entry)
                        } else if (state.btDevices[existingIdx].name != name && name != dev.address) {
                            // Upgrade entry: a real name has arrived; previously we only had MAC.
                            state.btDevices[existingIdx] = state.btDevices[existingIdx].copy(name = name)
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> ui.post { state.btScanning = false }
                // Pairing just completed → kick off A2DP/HSP connect so the user
                // doesn't have to tap a second time. Android sometimes does this
                // automatically but on custom ROMs it's unreliable.
                android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val dev = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                    }
                    val newState = intent.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE, -1)
                    if (dev != null && newState == android.bluetooth.BluetoothDevice.BOND_BONDED) {
                        btProfileAction(dev, connect = true)
                    }
                    ui.post { if (state.panel == Panel.BT_SCAN) refreshBtDevices() }
                }
                // Profile or ACL state changed → rebuild the bonded portion so the
                // "● connected" badge updates live.
                android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
                android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED,
                android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED -> ui.post {
                    if (state.panel == Panel.BT_SCAN) refreshBtDevices()
                }
            }
        }
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val now = Date()
            state.clockText = hm.format(now)
            state.dateText = dt.format(now)
            // Re-post aligned to the next second boundary so the display doesn't drift.
            ui.postDelayed(this, 1000L - (System.currentTimeMillis() % 1000L))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        // Kill the Android system "click" beep that View.playSoundEffect(CLICK)
        // fires on every Compose Modifier.clickable activation. We ship our own
        // UI click cue (SoundPrefs / playUiClickSound) and it stacks on top of
        // the system one, producing a double-tick. Disable on the decor view —
        // propagates to the AndroidComposeView and every clickable inside.
        window.decorView.isSoundEffectsEnabled = false

        telephony = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        ensurePhonePerm()

        // UI click cues live on STREAM_SYSTEM so the "speaker" slider (which
        // governs STREAM_MUSIC for TTS / media) doesn't bleed into them.
        // STREAM_SYSTEM is held at max below; the per-call ToneGenerator volume
        // (0-100) is rebuilt from the user's "system sound" slider via
        // rebuildTone(), called here and again from setUiVolume /
        // toggleUiSoundEnabled. The PTT recording cue used to fire this at
        // hardcoded max on top of the slider-scaled mp3, which is what made
        // long-press side-button beeps louder than every other UI sound.
        // Hydrate UI-sound prefs first so the very first rebuildTone() picks
        // up the user's saved level (seedSettingsLevels only runs on Settings
        // open, which can be after the user's first long-press).
        state.uiVolumeMax = com.r1.launcher.sound.SoundPrefs.MAX_UI_LEVEL
        state.uiVolumeLevel = soundPrefs.uiVolumeLevel
        state.uiSoundEnabled = soundPrefs.uiSoundEnabled
        state.hapticsEnabled = hapticPrefs.enabled
        applySystemHaptics(hapticPrefs.enabled)
        rebuildTone()

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attrs)
            .build()
        runCatching {
            audioManager?.let { am ->
                am.setStreamVolume(
                    AudioManager.STREAM_SYSTEM,
                    am.getStreamMaxVolume(AudioManager.STREAM_SYSTEM),
                    0,
                )
            }
        }
        // MTK MP3 decoder init (`c2.mtk.mp3.decoder allocate`) on the UI thread
        // causes a ~150-frame Choreographer skip on cold start. Push it off-main
        // — the IDs are only read by tone helpers, so a missed first-second click
        // is acceptable.
        Thread {
            runCatching {
                val afd = assets.openFd("moving.mp3")
                movingSoundId = soundPool?.load(afd, 1) ?: 0
                afd.close()
            }
            runCatching {
                val afd = assets.openFd("UIClick-Very_short_wooden_UI-Elevenlabs.mp3")
                uiClickSoundId = soundPool?.load(afd, 1) ?: 0
                afd.close()
            }
            runCatching {
                val afd = assets.openFd("UIClick-very_subtle_button_p-Elevenlabs.mp3")
                selectSoundId = soundPool?.load(afd, 1) ?: 0
                afd.close()
            }
            runCatching {
                val afd = assets.openFd("record.mp3")
                recordStartSoundId = soundPool?.load(afd, 1) ?: 0
                afd.close()
            }
            runCatching {
                val afd = assets.openFd("release-record.mp3")
                recordStopSoundId = soundPool?.load(afd, 1) ?: 0
                afd.close()
            }
        }.start()

        // OTA: silent boot check (no toast on "up to date"), wired through carroot
        // for the post-install reboot. The Settings → "check for updates" row
        // calls back in with forcePrompt = true.
        OTAUpdater.executeRootCommand = { cmd -> sendToCarroot(cmd) }
        OTAUpdater.checkForUpdates(this, state, forcePrompt = false) { msg ->
            toast(msg)
        }

        // Pre-bind to the recording service so a recovery from low-mem-kill
        // (FGS survives, activity doesn't) immediately sees the active recording.
        bindTranscriberService()

        // Screen-recording audio toggles — plain prefs (cheap), so kept on the
        // main thread. setCapture*Enabled writes back through here for live updates.
        state.captureMicEnabled = com.r1.launcher.media.MediaCapturePrefs.micEnabled(this)
        state.capturePlaybackEnabled = com.r1.launcher.media.MediaCapturePrefs.playbackEnabled(this)

        // Defer every EncryptedSharedPreferences-backed hydration off the main
        // thread. Each .create is a MasterKey keystore round-trip + AES-SIV
        // decrypt; six of them serially before setContent is what stalls first
        // paint. None of these gate HOME (the first frame) — LauncherState already
        // carries safe defaults, so reading them a frame or two later is fine.
        // Transcriber / OpenClaw / Hermes / Voice / WifiShare / Notif live here.
        prefsHydrationExecutor.execute {
            // Read everything off-thread first (this is where the keystore +
            // AES-SIV + history-JSON cost lives), then apply to state on the UI
            // thread in one post so Compose sees a consistent snapshot.
            val openClawHideChat = openClawPrefs.hideChat
            val chatFontSize = openClawPrefs.chatFontSize
            val voiceEnabled = voicePrefs.enabled
            val voiceId = voicePrefs.voiceId
            val voiceCustomId = voicePrefs.customVoiceId.orEmpty()
            val talkShortcut = voicePrefs.talkShortcut
            val voiceModel = voicePrefs.model
            val voiceStability = voicePrefs.stability
            val voiceSimilarity = voicePrefs.similarity
            val voiceStyle = voicePrefs.style
            val voiceSpeed = voicePrefs.speed
            val voiceSpeakerBoost = voicePrefs.speakerBoost
            val wifiShareSsid = wifiSharePrefs.ssid
            val wifiSharePassword = wifiSharePrefs.password
            val wifiShareTimerMinutes = wifiSharePrefs.timerMinutes
            val notificationSoundEnabled = notifPrefs.soundEnabled
            val panelPasscode = notifPrefs.panelPasscode
            // Transcriber SMTP display mirrors (encrypted).
            val hasSmtp = transcriberPrefs.hasSmtp()
            val smtpHost = transcriberPrefs.smtpHost
            val smtpPort = transcriberPrefs.smtpPort
            val smtpUser = transcriberPrefs.smtpUser ?: ""
            val defaultRecipient = transcriberPrefs.defaultRecipient
            // Voice key display (encrypted).
            val voiceKey = voicePrefs.elevenlabsKey
            // Hermes: connections + active + per-connection history. `active`
            // decrypts the connection list (AES-SIV); the history load is a file
            // readText + JSON decode of up to 200 entries — all of it off-thread.
            val hermesActive = hermesPrefs.active
            val hermesUrl = hermesActive?.url.orEmpty()
            val hermesKey = hermesActive?.apiKey.orEmpty()
            val hermesModel = hermesPrefs.model
            val hermesFontSize = hermesPrefs.fontSize
            val hermesHideChat = hermesPrefs.hideChat
            val hermesConnections = hermesPrefs.connections
            val hermesActiveId = hermesActive?.id
            val hermesHistory = if (hermesActiveId != null)
                com.r1.launcher.hermes.HermesHistoryStore.load(this, hermesActiveId)
            else emptyList()
            // Credentials panel: webhook token + control secret (encrypted notif).
            val webhookTokenTail = notifPrefs.webhookToken.takeLast(8)
            val controlSecret = notifPrefs.controlSecret
            // Notifications from disk — append-only JSON, oldest-first. File IO,
            // not encrypted, but still off the first-paint path; badge updates
            // a frame or two later.
            val persistedNotifs = runCatching {
                com.r1.launcher.notifications.NotificationStore.all(this)
            }.getOrNull()
            ui.post {
                state.openClawHideChat = openClawHideChat
                state.chatFontSize = chatFontSize
                state.voiceEnabled = voiceEnabled
                state.voiceId = voiceId
                state.voiceCustomId = voiceCustomId
                state.talkShortcut = talkShortcut
                state.voiceModel = voiceModel
                state.voiceStability = voiceStability
                state.voiceSimilarity = voiceSimilarity
                state.voiceStyle = voiceStyle
                state.voiceSpeed = voiceSpeed
                state.voiceSpeakerBoost = voiceSpeakerBoost
                state.wifiShareSsid = wifiShareSsid
                state.wifiSharePassword = wifiSharePassword
                state.wifiShareTimerMinutes = wifiShareTimerMinutes
                state.notificationSoundEnabled = notificationSoundEnabled
                // 4-digit web-panel passcode — shown in Settings → Network.
                state.panelPasscode = panelPasscode
                if (persistedNotifs != null) {
                    state.notifications.clear()
                    state.notifications.addAll(persistedNotifs)
                    state.notificationsUnread = persistedNotifs.count { !it.read }
                }
                // Transcriber SMTP display (mirrors refreshTranscriberPrefsCache).
                state.hasSmtp = hasSmtp
                state.smtpHostDisplay = smtpHost
                state.smtpPortDisplay = smtpPort
                state.smtpUserDisplay = smtpUser
                state.defaultRecipientDisplay = defaultRecipient
                // Voice key display (mirrors refreshVoiceKeyState).
                if (voiceKey.isNullOrBlank()) {
                    state.hasVoiceKey = false
                    state.voiceKeyTail = ""
                } else {
                    state.hasVoiceKey = true
                    state.voiceKeyTail = voiceKey.takeLast(4)
                }
                // Hermes (mirrors hydrateHermesStateFromPrefs). Only seed history
                // if the in-memory list is still empty — the user may have opened
                // the chat and loaded/typed before this post landed.
                state.hermesServerUrl = hermesUrl
                state.hermesApiKeyTail = if (hermesKey.length > 6) "…" + hermesKey.takeLast(4)
                    else if (hermesKey.isNotEmpty()) "set" else ""
                state.hermesModel = hermesModel
                state.hermesFontSize = hermesFontSize
                state.hermesHideChat = hermesHideChat
                state.hermesServerUrlInput = hermesUrl
                state.hermesApiKeyInput = ""
                state.hermesConnections.clear()
                state.hermesConnections.addAll(hermesConnections)
                state.hermesActiveId = hermesActiveId
                if (hermesActiveId != null && hermesHistory.isNotEmpty()) {
                    val list = state.hermesActiveHistory()
                    if (list != null && list.isEmpty()) list.addAll(hermesHistory)
                }
                // Credentials panel display mirrors (mirrors refreshCredentialsDisplay;
                // ElevenLabs/voice key already handled above).
                state.hasHermesKey = hermesKey.isNotBlank()
                state.hermesKeyTail = if (hermesKey.isNotBlank()) hermesKey.takeLast(4) else ""
                state.webhookTokenDisplay = webhookTokenTail
                state.controlSecretDisplay = controlSecret
            }
        }
        // ntfy.sh subscriber: hydrate display state + auto-start if the
        // user previously enabled it (matches webserver auto-on pattern).
        // ensureTopic() seeds a random per-device topic on first cold boot
        // after a fresh flash so the user has a usable URL out of the box;
        // the subscriber stays off until they flip the enable toggle.
        ntfyPrefs.ensureTopic()
        state.ntfyTopic = ntfyPrefs.topic
        state.ntfySubscriberEnabled = ntfyPrefs.enabled
        state.ntfyStatus = if (ntfyPrefs.enabled && ntfyPrefs.isConfigured()) "connecting" else "disabled"
        if (ntfyPrefs.enabled && ntfyPrefs.isConfigured()) {
            startNtfySubscriber()
        }
        loadApps()

        // Media capture (web companion): ensure dirs exist, clean stale tmps,
        // recover any orphan screenrecord from a prior crash. Off-thread so a
        // dead carroot socket doesn't slow onCreate.
        Thread {
            runCatching { com.r1.launcher.media.MediaCaptureManager.init(this) }
        }.start()

        // Default the remote panel and Bluetooth to off on every cold start —
        // user opts in via the Network panel.
        toggleBluetooth(false)

        if (!com.r1.launcher.onboarding.OnboardingPrefs.isDone(this)) {
            state.openOnboarding()
            // If the user already picked a language (typically on the previous
            // run before recreate(), or when launching after factory-fresh prefs),
            // skip the language picker and start at welcome.
            if (com.r1.launcher.locale.LocalePrefs.get(this).picked) {
                state.onboardingStep = 1
            }
        }

        setContent {
            R1Theme {
                LauncherRoot(state = state, host = this)
            }
        }
    }

    private fun loadApps() {
        // Off-main: queryIntentActivities + per-app loadLabel binder IPC is
        // the bulk of onCreate / onResume / package-change work. Snapshot
        // packageManager into a local so the worker thread doesn't capture
        // anything that needs the main looper.
        val pm = packageManager
        val ownPkg = packageName
        Thread {
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val found = pm.queryIntentActivities(main, 0)
                .filter { it.activityInfo.packageName != ownPkg && it.activityInfo.packageName != "com.android.settings" }
                .sortedBy { it.loadLabel(pm).toString().lowercase(Locale.getDefault()) }
            ui.post {
                state.apps.clear()
                found.forEach { state.apps.add(AppEntry.Real(it)) }
                state.apps.add(AppEntry.Messages)
                state.apps.add(AppEntry.OpenClaw)
                state.apps.add(AppEntry.Terminal)
                state.apps.add(AppEntry.Hermes)
                state.apps.add(AppEntry.Translator)
                state.apps.add(AppEntry.Meetings)
                state.apps.add(AppEntry.Chat)
                state.apps.add(AppEntry.Camera)
                state.apps.add(AppEntry.Testing)
                state.apps.add(AppEntry.Settings)
                state.appsLoaded = true
                if (state.appsFocus >= state.apps.size) state.appsFocus = 0
            }
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The side button is mapped to BUTTON_1 (not HOME), so its handling lives in
        // dispatchKeyEvent. onNewIntent fires only for genuine HOME-category redirects
        // — typically when a third-party app finishes / the user invokes home. Land
        // on the clock screen unless we're already on it.
        if (state.panel != Panel.HOME) state.goHome()
    }

    override fun onResume() {
        super.onResume()
        lastResumeMs = System.currentTimeMillis()
        ui.post(tick)

        val netFilter = IntentFilter().apply {
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        registerReceiver(netRx, netFilter)
        registerReceiver(batteryRx, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val btScanFilter = IntentFilter().apply {
            addAction(android.bluetooth.BluetoothDevice.ACTION_FOUND)
            addAction(android.bluetooth.BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(btScanRx, btScanFilter)
        // Bind A2DP and HEADSET profile proxies so we can query which paired
        // devices are currently connected (the only authoritative source).
        runCatching {
            BluetoothAdapter.getDefaultAdapter()?.let { adp ->
                adp.getProfileProxy(this, btProfileListener, android.bluetooth.BluetoothProfile.A2DP)
                adp.getProfileProxy(this, btProfileListener, android.bluetooth.BluetoothProfile.HEADSET)
            }
        }

        val pkgFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packageRx, pkgFilter)

        val keyFilter = IntentFilter("com.r1.launcher.SET_ELEVENLABS_KEY")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(voiceKeyRx, keyFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(voiceKeyRx, keyFilter)
        }

        val chatSendFilter = IntentFilter("com.r1.launcher.CHAT_SEND")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(chatSendRx, chatSendFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(chatSendRx, chatSendFilter)
        }

        val openAiFilter = IntentFilter("com.r1.launcher.SET_OPENAI_KEY")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(openAiKeyRx, openAiFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(openAiKeyRx, openAiFilter)
        }

        val hermesCfgFilter = IntentFilter("com.r1.launcher.SET_HERMES_CONFIG")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(hermesConfigRx, hermesCfgFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(hermesConfigRx, hermesCfgFilter)
        }

        val smsLocalFilter = IntentFilter(com.r1.launcher.messages.SmsReceiver.ACTION_NEW_SMS_LOCAL)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(smsLocalRx, smsLocalFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(smsLocalRx, smsLocalFilter)
        }

        val webToggleFilter = IntentFilter("com.r1.launcher.TOGGLE_WEB_SERVER")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(webToggleRx, webToggleFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(webToggleRx, webToggleFilter)
        }

        runCatching {
            telephony?.listen(phoneListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        }

        refreshNetwork()
        refreshBluetooth()
        refreshSim()

        loadApps()
    }

    override fun onPause() {
        super.onPause()
        lastPauseMs = System.currentTimeMillis()
        // Cancel any in-flight push-to-talk recording — the activity is
        // singleTask so backgrounding doesn't tear down the AudioCapture
        // automatically, which would otherwise complete and send after
        // the user has left the app.
        if (state.chatRecording) runCatching { openClawRecordStop() }
        if (state.hermesRecording) runCatching { hermesRecordStop() }
        if (state.translatorRecording) runCatching { translatorRecordStop() }
        ui.removeCallbacks(tick)
        runCatching { unregisterReceiver(netRx) }
        runCatching { unregisterReceiver(batteryRx) }
        runCatching { unregisterReceiver(btScanRx) }
        @Suppress("DEPRECATION")
        runCatching {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            btA2dpProxy?.let { adapter?.closeProfileProxy(android.bluetooth.BluetoothProfile.A2DP, it) }
            btHeadsetProxy?.let { adapter?.closeProfileProxy(android.bluetooth.BluetoothProfile.HEADSET, it) }
        }
        runCatching { unregisterReceiver(packageRx) }
        runCatching { unregisterReceiver(voiceKeyRx) }
        runCatching { unregisterReceiver(openAiKeyRx) }
        runCatching { unregisterReceiver(chatSendRx) }
        runCatching { unregisterReceiver(hermesConfigRx) }
        runCatching { unregisterReceiver(smsLocalRx) }
        runCatching { unregisterReceiver(webToggleRx) }
        runCatching { telephony?.listen(phoneListener, PhoneStateListener.LISTEN_NONE) }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { openClawCloseSessionInternal() }
        runCatching { voiceCapture?.close() }
        voiceCapture = null
        runCatching { voiceSession?.cancel() }
        voiceSession = null
        runCatching { webServer?.stopServer() }
        webServer = null
        runCatching { ntfySubscriber?.stop() }
        ntfySubscriber = null
        // Clean up UI handlers
        runCatching { ui.removeCallbacksAndMessages(null) }
        runCatching { tone?.release() }
        tone = null
        runCatching { soundPool?.release() }
        soundPool = null
        runCatching { openClawTtsCall?.cancel() }
        openClawTtsCall = null
        runCatching { openClawSpeechPlayer?.release() }
        openClawSpeechPlayer = null
        runCatching { cancelHermesSpeech() }
        runCatching { hermesClient.cancelAll() }
        runCatching { cancelTranslatorSpeech() }
        runCatching { translatorClient.cancel() }
        // Stop transcriber playback but DO NOT stop the FGS — if a meeting is
        // recording when the user kills the launcher, we want it to keep going
        // until they explicitly stop it. Just unbind from our side.
        runCatching { transcriberPlayer?.release() }
        transcriberPlayer = null
        unbindTranscriberService()
        runCatching { transcriberExecutor.shutdownNow() }
        runCatching { prefsHydrationExecutor.shutdownNow() }
    }

    override fun onBackPressed() {
        if (state.panel == Panel.HOME) {
            // No-op on home — we ARE the home activity.
        } else {
            state.backPressed(this)
        }
    }

    // --- network/battery/sim refresh ---

    private fun refreshNetwork() {
        var wifiConnected = false
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork
            if (n != null) {
                val caps = cm.getNetworkCapabilities(n)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    wifiConnected = true
                }
            }
        }
        state.wifiOn = wifiConnected
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) state.wifiEnabled = wm.isWifiEnabled
        }

        // Asynchronously fetch connected SSID name via root shell
        if (state.wifiOn) {
            Thread {
                if (sendToCarroot("cmd wifi status > /data/local/tmp/wifi_status.txt && chmod 666 /data/local/tmp/wifi_status.txt")) {
                    Thread.sleep(300)
                    try {
                        val txt = java.io.File("/data/local/tmp/wifi_status.txt").readText()
                        val match = Regex("Wifi is connected to \"(.*?)\"").find(txt)
                        val ssid = match?.groupValues?.get(1) ?: ""
                        ui.post { state.wifiConnectedSsid = ssid }
                    } catch (e: Exception) {}
                }
            }.start()
        } else {
            state.wifiConnectedSsid = ""
        }

        // Reconcile the hotspot toggle with the real ap0 interface. wifiShareEnabled
        // defaults false and is otherwise only flipped by a manual toggle, so a softap
        // that's actually up (process restart, prior session, externally started) would
        // wrongly read OFF in Settings. isWifiShareEnabled() does blocking carroot I/O,
        // so probe off-thread and post the result. Guards: skip while a user toggle is
        // verifying, and coalesce concurrent refreshNetwork() calls into one probe.
        if (!wifiShareToggleInFlight && !wifiShareSyncInFlight) {
            wifiShareSyncInFlight = true
            Thread {
                val up = isWifiShareEnabled()
                ui.post {
                    if (!wifiShareToggleInFlight && state.wifiShareEnabled != up) {
                        state.wifiShareEnabled = up
                        if (up) {
                            armWifiShareTimer()
                            ui.removeCallbacks(wifiShareClientPollRunnable)
                            ui.post(wifiShareClientPollRunnable)
                        } else {
                            ui.removeCallbacks(wifiShareTimerRunnable)
                            ui.removeCallbacks(wifiShareCountdownRunnable)
                            ui.removeCallbacks(wifiShareClientPollRunnable)
                            state.wifiShareTimerRemainingSec = 0
                            state.wifiShareConnectedClients.clear()
                        }
                    }
                }
                wifiShareSyncInFlight = false
            }.start()
        }
    }

    @Suppress("DEPRECATION")
    private fun refreshBluetooth() {
        val now = runCatching {
            BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        }.getOrDefault(false)
        val was = state.btOn
        state.btOn = now
        // When BT comes on, (re-)bind the profile proxies if we don't have them
        // yet. getProfileProxy returns false silently while the adapter is off,
        // so binds attempted at cold-start never reach onServiceConnected.
        if (now) ensureBtProxiesBound()
        // If we're on the BT panel and BT just came on, kick off a fresh scan.
        // If it just went off, clear the list so the toggle row stands alone.
        if (state.panel == Panel.BT_SCAN && now != was) {
            if (now) startBtScan() else state.btDevices.clear()
        }
    }

    @Suppress("DEPRECATION")
    private fun refreshSim() {
        val tm = telephony ?: run {
            state.simPresent = false; return
        }
        val simState = runCatching { tm.simState }.getOrDefault(TelephonyManager.SIM_STATE_UNKNOWN)
        val operator = (tm.networkOperatorName?.takeIf { it.isNotBlank() }
            ?: tm.simOperatorName?.takeIf { it.isNotBlank() }
            ?: "")
        // Be lenient: if we have an operator name we have a SIM in service, even if
        // simState briefly reports something other than READY (locked, loaded, etc.).
        val present = simState == TelephonyManager.SIM_STATE_READY || operator.isNotEmpty()
        state.simPresent = present
        if (!present) {
            state.simOperator = ""
            state.cellularOn = false
            state.networkType = ""
            return
        }
        state.simOperator = operator.ifEmpty { "SIM" }

        // Mobile data on/off. Requires READ_PHONE_STATE (READ_BASIC_PHONE_STATE on API 31+).
        state.cellularOn = runCatching { tm.isDataEnabled }.getOrDefault(false)

        // Direct radio type lookup — no shell-out. Works because the launcher is
        // system-signed in our OS image so READ_PHONE_STATE is granted.
        val radio = runCatching {
            if (Build.VERSION.SDK_INT >= 24) tm.dataNetworkType else tm.networkType
        }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        state.networkType = when (radio) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT -> "2G"
            else -> ""
        }
        android.util.Log.d("LauncherActivity",
            "refreshSim: simState=$simState op='$operator' dataOn=${state.cellularOn} radio=$radio -> '${state.networkType}'")
    }

    // --- LauncherHost: side effects ---

    override fun launchApp(idx: Int) {
        when (val entry = state.apps.getOrNull(idx)) {
            is AppEntry.Real -> {
                val info = entry.info
                launchTone()
                runCatching {
                    val i = Intent().apply {
                        setClassName(info.activityInfo.packageName, info.activityInfo.name)
                        action = Intent.ACTION_MAIN
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    }
                    startActivity(i)
                    state.back()
                }
            }
            AppEntry.Settings -> {
                if (!ensureWriteSettingsGrant()) return
                seedSettingsLevels()
                state.openSettings()
                selectTone()
            }
            AppEntry.OpenClaw -> {
                selectTone()
                if (openClawPrefs.hasPairing()) {
                    refreshVoiceKeyState()
                    openClawStartSession()
                    state.openOpenClawChat()
                } else {
                    state.qrError = null
                    ensureCameraPerm()
                    state.openOpenClawQr()
                }
            }
            AppEntry.Messages -> {
                selectTone()
                state.openMessages()
                if (ensureSmsPerm()) loadSmsConversations()
                else state.smsError = "permission required"
            }
            AppEntry.Terminal -> {
                selectTone()
                state.openTerminal()
            }
            AppEntry.Hermes -> {
                selectTone()
                hydrateHermesStateFromPrefs()
                if (hermesPrefs.hasConfig()) {
                    state.openHermesChat()
                    hermesTestConnection()
                } else {
                    state.openHermesConfig(fromChat = false)
                }
            }
            AppEntry.Meetings -> {
                selectTone()
                transcriberOpen()
            }
            AppEntry.Chat -> {
                selectTone()
                hydrateChatPrefs()
                reloadChatHistory()
                state.openChatList()
            }
            AppEntry.Camera -> {
                selectTone()
                if (!ensureCameraPerm()) return
                reloadPhotos()
                state.openCamera()
            }
            AppEntry.Testing -> {
                selectTone()
                state.openTesting()
            }
            AppEntry.Translator -> {
                selectTone()
                hydrateTranslatorStateFromPrefs()
                // First run → wizard (pick source/target, set a key). After that
                // it goes straight to the focused translation screen.
                if (translatorPrefs.onboarded) state.openTranslator()
                else state.openTranslatorOnboarding()
            }
            null -> Unit
        }
    }

    private fun hydrateHermesStateFromPrefs() {
        state.hermesServerUrl = hermesPrefs.active?.url.orEmpty()
        val key = hermesPrefs.active?.apiKey.orEmpty()
        state.hermesApiKeyTail = if (key.length > 6) "…" + key.takeLast(4) else if (key.isNotEmpty()) "set" else ""
        state.hermesModel = hermesPrefs.model
        state.hermesFontSize = hermesPrefs.fontSize
        state.hermesHideChat = hermesPrefs.hideChat
        state.hermesServerUrlInput = hermesPrefs.active?.url.orEmpty()
        state.hermesApiKeyInput = ""
        state.hermesConnections.clear()
        state.hermesConnections.addAll(hermesPrefs.connections)
        state.hermesActiveId = hermesPrefs.active?.id
        val activeId = state.hermesActiveId
        if (activeId != null) {
            val list = state.hermesActiveHistory() ?: return
            if (list.isEmpty()) {
                // History load is a file readText + JSON decode of up to 200
                // entries — move it off the (app-open) main thread. Re-check the
                // list is still empty on the UI thread before seeding so a
                // concurrent send/load isn't clobbered.
                prefsHydrationExecutor.execute {
                    val persisted = com.r1.launcher.hermes.HermesHistoryStore.load(this, activeId)
                    if (persisted.isNotEmpty()) ui.post {
                        val live = state.hermesActiveHistory()
                        if (live != null && live.isEmpty()) live.addAll(persisted)
                    }
                }
            }
        }
    }

    private fun persistHermesHistory() {
        val activeId = state.hermesActiveId ?: return
        val list = state.hermesActiveHistory() ?: return
        com.r1.launcher.hermes.HermesHistoryStore.save(this, activeId, list.toList())
    }

    private fun openClawStartSession() {
        if (openClawSession != null) return
        // Hydrate session state from prefs so the chat panel can render pills
        // and pick the right thread before the WebSocket finishes its handshake.
        state.selectedSessionKey = openClawPrefs.selectedSessionKey?.takeUnless { it.isBlank() } ?: "main"
        state.mainSessionKey = openClawPrefs.lastMainSessionKey?.takeUnless { it.isBlank() } ?: "main"
        val session = GatewaySession(this, openClawPrefs)
        // Each callback compares the captured `session` against the current
        // `openClawSession` — late events from a session the user has since
        // closed or replaced must not mutate UI state.
        session.onState = { st ->
            ui.post {
                if (openClawSession !== session) return@post
                state.chatStatus = when (st) {
                    GatewaySession.State.Idle -> "idle"
                    GatewaySession.State.Connecting -> "connecting"
                    is GatewaySession.State.Live -> "live"
                    is GatewaySession.State.Switching -> "switching"
                    is GatewaySession.State.Error -> "error: ${st.message}"
                    is GatewaySession.State.AuthExpired -> "error: ${st.message}"
                }
                if (st is GatewaySession.State.Live) {
                    state.selectedSessionKey = st.sessionKey
                    // One-shot fetch of available threads after the gateway is up.
                    if (state.chatSessions.isEmpty() && !state.sessionsLoading) {
                        state.sessionsLoading = true
                        openClawSession?.listSessions()
                    }
                    // Clock-screen talk shortcut armed the mic before the socket
                    // was up — fire it now that we're Live, but only if the side
                    // button is still held (PttKeyCode set) and we're still in
                    // the chat. Guards a late reconnect from opening the mic after
                    // the user already let go or navigated away.
                    if (pendingChatVoiceArm &&
                        openClawPttKeyCode != KeyEvent.KEYCODE_UNKNOWN &&
                        state.panel == Panel.OPENCLAW_CHAT) {
                        pendingChatVoiceArm = false
                        openClawRecordStart()
                    }
                }
                if (st is GatewaySession.State.Switching) {
                    state.selectedSessionKey = st.sessionKey
                }
                // Connect failed (auth or transport) — drop any pending
                // clock-screen talk-shortcut arm so it doesn't fire later.
                if (st is GatewaySession.State.Error || st is GatewaySession.State.AuthExpired) {
                    pendingChatVoiceArm = false
                }
                // Only structured AuthExpired (emitted on connect-time auth
                // failures with known token codes) wipes pairing. Generic
                // method-level "unauthorized" errors no longer force re-pair.
                if (st is GatewaySession.State.AuthExpired) {
                    state.chatBusy = false
                    openClawPrefs.bootstrapToken = null
                    openClawPrefs.deviceToken = null
                    openClawPrefs.sharedToken = null
                    runCatching { openClawCloseSessionInternal() }
                    if (state.panel == Panel.OPENCLAW_CHAT) {
                        state.qrError = st.message
                        state.openOpenClawQr()
                    }
                } else if (st is GatewaySession.State.Error) {
                    state.chatBusy = false
                }
            }
        }
        session.onHistory = { msgs ->
            ui.post {
                if (openClawSession !== session) return@post
                applyOpenClawHistory(msgs)
                state.chatScrollIndex = 0
                speakLatestAssistantIfNeeded()
            }
        }
        session.onChatDelta = { runId, text ->
            ui.post {
                if (openClawSession !== session) return@post
                // Only show streaming preview for runs *we* initiated. Other
                // operators talking to the same agent shouldn't bleed into our
                // local UI (matches official client ChatController.kt:347).
                if (runId == null || state.chatPendingRunIds.contains(runId)) {
                    state.chatStreamingText = text
                    // Slice off any newly-completed sentences and start TTS
                    // before the gateway is done writing — first-audio latency
                    // drops from "stream end + history round-trip" to "first
                    // sentence boundary".
                    maybeEmitStreamingTtsChunk()
                }
            }
        }
        session.onChatTerminal = { runId, evState, errMsg ->
            ui.post {
                if (openClawSession !== session) return@post
                if (runId != null) state.chatPendingRunIds.remove(runId)
                // Don't clear chatStreamingText here — refreshHistory() takes a
                // network round-trip, and clearing now would leave a flicker
                // gap where the assistant bubble vanishes before its persisted
                // copy lands. applyOpenClawHistory() clears it once the new
                // messages are in chatMessages, in the same frame.
                state.chatBusy = false
                // Speak any residue past the last sentence boundary — covers
                // one-line replies, code-blocks, lists with no terminator.
                flushStreamingTtsTail()
                openClawSession?.refreshHistory()
                // refreshHistory() is best-effort over the network. If it fails
                // (timeout / socket hiccup), applyOpenClawHistory never runs and
                // the streaming preview bubble stays frozen on screen forever.
                // After a grace period, if the same streaming text is still
                // showing, promote it into a persisted bubble and clear the
                // preview. A late history refresh replaces the whole list, so
                // this can't duplicate.
                val stuckText = state.chatStreamingText
                if (stuckText.isNotBlank()) {
                    ui.postDelayed({
                        if (openClawSession === session && state.chatStreamingText == stuckText) {
                            state.chatMessages.add(
                                com.r1.launcher.openclaw.ChatMessage(role = "assistant", text = stuckText),
                            )
                            while (state.chatMessages.size > state.chatMessagesMax) {
                                state.chatMessages.removeAt(0)
                            }
                            state.chatStreamingText = ""
                        }
                    }, 6000L)
                }
            }
        }
        session.onMainSessionKey = { key ->
            ui.post {
                if (openClawSession !== session) return@post
                state.mainSessionKey = key
                openClawPrefs.lastMainSessionKey = key
            }
        }
        session.onSessions = { list ->
            ui.post {
                if (openClawSession !== session) return@post
                state.chatSessions.clear()
                state.chatSessions.addAll(list)
                state.sessionsLoading = false
            }
        }
        openClawSession = session
        session.start()
    }

    /** Cap chat list at chatMessagesMax, dropping oldest. Call after each add. */
    private fun trimChatMessages() {
        while (state.chatMessages.size > state.chatMessagesMax) {
            state.chatMessages.removeAt(0)
        }
    }

    /**
     * The gateway is authoritative when it returns a complete history, but some
     * OpenClaw builds can briefly return a shorter tail or empty list during
     * long/tool-heavy runs. Preserve the local prefix only while a run is in
     * flight — once the run terminates and history refreshes, trust the
     * server. Otherwise legitimate server-side deletes/truncations would
     * leave stale messages on the R1 forever.
     */
    private fun applyOpenClawHistory(msgs: List<com.r1.launcher.openclaw.ChatMessage>) {
        val incoming = if (msgs.size > state.chatMessagesMax) {
            msgs.takeLast(state.chatMessagesMax)
        } else msgs
        val current = state.chatMessages.filterNot { it.streaming }
        val lastLocal = current.lastOrNull()?.text?.trim().orEmpty()
        val likelyCommandReset = lastLocal.startsWith("/") && lastLocal.length < 40
        val runInFlight = state.chatBusy || state.chatPendingRunIds.isNotEmpty()
        val next = if (runInFlight && current.isNotEmpty() && incoming.size < current.size && !likelyCommandReset) {
            android.util.Log.w(
                "OpenClaw",
                "chat.history returned fewer messages (${incoming.size}) than local UI (${current.size}); preserving local prefix (run in flight)",
            )
            current.take(current.size - incoming.size) + incoming
        } else {
            incoming
        }
        // Detect a *new* assistant message landing (compared with what we had
        // before) so we can push a notification when the chat panel isn't the
        // current panel. Computed before we overwrite chatMessages.
        val prevLastAssistantText = current.lastOrNull { it.role == "assistant" }?.text.orEmpty()
        val incomingLastAssistantText = next.lastOrNull { it.role == "assistant" }?.text.orEmpty()
        val newAssistantArrived = incomingLastAssistantText.isNotBlank() &&
            incomingLastAssistantText != prevLastAssistantText

        state.chatMessages.clear()
        state.chatMessages.addAll(next.takeLast(state.chatMessagesMax))
        // Drop the streaming preview now that the persisted messages have
        // landed — same-frame replacement so there's no flicker between the
        // streaming bubble disappearing and the final assistant bubble
        // appearing in chatMessages.
        state.chatStreamingText = ""

        if (newAssistantArrived && state.panel != Panel.OPENCLAW_CHAT) {
            notify(
                source = "openclaw",
                title = "openclaw",
                body = incomingLastAssistantText.replace('\n', ' ').take(120),
                deeplink = "openclaw_chat",
            )
        }
    }

    private fun speakLatestAssistantIfNeeded() {
        // No more "OPENCLAW_TALK panel" guard — the talk panel is gone, and
        // we now auto-speak whenever the user's enabled it globally and the
        // chat panel is the active surface (avoid speaking while scanning QR
        // or buried in settings).
        if (!state.voiceEnabled || !openClawSpeakNextAssistant) return
        if (state.panel != Panel.OPENCLAW_CHAT) return
        val msg = state.chatMessages.lastOrNull { it.role == "assistant" && it.text.isNotBlank() } ?: return
        val key = "${msg.timestamp}:${msg.text.hashCode()}"
        if (key == openClawLastSpokenKey) return
        val apiKey = voicePrefs.elevenlabsKey
        if (apiKey.isNullOrBlank()) {
            toastFail("voice: set elevenlabs key in settings → voice")
            openClawSpeakNextAssistant = false
            return
        }
        openClawLastSpokenKey = key
        openClawSpeakNextAssistant = false
        // Cancel any prior in-flight TTS download / playback before starting a
        // new one — back-to-back assistant messages shouldn't stack audio.
        cancelOpenClawSpeech()
        val cleanText = stripMarkdownForTts(msg.text)
        if (cleanText.isBlank()) return
        val outFile = File(File(cacheDir, "openclaw-voice").apply { mkdirs() }, "assistant.mp3")
        openClawTtsCall = com.r1.launcher.voice.ElevenLabsTtsClient.synthesize(
            text = cleanText,
            apiKey = apiKey,
            voiceId = voicePrefs.effectiveVoiceId(),
            model = voicePrefs.model,
            tuning = voicePrefs.tuning(),
            outFile = outFile,
        ) { mp3Bytes, err ->
            openClawTtsCall = null
            if (err == "canceled") return@synthesize  // user-initiated, silent
            if (err != null || mp3Bytes == null) {
                toastFail("voice: ${err ?: "no audio"}")
                return@synthesize
            }
            playOpenClawSpeech(mp3Bytes)
        }
    }

    /** Cancel any in-flight TTS download AND stop current playback. Safe to
     *  call when nothing is happening. Used for: back-to-back assistant
     *  messages (stack-prevent), session close, and the interrupt-on-record
     *  path in startVoiceCapture (so press-to-talk over a playing reply
     *  immediately silences it instead of waiting for the audio to finish).
     *  Also tears down the streaming-TTS pipeline (chunk calls + slot map +
     *  per-turn offsets) so a new turn can start clean. */
    private fun cancelOpenClawSpeech() {
        runCatching { openClawTtsCall?.cancel() }
        openClawTtsCall = null
        runCatching { openClawSpeechPlayer?.stop() }
        runCatching { openClawSpeechPlayer?.release() }
        openClawSpeechPlayer = null
        runCatching { openClawSpeechCurrentFile?.delete() }
        openClawSpeechCurrentFile = null

        // Streaming-TTS teardown: bumping turnId invalidates any in-flight
        // chunk callbacks that race past the cancel(). Cancel HTTP, drop
        // queued slots, delete leftover chunk MP3s.
        openClawStreamingTtsTurnId++
        openClawTtsChunkCalls.forEach { runCatching { it.cancel() } }
        openClawTtsChunkCalls.clear()
        openClawSpeechSlots.values.forEach { f -> f?.let { runCatching { it.delete() } } }
        openClawSpeechSlots.clear()
        // Belt-and-suspenders: any stream-*.mp3 still in cache (e.g., from a
        // prior process kill where onDestroy didn't run) gets reaped here.
        runCatching {
            File(cacheDir, "openclaw-voice")
                .listFiles { f -> f.name.startsWith("stream-") }
                ?.forEach { runCatching { it.delete() } }
        }
        openClawSpeechIssuedSeq = 0
        openClawSpeechNextToPlay = 1
        openClawSpeechPlaying = false; state.openClawSpeaking = false
        openClawStreamingSpokenOffset = 0
        openClawStreamingTtsActive = false
    }

    /** Strip Markdown markers so ElevenLabs reads natural prose, not
     *  "asterisk asterisk bold". Conservative — keeps content, drops
     *  formatting glyphs. Order matters: more specific patterns first. */
    private fun stripMarkdownForTts(input: String): String {
        var s = input
        // <think>…</think> blocks — reasoning is meta, never speak it. Cheap
        // belt-and-suspenders alongside the Hermes streaming splitter; needed
        // for the one-shot path and for any other source that hands raw text
        // to TTS (e.g. OpenClaw assistant replies with rare embedded tags).
        s = s.replace(Regex("(?s)<think>.*?</think>"), "")
        // Code fences ```lang ... ``` — keep contents, drop fences.
        s = s.replace(Regex("```[a-zA-Z0-9_+-]*\\n?"), "")
        s = s.replace("```", "")
        // Inline code `foo` → foo
        s = s.replace(Regex("`([^`\\n]+)`"), "$1")
        // Image ![alt](url) → alt
        s = s.replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        // Link [text](url) → text
        s = s.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        // Bold **foo** / __foo__ → foo
        s = s.replace(Regex("\\*\\*([^*\\n]+?)\\*\\*"), "$1")
        s = s.replace(Regex("__([^_\\n]+?)__"), "$1")
        // Italic *foo* / _foo_ — guarded so bullet "* item" at line start is left
        // alone; the bullet rule below removes the marker.
        s = s.replace(Regex("(?<![*\\s])\\*([^*\\n]+?)\\*(?!\\*)"), "$1")
        s = s.replace(Regex("(?<![_\\w])_([^_\\n]+?)_(?!\\w)"), "$1")
        // Strikethrough ~~foo~~ → foo
        s = s.replace(Regex("~~([^~\\n]+)~~"), "$1")
        // ATX headings "# Foo" → "Foo"
        s = s.replace(Regex("(?m)^\\s*#{1,6}\\s+"), "")
        // Blockquote "> foo" → "foo"
        s = s.replace(Regex("(?m)^\\s*>\\s?"), "")
        // List bullets "- foo" / "* foo" / "+ foo" → "foo"
        s = s.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        // Collapse runaway whitespace introduced by stripped markers.
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        s = s.replace(Regex(" {2,}"), " ")
        return s.trim()
    }

    /** Find a sentence-boundary split point in `tail`. Returns the index
     *  AFTER the boundary (so substring(0, idx) is ready to speak), or 0
     *  if nothing is ready.
     *
     *  When `firstChunk == true` (no chunks emitted for this run yet),
     *  return the FIRST qualifying boundary with a lower minChunk — gets
     *  audio playing sooner. Otherwise return the LAST qualifying boundary
     *  with a higher minChunk — maximizes prosody quality on subsequent
     *  chunks.
     *
     *  Boundaries: `.`, `!`, `?` followed by whitespace AND preceded by a
     *  letter (skips numbered-list markers `1.`, `2.`, decimals `3.14`);
     *  any `\n\n`. Force-flush at MAX_TAIL on last whitespace if no
     *  qualifying boundary exists yet (code, lists, abbrev-heavy prose). */
    private fun findStreamingSplitPoint(tail: String, firstChunk: Boolean): Int {
        if (tail.isEmpty()) return 0
        val minChunk = if (firstChunk) 16 else 32
        val maxTail = if (firstChunk) 160 else 240
        var firstBoundary = 0
        var lastBoundary = 0
        var i = 0
        while (i < tail.length) {
            val c = tail[i]
            var here = 0
            if ((c == '.' || c == '!' || c == '?') && i + 1 < tail.length) {
                val prev = if (i > 0) tail[i - 1] else ' '
                if (tail[i + 1].isWhitespace() && prev.isLetter() && i + 1 >= minChunk) {
                    here = i + 1
                }
            } else if (c == '\n' && i + 1 < tail.length && tail[i + 1] == '\n' && i + 2 >= minChunk) {
                here = i + 2
            }
            if (here > 0) {
                if (firstBoundary == 0) firstBoundary = here
                lastBoundary = here
                if (firstChunk) return here  // emit ASAP for the opening
            }
            i++
        }
        if (lastBoundary > 0) return lastBoundary
        if (tail.length >= maxTail) {
            val cap = minOf(tail.length, maxTail)
            val ws = tail.substring(0, cap).lastIndexOfAny(charArrayOf(' ', '\n', '\t'))
            if (ws >= minChunk) return ws + 1
        }
        return 0
    }

    /** Look at the cumulative `chatStreamingText`, slice off any newly-
     *  completed sentences past `openClawStreamingSpokenOffset`, and
     *  enqueue them for ElevenLabs synth. Idempotent and turn-scoped.
     *  Stays a no-op when ElevenLabs key is missing — the post-stream
     *  fallback then surfaces the toast (don't double-error). */
    private fun maybeEmitStreamingTtsChunk() {
        if (!openClawStreamingTtsActive && !openClawSpeakNextAssistant) {
            android.util.Log.d("StreamingTts", "skip: active=$openClawStreamingTtsActive arm=$openClawSpeakNextAssistant")
            return
        }
        if (state.panel != Panel.OPENCLAW_CHAT || !state.voiceEnabled) {
            android.util.Log.d("StreamingTts", "skip: panel=${state.panel} voiceEnabled=${state.voiceEnabled}")
            return
        }
        if (voicePrefs.elevenlabsKey.isNullOrBlank()) {
            android.util.Log.d("StreamingTts", "skip: elevenlabs key missing")
            return
        }
        val full = state.chatStreamingText
        if (full.length <= openClawStreamingSpokenOffset) return
        val tail = full.substring(openClawStreamingSpokenOffset)
        // First chunk uses a faster, more permissive splitter so audio
        // starts as soon as possible. Subsequent chunks pick the last
        // boundary in the tail for smoother prosody.
        val firstChunk = openClawSpeechIssuedSeq == 0
        val split = findStreamingSplitPoint(tail, firstChunk)
        if (split <= 0) {
            android.util.Log.d("StreamingTts", "no boundary in tail (len=${tail.length}, first=$firstChunk)")
            return
        }
        val chunk = tail.substring(0, split).trim()
        openClawStreamingSpokenOffset += split
        if (chunk.isEmpty()) return
        openClawStreamingTtsActive = true
        openClawSpeakNextAssistant = false
        android.util.Log.i("StreamingTts", "emit chunk: '${chunk.take(60)}...' (len=${chunk.length})")
        enqueueStreamingTtsChunk(chunk)
    }

    /** Flush any non-empty residue past `openClawStreamingSpokenOffset` —
     *  for replies that ended without a sentence terminator (one-line
     *  numeric answers, raw lists, model truncation). Called from
     *  onChatTerminal, after the last delta has landed. Only runs if
     *  streaming TTS already claimed this run (otherwise the post-stream
     *  one-shot will speak the full message). */
    private fun flushStreamingTtsTail() {
        if (state.panel != Panel.OPENCLAW_CHAT || !state.voiceEnabled) return
        if (!openClawStreamingTtsActive) return  // streaming never started for this run
        if (voicePrefs.elevenlabsKey.isNullOrBlank()) return
        val full = state.chatStreamingText
        if (full.length <= openClawStreamingSpokenOffset) return
        val tail = full.substring(openClawStreamingSpokenOffset).trim()
        openClawStreamingSpokenOffset = full.length
        if (tail.isEmpty()) return
        enqueueStreamingTtsChunk(tail)
    }

    private fun enqueueStreamingTtsChunk(chunk: String) {
        val apiKey = voicePrefs.elevenlabsKey
        if (apiKey.isNullOrBlank()) return
        val cleanChunk = stripMarkdownForTts(chunk)
        if (cleanChunk.isBlank()) return  // chunk was pure markdown decoration
        val turnId = openClawStreamingTtsTurnId
        val seq = ++openClawSpeechIssuedSeq
        val outFile = File(
            File(cacheDir, "openclaw-voice").apply { mkdirs() },
            "stream-$turnId-$seq.mp3",
        )
        val call = com.r1.launcher.voice.ElevenLabsTtsClient.synthesize(
            text = cleanChunk,
            apiKey = apiKey,
            voiceId = voicePrefs.effectiveVoiceId(),
            model = voicePrefs.model,
            tuning = voicePrefs.tuning(),
            outFile = outFile,
        ) { _, err ->
            // synthesize callbacks come back on main via its internal Handler.
            if (turnId != openClawStreamingTtsTurnId) {
                android.util.Log.d("StreamingTts", "stale chunk seq=$seq (turn $turnId vs ${openClawStreamingTtsTurnId})")
                runCatching { outFile.delete() }
                return@synthesize
            }
            if (err != null) {
                android.util.Log.w("StreamingTts", "chunk seq=$seq err=$err")
                openClawSpeechSlots[seq] = null
                runCatching { outFile.delete() }
            } else {
                android.util.Log.i("StreamingTts", "chunk seq=$seq ready (${outFile.length()} bytes)")
                openClawSpeechSlots[seq] = outFile
            }
            drainStreamingSpeechQueue()
        }
        if (call != null) {
            if (turnId != openClawStreamingTtsTurnId) {
                runCatching { call.cancel() }
            } else {
                openClawTtsChunkCalls.add(call)
            }
        }
    }

    /** If the next-to-play slot is filled and nothing is currently playing,
     *  start playback. Skips errored slots. Re-fires from each MediaPlayer's
     *  onCompletion to chain the queue. Main thread only. */
    private fun drainStreamingSpeechQueue() {
        while (true) {
            if (openClawSpeechPlaying) return
            val next = openClawSpeechNextToPlay
            if (!openClawSpeechSlots.containsKey(next)) return
            val file = openClawSpeechSlots.remove(next)
            openClawSpeechNextToPlay = next + 1
            if (file == null) continue  // errored slot — skip
            playStreamingSpeechFile(file)
            return  // playStreamingSpeechFile sets playing = true
        }
    }

    private fun playStreamingSpeechFile(file: File) {
        android.util.Log.i("StreamingTts", "play ${file.name} (${file.length()} bytes)")
        runCatching { openClawSpeechPlayer?.stop() }
        runCatching { openClawSpeechPlayer?.release() }
        openClawSpeechPlayer = null
        // If cancel runs mid-playback, this file isn't in the slots map
        // anymore — track it so cancelOpenClawSpeech can delete it.
        runCatching { openClawSpeechCurrentFile?.delete() }
        openClawSpeechCurrentFile = file
        val turnId = openClawStreamingTtsTurnId
        runCatching {
            openClawSpeechPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener { mp ->
                    runCatching { mp.release() }
                    if (openClawSpeechPlayer === mp) openClawSpeechPlayer = null
                    runCatching { file.delete() }
                    if (openClawSpeechCurrentFile === file) openClawSpeechCurrentFile = null
                    if (turnId != openClawStreamingTtsTurnId) {
                        openClawSpeechPlaying = false; state.openClawSpeaking = false
                        return@setOnCompletionListener
                    }
                    openClawSpeechPlaying = false; state.openClawSpeaking = false
                    drainStreamingSpeechQueue()
                }
                setOnErrorListener { mp, _, _ ->
                    runCatching { mp.release() }
                    if (openClawSpeechPlayer === mp) openClawSpeechPlayer = null
                    runCatching { file.delete() }
                    if (openClawSpeechCurrentFile === file) openClawSpeechCurrentFile = null
                    openClawSpeechPlaying = false; state.openClawSpeaking = false
                    if (turnId == openClawStreamingTtsTurnId) drainStreamingSpeechQueue()
                    true
                }
                // Async prepare — a synchronous prepare() here parses the MP3
                // header + spins up the codec on the main thread, repeated once
                // per streamed sentence while the chat panel animates. start()
                // fires from onPrepared instead. Gate flags are set below before
                // prepareAsync() returns so the drain loop can't double-start a
                // second player while this one prepares.
                setOnPreparedListener { mp ->
                    mp.start()
                    voiceSpeakingStartedAtMs = System.currentTimeMillis()
                }
                prepareAsync()
            }
            openClawSpeechPlaying = true; state.openClawSpeaking = true
        }.onFailure {
            openClawSpeechPlaying = false; state.openClawSpeaking = false
            runCatching { file.delete() }
            if (openClawSpeechCurrentFile === file) openClawSpeechCurrentFile = null
        }
    }

    /** Play TTS audio bytes (MP3 from ElevenLabs Flash v2.5) via MediaPlayer.
     *  MediaPlayer handles MP3 natively — no WAV header normalization needed
     *  like the prior Whisper/Sherpa pipelines. */
    private fun playOpenClawSpeech(audioBytes: ByteArray) {
        runCatching {
            // Don't force MEDIA volume to max — that ignored the user's volume
            // setting and blasted every TTS reply. Playback still routes through
            // USAGE_MEDIA → STREAM_MUSIC, so the existing Settings → Sound →
            // Volume slider directly controls reply loudness.
            val dir = File(cacheDir, "openclaw-voice").apply { mkdirs() }
            val out = File(dir, "assistant.mp3")
            out.writeBytes(audioBytes)
            runCatching { openClawSpeechPlayer?.stop() }
            runCatching { openClawSpeechPlayer?.release() }
            openClawSpeechPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(out.absolutePath)
                setOnCompletionListener {
                    runCatching { it.release() }
                    if (openClawSpeechPlayer === it) openClawSpeechPlayer = null
                }
                setOnErrorListener { mp, _, _ ->
                    runCatching { mp.release() }
                    if (openClawSpeechPlayer === mp) openClawSpeechPlayer = null
                    toastFail("voice playback failed")
                    true
                }
                prepare()
                start()
            }
        }.onFailure {
            toastFail("voice playback: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun openClawCloseSessionInternal() {
        runCatching { voiceCapture?.close() }
        voiceCapture = null
        runCatching { voiceSession?.cancel() }
        voiceSession = null
        voiceSink = null
        // Tears down both the legacy one-shot TTS and the streaming-TTS
        // pipeline (chunk HTTP calls + queued MP3 files + per-turn offsets).
        cancelOpenClawSpeech()
        runCatching { openClawSession?.stop() }
        openClawSession = null

        state.chatRecording = false
        state.chatBusy = false
        state.chatInputLevel = 0
        state.chatPartialText = ""
        state.chatStreamingText = ""
        // Reset liveness — the send/record guards read chatStatus, and a stale
        // "live" left here would let a send/PTT fire against the torn-down
        // session (IllegalStateException("not connected") + an opened mic).
        state.chatStatus = "idle"
        state.chatPendingRunIds.clear()
        openClawSpeakNextAssistant = false
        // Drop any pending clock-screen talk-shortcut arm — the session it was
        // waiting on is gone.
        pendingChatVoiceArm = false
    }

    private fun seedSettingsLevels() {
        val brightness = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(state.brightnessLevel)
        state.brightnessLevel = brightness.coerceIn(1, 255)

        audioManager?.let { am ->
            state.volumeMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            state.volumeLevel = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                .coerceIn(0, state.volumeMax)
        }
        // UI feedback volume comes from our own pref — independent of STREAM_MUSIC.
        state.uiVolumeMax = com.r1.launcher.sound.SoundPrefs.MAX_UI_LEVEL
        state.uiVolumeLevel = soundPrefs.uiVolumeLevel
        state.uiSoundEnabled = soundPrefs.uiSoundEnabled
    }

    private fun ensureWriteSettingsGrant(): Boolean {
        if (Settings.System.canWrite(this)) return true
        toast("Grant 'Modify system settings' for R1 Home")
        runCatching {
            val i = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:$packageName"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }
        return false
    }

    override fun setBrightness(level: Int) {
        val clamped = level.coerceIn(1, 255)
        runCatching {
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                clamped,
            )
        }
        // Apply to this window too, so the change is visible immediately even
        // before the system broadcasts the new value to other windows.
        val attrs = window.attributes
        attrs.screenBrightness = clamped / 255f
        window.attributes = attrs
    }

    override fun setVolume(level: Int) {
        val am = audioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        runCatching {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, max), 0)
        }
    }

    override fun setUiVolume(level: Int) {
        val clamped = level.coerceIn(0, com.r1.launcher.sound.SoundPrefs.MAX_UI_LEVEL)
        state.uiVolumeLevel = clamped
        soundPrefs.uiVolumeLevel = clamped
        rebuildTone()
    }

    override fun toggleUiSoundEnabled(enabled: Boolean) {
        state.uiSoundEnabled = enabled
        soundPrefs.uiSoundEnabled = enabled
        rebuildTone()
    }

    override fun toggleHaptics(enabled: Boolean) {
        state.hapticsEnabled = enabled
        hapticPrefs.enabled = enabled
        applySystemHaptics(enabled)
        if (enabled) haptics.click()
    }

    /** Mirror our toggle into the framework's system-wide haptic settings.
     *  Without this, Android's PhoneWindowManager fires a vibrator tick on
     *  every KEYCODE_VOLUME_UP/DOWN — which is the wheel-scroll buzz the user
     *  sees in the apps list even though the launcher itself emits nothing. */
    private fun applySystemHaptics(enabled: Boolean) {
        val v = if (enabled) 1 else 0
        runCatching {
            Settings.System.putInt(contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, v)
        }
        runCatching {
            // KEYBOARD_VIBRATION_ENABLED — added in API 35 as a per-channel
            // toggle for the on-screen keyboard, but the framework also honors
            // it as a master gate for keyboard-class haptics. Constant isn't
            // exposed in our compile SDK, so reference the key by string.
            Settings.System.putInt(contentResolver, "keyboard_vibration_enabled", v)
        }
    }

    override fun lockScreen() {
        val ok = PowerService.lockScreen()
        if (!ok) toast("Enable Accessibility → R1 Launcher to lock screen")
    }

    override fun checkForUpdate() {
        OTAUpdater.checkForUpdates(this, state, forcePrompt = true) { msg ->
            toast(msg)
        }
    }

    override fun onOnboardingDone() {
        com.r1.launcher.onboarding.OnboardingPrefs.markDone(this)
        state.isOnboarding = false
        state.goHome()
    }

    override fun setLanguage(code: String) {
        val prefs = com.r1.launcher.locale.LocalePrefs.get(this)
        if (prefs.language == code && prefs.picked) return
        prefs.language = code
        prefs.picked = true
        // recreate() rebuilds attachBaseContext → applyLocale → all stringResource
        // lookups + LocalLayoutDirection re-evaluate against the new locale.
        recreate()
    }

    override fun openAirplaneSettings() {
        val actions = listOf(
            Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            Settings.ACTION_WIRELESS_SETTINGS,
            Settings.ACTION_SETTINGS,
        )
        for (a in actions) {
            runCatching {
                val i = Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (i.resolveActivity(packageManager) != null) {
                    startActivity(i)
                    state.back()
                    return
                }
            }
        }
        toastFail("Settings UI unavailable")
        state.back()
    }

    override fun openDateSettings() {
        runCatching {
            val i = Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(packageManager) != null) {
                startActivity(i)
                state.back()
                return
            }
        }
        toastFail("Date settings unavailable")
        state.back()
    }


    @Suppress("DEPRECATION")
    override fun toggleWifi(enable: Boolean) {
        state.wifiEnabled = enable
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        Thread {
            // Try framework API first; ignore the boolean (it lies on API 29+).
            runCatching { wm?.setWifiEnabled(enable) }
            Thread.sleep(400)
            val applied = runCatching { wm?.isWifiEnabled == enable }.getOrDefault(false)
            android.util.Log.d("LauncherActivity", "toggleWifi($enable) direct applied=$applied")
            val ok = applied || sendToCarroot(
                if (enable) "cmd wifi set-wifi-enabled enabled" else "cmd wifi set-wifi-enabled disabled"
            )
            android.util.Log.d("LauncherActivity", "toggleWifi($enable) ok=$ok (applied=$applied)")
            if (!ok) {
                ui.post {
                    toastFail("Wi-Fi toggle failed")
                    refreshNetwork()
                }
                return@Thread
            }
            Thread.sleep(1500)
            ui.post { refreshNetwork() }
            Thread.sleep(2500)
            ui.post { refreshNetwork() }
        }.start()
    }

    @Suppress("DEPRECATION")
    override fun toggleCellular(enable: Boolean) {
        state.cellularOn = enable
        if (!enable) state.networkType = ""
        Thread {
            // Try framework API first — silently no-ops without MODIFY_PHONE_STATE,
            // so we must verify the read-back before trusting it.
            runCatching { telephony?.setDataEnabled(enable) }
            Thread.sleep(400)
            val applied = runCatching { telephony?.isDataEnabled == enable }.getOrDefault(false)
            android.util.Log.d("LauncherActivity", "toggleCellular($enable) direct applied=$applied")
            // `svc data enable/disable` is a no-op on this build — modem state doesn't follow.
            // Toggling Settings.Global.mobile_data flips the framework setting which the
            // TelephonyController watches, and the modem deregisters/reattaches accordingly.
            val ok = applied || sendToCarroot(if (enable) "settings put global mobile_data 1" else "settings put global mobile_data 0")
            android.util.Log.d("LauncherActivity", "toggleCellular($enable) ok=$ok (applied=$applied)")
            if (!ok) {
                ui.post {
                    toastFail("Cellular toggle failed")
                    refreshSim()
                }
                return@Thread
            }
            Thread.sleep(1500)
            ui.post { refreshSim() }
            Thread.sleep(2500)
            ui.post { refreshSim() }
        }.start()
    }

    override fun startWifiScan() {
        state.wifiScanResults.clear()
        state.wifiScanResults.add("Scanning...")
        Thread {
            if (sendToCarroot("cmd wifi start-scan")) {
                Thread.sleep(2000)
                sendToCarroot("cmd wifi list-scan-results > /data/local/tmp/wifi.txt && chmod 666 /data/local/tmp/wifi.txt")
                Thread.sleep(500)
                try {
                    val lines = java.io.File("/data/local/tmp/wifi.txt").readLines()
                    val ssids = mutableSetOf<String>()
                    for (i in 1 until lines.size) {
                        val line = lines[i].trim()
                        if (line.isEmpty()) continue
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size >= 6) {
                            val ssid = parts.drop(4).dropLast(1).joinToString(" ").trim()
                            if (ssid.isNotBlank()) ssids.add(ssid)
                        }
                    }
                    ui.post {
                        state.wifiScanResults.clear()
                        if (ssids.isEmpty()) {
                            state.wifiScanResults.add("No networks found")
                        } else {
                            state.wifiScanResults.addAll(ssids.sorted())
                        }
                    }
                } catch (e: Exception) {
                    ui.post {
                        state.wifiScanResults.clear()
                        state.wifiScanResults.add("Error reading scan")
                    }
                }
            } else {
                ui.post {
                    state.wifiScanResults.clear()
                    state.wifiScanResults.add("Root shell unavailable")
                }
            }
        }.start()
    }

    override fun connectToWifi(ssid: String, pass: String) {
        if (state.isOnboarding) {
            state.advanceOnboarding()
        } else {
            state.back()
        }
        toast("Connecting to $ssid...")
        Thread {
            // shellQuote: SSID comes from `cmd wifi list-scan-results` which
            // returns whatever local APs broadcast — a nearby attacker can
            // plant an SSID like `$(reboot)` and bypass the prior `"`-only
            // escape (carroot's outer shell expanded $() before
            // `cmd wifi connect-network` ever ran).
            val cmd = "cmd wifi connect-network ${shellQuote(ssid)} wpa2 ${shellQuote(pass)}"
            sendToCarroot(cmd)
            Thread.sleep(2000)
            ui.post { refreshNetwork() }
        }.start()
    }

    override fun toggleWifiShare(enable: Boolean) {
        wifiShareToggleInFlight = true
        state.wifiShareEnabled = enable
        if (!enable) {
            ui.removeCallbacks(wifiShareTimerRunnable)
            ui.removeCallbacks(wifiShareCountdownRunnable)
            ui.removeCallbacks(wifiShareClientPollRunnable)
            state.wifiShareTimerRemainingSec = 0
            state.wifiShareConnectedClients.clear()
        }
        Thread {
            val ssid = state.wifiShareSsid
            val pass = state.wifiSharePassword
            // Single one-shot form on this build: `cmd wifi start-softap <ssid> wpa2 <pass>`.
            // Capture stdout/stderr so we can surface real failure reasons.
            // shellQuote: SSID and password are user-configured locally and
            // less attacker-influenced than scan-result SSIDs, but apply the
            // same safe-quoting for consistency with [connectToWifi].
            val cmdOut = "/data/local/tmp/softap_cmd.txt"
            val cmd = if (enable) {
                "cmd wifi start-softap ${shellQuote(ssid)} wpa2 ${shellQuote(pass)} > $cmdOut 2>&1; chmod 666 $cmdOut"
            } else {
                "cmd wifi stop-softap > $cmdOut 2>&1; chmod 666 $cmdOut"
            }
            sendToCarroot(cmd)
            // The cmd-line tool tails state events for ~3s on success. Verify the
            // ap0 interface — that's the authoritative "is the radio actually up?"
            // signal. Retry up to ~6s because softap brings down STA first on MTK.
            var applied = false
            for (attempt in 0..5) {
                Thread.sleep(1000)
                applied = isWifiShareEnabled() == enable
                android.util.Log.d("LauncherActivity", "toggleWifiShare($enable) attempt=$attempt applied=$applied")
                if (applied) break
            }
            if (!applied) {
                val out = runCatching { java.io.File(cmdOut).readText() }.getOrDefault("")
                android.util.Log.w("LauncherActivity", "toggleWifiShare($enable) FAILED. cmd output:\n$out")
                val reason = out.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.contains("fail", true) || it.contains("error", true) || it.contains("denied", true) }
                    ?: out.lineSequence().firstOrNull { it.isNotBlank() }
                    ?: "no response"
                ui.post {
                    state.wifiShareEnabled = !enable
                    toastFail((if (enable) "Hotspot failed: " else "Stop failed: ") + reason.take(80))
                }
                wifiShareToggleInFlight = false
                return@Thread
            }
            ui.post {
                state.wifiShareEnabled = enable
                if (enable) {
                    armWifiShareTimer()
                    ui.removeCallbacks(wifiShareClientPollRunnable)
                    ui.post(wifiShareClientPollRunnable)
                }
            }
            wifiShareToggleInFlight = false
        }.start()
    }

    private var webServer: com.r1.launcher.web.R1WebServer? = null

    override fun toggleWebServer(enable: Boolean) {
        android.util.Log.i("LauncherActivity", "toggleWebServer($enable) entry, current=${webServer != null}")
        if (enable && webServer != null) return
        if (!enable && webServer == null) {
            state.webServerEnabled = false
            return
        }
        if (enable) {
            val srv = com.r1.launcher.web.R1WebServer(this, this, state)
            webServer = srv
            Thread {
                runCatching { srv.startServer() }
                    .onFailure { android.util.Log.e("LauncherActivity", "startServer failed", it) }
                ui.post {
                    state.webServerIp = discoverLocalIp()
                    // The token still gates the WS handshake — exposed so
                    // existing legacy `?t=` URLs keep working. The new UX
                    // is the 4-digit passcode exchange at /api/auth, which
                    // the SPA's unlock overlay handles.
                    state.webServerToken = com.r1.launcher.notifications.NotifPrefs.get(this).panelToken
                    state.webServerEnabled = true
                    android.util.Log.i("LauncherActivity", "web server up at ${state.webServerIp}:${state.webServerPort} (passcode gated)")
                    toast("remote panel: http://${state.webServerIp}:${state.webServerPort} · passcode ${state.panelPasscode}")
                }
            }.start()
        } else {
            val srv = webServer
            webServer = null
            state.webServerEnabled = false
            state.webServerToken = ""
            state.webServerIp = ""
            Thread { runCatching { srv?.stopServer() } }.start()
        }
    }

    override fun panelPasscodeSave(passcode: String) {
        if (passcode.length != 4 || !passcode.all { it.isDigit() }) {
            toast("passcode must be 4 digits")
            return
        }
        val prefs = com.r1.launcher.notifications.NotifPrefs.get(this)
        prefs.panelPasscode = passcode
        state.panelPasscode = passcode
        // Rotating the token kicks out any already-authenticated browsers —
        // a passcode change is effectively a logout. Re-mirror so the
        // Settings subtitle picks up the new token if the server is on.
        val newToken = prefs.regeneratePanelToken()
        if (state.webServerEnabled) {
            state.webServerToken = newToken
        }
        toast("panel passcode updated")
    }

    /**
     * Pick the IP that's actually reachable from a phone/PC on the same LAN.
     * Priority: hotspot (ap0) then Wi-Fi (wlan) then Ethernet (eth) then anything else.
     * Skips cellular modem interfaces (ccmni, rmnet, ppp) — they sit on the
     * carrier's CGN and are not routable from your home network.
     */
    private fun discoverLocalIp(): String {
        val ifaces = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
        }.getOrDefault(emptyList())

        fun priority(name: String): Int = when {
            name.startsWith("ap0") -> 0
            name.startsWith("wlan") -> 1
            name.startsWith("eth") -> 2
            name.startsWith("ccmni") || name.startsWith("rmnet") || name.startsWith("ppp") -> 99
            else -> 50
        }

        val ordered = ifaces
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { priority(it.name) }

        for (iface in ordered) {
            if (priority(iface.name) >= 99) continue // never use cellular
            for (addr in iface.inetAddresses) {
                if (addr.isLoopbackAddress || addr is java.net.Inet6Address) continue
                val host = addr.hostAddress ?: continue
                if (host.startsWith("127.")) continue
                return host
            }
        }
        return "0.0.0.0"
    }

    override fun wifiShareSaveEdit() {
        val target = state.wifiShareEditTarget
        val input = state.wifiShareEditInput.trim()
        if (target == WifiShareEditTarget.SSID && input.isEmpty()) {
            toastFail("name can't be empty")
            return
        }
        if (target == WifiShareEditTarget.PASSWORD && input.length < 8) {
            toastFail("password needs 8+ chars")
            return
        }
        when (target) {
            WifiShareEditTarget.SSID -> {
                state.wifiShareSsid = input
                wifiSharePrefs.ssid = input
            }
            WifiShareEditTarget.PASSWORD -> {
                state.wifiSharePassword = input
                wifiSharePrefs.password = input
            }
        }
        state.back()
        // If the hotspot is currently up, cycle it so the new config takes effect.
        if (state.wifiShareEnabled) {
            Thread {
                ui.post { toggleWifiShare(false) }
                Thread.sleep(1200)
                ui.post { toggleWifiShare(true) }
            }.start()
        }
    }

    override fun wifiShareCycleTimer() {
        val choices = intArrayOf(0, 15, 30, 60, 120)
        val cur = choices.indexOf(state.wifiShareTimerMinutes).let { if (it < 0) 0 else it }
        val next = choices[(cur + 1) % choices.size]
        state.wifiShareTimerMinutes = next
        wifiSharePrefs.timerMinutes = next
        if (state.wifiShareEnabled) armWifiShareTimer()
    }

    private fun armWifiShareTimer() {
        ui.removeCallbacks(wifiShareTimerRunnable)
        ui.removeCallbacks(wifiShareCountdownRunnable)
        val mins = state.wifiShareTimerMinutes
        if (mins <= 0) {
            state.wifiShareTimerRemainingSec = 0
            return
        }
        val durationMs = mins * 60_000L
        wifiShareTimerEndMs = System.currentTimeMillis() + durationMs
        state.wifiShareTimerRemainingSec = (durationMs / 1000L).toInt()
        ui.postDelayed(wifiShareTimerRunnable, durationMs)
        ui.post(wifiShareCountdownRunnable)
    }

    private fun isWifiShareEnabled(): Boolean {
        // Authoritative signal: kernel interface state. ap0 is the SoftAp iface on
        // this build. When softap is up, `ip link show ap0` reports `state UP` and
        // the UP,LOWER_UP flags; when down (or before first start), DOWN or absent.
        // dumpsys wifi here doesn't include the SoftApState lines we used to grep.
        val out = "/data/local/tmp/softap_iface.txt"
        if (!sendToCarroot("ip link show ap0 > $out 2>&1; chmod 666 $out")) return false
        return runCatching {
            val txt = java.io.File(out).readText()
            txt.contains("state UP") && txt.contains("LOWER_UP")
        }.getOrDefault(false)
    }

    private fun pollWifiShareClients() {
        Thread {
            val out = "/data/local/tmp/softap_clients.txt"
            // ap0 is the SoftAp interface on this build.
            // Only `ip neigh` is consulted: /proc/net/arp has no state column,
            // so it would re-include clients the kernel knows are gone but
            // hasn't GC'd yet (and on a launcher that's ALSO a wifi STA, it
            // mixes upstream-LAN hosts into the softap count). `ip neigh`
            // populates REACHABLE within one packet of the first connect,
            // which is fast enough that the prior arp-union is unnecessary.
            sendToCarroot("ip neigh show dev ap0 > $out 2>/dev/null; chmod 666 $out")
            Thread.sleep(250)
            val rawText = runCatching { java.io.File(out).readText() }.getOrDefault("")
            // Accept only states that mean "currently or recently confirmed
            // reachable". Reject:
            //   STALE       — entry past nud_stale_time, kernel hasn't probed yet;
            //                 may be a still-connected idle client OR a dead one.
            //                 We err on the side of "not counting" since the
            //                 user-visible bug is inflated counts.
            //   FAILED      — probe failed; client is gone.
            //   INCOMPLETE  — initial ARP request pending, no MAC yet.
            //   NOARP       — typically not seen for normal wifi clients.
            // PERMANENT/NOARP kept because manually-added static entries may
            // be legitimate (no harm if none exist).
            val live = setOf("REACHABLE", "DELAY", "PROBE", "PERMANENT")
            val macs = runCatching {
                val seen = linkedSetOf<String>()
                rawText.lineSequence().forEach { line ->
                    val m = Regex("lladdr ([0-9a-fA-F:]{17})\\s+(\\S+)").find(line) ?: return@forEach
                    val mac = m.groupValues[1].uppercase()
                    val state = m.groupValues[2].uppercase()
                    if (state in live && mac != "00:00:00:00:00:00") {
                        seen.add(mac)
                    }
                }
                seen.toList()
            }.getOrDefault(emptyList())
            android.util.Log.d(
                "WifiShare",
                "softap clients: count=${macs.size} macs=${macs.joinToString()} raw=${rawText.replace("\n", " | ").take(400)}",
            )
            ui.post {
                state.wifiShareConnectedClients.clear()
                state.wifiShareConnectedClients.addAll(macs)
                if (macs.isEmpty()) state.wifiShareClientsExpanded = false
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    override fun toggleBluetooth(enable: Boolean) {
        state.btOn = enable
        Thread {
            if (enable) {
                // CarrotOS ships without audio profile services enabled — the
                // build.prop flags for them are unset, so com.android.bluetooth
                // starts without A2dpService / HeadsetService, leaving the
                // headset paired but audio routing broken. Check the prop via
                // `getprop` (carroot), and only restart the BT process when
                // we actually need to flip a profile from off to on.
                val propsOut = "/data/local/tmp/bt_profile.txt"
                sendToCarroot("getprop bluetooth.profile.a2dp.source.enabled > $propsOut; chmod 666 $propsOut")
                Thread.sleep(100)
                val currentVal = runCatching { java.io.File(propsOut).readText().trim() }.getOrDefault("")
                if (currentVal != "true") {
                    sendToCarroot("setprop bluetooth.profile.a2dp.source.enabled true; setprop bluetooth.profile.hfp.ag.enabled true; setprop bluetooth.profile.avrcp.target.enabled true; am force-stop com.android.bluetooth")
                    Thread.sleep(800)
                }
            }
            // Try framework API first. BluetoothAdapter.enable()/disable() are deprecated since
            // API 33 but still work for system-signed apps; otherwise they silently no-op.
            val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
            runCatching { if (enable) adapter?.enable() else adapter?.disable() }
            Thread.sleep(800)
            val applied = runCatching { (adapter?.isEnabled == true) == enable }.getOrDefault(false)
            android.util.Log.d("LauncherActivity", "toggleBluetooth($enable) direct applied=$applied")
            val ok = applied || sendToCarroot(if (enable) "cmd bluetooth_manager enable" else "cmd bluetooth_manager disable")
            android.util.Log.d("LauncherActivity", "toggleBluetooth($enable) ok=$ok (applied=$applied)")
            if (!ok) {
                ui.post {
                    toast("Bluetooth toggle failed")
                    refreshBluetooth()
                }
                return@Thread
            }
            Thread.sleep(1500)
            ui.post { refreshBluetooth() }
            Thread.sleep(2500)
            ui.post { refreshBluetooth() }
        }.start()
    }

    @Suppress("DEPRECATION")
    override fun startBtScan() {
        if (!ensureBtScanPerm()) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        state.btDevices.clear()
        if (!adapter.isEnabled) {
            // BT is off — list stays empty; user toggles on from the page itself.
            state.btScanning = false
            return
        }
        refreshBtDevices()
        // If a discovery is already running (re-entering panel), don't double-start.
        if (runCatching { adapter.isDiscovering }.getOrDefault(false)) {
            state.btScanning = true
            return
        }
        val started = runCatching { adapter.startDiscovery() }.getOrDefault(false)
        state.btScanning = started
    }

    @Suppress("DEPRECATION")
    override fun stopBtScan() {
        runCatching { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() }
        state.btScanning = false
    }

    @Suppress("DEPRECATION")
    override fun pairBtDevice(address: String) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val dev = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return
        val bonded = dev.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED
        val connected = isBtDeviceConnected(dev)
        when {
            !bonded -> {
                runCatching { dev.createBond() }
                toast("Pairing…")
            }
            connected -> {
                toast("Disconnecting…")
                tryBtProfileActionWithRetry(dev, connect = false)
            }
            else -> {
                toast("Connecting…")
                tryBtProfileActionWithRetry(dev, connect = true)
            }
        }
    }

    // ===== Media capture (web companion only) =====

    private var recordingWatchdog: Runnable? = null

    override fun mediaCaptureScreenshot(): Result<com.r1.launcher.media.CaptureItem> {
        val r = com.r1.launcher.media.MediaCaptureManager.captureScreenshot()
        r.onSuccess { item -> webServer?.broadcastCaptureAdded(item) }
        return r
    }

    override fun mediaStartVideo(): Result<Long> {
        // RECORD_AUDIO is needed by AudioCaptureSession (mic leg). Pre-grant
        // via `adb shell pm grant com.r1.launcher android.permission.RECORD_AUDIO`
        // — see CLAUDE.md. If missing, ensureAudioPerm fires the runtime
        // dialog and we fail this attempt; the user retries after granting.
        if (!ensureAudioPerm()) {
            return Result.failure(SecurityException("record_audio_not_granted"))
        }
        val r = com.r1.launcher.media.MediaCaptureManager.startVideoRecording()
        r.onSuccess { startedAt ->
            ui.post {
                state.mediaRecording = true
                state.mediaRecordingStartedAt = startedAt
            }
            webServer?.broadcastCaptureRecording(true, startedAt)
            armRecordingWatchdog()
        }
        return r
    }

    override fun mediaStopVideo(): Result<com.r1.launcher.media.CaptureItem> {
        val r = com.r1.launcher.media.MediaCaptureManager.stopVideoRecording()
        ui.post {
            state.mediaRecording = false
            state.mediaRecordingStartedAt = 0L
        }
        webServer?.broadcastCaptureRecording(false, 0L)
        cancelRecordingWatchdog()
        r.onSuccess { item -> webServer?.broadcastCaptureAdded(item) }
        return r
    }

    override fun mediaList(limit: Int) =
        com.r1.launcher.media.MediaCaptureManager.list(limit)

    override fun mediaTotalBytes() =
        com.r1.launcher.media.MediaCaptureManager.totalBytes()

    override fun mediaCount() =
        com.r1.launcher.media.MediaCaptureManager.count()

    override fun mediaIsRecording() =
        com.r1.launcher.media.MediaCaptureManager.isRecording()

    override fun mediaDelete(name: String) =
        com.r1.launcher.media.MediaCaptureManager.delete(name)

    override fun mediaClear() =
        com.r1.launcher.media.MediaCaptureManager.clear()

    private fun armRecordingWatchdog() {
        cancelRecordingWatchdog()
        val r = Runnable {
            if (state.mediaRecording) {
                android.util.Log.w("LauncherActivity", "recording watchdog fired — forcing stop")
                // mediaStopVideo() blocks for up to ~9s (sleeps + 8s join +
                // remux). The watchdog fires on the main looper, so offload the
                // stop to a background thread — running it inline here would ANR
                // the launcher exactly when a long recording auto-stops. The
                // method already marshals its state writes back via ui.post.
                Thread { runCatching { mediaStopVideo() } }.start()
            }
        }
        recordingWatchdog = r
        ui.postDelayed(r, (com.r1.launcher.media.MediaCaptureManager.VIDEO_TIME_LIMIT_S + 5) * 1000L)
    }

    private fun cancelRecordingWatchdog() {
        recordingWatchdog?.let { ui.removeCallbacks(it) }
        recordingWatchdog = null
    }

    /** Drive A2DP/HSP transition. The proper sequence on Android 14:
     *   1. setConnectionPolicy(ALLOWED) on each profile so the stack accepts.
     *   2. profile.connect(device) on A2DP and HEADSET to establish the link.
     *   3. adapter.setActiveDevice(device, ALL) to route audio + call audio.
     *  All three are hidden APIs; require BLUETOOTH_PRIVILEGED (platform sig). */
    @Suppress("DEPRECATION")
    private fun tryBtProfileActionWithRetry(dev: android.bluetooth.BluetoothDevice, connect: Boolean) {
        val runIt = {
            // 1+2: profile-proxy reflection (sets policy then calls connect/disconnect).
            btProfileAction(dev, connect)
            // 3: route audio.
            val adapter = BluetoothAdapter.getDefaultAdapter()
            val setActiveRes = runCatching {
                adapter?.javaClass?.getMethod(
                    "setActiveDevice",
                    android.bluetooth.BluetoothDevice::class.java,
                    Int::class.javaPrimitiveType,
                )?.invoke(adapter, if (connect) dev else null, 2)
            }
            android.util.Log.i("R1Bt", "setActiveDevice(${if (connect) "dev" else "null"}, ALL)=${setActiveRes.getOrNull()} err=${setActiveRes.exceptionOrNull()?.cause?.message}")
        }
        if (btA2dpProxy != null || btHeadsetProxy != null) {
            runIt()
            return
        }
        ensureBtProxiesBound()
        ui.postDelayed({
            if (btA2dpProxy != null || btHeadsetProxy != null) {
                runIt()
            } else {
                toast("BT bind failed — toggle BT off then on")
            }
        }, 1200L)
    }

    /** Trigger audio-profile connection / disconnection on both A2DP and HEADSET
     *  proxies the same way Android Settings does: set ConnectionPolicy to
     *  ALLOWED (otherwise the stack rejects the connect request) and then call
     *  the hidden `connect()` / `disconnect()` methods. Both are `@SystemApi`,
     *  available to platform-signed apps with `BLUETOOTH_PRIVILEGED`.
     *  Per-profile failure is non-fatal — a device may only support one
     *  profile (e.g. headphones without a mic). */
    @Suppress("DEPRECATION")
    private fun btProfileAction(dev: android.bluetooth.BluetoothDevice, connect: Boolean) {
        val devClass = android.bluetooth.BluetoothDevice::class.java
        val proxies = listOfNotNull(btA2dpProxy, btHeadsetProxy)
        android.util.Log.i("R1Bt", "btProfileAction connect=$connect proxies=${proxies.size} a2dp=${btA2dpProxy != null} hs=${btHeadsetProxy != null}")
        if (proxies.isEmpty()) {
            toast("BT profile not ready, try again in 2s")
            return
        }
        var anySuccess = false
        var lastError: String? = null
        var sawPolicyFailure = false
        proxies.forEach { proxy ->
            val proxyName = proxy.javaClass.simpleName
            // Try setConnectionPolicy first (Android 12+), fall back to setPriority (older).
            val policyRes = runCatching {
                proxy.javaClass.getMethod("setConnectionPolicy", devClass, Int::class.javaPrimitiveType)
                    .invoke(proxy, dev, if (connect) 100 else 0)
            }.recoverCatching {
                proxy.javaClass.getMethod("setPriority", devClass, Int::class.javaPrimitiveType)
                    .invoke(proxy, dev, if (connect) 100 else 0)
            }
            if (policyRes.isFailure) {
                sawPolicyFailure = true
                android.util.Log.w("R1Bt", "$proxyName setConnectionPolicy failed: ${policyRes.exceptionOrNull()?.cause?.message ?: policyRes.exceptionOrNull()?.message}")
            }
            val res = runCatching {
                proxy.javaClass.getMethod(if (connect) "connect" else "disconnect", devClass)
                    .invoke(proxy, dev) as? Boolean
            }
            res.onSuccess { ok ->
                android.util.Log.i("R1Bt", "$proxyName ${if (connect) "connect" else "disconnect"} returned $ok")
                if (ok == true) anySuccess = true
            }.onFailure { t ->
                lastError = "$proxyName: ${t.cause?.message ?: t.message}"
                android.util.Log.w("R1Bt", "$proxyName ${if (connect) "connect" else "disconnect"} threw: $lastError")
            }
        }
        if (!anySuccess) {
            val why = lastError ?: if (sawPolicyFailure) "policy reject" else "no-op (already in state?)"
            toast("BT ${if (connect) "connect" else "disconnect"}: $why")
        }
    }

    override fun factoryReset() {
        // Last-line UX: a toast as the wipe broadcast goes out. The system tears the
        // process down within seconds so anything below this rarely runs.
        toast("Wiping device...")
        Thread {
            // Android 14+ uses FACTORY_RESET; older builds expect MASTER_CLEAR. Both are
            // protected broadcasts — must be sent from a privileged shell, hence carroot.
            val a14 = "am broadcast -a android.intent.action.FACTORY_RESET --receiver-foreground -p android"
            val legacy = "am broadcast -a android.intent.action.MASTER_CLEAR -p android"
            if (!sendToCarroot(a14)) {
                Thread.sleep(300)
                if (!sendToCarroot(legacy)) {
                    ui.post { toast("Factory reset failed: no root shell") }
                }
            }
        }.start()
    }

    override fun rebootDevice() {
        toast("Restarting…")
        Thread {
            // `svc power reboot` is the userspace API; falls back to the binary
            // `reboot` (toybox) which carroot can invoke as root.
            if (!sendToCarroot("svc power reboot null")) {
                Thread.sleep(200)
                if (!sendToCarroot("reboot")) {
                    ui.post { toast("Reboot failed: no root shell") }
                }
            }
        }.start()
    }

    override fun powerOffDevice() {
        toast("Powering off…")
        Thread {
            // `svc power shutdown` triggers the orderly Android shutdown
            // sequence; `reboot -p` is the busybox/toybox fallback.
            if (!sendToCarroot("svc power shutdown")) {
                Thread.sleep(200)
                if (!sendToCarroot("reboot -p")) {
                    ui.post { toast("Power off failed: no root shell") }
                }
            }
        }.start()
    }

    override fun resetCameraMotor() {
        // Uses R1Motor.calibrateMotorToHome — a deliberately-slow ladder
        // with 250 ms settles and a double-write at FACE, the same pattern
        // we know works manually. The fast chunked path used by regular
        // motor writes isn't reliable enough to recover an already-drifted
        // lens.
        toast("resetting camera…")
        com.r1.launcher.ui.calibrateMotorToHome()
    }

    private fun sendToCarroot(cmd: String): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", 1337), 1500)
            val os: OutputStream = s.getOutputStream()
            os.write((cmd + "\n").toByteArray())
            os.flush()
            // shutdownOutput signals EOF so carroot's `nc -L … sh` reads the
            // command and runs it; the prior Thread.sleep(500) was a half-second
            // main-thread hold from every UI-thread caller (OTAUpdater, isHotspotUp).
            s.shutdownOutput()
        }
        true
    } catch (_: Exception) {
        false
    }

    // --- LauncherHost: terminal panel ---

    private var terminalActiveThread: Thread? = null

    /**
     * One-shot streaming variant of [sendToCarroot]. Each carroot connection
     * gets a fresh `sh`, so cwd doesn't survive between calls — we wrap the
     * user command in `cd <cwd> ; (cmd) ; pwd > <pwdFile> ; printf SENTINEL`
     * and read the new cwd from disk after the stream ends.
     *
     * `onLine` fires on a background thread for every newline-terminated chunk
     * the shell emits. `onDone(exitCode, newCwd)` fires once after EOF.
     */
    private fun sendToCarrootStreaming(
        userCmd: String,
        cwd: String,
        onLine: (String) -> Unit,
        onDone: (exitCode: Int, newCwd: String) -> Unit,
    ): Thread {
        val t = Thread {
            val pwdFile = "/data/local/tmp/r1_term_cwd"
            val safeCwd = cwd.replace("\"", "\\\"")
            // ; not && so the user's command runs even if `cd` fails (e.g.
            // first launch where cwd doesn't exist yet). The trailing pwd
            // captures whatever directory the shell ended up in — that's how
            // `cd X` from the user persists into the next command.
            val script = "cd \"$safeCwd\" 2>/dev/null; $userCmd; ec=\$?; " +
                "pwd > $pwdFile 2>/dev/null; printf '\\n__R1_END__%s__\\n' \"\$ec\""
            var exitCode = -1
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", 1337), 1500)
                    val out = s.getOutputStream()
                    out.write((script + "\n").toByteArray())
                    out.flush()
                    s.shutdownOutput()  // signal EOF so `sh` reads the script and exits
                    val reader = s.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        val sentinel = Regex("__R1_END__(-?\\d+)__").find(line)
                        if (sentinel != null) {
                            exitCode = sentinel.groupValues[1].toIntOrNull() ?: -1
                            break
                        }
                        onLine(line)
                    }
                }
            } catch (e: Exception) {
                onLine("[r1-terminal] socket: ${e.message ?: e.javaClass.simpleName}")
            }
            val newCwd = runCatching { File(pwdFile).readText().trim() }.getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: cwd
            onDone(exitCode, newCwd)
        }
        t.start()
        return t
    }

    /** Monotonic id source for [TerminalLine] so the output list keeps stable
     *  row identity across the FIFO cap. */
    private var terminalLineSeq = 0L

    private fun appendTerminalLine(line: String) {
        state.terminalOutput.add(TerminalLine(terminalLineSeq++, line))
        while (state.terminalOutput.size > state.terminalOutputMax) {
            state.terminalOutput.removeAt(0)
        }
        // Fan out to any connected web clients so the Terminal tab mirrors
        // the on-device buffer in real time. (raw string, unchanged wire format)
        runCatching { webServer?.broadcastTerminalOutput(line, state.terminalCwd) }
    }

    override fun terminalRun(cmd: String) {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) return
        if (state.terminalBusy) {
            toast("busy")
            return
        }
        // Echo prompt + command into the buffer so the scrollback is readable.
        appendTerminalLine("${promptLabel(state.terminalCwd)} $trimmed")
        state.terminalInput = ""
        state.terminalScrollIndex = 0

        // Client-side `clear` shortcut: don't burn a carroot round-trip on it.
        if (trimmed == "clear" || trimmed == "cls") {
            state.terminalOutput.clear()
            return
        }

        // Terminal panel runs commands directly against Android's shell via
        // carroot — no more alpine chroot wrapping. Users who need a real
        // Linux env (node/npm/python/claude) open Termux.
        state.terminalBusy = true
        terminalActiveThread = sendToCarrootStreaming(
            userCmd = trimmed,
            cwd = state.terminalCwd,
            onLine = { line -> ui.post { appendTerminalLine(line) } },
            onDone = { exitCode, newCwd ->
                ui.post {
                    if (newCwd != state.terminalCwd) state.terminalCwd = newCwd
                    if (exitCode != 0 && exitCode != -1) {
                        appendTerminalLine("[exit $exitCode]")
                    }
                    state.terminalBusy = false
                    terminalActiveThread = null
                }
            },
        )
    }

    override fun terminalClear() {
        state.terminalOutput.clear()
        state.terminalScrollIndex = 0
    }

    override fun setWebTerminalEnabled(enable: Boolean) {
        state.webTerminalEnabled = enable
        toast(if (enable) "remote terminal: on (root over LAN)" else "remote terminal: off")
    }

    override fun setCaptureMicEnabled(enable: Boolean) {
        com.r1.launcher.media.MediaCapturePrefs.setMicEnabled(this, enable)
        state.captureMicEnabled = enable
        toast(if (enable) "mic in recordings: on" else "mic in recordings: off")
    }

    override fun setCapturePlaybackEnabled(enable: Boolean) {
        com.r1.launcher.media.MediaCapturePrefs.setPlaybackEnabled(this, enable)
        state.capturePlaybackEnabled = enable
        toast(if (enable) "system audio in recordings: on" else "system audio in recordings: off")
    }

    /**
     * Open an ElevenLabs Realtime STT session and start streaming mic PCM
     * into it. Single shared client across the three voice-input panels —
     * different sinks for the final transcript:
     *   CHAT:     auto-send via openClawSendText (release-to-send UX)
     *   TERMINAL: paste into terminalInput (don't auto-execute)
     *   CLAUDE:   paste into claudeInput (don't auto-execute)
     *
     * Does the audio-perm check, voice-key check, and recording-state setup;
     * also wires partial transcripts to the right state field for the UI to
     * show "listening: <live text>" while the user speaks.
     */
    private fun startVoiceCapture(sink: VoiceSink) {
        if (!ensureAudioPerm()) return
        // Mic source is single-consumer: if the meetings FGS is recording, abort.
        // Without this we'd silently get garbage frames or have the FGS lose
        // its audio path mid-meeting.
        if (transcriberBinder?.isRecording == true) {
            toastFail("stop recording first")
            return
        }
        val key = voicePrefs.elevenlabsKey
        if (key.isNullOrBlank()) {
            toast("voice: set elevenlabs key in settings → voice")
            return
        }
        if (voiceCapture != null) return // already recording
        // Interrupt any TTS reply that's mid-download or mid-playback. Two
        // wins: (1) the user can cut the assistant off to interject without
        // waiting for it to finish speaking, (2) cancelling the OkHttp call
        // mid-stream stops further bytes from arriving (saves bandwidth, may
        // also save credits depending on ElevenLabs' refund behavior for
        // cancelled streams).
        cancelOpenClawSpeech()
        cancelHermesSpeech()
        cancelTranslatorSpeech()
        voiceSink = sink
        when (sink) {
            VoiceSink.CHAT -> {
                state.chatRecording = true
                state.chatPartialText = ""
                state.chatInputLevel = 0
            }
            VoiceSink.TERMINAL -> {
                state.terminalRecording = true
                state.terminalPartial = ""
            }
            VoiceSink.HERMES_CHAT -> {
                state.hermesRecording = true
                state.hermesPartialText = ""
                state.hermesInputLevel = 0
            }
            VoiceSink.TRANSLATOR -> {
                state.translatorRecording = true
                state.translatorPartialText = ""
            }
        }
        playRecordStartTone()
        // SCO is a no-op when no BT headset is connected; when one is paired
        // the 200ms delay below gives SCO time to establish before AudioRecord opens.
        audioManager?.startBluetoothSco()

        val session = com.r1.launcher.voice.ElevenLabsRealtimeClient.open(
            apiKey = key,
            onPartial = { text ->
                ui.post {
                    when (sink) {
                        VoiceSink.CHAT -> state.chatPartialText = text
                        VoiceSink.TERMINAL -> state.terminalPartial = text
                        VoiceSink.HERMES_CHAT -> state.hermesPartialText = text
                        VoiceSink.TRANSLATOR -> state.translatorPartialText = text
                    }
                }
            },
            onCommitted = { text ->
                ui.post { handleCommittedTranscript(sink, text) }
            },
            onError = { msg ->
                ui.post {
                    toast("voice: $msg")
                    cancelVoiceCapture()
                }
            },
        )
        voiceSession = session

        val cap = com.r1.launcher.voice.StreamingAudioCapture()
        voiceCapture = cap
        // Delay mic open by ~200ms so the recording cue (record.mp3 via SoundPool
        // on USAGE_MEDIA) plays through. AudioRecord on VOICE_RECOGNITION steals
        // the audio path and silences MEDIA mid-sample, so without the delay
        // the cue is inaudible. Re-check voiceCapture inside the post — the
        // user may have released before the delay fired (very-short tap).
        ui.postDelayed({
            if (voiceCapture !== cap) return@postDelayed
            voiceMicOpenedAtMs = System.currentTimeMillis()
            cap.start(object : com.r1.launcher.voice.StreamingAudioCapture.Callback {
                override fun onPcm(chunk: ByteArray) {
                    // Half-duplex forwarding gate: while THIS sink's AI is
                    // speaking, mic frames are NOT forwarded to ElevenLabs
                    // STT. Echo through the speaker→mic loop would otherwise
                    // produce partial transcripts of the AI's own voice that
                    // look like a real user interruption and cancel our own
                    // playback (the "AI talks a little bit then stops" bug).
                    //
                    // Scoped per-sink so a stale speaking flag from a
                    // different chat surface (e.g. OpenClaw TTS that didn't
                    // clean up properly) can't silently mute a fresh Hermes
                    // session. Mic stays open the whole time so onLevel
                    // still fires for the orb's mic-level ring — only the
                    // STT pipe is muted. When TTS finishes, the speaking
                    // flag flips false and forwarding resumes naturally on
                    // the next chunk.
                    //
                    // Tradeoff: voice-driven barge-in is disabled in this
                    // mode. To interrupt, use the end-call gesture (back
                    // pill / end button) which cancels everything.
                    val speakerActive = when (sink) {
                        VoiceSink.CHAT -> state.openClawSpeaking
                        VoiceSink.HERMES_CHAT -> state.hermesSpeaking
                        VoiceSink.TERMINAL -> false
                        VoiceSink.TRANSLATOR -> state.translatorSpeaking
                    }
                    if (speakerActive) return
                    voiceSession?.sendPcm(chunk)
                }
                override fun onLevel(levelPct: Int) {
                    if (sink == VoiceSink.CHAT) state.chatInputLevel = levelPct
                    else if (sink == VoiceSink.HERMES_CHAT) state.hermesInputLevel = levelPct
                }
                override fun onError(msg: String) {
                    ui.post {
                        toast("mic: $msg")
                        cancelVoiceCapture()
                    }
                }
            })
        }, 200L)
    }

    /** Stop streaming and ask the server for a final transcript via VAD/commit.
     *  The committed_transcript callback will deliver the result, then we
     *  finalize through handleCommittedTranscript. */
    private fun stopVoiceCapture() {
        voiceCapture?.close()
        audioManager?.stopBluetoothSco()
        voiceCapture = null
        voiceSession?.finish()
        // voiceSession nulled inside handleCommittedTranscript or onError.
        // Watchdog: finish() waits for the server's committed_transcript frame
        // (the only thing that nulls voiceSession on the happy path). If the
        // network drops right after the commit write, that frame never lands
        // and the WebSocket leaks open (25s pings keep it alive) until the next
        // capture overwrites the field. Force-close it after a grace period if
        // it's still the same instance, and clear any stuck "transcribing" UI.
        val pendingSession = voiceSession
        if (pendingSession != null) {
            ui.postDelayed({
                if (voiceSession === pendingSession) {
                    android.util.Log.w("LauncherActivity", "STT commit timeout — force-closing session")
                    runCatching { pendingSession.cancel() }
                    voiceSession = null
                    state.chatTranscribing = false
                    state.hermesTranscribing = false
                    state.terminalTranscribing = false
                }
            }, 5000L)
        }
        when (voiceSink) {
            VoiceSink.CHAT -> {
                state.chatRecording = false
                state.chatInputLevel = 0
            }
            VoiceSink.TERMINAL -> state.terminalRecording = false
            VoiceSink.HERMES_CHAT -> {
                state.hermesRecording = false
                state.hermesInputLevel = 0
            }
            VoiceSink.TRANSLATOR -> {
                state.translatorRecording = false
            }
            null -> {}
        }
        playRecordStopTone()
    }

    /** Hard-cancel: drop session, no transcript expected. */
    private fun cancelVoiceCapture() {
        voiceCapture?.close()
        audioManager?.stopBluetoothSco()
        voiceCapture = null
        voiceSession?.cancel()
        voiceSession = null
        when (voiceSink) {
            VoiceSink.CHAT -> {
                state.chatRecording = false
                state.chatInputLevel = 0
                state.chatPartialText = ""
            }
            VoiceSink.TERMINAL -> {
                state.terminalRecording = false
                state.terminalPartial = ""
            }
            VoiceSink.HERMES_CHAT -> {
                state.hermesRecording = false
                state.hermesPartialText = ""
                state.hermesInputLevel = 0
            }
            VoiceSink.TRANSLATOR -> {
                state.translatorRecording = false
                state.translatorPartialText = ""
            }
            null -> {}
        }
        voiceSink = null
        voiceAutoSend = false
    }

    private fun handleCommittedTranscript(sink: VoiceSink, text: String) {
        // Server-side VAD commit means the capture is done — the user may
        // not have pressed stop. Close the mic explicitly so the next
        // startVoiceCapture (resume in conversation mode) isn't blocked by
        // the `voiceCapture != null` early-return guard.
        runCatching { voiceCapture?.close() }
        voiceCapture = null
        val clean = text.trim()
        when (sink) {
            VoiceSink.CHAT -> {
                state.chatPartialText = ""
                if (clean.isNotEmpty()) {
                    if (voiceAutoSend) {
                        openClawSendText(clean)
                    } else {
                        state.chatInputText = if (state.chatInputText.isBlank()) clean
                            else state.chatInputText.trimEnd() + " " + clean
                    }
                }
            }
            VoiceSink.TERMINAL -> {
                state.terminalPartial = ""
                if (clean.isNotEmpty()) {
                    state.terminalInput = if (state.terminalInput.isBlank()) clean
                        else state.terminalInput.trimEnd() + " " + clean
                }
            }
            VoiceSink.HERMES_CHAT -> {
                state.hermesPartialText = ""
                if (clean.isNotEmpty()) {
                    if (voiceAutoSend) {
                        hermesSendText(clean)
                    } else {
                        state.hermesInputText = if (state.hermesInputText.isBlank()) clean
                            else state.hermesInputText.trimEnd() + " " + clean
                    }
                }
            }
            VoiceSink.TRANSLATOR -> {
                // Translator always auto-submits — the explicit hold-to-talk
                // gesture is itself the "send" intent. Drop the transcript into
                // the input draft AND fire translatorSendText so a failed
                // translate doesn't lose the source phrase (the panel shows
                // the partial draft until cleared by send success).
                state.translatorPartialText = ""
                if (clean.isNotEmpty()) {
                    translatorSendText(clean)
                }
            }
        }
        voiceSession = null
        voiceSink = null
        voiceAutoSend = false
    }

    override fun terminalRecordStart() = startVoiceCapture(VoiceSink.TERMINAL)
    override fun terminalRecordStop()  = stopVoiceCapture()

    override fun terminalPasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val raw = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (raw.isEmpty()) {
            toast("clipboard empty")
            return
        }
        // Append (with a leading space if the field is non-empty) so prior
        // typing isn't clobbered. User can wheel-press to run.
        state.terminalInput = if (state.terminalInput.isBlank()) raw
            else state.terminalInput.trimEnd() + " " + raw
    }

    override fun getClipboardText(): String {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        return cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
    }

    override fun copyToClipboard(text: String, label: String) {
        if (text.isEmpty()) {
            toast("nothing to copy")
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        if (cm == null) {
            toast("clipboard unavailable")
            return
        }
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        toast("copied (${text.length} chars)")
    }

    private fun promptLabel(cwd: String): String {
        // Compact home-style indicator: /sdcard/foo → ~/foo when on the
        // user-data root, otherwise the absolute path.
        val short = if (cwd == "/sdcard") "~"
            else if (cwd.startsWith("/sdcard/")) "~" + cwd.removePrefix("/sdcard")
            else cwd
        return "$short \$"
    }

    /** Quote `s` for safe inclusion in a shell command that will be evaluated
     *  by sh/bash. Wraps in single quotes and escapes any embedded `'` as
     *  `'\''` (close, escape, reopen) — the canonical POSIX-shell quoting
     *  pattern. Inside single quotes the shell treats every character
     *  literally: no $VAR expansion, no backtick, no \-escape. Use this
     *  anywhere user-supplied or attacker-controlled strings (SSIDs, file
     *  names, passwords, terminal commands) are interpolated into a shell
     *  command sent to carroot. */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    // --- LauncherHost: openclaw ---

    override fun openClawScanned(raw: String) {
        val code = decodeGatewaySetupCode(raw) ?: run {
            state.qrError = "QR not recognised"
            return
        }
        openClawPrefs.gatewayUrl = code.url
        openClawPrefs.bootstrapToken = code.bootstrapToken
        openClawPrefs.sharedToken = code.token
        openClawPrefs.deviceToken = null
        // Drop any selected/main session key carried over from a previous
        // gateway — those keys are scoped to that gateway and would 404
        // (or join an unrelated thread) on the new one.
        openClawPrefs.selectedSessionKey = null
        openClawPrefs.lastMainSessionKey = null
        state.qrError = null
        selectTone()
        openClawStartSession()
        state.chatMessages.clear()
        state.openOpenClawChat()
    }

    override fun openClawToggleRecord() {
        if (state.chatRecording) openClawRecordStop() else openClawRecordStart()
    }

    /** Hold-to-talk: the chat mic pill, wheel-press toggle, and side-button
     *  PTT all record while held/active and auto-send the committed
     *  transcript. */
    override fun openClawRecordStart() {
        // Don't even open the mic if there's no live OpenClaw session — the
        // committed transcript would have nowhere to send. (Original Whisper
        // flow checked the same thing.)
        openClawSession ?: return
        if (state.chatStatus.startsWith("error") || state.chatStatus == "idle") return
        voiceAutoSend = true
        startVoiceCapture(VoiceSink.CHAT)
    }

    override fun openClawRecordStop() {
        // Releasing the side button cancels a still-pending arm so a late
        // socket connect (talk shortcut) doesn't start recording after the fact.
        pendingChatVoiceArm = false
        stopVoiceCapture()
    }

    /**
     * Clock-screen talk shortcut entry: jump straight into the chosen AI chat
     * and fire that panel's existing push-to-talk. Navigation happens here on
     * the long-press DOWN-repeat, so by the time the side button is released
     * the panel is the chat panel and the existing PTT routing in
     * [dispatchKeyEvent] (handleOpenClawPttKey / handleHermesPttKey) stops the
     * recording and auto-sends — we just pre-set the panel's PTT key code so it
     * recognizes the release. Hermes records instantly; OpenClaw arms once its
     * socket goes Live (see [pendingChatVoiceArm]). Unconfigured targets drop
     * into their setup screen (QR / config) instead of failing silently.
     */
    private fun homeTalkStart(target: String, keyCode: Int) {
        when (target) {
            com.r1.launcher.voice.VoicePrefs.TALK_HERMES -> {
                hydrateHermesStateFromPrefs()
                if (hermesPrefs.hasConfig()) {
                    state.openHermesChat()
                    hermesPttKeyCode = keyCode
                    hermesRecordStart()
                } else {
                    state.openHermesConfig(fromChat = false)
                }
            }
            com.r1.launcher.voice.VoicePrefs.TALK_OPENCLAW -> {
                if (openClawPrefs.hasPairing()) {
                    // Capture liveness BEFORE (re)starting. A reused warm session
                    // is genuinely live and can record right away. A fresh start
                    // leaves chatStatus stale (it isn't reset on close and the
                    // connect updates it async), so never record immediately on a
                    // fresh session — arm and let onState Live fire once the
                    // socket is actually up.
                    val reusedLive = openClawSession != null && state.chatStatus == "live"
                    openClawStartSession()
                    state.openOpenClawChat()
                    openClawPttKeyCode = keyCode
                    if (reusedLive) openClawRecordStart() else pendingChatVoiceArm = true
                } else {
                    state.openOpenClawQr()
                }
            }
        }
    }

    override fun openClawSendText(text: String) {
        val session = openClawSession ?: return
        if (state.chatStatus.startsWith("error") || state.chatStatus == "idle") return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val optimistic = com.r1.launcher.openclaw.ChatMessage(role = "user", text = trimmed)
        state.chatMessages.add(optimistic)
        trimChatMessages()
        state.chatScrollIndex = 0
        if (!trimmed.startsWith("/")) {
            state.chatBusy = true
            // Arm the auto-speak gate when the user has voice enabled and is
            // sitting in the chat panel; streaming TTS will start mid-reply
            // as sentence boundaries arrive in chatStreamingText.
            if (state.voiceEnabled && state.panel == Panel.OPENCLAW_CHAT) {
                // cancelOpenClawSpeech() bumps turnId, drops slots, stops any
                // prior playback, and resets spokenOffset — so a fresh turn
                // starts clean even if the prior reply was still speaking.
                cancelOpenClawSpeech()
                openClawSpeakNextAssistant = true
                // Pre-warm api.elevenlabs.io: fires a tiny GET /v1/voices in
                // parallel with session.send. By the time the model finishes
                // its ~2s warm-up and the first chunk's POST /stream goes
                // out, OkHttp's pool already has a live TLS socket — saves
                // ~150ms on first audio. Rate-limited internally to ≤1/min.
                voicePrefs.elevenlabsKey?.takeIf { it.isNotBlank() }?.let { key ->
                    com.r1.launcher.voice.ElevenLabsTtsClient.warmConnection(key)
                }
            }
        }
        session.send(text = trimmed, audioBase64 = null) { ok, runId, err ->
            ui.post {
                if (!ok) {
                    // Roll back the optimistic bubble — the server never accepted
                    // it. ChatMessage.id makes equality unique even if the user
                    // sends the same text twice in the same millisecond.
                    val idx = state.chatMessages.indexOfFirst { it.id == optimistic.id }
                    if (idx >= 0) state.chatMessages.removeAt(idx)
                    state.chatBusy = false
                    if (!err.isNullOrBlank()) toast("send failed: $err")
                } else if (runId != null) {
                    state.chatPendingRunIds.add(runId)
                }
            }
        }
    }

    override fun openClawScrollUp() {
        state.chatScrollIndex++
    }

    override fun openClawScrollDown() {
        state.chatScrollIndex--
    }

    // -------- Hermes Agent --------

    override fun hermesSendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (!hermesPrefs.hasConfig()) {
            toastFail("hermes: configure server url first")
            return
        }
        val active = hermesPrefs.active ?: run {
            toastFail("hermes: no connection")
            return
        }
        val capturedId = active.id
        val history = state.hermesActiveHistory() ?: run {
            toastFail("hermes: no connection")
            return
        }
        val userMsg = com.r1.launcher.hermes.HermesMessage(role = "user", text = trimmed)
        history.add(userMsg)
        trimHermesMessages(history)
        persistHermesHistory()
        state.hermesScrollIndex = 0
        state.hermesStreamingText = ""
        state.hermesReasoningText = ""
        state.hermesToolEvents.clear()
        state.hermesBusy = true
        state.hermesStatus = "streaming"

        // Arm TTS auto-readback so the next committed assistant message reads aloud
        // (matches the OpenClaw chat pattern). Cancel any prior playback so back-to-back
        // turns don't stack audio.
        if (state.voiceEnabled && state.panel == Panel.HERMES_CHAT) {
            cancelHermesSpeech()
            hermesSpeakNextAssistant = true
        }

        hermesClient.streamChat(
            connection = active,
            history = history.toList(),
            onDelta = { delta ->
                ui.post {
                    state.hermesStreamingText = state.hermesStreamingText + delta
                    maybeEmitHermesStreamingTtsChunk()
                }
            },
            onReasoning = { delta ->
                ui.post { state.hermesReasoningText = state.hermesReasoningText + delta }
            },
            onToolProgress = { ev ->
                ui.post {
                    // Upsert by toolCallId: a `completed` event mutates the
                    // existing `running` entry in place so the timeline shows
                    // one row per tool call. Events without an id fall back
                    // to append-only (rare; only seen on malformed payloads).
                    //
                    // The gateway only includes `label` + `emoji` on the
                    // `running` frame; the `completed` frame carries just
                    // {tool, toolCallId, status}. Carry both forward from the
                    // prior entry so the UI keeps the command text visible
                    // after the call finishes.
                    val list = state.hermesToolEvents
                    val idx = if (ev.toolCallId.isNotEmpty())
                        list.indexOfFirst { it.toolCallId == ev.toolCallId } else -1
                    if (idx >= 0) {
                        val prev = list[idx]
                        list[idx] = ev.copy(
                            label = ev.label.ifBlank { prev.label },
                            emoji = ev.emoji.ifBlank { prev.emoji },
                        )
                    } else list.add(ev)
                }
            },
            onDone = { full ->
                ui.post {
                    // Flush any residue BEFORE clearing the streaming buffer.
                    // Pass `full` explicitly so it works regardless of which
                    // source the splitter was reading from.
                    flushHermesStreamingTtsTail(full)
                    val streamingHandledTts = hermesStreamingTtsActive
                    val turnReasoning = state.hermesReasoningText.takeIf { it.isNotBlank() }
                    val turnTools = state.hermesToolEvents.toList()
                    state.hermesStreamingText = ""
                    state.hermesReasoningText = ""
                    state.hermesToolEvents.clear()
                    state.hermesBusy = false
                    if (full.isNotBlank()) {
                        history.add(
                            com.r1.launcher.hermes.HermesMessage(
                                role = "assistant",
                                text = full,
                                reasoning = turnReasoning,
                                toolEvents = turnTools,
                            )
                        )
                        trimHermesMessages(history)
                        com.r1.launcher.hermes.HermesHistoryStore.save(this, capturedId, history.toList())
                        state.hermesScrollIndex = 0
                        state.hermesStatus = "live"
                        // Only fall back to the one-shot read-back if the
                        // streaming pipeline never claimed this run (e.g.,
                        // reply was too short to hit a sentence boundary
                        // before onDone — flushHermesStreamingTtsTail is a
                        // no-op when streaming wasn't active).
                        if (!streamingHandledTts) speakLatestHermesAssistantIfNeeded()
                        if (state.panel != Panel.HERMES_CHAT) {
                            notify(
                                source = "hermes",
                                title = "hermes",
                                body = full.replace('\n', ' ').take(120),
                                deeplink = "hermes_chat",
                            )
                        }
                    } else {
                        state.hermesStatus = "idle"
                    }
                }
            },
            onError = { msg ->
                ui.post {
                    // Tear down any in-flight streaming TTS so half-played
                    // chunks don't keep talking after an error and so the
                    // next turn starts with a clean offset.
                    if (hermesStreamingTtsActive) cancelHermesSpeech()
                    state.hermesStreamingText = ""
                    state.hermesReasoningText = ""
                    state.hermesToolEvents.clear()
                    state.hermesBusy = false
                    state.hermesStatus = "error: $msg"
                    history.add(
                        com.r1.launcher.hermes.HermesMessage(role = "error", text = msg)
                    )
                    trimHermesMessages(history)
                    com.r1.launcher.hermes.HermesHistoryStore.save(this, capturedId, history.toList())
                    state.hermesScrollIndex = 0
                }
            },
        )
    }

    private fun trimHermesMessages(target: SnapshotStateList<com.r1.launcher.hermes.HermesMessage>) {
        val over = target.size - state.hermesMessagesMax
        if (over > 0) repeat(over) { target.removeAt(0) }
    }

    /** Hold-to-talk: the chat mic pill and the side-button PTT both record
     *  while held and auto-send the committed transcript on release. */
    override fun hermesRecordStart() {
        if (!hermesPrefs.hasConfig()) {
            toastFail("hermes: configure server url first")
            return
        }
        voiceAutoSend = true
        startVoiceCapture(VoiceSink.HERMES_CHAT)
    }

    override fun hermesRecordStop() = stopVoiceCapture()

    override fun hermesScrollUp() {
        state.hermesScrollIndex++
    }

    override fun hermesScrollDown() {
        state.hermesScrollIndex--
    }

    override fun hermesClearHistory() {
        cancelHermesSpeech()
        val activeId = hermesPrefs.active?.id
        hermesClient.cancel(activeId)
        state.hermesActiveHistory()?.clear()
        if (activeId != null) com.r1.launcher.hermes.HermesHistoryStore.clear(this, activeId)
        state.hermesStreamingText = ""
        state.hermesReasoningText = ""
        state.hermesToolEvents.clear()
        state.hermesBusy = false
        state.hermesStatus = "idle"
        activeId?.let { hermesPrefs.rotateSessionId(it) }
    }

    override fun hermesTestConnection() {
        if (!hermesPrefs.hasConfig()) {
            state.hermesStatus = "error: no url"
            toastFail("hermes: configure server url first")
            return
        }
        val active = hermesPrefs.active ?: run {
            state.hermesStatus = "error: no connection"
            return
        }
        state.hermesStatus = "connecting"
        hermesClient.testConnection(active) { ok, msg ->
            ui.post {
                state.hermesStatus = if (ok) "live" else "error: $msg"
                if (!ok) toastFail("hermes: $msg")
            }
        }
    }

    override fun hermesSetActiveConnection(id: String) {
        val current = hermesPrefs.active?.id
        if (current == id) return
        hermesClient.cancel(current)
        cancelHermesSpeech()
        state.hermesStreamingText = ""
        state.hermesPartialText = ""
        state.hermesBusy = false
        state.hermesStatus = "idle"
        hermesPrefs.setActive(id)
        hydrateHermesStateFromPrefs()
    }

    override fun hermesAddConnection(url: String, key: String): com.r1.launcher.hermes.HermesConnection? {
        val added = hermesPrefs.addConnection(url, key)
        if (added == null) {
            toastFail("hermes: max ${com.r1.launcher.hermes.HermesPrefs.MAX_CONNECTIONS} connections")
            return null
        }
        hermesPrefs.setActive(added.id)
        hydrateHermesStateFromPrefs()
        return added
    }

    override fun hermesUpdateConnection(id: String, url: String?, key: String?) {
        val credsChanged = url != null || key != null
        if (credsChanged) hermesClient.cancel(id)
        hermesPrefs.updateConnection(id, url = url, key = key)
        hydrateHermesStateFromPrefs()
    }

    override fun hermesDeleteConnection(id: String) {
        hermesClient.cancel(id)
        com.r1.launcher.hermes.HermesHistoryStore.deleteAll(this, id)
        state.hermesHistories.remove(id)
        hermesPrefs.deleteConnection(id)
        hydrateHermesStateFromPrefs()
    }

    override fun hermesRotateSession(id: String) {
        hermesClient.cancel(id)
        hermesPrefs.rotateSessionId(id)
        state.hermesHistories[id]?.clear()
        com.r1.launcher.hermes.HermesHistoryStore.clear(this, id)
        hydrateHermesStateFromPrefs()
    }

    override fun hermesConfigRowActivate(idx: Int) {
        val conns = hermesPrefs.connections
        val canAdd = conns.size < com.r1.launcher.hermes.HermesPrefs.MAX_CONNECTIONS
        val addRowIdx = if (canAdd) conns.size + 1 else -1
        val scanRowIdx = if (canAdd) conns.size + 2 else conns.size + 1
        val speakRowIdx = scanRowIdx + 1
        val hideRowIdx = scanRowIdx + 2
        val testRowIdx = scanRowIdx + 3
        when {
            idx == 0 -> { state.back(); backTone() }
            idx in 1..conns.size -> {
                val conn = conns[idx - 1]
                val active = hermesPrefs.active
                if (active?.id == conn.id) {
                    state.openHermesConnectionEdit(conn.id)
                } else {
                    hermesSetActiveConnection(conn.id)
                }
                popTone()
            }
            idx == addRowIdx -> { state.openHermesConnectionEdit(null); popTone() }
            idx == scanRowIdx -> { openHermesQr(); popTone() }
            idx == speakRowIdx -> { voiceToggleEnabled(); popTone() }
            idx == hideRowIdx -> {
                val newHide = !state.hermesHideChat
                state.hermesHideChat = newHide
                hermesPrefs.hideChat = newHide
                popTone()
            }
            idx == testRowIdx -> { hermesTestConnection(); popTone() }
        }
    }

    override fun hermesConnectionEditRowActivate(idx: Int) {
        val editId = state.hermesConnectionEditId
        val isNew = editId == null
        when (idx) {
            0 -> {
                state.hermesConnectionEditDeleteArmedAt = 0L
                state.back()
                backTone()
            }
            1, 2 -> popTone()  // row 1/2 open the inline keyboard in the panel itself
            3 -> if (!isNew && editId != null) {
                hermesRotateSession(editId)
                toast("hermes: session rotated")
                popTone()
            }
            4 -> if (!isNew && editId != null) {
                val now = android.os.SystemClock.uptimeMillis()
                val armed = state.hermesConnectionEditDeleteArmedAt
                if (armed > 0L && now - armed < com.r1.launcher.ui.DELETE_ARM_MS) {
                    hermesDeleteConnection(editId)
                    state.hermesConnectionEditDeleteArmedAt = 0L
                    state.back()
                    toast("hermes: connection deleted")
                } else {
                    state.hermesConnectionEditDeleteArmedAt = now
                }
                popTone()
            }
        }
    }

    override fun hermesConnectionEditSaveUrl(value: String) {
        val editId = state.hermesConnectionEditId
        if (editId == null) {
            val added = hermesAddConnection(value, state.hermesConnectionEditKeyInput)
            if (added != null) {
                state.hermesConnectionEditDeleteArmedAt = 0L
                state.back()
                hermesTestConnection()
            }
        } else {
            hermesUpdateConnection(editId, url = value)
            toast("hermes: url saved")
        }
    }

    override fun hermesConnectionEditSaveKey(value: String) {
        val editId = state.hermesConnectionEditId
        if (editId == null) {
            // Buffer-only — wait for URL save to commit the new connection.
            state.hermesConnectionEditKeyInput = value
            toast("hermes: key buffered (save url to create)")
        } else {
            hermesUpdateConnection(editId, key = value)
            toast(if (value.isBlank()) "hermes: key cleared" else "hermes: key saved")
        }
    }

    override fun hermesConnectionEditPasteUrl() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val raw = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (raw.isBlank()) { toastFail("clipboard empty"); return }
        state.hermesConnectionEditUrlInput = raw
    }

    override fun hermesConnectionEditPasteKey() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val raw = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (raw.isBlank()) { toastFail("clipboard empty"); return }
        state.hermesConnectionEditKeyInput = raw
    }

    override fun openHermesQr() {
        ensureCameraPerm()
        state.openHermesQr()
    }

    override fun hermesScanned(raw: String) {
        val code = com.r1.launcher.hermes.decodeHermesSetupCode(raw) ?: run {
            state.hermesQrError = "QR not recognised"
            return
        }
        val added = hermesAddConnection(code.url, code.key.orEmpty())
        if (added == null) {
            state.hermesQrError = "max ${com.r1.launcher.hermes.HermesPrefs.MAX_CONNECTIONS} connections — delete one first"
            return
        }
        state.back()
        hermesTestConnection()
        toastSuccess("hermes paired")
    }

    override fun hermesSetServerUrl(value: String) {
        val active = hermesPrefs.active
        if (active == null) {
            hermesAddConnection(value, "")
        } else {
            hermesUpdateConnection(active.id, url = value)
        }
        toast("hermes: url saved")
    }

    override fun hermesSetApiKey(value: String) {
        val active = hermesPrefs.active ?: return
        hermesUpdateConnection(active.id, key = value)
        toast(if (value.isBlank()) "hermes: key cleared" else "hermes: key saved")
    }

override fun hermesPasteServerUrlFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val raw = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (raw.isBlank()) {
            toastFail("clipboard empty")
            return
        }
        state.hermesServerUrlInput = raw
        hermesSetServerUrl(raw)
    }

    override fun hermesPasteApiKeyFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val raw = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (raw.isBlank()) {
            toastFail("clipboard empty")
            return
        }
        state.hermesApiKeyInput = raw
        hermesSetApiKey(raw)
    }

    private fun speakLatestHermesAssistantIfNeeded() {
        if (!state.voiceEnabled || !hermesSpeakNextAssistant) return
        if (state.panel != Panel.HERMES_CHAT) return
        val msg = state.hermesActiveHistory()?.lastOrNull { it.role == "assistant" && it.text.isNotBlank() } ?: return
        val key = "${msg.timestamp}:${msg.text.hashCode()}"
        if (key == hermesLastSpokenKey) return
        val apiKey = voicePrefs.elevenlabsKey
        if (apiKey.isNullOrBlank()) {
            toastFail("voice: set elevenlabs key in settings → voice")
            hermesSpeakNextAssistant = false
            return
        }
        hermesLastSpokenKey = key
        hermesSpeakNextAssistant = false
        cancelHermesSpeech()
        val cleanText = stripMarkdownForTts(msg.text)
        if (cleanText.isBlank()) return
        val outFile = File(File(cacheDir, "hermes-voice").apply { mkdirs() }, "assistant.mp3")
        hermesTtsCall = com.r1.launcher.voice.ElevenLabsTtsClient.synthesize(
            text = cleanText,
            apiKey = apiKey,
            voiceId = voicePrefs.effectiveVoiceId(),
            model = voicePrefs.model,
            tuning = voicePrefs.tuning(),
            outFile = outFile,
        ) { mp3Bytes, err ->
            hermesTtsCall = null
            if (err == "canceled") return@synthesize
            if (err != null || mp3Bytes == null) {
                toastFail("voice: ${err ?: "no audio"}")
                return@synthesize
            }
            playHermesSpeech(outFile)
        }
    }

    /** Mirror of [maybeEmitStreamingTtsChunk] for the Hermes chat panel.
     *  Slices off any newly-completed sentences past
     *  [hermesStreamingSpokenOffset] and enqueues them for ElevenLabs synth
     *  while the SSE stream is still mid-flight. */
    private fun maybeEmitHermesStreamingTtsChunk() {
        if (!hermesStreamingTtsActive && !hermesSpeakNextAssistant) return
        if (state.panel != Panel.HERMES_CHAT || !state.voiceEnabled) return
        if (voicePrefs.elevenlabsKey.isNullOrBlank()) return
        val full = state.hermesStreamingText
        if (full.length <= hermesStreamingSpokenOffset) return
        val tail = full.substring(hermesStreamingSpokenOffset)
        val firstChunk = hermesSpeechIssuedSeq == 0
        val split = findStreamingSplitPoint(tail, firstChunk)
        if (split <= 0) return
        val chunk = tail.substring(0, split).trim()
        hermesStreamingSpokenOffset += split
        if (chunk.isEmpty()) return
        hermesStreamingTtsActive = true
        hermesSpeakNextAssistant = false
        enqueueHermesStreamingTtsChunk(chunk)
    }

    /** Flush any non-empty residue past [hermesStreamingSpokenOffset] —
     *  for replies that ended without a sentence terminator. Only runs if
     *  streaming TTS already claimed this run (otherwise the post-stream
     *  one-shot still speaks the full message). Pass the final accumulated
     *  text directly so the flush still works after onDone resets
     *  [state.hermesStreamingText] = "". */
    private fun flushHermesStreamingTtsTail(fullText: String) {
        if (state.panel != Panel.HERMES_CHAT || !state.voiceEnabled) return
        if (!hermesStreamingTtsActive) return
        if (voicePrefs.elevenlabsKey.isNullOrBlank()) return
        if (fullText.length <= hermesStreamingSpokenOffset) return
        val tail = fullText.substring(hermesStreamingSpokenOffset).trim()
        hermesStreamingSpokenOffset = fullText.length
        if (tail.isEmpty()) return
        enqueueHermesStreamingTtsChunk(tail)
    }

    private fun enqueueHermesStreamingTtsChunk(chunk: String) {
        val apiKey = voicePrefs.elevenlabsKey
        if (apiKey.isNullOrBlank()) return
        val cleanChunk = stripMarkdownForTts(chunk)
        if (cleanChunk.isBlank()) return
        val turnId = hermesStreamingTtsTurnId
        val seq = ++hermesSpeechIssuedSeq
        val outFile = File(
            File(cacheDir, "hermes-voice").apply { mkdirs() },
            "stream-$turnId-$seq.mp3",
        )
        val call = com.r1.launcher.voice.ElevenLabsTtsClient.synthesize(
            text = cleanChunk,
            apiKey = apiKey,
            voiceId = voicePrefs.effectiveVoiceId(),
            model = voicePrefs.model,
            tuning = voicePrefs.tuning(),
            outFile = outFile,
        ) { _, err ->
            if (turnId != hermesStreamingTtsTurnId) {
                runCatching { outFile.delete() }
                return@synthesize
            }
            if (err != null) {
                hermesSpeechSlots[seq] = null
                runCatching { outFile.delete() }
            } else {
                hermesSpeechSlots[seq] = outFile
            }
            drainHermesStreamingSpeechQueue()
        }
        if (call != null) {
            if (turnId != hermesStreamingTtsTurnId) {
                runCatching { call.cancel() }
            } else {
                hermesTtsChunkCalls.add(call)
            }
        }
    }

    private fun drainHermesStreamingSpeechQueue() {
        while (true) {
            if (hermesSpeechPlaying) return
            val next = hermesSpeechNextToPlay
            if (!hermesSpeechSlots.containsKey(next)) return
            val file = hermesSpeechSlots.remove(next)
            hermesSpeechNextToPlay = next + 1
            if (file == null) continue
            playHermesStreamingSpeechFile(file)
            return
        }
    }

    private fun playHermesStreamingSpeechFile(file: File) {
        runCatching { hermesSpeechPlayer?.stop() }
        runCatching { hermesSpeechPlayer?.release() }
        hermesSpeechPlayer = null
        runCatching { hermesSpeechCurrentFile?.delete() }
        hermesSpeechCurrentFile = file
        val turnId = hermesStreamingTtsTurnId
        runCatching {
            hermesSpeechPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener { mp ->
                    runCatching { mp.release() }
                    if (hermesSpeechPlayer === mp) hermesSpeechPlayer = null
                    runCatching { file.delete() }
                    if (hermesSpeechCurrentFile === file) hermesSpeechCurrentFile = null
                    if (turnId != hermesStreamingTtsTurnId) {
                        hermesSpeechPlaying = false
                        state.hermesSpeaking = false
                        return@setOnCompletionListener
                    }
                    hermesSpeechPlaying = false
                    drainHermesStreamingSpeechQueue()
                    if (!hermesSpeechPlaying && hermesSpeechSlots.isEmpty()) {
                        state.hermesSpeaking = false
                    }
                }
                setOnErrorListener { mp, _, _ ->
                    runCatching { mp.release() }
                    if (hermesSpeechPlayer === mp) hermesSpeechPlayer = null
                    runCatching { file.delete() }
                    if (hermesSpeechCurrentFile === file) hermesSpeechCurrentFile = null
                    hermesSpeechPlaying = false
                    if (turnId == hermesStreamingTtsTurnId) drainHermesStreamingSpeechQueue()
                    if (!hermesSpeechPlaying && hermesSpeechSlots.isEmpty()) {
                        state.hermesSpeaking = false
                    }
                    true
                }
                // Async prepare — a synchronous prepare() here parses the MP3
                // header + spins up the codec on the main thread, repeated once
                // per streamed sentence while the chat panel animates. start()
                // fires from onPrepared instead. Gate flags are set below before
                // prepareAsync() returns so the drain loop can't double-start a
                // second player while this one prepares.
                setOnPreparedListener { mp ->
                    mp.start()
                    voiceSpeakingStartedAtMs = System.currentTimeMillis()
                }
                prepareAsync()
            }
            hermesSpeechPlaying = true
            state.hermesSpeaking = true
        }.onFailure {
            hermesSpeechPlaying = false
            runCatching { file.delete() }
            if (hermesSpeechCurrentFile === file) hermesSpeechCurrentFile = null
            if (!hermesSpeechPlaying && hermesSpeechSlots.isEmpty()) {
                state.hermesSpeaking = false
            }
        }
    }

    private fun playHermesSpeech(file: File) {
        runCatching { hermesSpeechPlayer?.release() }
        val mp = MediaPlayer()
        runCatching {
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                runCatching { it.release() }
                if (hermesSpeechPlayer === mp) hermesSpeechPlayer = null
                if (hermesSpeechCurrentFile === file) {
                    runCatching { file.delete() }
                    hermesSpeechCurrentFile = null
                }
                state.hermesSpeaking = false
            }
            mp.setOnErrorListener { player, _, _ ->
                runCatching { player.release() }
                if (hermesSpeechPlayer === player) hermesSpeechPlayer = null
                state.hermesSpeaking = false
                true
            }
            mp.prepare()
            hermesSpeechPlayer = mp
            hermesSpeechCurrentFile = file
            mp.start()
            state.hermesSpeaking = true
            voiceSpeakingStartedAtMs = System.currentTimeMillis()
        }.onFailure {
            runCatching { mp.release() }
            hermesSpeechPlayer = null
            hermesSpeechCurrentFile = null
            state.hermesSpeaking = false
        }
    }

    private fun cancelHermesSpeech() {
        runCatching { hermesTtsCall?.cancel() }
        hermesTtsCall = null
        runCatching { hermesSpeechPlayer?.stop() }
        runCatching { hermesSpeechPlayer?.release() }
        hermesSpeechPlayer = null
        runCatching { hermesSpeechCurrentFile?.delete() }
        hermesSpeechCurrentFile = null

        // Streaming pipeline teardown: bumping turnId invalidates any
        // in-flight chunk callbacks that race past cancel(). Cancel HTTP,
        // drop queued slots, delete leftover chunk MP3s.
        hermesStreamingTtsTurnId++
        hermesTtsChunkCalls.forEach { runCatching { it.cancel() } }
        hermesTtsChunkCalls.clear()
        hermesSpeechSlots.values.forEach { f -> f?.let { runCatching { it.delete() } } }
        hermesSpeechSlots.clear()
        runCatching {
            File(cacheDir, "hermes-voice")
                .listFiles { f -> f.name.startsWith("stream-") }
                ?.forEach { runCatching { it.delete() } }
        }
        hermesSpeechIssuedSeq = 0
        hermesSpeechNextToPlay = 1
        hermesSpeechPlaying = false
        hermesStreamingSpokenOffset = 0
        hermesStreamingTtsActive = false
        state.hermesSpeaking = false
    }

    // -------- Translator --------

    /** Mirror current TranslatorPrefs values + per-provider key status into
     *  LauncherState so the panels render reactively. Called on entry to the
     *  Translator app and after any prefs mutation. */
    private fun hydrateTranslatorStateFromPrefs() {
        state.translatorProvider = translatorPrefs.provider
        state.translatorSource = translatorPrefs.sourceLang
        state.translatorTarget = translatorPrefs.targetLang
        state.translatorAutoDetect = translatorPrefs.autoDetectSource
        state.translatorAutoSpeak = translatorPrefs.autoSpeak
        state.translatorHideInput = translatorPrefs.hideInput
        state.translatorGeminiHasKey = translatorPrefs.hasKey(com.r1.launcher.translator.ProviderId.GEMINI)
        state.translatorGeminiKeyTail = translatorPrefs.keyTail(com.r1.launcher.translator.ProviderId.GEMINI)
        state.translatorOpenAIHasKey = translatorPrefs.hasKey(com.r1.launcher.translator.ProviderId.OPENAI)
        state.translatorOpenAIKeyTail = translatorPrefs.keyTail(com.r1.launcher.translator.ProviderId.OPENAI)
        state.translatorClaudeHasKey = translatorPrefs.hasKey(com.r1.launcher.translator.ProviderId.ANTHROPIC)
        state.translatorClaudeKeyTail = translatorPrefs.keyTail(com.r1.launcher.translator.ProviderId.ANTHROPIC)
        if (state.translatorMessages.isEmpty()) {
            // History load is a file readText + JSON decode of up to 100 entries
            // — move it off the (app-open) main thread. Re-check still-empty on
            // the UI thread before seeding so a concurrent translate isn't lost.
            prefsHydrationExecutor.execute {
                val persisted = com.r1.launcher.translator.TranslationHistoryStore.load(this)
                if (persisted.isNotEmpty()) ui.post {
                    if (state.translatorMessages.isEmpty()) state.translatorMessages.addAll(persisted)
                }
            }
        }
    }

    private fun persistTranslatorHistory() {
        val snapshot = state.translatorMessages.toList()
        Thread { com.r1.launcher.translator.TranslationHistoryStore.save(this, snapshot) }.start()
    }

    override fun translatorSendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val provider = com.r1.launcher.translator.TranslatorProvider.of(translatorPrefs.provider)
        val key = translatorPrefs.keyFor(provider.id)
        if (key.isNullOrBlank()) {
            toastFail("translator: set ${provider.id.label} key in settings")
            state.translatorStatus = "error: no api key"
            return
        }
        val src = translatorPrefs.sourceLang
        val tgt = translatorPrefs.targetLang
        // Optimistically append a pending entry — the user sees their source
        // bubble immediately while the network call is in flight.
        val pending = com.r1.launcher.translator.TranslationMessage(
            sourceText = trimmed,
            sourceLang = src,
            targetLang = tgt,
            pending = true,
        )
        state.translatorMessages.add(pending)
        trimTranslatorMessages()
        state.translatorScrollIndex = 0
        state.translatorBusy = true
        state.translatorStatus = "busy"
        // Cancel any in-flight TTS — if the user is mid-translation we don't
        // want stale audio from the previous reply.
        cancelTranslatorSpeech()

        translatorClient.translate(
            provider = provider,
            apiKey = key,
            text = trimmed,
            sourceLangCode = src,
            targetLangCode = tgt,
        ) { result ->
            ui.post {
                val idx = state.translatorMessages.indexOfFirst { it.id == pending.id }
                if (idx < 0) {
                    // History was cleared mid-flight; nothing to update.
                    state.translatorBusy = false
                    return@post
                }
                result.onSuccess { translated ->
                    state.translatorMessages[idx] = pending.copy(
                        targetText = translated,
                        pending = false,
                    )
                    state.translatorBusy = false
                    state.translatorStatus = "ready"
                    persistTranslatorHistory()
                    if (translatorPrefs.autoSpeak && state.panel == Panel.TRANSLATOR) {
                        speakTranslatorTarget(state.translatorMessages[idx])
                    }
                }.onFailure { err ->
                    state.translatorMessages[idx] = pending.copy(
                        pending = false,
                        error = err.message ?: "translate failed",
                    )
                    state.translatorBusy = false
                    state.translatorStatus = "error: ${err.message ?: "failed"}"
                }
            }
        }
    }

    private fun trimTranslatorMessages() {
        val over = state.translatorMessages.size - com.r1.launcher.translator.TranslationHistoryStore.MAX_ENTRIES
        if (over > 0) repeat(over) { state.translatorMessages.removeAt(0) }
    }

    override fun translatorRecordStart() {
        // The translator always auto-submits on commit (handled in
        // handleCommittedTranscript). autoSend = true mirrors hermes power-user
        // path but the value is effectively ignored — the sink's own commit
        // handler does the dispatch directly.
        voiceAutoSend = true
        startVoiceCapture(VoiceSink.TRANSLATOR)
    }

    override fun translatorRecordStop() = stopVoiceCapture()

    override fun translatorCycleSource(delta: Int) {
        val next = com.r1.launcher.translator.Languages.cycle(state.translatorSource, delta)
        translatorPrefs.sourceLang = next
        state.translatorSource = next
        toast("source: ${com.r1.launcher.translator.Languages.get(next).english}")
    }

    override fun translatorCycleTarget(delta: Int) {
        val next = com.r1.launcher.translator.Languages.cycle(state.translatorTarget, delta)
        translatorPrefs.targetLang = next
        state.translatorTarget = next
        toast("target: ${com.r1.launcher.translator.Languages.get(next).english}")
    }

    override fun translatorSetProvider(provider: String) {
        val id = providerIdFromField(provider) ?: return
        translatorPrefs.provider = id
        state.translatorProvider = id
    }

    override fun translatorToggleHideInput() {
        val next = !translatorPrefs.hideInput
        translatorPrefs.hideInput = next
        state.translatorHideInput = next
    }

    override fun translatorSetSource(code: String) {
        translatorPrefs.sourceLang = code
        state.translatorSource = code
    }

    override fun translatorSetTarget(code: String) {
        translatorPrefs.targetLang = code
        state.translatorTarget = code
    }

    override fun translatorSwapLangs() {
        val oldSrc = state.translatorSource
        val oldTgt = state.translatorTarget
        translatorPrefs.sourceLang = oldTgt
        translatorPrefs.targetLang = oldSrc
        state.translatorSource = oldTgt
        state.translatorTarget = oldSrc
    }

    override fun translatorReplay(messageId: String) {
        val msg = state.translatorMessages.firstOrNull { it.id == messageId } ?: return
        if (msg.pending || msg.error != null || msg.targetText.isBlank()) return
        // Force re-speak even when key matches a prior playback by clearing the
        // guard — explicit user tap is the intent to hear it again.
        translatorLastSpokenKey = ""
        speakTranslatorTarget(msg)
    }

    override fun translatorClearHistory() {
        state.translatorMessages.clear()
        state.translatorPartialText = ""
        state.translatorScrollIndex = 0
        com.r1.launcher.translator.TranslationHistoryStore.clear(this)
        translatorClient.clearCache()
        cancelTranslatorSpeech()
        toast("translator: history cleared")
    }

    override fun translatorSettingsRowActivate(idx: Int) {
        when (idx) {
            0 -> { state.back(); backTone() }
            1 -> {
                // Cycle provider: gemini → openai → claude → gemini
                val current = translatorPrefs.provider
                val next = when (current) {
                    com.r1.launcher.translator.ProviderId.GEMINI -> com.r1.launcher.translator.ProviderId.OPENAI
                    com.r1.launcher.translator.ProviderId.OPENAI -> com.r1.launcher.translator.ProviderId.ANTHROPIC
                    com.r1.launcher.translator.ProviderId.ANTHROPIC -> com.r1.launcher.translator.ProviderId.GEMINI
                }
                translatorPrefs.provider = next
                state.translatorProvider = next
                popTone()
            }
            2 -> openTranslatorKeyboard("gemini")
            3 -> openTranslatorKeyboard("openai")
            4 -> openTranslatorKeyboard("claude")
            // Source / target rows: handled by overlay in the panel (no host
            // call needed — the picker fires onPickSource/Target directly).
            5, 6 -> { /* no-op — picker overlay drives changes */ }
            7 -> {
                val next = !translatorPrefs.autoDetectSource
                translatorPrefs.autoDetectSource = next
                state.translatorAutoDetect = next
                popTone()
            }
            8 -> {
                val next = !translatorPrefs.autoSpeak
                translatorPrefs.autoSpeak = next
                state.translatorAutoSpeak = next
                popTone()
            }
            9 -> {
                translatorToggleHideInput()
                popTone()
            }
            10 -> {
                translatorClearHistory()
                popTone()
            }
        }
    }

    private fun openTranslatorKeyboard(field: String) {
        state.translatorEditField = field
        state.translatorEditInput = ""
        selectTone()
    }

    override fun translatorSaveKey(provider: String, value: String) {
        val id = providerIdFromField(provider) ?: return
        translatorPrefs.setKey(id, value)
        hydrateTranslatorStateFromPrefs()
        state.translatorEditField = ""
        state.translatorEditInput = ""
        toast("translator: $provider key saved")
        popTone()
    }

    override fun translatorPasteKey(provider: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val raw = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (raw.isBlank()) {
            toastFail("clipboard empty")
            return
        }
        state.translatorEditInput = raw
        translatorSaveKey(provider, raw)
    }

    override fun translatorClearKey(provider: String) {
        val id = providerIdFromField(provider) ?: return
        translatorPrefs.setKey(id, null)
        hydrateTranslatorStateFromPrefs()
        state.translatorEditField = ""
        state.translatorEditInput = ""
        toast("translator: $provider key cleared")
        popTone()
    }

    private fun providerIdFromField(field: String): com.r1.launcher.translator.ProviderId? = when (field) {
        "gemini" -> com.r1.launcher.translator.ProviderId.GEMINI
        "openai" -> com.r1.launcher.translator.ProviderId.OPENAI
        "claude" -> com.r1.launcher.translator.ProviderId.ANTHROPIC
        else -> null
    }

    // ---- Translator first-run onboarding ----

    override fun translatorOnboardingPickSource(code: String) {
        translatorPrefs.sourceLang = code
        state.translatorSource = code
        // "auto-detect" implies STT shouldn't be pinned to one language.
        translatorPrefs.autoDetectSource = com.r1.launcher.translator.Languages.isAuto(code)
        state.translatorAutoDetect = translatorPrefs.autoDetectSource
        state.translatorOnboardingStep = 1
        state.translatorOnboardingFocus = 0
    }

    override fun translatorOnboardingPickTarget(code: String) {
        translatorPrefs.targetLang = code
        state.translatorTarget = code
        state.translatorOnboardingStep = 2
        state.translatorOnboardingFocus = 0
        state.translatorOnboardingWaitingForKey = false
    }

    /** Phone-handoff key step: ensure the web companion is running so the user
     *  can paste a key from their phone, then flip into "waiting" mode. The
     *  onboarding panel polls [LauncherState.translatorGeminiHasKey] (et al.)
     *  and auto-finishes when a key lands. Default provider is Gemini (free). */
    override fun translatorOnboardingEnablePhoneKey() {
        translatorPrefs.provider = com.r1.launcher.translator.ProviderId.GEMINI
        state.translatorProvider = com.r1.launcher.translator.ProviderId.GEMINI
        if (!state.webServerEnabled) toggleWebServer(true)
        state.translatorOnboardingWaitingForKey = true
    }

    override fun translatorOnboardingPasteKey() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val raw = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            toastFail("clipboard empty — copy your key first")
            return
        }
        // Default provider Gemini; if the key looks like sk-ant-/sk- route it to
        // the matching provider so a pasted OpenAI/Claude key isn't mis-filed.
        val provider = when {
            raw.startsWith("sk-ant-") -> com.r1.launcher.translator.ProviderId.ANTHROPIC
            raw.startsWith("sk-") -> com.r1.launcher.translator.ProviderId.OPENAI
            else -> com.r1.launcher.translator.ProviderId.GEMINI
        }
        translatorPrefs.setKey(provider, raw)
        translatorPrefs.provider = provider
        hydrateTranslatorStateFromPrefs()
        toast("translator: ${provider.label} key saved")
        finishTranslatorOnboarding()
    }

    override fun translatorOnboardingSkipKey() {
        toast("translator: add a key anytime in settings")
        finishTranslatorOnboarding()
    }

    override fun translatorOnboardingFinish() {
        finishTranslatorOnboarding()
    }

    private fun finishTranslatorOnboarding() {
        translatorPrefs.onboarded = true
        state.translatorOnboardingWaitingForKey = false
        hydrateTranslatorStateFromPrefs()
        state.openTranslator()
    }

    /** Synthesize + play the target text via ElevenLabs TTS. Uses the global
     *  ElevenLabs key from VoicePrefs — translator doesn't have its own TTS
     *  key (TTS is a separate ElevenLabs product from translation). */
    private fun speakTranslatorTarget(msg: com.r1.launcher.translator.TranslationMessage) {
        val apiKey = voicePrefs.elevenlabsKey
        if (apiKey.isNullOrBlank()) {
            toast("translator: set elevenlabs key in voice settings to enable speech")
            return
        }
        val key = "${msg.id}:${msg.targetText.hashCode()}"
        if (key == translatorLastSpokenKey) return
        translatorLastSpokenKey = key
        cancelTranslatorSpeech()
        val outFile = File(File(cacheDir, "translator-voice").apply { mkdirs() }, "tr.mp3")
        translatorTtsCall = com.r1.launcher.voice.ElevenLabsTtsClient.synthesize(
            text = msg.targetText,
            apiKey = apiKey,
            voiceId = voicePrefs.effectiveVoiceId(),
            // Translation often involves non-English output (the whole point).
            // Force the multilingual model so Arabic / Hindi / Mandarin etc
            // come out intelligible instead of the Flash model's English-leaning
            // phoneme set garbling them.
            model = "eleven_multilingual_v2",
            tuning = voicePrefs.tuning(),
            outFile = outFile,
        ) { _, err ->
            translatorTtsCall = null
            if (err == "canceled") return@synthesize
            if (err != null) {
                ui.post { toastFail("tts: $err") }
                return@synthesize
            }
            ui.post { playTranslatorSpeech(outFile) }
        }
    }

    private fun playTranslatorSpeech(file: File) {
        runCatching { translatorSpeechPlayer?.release() }
        val mp = MediaPlayer()
        runCatching {
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                runCatching { it.release() }
                if (translatorSpeechPlayer === mp) translatorSpeechPlayer = null
                if (translatorSpeechFile === file) {
                    runCatching { file.delete() }
                    translatorSpeechFile = null
                }
                state.translatorSpeaking = false
            }
            mp.setOnErrorListener { player, _, _ ->
                runCatching { player.release() }
                if (translatorSpeechPlayer === player) translatorSpeechPlayer = null
                state.translatorSpeaking = false
                true
            }
            // Async prepare — a synchronous prepare() here parses the MP3 header
            // + spins up the codec on the main thread. start() fires from
            // onPrepared instead. State is set below before prepareAsync()
            // returns so the player/file refs are live for the listeners.
            mp.setOnPreparedListener {
                it.start()
                voiceSpeakingStartedAtMs = System.currentTimeMillis()
            }
            translatorSpeechPlayer = mp
            translatorSpeechFile = file
            state.translatorSpeaking = true
            mp.prepareAsync()
        }.onFailure {
            runCatching { mp.release() }
            translatorSpeechPlayer = null
            translatorSpeechFile = null
            state.translatorSpeaking = false
        }
    }

    private fun cancelTranslatorSpeech() {
        runCatching { translatorTtsCall?.cancel() }
        translatorTtsCall = null
        runCatching { translatorSpeechPlayer?.stop() }
        runCatching { translatorSpeechPlayer?.release() }
        translatorSpeechPlayer = null
        runCatching { translatorSpeechFile?.delete() }
        translatorSpeechFile = null
        state.translatorSpeaking = false
    }

    override fun openClawCloseSession() {
        openClawCloseSessionInternal()
    }

    private fun isValidElevenKey(raw: String): Boolean {
        // ElevenLabs keys: either "sk_<29 hex>" prefix form (~32 char) or a
        // bare 32-char hex string. Lower-case hex is the canonical form.
        if (raw.startsWith("sk_") && raw.length >= 20) return true
        if (raw.length == 32 && raw.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return true
        return false
    }

    override fun voiceToggleEnabled() {
        val next = !state.voiceEnabled
        state.voiceEnabled = next
        voicePrefs.enabled = next
        if (!next) runCatching { openClawSpeechPlayer?.stop() }
        popTone()
    }

    override fun voiceCycleVoiceId() {
        val voices = com.r1.launcher.voice.VoicePrefs.VOICES
        val curIdx = voices.indexOfFirst { it.second == state.voiceId }
        val next = voices[(curIdx + 1).coerceAtLeast(0) % voices.size]
        state.voiceId = next.second
        voicePrefs.voiceId = next.second
        toast("voice: ${next.first}")
        popTone()
    }

    override fun voiceSetVoiceId(id: String) {
        val voices = com.r1.launcher.voice.VoicePrefs.VOICES
        val match = voices.firstOrNull { it.second == id }
        if (match == null) {
            android.util.Log.w("R1Voice", "voiceSetVoiceId($id) ignored: not in catalog")
            return
        }
        state.voiceId = match.second
        voicePrefs.voiceId = match.second
        toast("voice: ${match.first}")
    }

    override fun voiceSaveKey(key: String) {
        val k = key.trim()
        when {
            k.isEmpty() -> { toast("key is empty"); return }
            !isValidElevenKey(k) -> { toast("not an elevenlabs key"); return }
            else -> {
                voicePrefs.elevenlabsKey = k
                refreshVoiceKeyState()
                toast("voice key saved")
                state.back()
            }
        }
    }

    override fun voiceClearKey() {
        voicePrefs.elevenlabsKey = null
        refreshVoiceKeyState()
        toast("voice key cleared")
    }

    override fun voicePasteKeyFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val raw = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            toast("clipboard empty"); return
        }
        if (!isValidElevenKey(raw)) {
            toast("not an elevenlabs key"); return
        }
        voicePrefs.elevenlabsKey = raw
        refreshVoiceKeyState()
        toast("voice key saved")
    }

    override fun voiceSettingsRowActivate(idx: Int) {
        // Row layout matches SettingsVoicePanel (post-credentials-migration):
        //   0  back
        //   1  voice on/off
        //   2  subscription (tap = force-refresh balance)
        //   3  voice picker (cycle catalog)
        //   4  custom voice id (handled inline by the panel's keyboard overlay)
        //   5  test voice
        //   6  tuning (opens SETTINGS_VOICE_TUNING)
        //   7  talk shortcut (cycle off → hermes → openclaw)
        when (idx) {
            0 -> { state.back(); backTone() }
            1 -> voiceToggleEnabled()
            2 -> {
                state.openSettingsVoiceSubscription()
                // Auto-fetch on entry; honors the 60s cache so re-entering
                // the page in quick succession doesn't re-hit the API.
                voiceFetchSubscription(force = false)
                selectTone()
            }
            3 -> voiceCycleVoiceId()
            // 4 handled by the panel's keyboard overlay
            5 -> voiceTestSynthesize()
            6 -> { state.openSettingsVoiceTuning(); selectTone() }
            7 -> voiceCycleTalkShortcut()
        }
    }

    /** Cycle the clock-screen long-press talk target: off → hermes → openclaw.
     *  Persists to VoicePrefs and updates the state mirror so the Settings →
     *  Voice row label refreshes. The HOME long-press reads [state.talkShortcut]. */
    override fun voiceCycleTalkShortcut() {
        val order = com.r1.launcher.voice.VoicePrefs.TALK_SHORTCUTS
        val cur = order.indexOf(state.talkShortcut).coerceAtLeast(0)
        val next = order[(cur + 1) % order.size]
        state.talkShortcut = next
        voicePrefs.talkShortcut = next
        val label = when (next) {
            com.r1.launcher.voice.VoicePrefs.TALK_HERMES -> "hermes"
            com.r1.launcher.voice.VoicePrefs.TALK_OPENCLAW -> "openclaw"
            else -> "off"
        }
        toast("talk shortcut: $label")
        popTone()
    }

    override fun voiceSaveCustomVoiceId(id: String) {
        val v = id.trim()
        if (v.isEmpty()) { toast("voice id is empty"); return }
        voicePrefs.customVoiceId = v
        state.voiceCustomId = v
        toast("custom voice id saved")
        popTone()
    }

    override fun voiceClearCustomVoiceId() {
        voicePrefs.customVoiceId = null
        state.voiceCustomId = ""
        toast("custom voice id cleared")
        popTone()
    }

    override fun voicePasteCustomVoiceIdFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val raw = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) { toast("clipboard empty"); return }
        voicePrefs.customVoiceId = raw
        state.voiceCustomId = raw
        toast("custom voice id saved")
    }

    override fun voiceCycleModel() {
        val models = com.r1.launcher.voice.VoicePrefs.MODELS
        val curIdx = models.indexOfFirst { it.second == state.voiceModel }
        val next = models[(curIdx + 1).coerceAtLeast(0) % models.size]
        state.voiceModel = next.second
        voicePrefs.model = next.second
        toast("model: ${next.first}")
        popTone()
    }

    override fun voiceSetStability(value: Float) {
        val v = value.coerceIn(0f, 1f)
        state.voiceStability = v
        voicePrefs.stability = v
    }

    override fun voiceSetSimilarity(value: Float) {
        val v = value.coerceIn(0f, 1f)
        state.voiceSimilarity = v
        voicePrefs.similarity = v
    }

    override fun voiceSetStyle(value: Float) {
        val v = value.coerceIn(0f, 1f)
        state.voiceStyle = v
        voicePrefs.style = v
    }

    override fun voiceSetSpeed(value: Float) {
        val v = value.coerceIn(
            com.r1.launcher.voice.VoicePrefs.MIN_SPEED,
            com.r1.launcher.voice.VoicePrefs.MAX_SPEED,
        )
        state.voiceSpeed = v
        voicePrefs.speed = v
    }

    override fun voiceToggleSpeakerBoost() {
        val next = !state.voiceSpeakerBoost
        state.voiceSpeakerBoost = next
        voicePrefs.speakerBoost = next
        popTone()
    }

    override fun voiceResetTuning() {
        voicePrefs.resetTuning()
        state.voiceModel = voicePrefs.model
        state.voiceStability = voicePrefs.stability
        state.voiceSimilarity = voicePrefs.similarity
        state.voiceStyle = voicePrefs.style
        state.voiceSpeed = voicePrefs.speed
        state.voiceSpeakerBoost = voicePrefs.speakerBoost
        toast("tuning reset")
        popTone()
    }

    private val voiceSubExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var voiceSubInFlight: Boolean = false

    override fun voiceFetchSubscription(force: Boolean) {
        val key = voicePrefs.elevenlabsKey
        if (key.isNullOrBlank()) {
            state.voiceSubError = "no key"
            state.voiceSubLoading = false
            return
        }
        // 60s in-memory cache so wheel-spamming the row doesn't hammer the API.
        // Force = true (manual tap) bypasses the cache.
        val ageMs = System.currentTimeMillis() - state.voiceSubFetchedAtMs
        if (!force && state.voiceSubFetchedAtMs > 0L && ageMs < 60_000L) return
        if (voiceSubInFlight) return
        voiceSubInFlight = true
        state.voiceSubLoading = true
        state.voiceSubError = null
        voiceSubExecutor.submit {
            val client = com.r1.launcher.voice.ElevenLabsSubscriptionClient(key)
            when (val r = client.fetch()) {
                is com.r1.launcher.voice.ElevenLabsSubscriptionClient.Result.Success -> {
                    ui.post {
                        state.voiceSubData = r.data
                        state.voiceSubFetchedAtMs = System.currentTimeMillis()
                        state.voiceSubLoading = false
                        state.voiceSubError = null
                    }
                }
                is com.r1.launcher.voice.ElevenLabsSubscriptionClient.Result.Failure -> {
                    ui.post {
                        state.voiceSubLoading = false
                        state.voiceSubError = if (r.httpCode == 401) "invalid key"
                            else "${r.httpCode}: ${r.message.take(80)}"
                    }
                }
            }
            voiceSubInFlight = false
        }
    }

    override fun voiceTestSynthesize() {
        val apiKey = voicePrefs.elevenlabsKey
        if (apiKey.isNullOrBlank()) {
            toast("voice: set elevenlabs key first")
            return
        }
        if (state.voiceTestBusy) return
        state.voiceTestBusy = true
        // Stop any in-flight TTS (chat readback, prior test) before starting.
        cancelOpenClawSpeech()
        val sample = getString(R.string.voice_test_phrase)
        val outFile = File(File(cacheDir, "openclaw-voice").apply { mkdirs() }, "test.mp3")
        openClawTtsCall = com.r1.launcher.voice.ElevenLabsTtsClient.synthesize(
            text = sample,
            apiKey = apiKey,
            voiceId = voicePrefs.effectiveVoiceId(),
            model = voicePrefs.model,
            tuning = voicePrefs.tuning(),
            outFile = outFile,
        ) { mp3Bytes, err ->
            openClawTtsCall = null
            state.voiceTestBusy = false
            if (err == "canceled") return@synthesize
            if (err != null || mp3Bytes == null) {
                toastFail("voice: ${err ?: "no audio"}")
                return@synthesize
            }
            playOpenClawSpeech(mp3Bytes)
        }
    }

    override fun voiceTuningRowActivate(idx: Int) {
        // Row layout matches SettingsVoiceTuningPanel:
        //   0 back
        //   1 model picker (cycle)
        //   2..5 sliders (handled inline by the panel's +/- pills)
        //   6 speaker boost toggle
        //   7 test voice
        //   8 reset to defaults
        when (idx) {
            0 -> { state.back(); backTone() }
            1 -> voiceCycleModel()
            // 2..5 handled inline by the panel's slider pills
            6 -> voiceToggleSpeakerBoost()
            7 -> voiceTestSynthesize()
            8 -> voiceResetTuning()
        }
    }

    override fun openClawSettingsRowActivate(idx: Int) {
        when (idx) {
            0 -> { state.back(); backTone() }
            1 -> {
                // Convenience inline toggle for the global voice-enabled flag.
                // The canonical config still lives in Settings → Voice; this row
                // just flips the same VoicePrefs.enabled, so it stays in sync.
                voiceToggleEnabled()
                popTone()
            }
            2 -> {
                val newHide = !state.openClawHideChat
                state.openClawHideChat = newHide
                openClawPrefs.hideChat = newHide
                popTone()
            }
            // 3 (font size) is handled by +/- buttons in the UI
            4 -> { openClawClearHistory(); popTone() }
            5 -> { openClawDisconnect(); popTone() }
        }
    }

    override fun openClawClearHistory() {
        state.chatMessages.clear()
        toast("chat history cleared")
    }

    override fun openClawDisconnect() {
        openClawPrefs.clear()
        runCatching { openClawCloseSessionInternal() }
        state.back()
        toast("gateway disconnected")
    }

    override fun openClawSwitchSession(key: String) {
        val target = key.trim()
        if (target.isEmpty() || target == state.selectedSessionKey) return
        val session = openClawSession ?: return
        // Reset chat UI immediately so the user sees the switch take effect even
        // before the new history lands. GatewaySession.switchSession will fire
        // onHistory once the new thread's messages are fetched.
        state.chatMessages.clear()
        state.chatStreamingText = ""
        state.chatPendingRunIds.clear()
        state.chatScrollIndex = 0
        state.chatBusy = false
        state.selectedSessionKey = target
        openClawPrefs.selectedSessionKey = target
        session.switchSession(target)
    }

    override fun openClawRefreshSessions() {
        val session = openClawSession ?: return
        if (state.sessionsLoading) return
        state.sessionsLoading = true
        session.listSessions()
    }

    override fun openClawCompactSession() {
        val session = openClawSession ?: run { toastFail("not connected"); return
        }
        val key = state.selectedSessionKey
        if (key.isBlank()) { toastFail("no session"); return }
        toast("compacting…")
        session.compactSession(key) { ok, err ->
            ui.post {
                if (ok) toastSuccess("compacted")
                else toastFail(err ?: "compact failed")
            }
        }
    }

    override fun openClawClearContext() {
        if (openClawSession == null) { toastFail("not connected"); return }
        // Upstream `sessions.reset` is operator.admin-scoped (core-descriptors
        // line 141), which the bootstrap pairing profile doesn't grant — the
        // RPC always returns "unauthorized role" / "missing scope" for this
        // client class. Mirror the "+ new thread" flow instead: pick a fresh
        // unused thread key and switch to it. chat.subscribe lazy-creates
        // server-side (operator.write, allowed). The previous thread lingers
        // on the gateway but the user gets the empty-transcript UX they
        // expected from "clear".
        val newKey = "thread-${System.currentTimeMillis()}"
        openClawSwitchSession(newKey)
        toastSuccess("context cleared")
    }

    override fun openClawSessionsRowActivate(idx: Int) {
        // Mirrors OpenClawSessionsPanel row order:
        //   0             "< back"
        //   1             "+ new thread"
        //   2..choices+1  switch to that thread
        //   choices+2     "refresh"
        if (idx == 0) {
            state.back(); backTone(); return
        }
        if (idx == 1) {
            // Fresh thread — pick a key the gateway hasn't seen before.
            // chat.subscribe lazy-creates server-side, so a unique timestamp
            // key is enough. Drop back to chat after dispatch.
            val newKey = "thread-${System.currentTimeMillis()}"
            openClawSwitchSession(newKey)
            popTone()
            state.back()
            return
        }
        val choices = com.r1.launcher.openclaw.resolveSessionChoices(
            currentSessionKey = state.selectedSessionKey,
            sessions = state.chatSessions.toList(),
            mainSessionKey = state.mainSessionKey,
        )
        val refreshIdx = 2 + choices.size.coerceAtLeast(1)
        if (idx == refreshIdx) {
            openClawRefreshSessions()
            popTone()
            return
        }
        val choice = choices.getOrNull(idx - 2) ?: return
        if (choice.key != state.selectedSessionKey) {
            openClawSwitchSession(choice.key)
        }
        state.back()
    }

    override fun openClawSetFontSize(size: Int) {
        val clamped = size.coerceIn(8, 28)
        state.chatFontSize = clamped
        openClawPrefs.chatFontSize = clamped
        popTone()
    }

    override fun openClawOpenCameraAsk() {
        ensureCameraPerm()
        // Pre-fire BACK before the panel composes. The stepper takes a
        // visible moment to physically rotate from its previous parked
        // angle; firing here gives it a head start while the AnimatedVisibility
        // enter animation runs and Camera2 spins up. The camera panel's
        // DisposableEffect re-fires BACK on compose — serialized through the
        // motor executor, the duplicate is harmless.
        setMotorOrientation(MOTOR_BACK)
        state.openOpenClawCamera()
    }

    override fun openClawCameraCaptured(jpegBytes: ByteArray) {
        // Invoked from the camera2 ImageReader callback on a background
        // HandlerThread. Encode off-main (CPU work), but marshal the Compose
        // state writes onto the main thread — mutableStateOf writes aren't
        // thread-safe and an off-main write can drop the recomposition (the
        // captured image fails to appear) or corrupt snapshot state.
        val b64 = android.util.Base64.encodeToString(jpegBytes, android.util.Base64.NO_WRAP)
        ui.post {
            state.openClawCameraJpegBase64 = b64
            state.openClawCameraError = null
            popTone()
        }
    }

    override fun openClawCameraRetake() {
        state.openClawCameraJpegBase64 = null
        state.openClawCameraError = null
        state.openClawCameraBusy = false
        navTone()
    }

    override fun openClawCameraMotorNudge(delta: Int) {
        val prev = state.openClawCameraMotor
        val next = (prev + delta).coerceIn(0, 180)
        if (next == prev) return
        state.openClawCameraMotor = next
        setMotorOrientation(next)
        navTone()
    }

    override fun openClawCameraSend(prompt: String) {
        val session = openClawSession ?: return
        if (state.chatStatus.startsWith("error") || state.chatStatus == "idle") return
        val image = state.openClawCameraJpegBase64 ?: run {
            state.openClawCameraError = "snap first"
            return
        }
        val trimmed = prompt.trim().ifEmpty { "what do you see?" }
        if (state.openClawCameraBusy) return

        state.openClawCameraBusy = true
        state.openClawCameraError = null
        val optimistic = com.r1.launcher.openclaw.ChatMessage(
            role = "user",
            text = trimmed,
            imageBase64 = image,
            hasImage = true,
        )
        state.chatMessages.add(optimistic)
        trimChatMessages()
        state.chatScrollIndex = 0
        state.chatBusy = true

        session.send(text = trimmed, imageBase64 = image) { ok, runId, err ->
            ui.post {
                state.openClawCameraBusy = false
                if (!ok) {
                    val idx = state.chatMessages.indexOfFirst { it.id == optimistic.id }
                    if (idx >= 0) state.chatMessages.removeAt(idx)
                    state.chatBusy = false
                    state.openClawCameraError = err ?: "send failed"
                    if (!err.isNullOrBlank()) toast("send failed: $err")
                } else {
                    if (runId != null) state.chatPendingRunIds.add(runId)
                    state.openClawCameraJpegBase64 = null
                    state.openClawCameraPrompt = "what do you see?"
                    state.back()
                }
            }
        }
    }

    override fun loadSmsConversations() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED) {
            state.smsError = "permission required"
            return
        }
        if (state.smsLoading) return
        state.smsLoading = true
        state.smsError = null
        Thread {
            val fastList = runCatching {
                com.r1.launcher.messages.SmsLoader.loadConversationsFast(this)
            }.getOrElse { emptyList() }
            ui.post {
                state.smsConversations.clear()
                state.smsConversations.addAll(fastList)
            }

            val fullList = runCatching {
                com.r1.launcher.messages.SmsLoader.loadConversations(this)
            }.getOrElse { fastList }
            ui.post {
                state.smsConversations.clear()
                state.smsConversations.addAll(fullList)
                state.smsLoading = false
                if (fullList.isEmpty() && state.smsError == null) state.smsError = "no messages"
            }
        }.start()
    }

    override fun openSmsThread(address: String, displayName: String) {
        state.openMessagesThread(address, displayName)
        Thread {
            val items = runCatching {
                com.r1.launcher.messages.SmsLoader.loadMessagesFor(this, address)
            }.getOrElse { emptyList() }
            ui.post {
                state.smsThreadMessages.clear()
                state.smsThreadMessages.addAll(items)
                state.smsThreadLoading = false
            }
        }.start()
    }

    private fun refreshVoiceKeyState() {
        val k = voicePrefs.elevenlabsKey
        if (k.isNullOrBlank()) {
            state.hasVoiceKey = false
            state.voiceKeyTail = ""
        } else {
            state.hasVoiceKey = true
            state.voiceKeyTail = k.takeLast(4)
        }
    }

    private fun ensureCameraPerm(): Boolean {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA_PERM)
        }
        return granted
    }

    /** API 33+ runtime grant for the persistent recording-cue notification.
     *  Denial doesn't block recording — a microphone-typed FGS still runs.
     *  Returns true when the perm is granted (or pre-API-33), false otherwise. */
    private fun ensureNotifPerm(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        val granted = ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_NOTIF_PERM)
        }
        return granted
    }

    private fun ensureBtScanPerm(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 31) return true
        val scan = ContextCompat.checkSelfPermission(this, "android.permission.BLUETOOTH_SCAN") ==
            PackageManager.PERMISSION_GRANTED
        val connect = ContextCompat.checkSelfPermission(this, "android.permission.BLUETOOTH_CONNECT") ==
            PackageManager.PERMISSION_GRANTED
        if (!scan || !connect) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"),
                REQ_BT_SCAN_PERM,
            )
        }
        return scan && connect
    }

    private fun ensureAudioPerm(): Boolean {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO_PERM)
        }
        return granted
    }

    private fun ensurePhonePerm(): Boolean {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), REQ_PHONE_PERM)
        }
        return granted
    }

    private fun ensureSmsPerm(): Boolean {
        val read = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val recv = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
        if (!read || !recv) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS),
                REQ_SMS_PERM,
            )
        }
        return read && recv
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PHONE_PERM &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            refreshSim()
        }
        if (requestCode == REQ_SMS_PERM &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            // User just granted READ_SMS while the panel is up; populate now.
            if (state.panel == Panel.MESSAGES) {
                state.smsError = null
                loadSmsConversations()
            }
        }
        if (requestCode == REQ_BT_SCAN_PERM &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            // Permissions just granted — start the scan the user originally requested.
            if (state.panel == Panel.BT_SCAN) startBtScan()
        }
    }

    // --- tones ---

    override fun navTone() {
        when {
            uiClickSoundId != 0 -> playUiClickSound()
            movingSoundId != 0  -> playMovingSound()
            else                -> playTone(ToneGenerator.TONE_PROP_BEEP, 18)
        }
    }

    override fun selectTone() {
        haptics.click()
        when {
            selectSoundId != 0  -> playSelectSound()
            movingSoundId != 0  -> playMovingSound()
            else                -> playTone(ToneGenerator.TONE_PROP_BEEP, 30)
        }
    }

    override fun popTone() {
        haptics.click()
        playMovingSound()
    }

    /** Recording-start cue — record.mp3 at full volume, plus a ToneGenerator
     *  beep as a guaranteed-audible fallback. SoundPool on MTK silently drops
     *  some plays (especially when the audio path is in flux from another
     *  stream just being released), so the ToneGenerator beep makes sure the
     *  user always gets audible feedback that the mic opened. Falls back to
     *  moving.mp3 if the dedicated cue isn't loaded yet. */
    private fun playRecordStartTone() {
        val sid = if (recordStartSoundId != 0) recordStartSoundId else movingSoundId
        val g = uiSoundGain()
        if (sid != 0 && g > 0f) {
            soundPool?.play(sid, g, g, 0, 0, 1f)
        }
        if (g > 0f) {
            runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60) }
        }
    }

    /** Recording-stop cue — release-record.mp3, mirrored shape of start. Same
     *  fallback chain. Slightly different ToneGenerator type (PROMPT vs BEEP)
     *  so even when the mp3 drops the user hears a distinctly different cue
     *  for "released / sending" versus "recording started". */
    private fun playRecordStopTone() {
        val sid = if (recordStopSoundId != 0) recordStopSoundId else movingSoundId
        val g = uiSoundGain()
        if (sid != 0 && g > 0f) {
            soundPool?.play(sid, g, g, 0, 0, 1f)
        }
        if (g > 0f) {
            runCatching { tone?.startTone(ToneGenerator.TONE_PROP_PROMPT, 60) }
        }
    }
    
    override fun backTone() {
        // Was a hardcoded ToneGenerator DTMF beep — that's the synthesized
        // "Android system" tick you hear when wheel-scrolling past the top of
        // a list. Mirror the navTone/selectTone fallback chain so back uses a
        // real mp3; pick the subtle button so back still sounds distinct from
        // forward nav (wooden). DTMF only as last resort.
        when {
            selectSoundId != 0 -> playSelectSound()
            movingSoundId != 0 -> playMovingSound()
            else               -> playTone(ToneGenerator.TONE_PROP_PROMPT, 35)
        }
    }

    private fun launchTone() {
        // Same fix as backTone: prefer a real mp3 over the DTMF "system" beep
        // that fires on every wheel-press app launch.
        when {
            uiClickSoundId != 0 -> playUiClickSound()
            movingSoundId != 0  -> playMovingSound()
            else                -> playTone(ToneGenerator.TONE_PROP_BEEP2, 80)
        }
    }

    private fun uiSoundGain(): Float {
        if (!state.uiSoundEnabled) return 0f
        val max = state.uiVolumeMax.coerceAtLeast(1).toFloat()
        return (state.uiVolumeLevel.toFloat() / max).coerceIn(0f, 1f)
    }

    private fun playMovingSound() {
        if (movingSoundId == 0) return
        val g = uiSoundGain()
        soundPool?.play(movingSoundId, g, g, 0, 0, 1f)
    }

    private fun playUiClickSound() {
        if (uiClickSoundId == 0) return
        val g = uiSoundGain()
        soundPool?.play(uiClickSoundId, g, g, 0, 0, 1f)
    }

    private fun playSelectSound() {
        if (selectSoundId == 0) return
        val g = uiSoundGain()
        soundPool?.play(selectSoundId, g, g, 0, 0, 1f)
    }

    private fun playTone(type: Int, durationMs: Int) {
        if (!state.uiSoundEnabled) return
        val t = tone ?: return
        runCatching {
            t.stopTone()
            t.startTone(type, durationMs)
        }
    }

    /** Rebuild `tone` with a volume scaled to the user's system-sound slider.
     *  ToneGenerator volume is set at construction (0-100) and immutable
     *  thereafter, so any slider/toggle change has to swap the instance. Called
     *  from onCreate, setUiVolume, and toggleUiSoundEnabled. */
    private fun rebuildTone() {
        runCatching { tone?.release() }
        tone = null
        if (!state.uiSoundEnabled) return
        val max = state.uiVolumeMax.coerceAtLeast(1)
        val pct = (state.uiVolumeLevel * 100 / max).coerceIn(0, 100)
        if (pct == 0) return
        tone = runCatching { ToneGenerator(AudioManager.STREAM_SYSTEM, pct) }.getOrNull()
    }


    // --- key dispatch ---

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode

        if (state.panel == Panel.OPENCLAW_CHAT && isOpenClawPttKey(code)) {
            return handleOpenClawPttKey(event)
        }
        if (state.panel == Panel.HERMES_CHAT && isOpenClawPttKey(code)) {
            return handleHermesPttKey(event)
        }

        // Side button is remapped from KEY_POWER (116) → BUTTON_1 in the keylayout
        // (system/usr/keylayout/mtk-kpd.kl, also overridable via /data/system/devices/keylayout/).
        // HOME-mapped keys collapse to one onNewIntent and POWER is intercepted by
        // PhoneWindowManager — BUTTON_1 reaches us with full DOWN/UP/REPEAT timing
        // so we can implement single/double/long press here.
        //
        //   single tap  → HOME panel: lock screen ; other panels: activate selection
        //   double tap  → from any non-home panel: return to home/clock screen
        //   long press  → HOME panel: power dialog ; OpenClaw chat/talk: PTT (handled
        //                 above by isOpenClawPttKey branch)
        if (code == KeyEvent.KEYCODE_BUTTON_1) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        sideDownAtMs = event.eventTime
                        sideLongFired = false
                        haptics.click()
                    } else if (!sideLongFired && event.eventTime - sideDownAtMs >= SIDE_LONG_PRESS_MS) {
                        // Long press: fires on the first key-repeat past the threshold,
                        // so the user gets feedback before they release.
                        sideLongFired = true
                        haptics.heavyClick()
                        pendingSideSingle?.let { ui.removeCallbacks(it) }
                        pendingSideSingle = null
                        sideLastShortUpMs = 0L
                        if (state.panel == Panel.HOME) {
                            // Clock-screen talk shortcut: jump into the chosen AI
                            // chat and fire its push-to-talk. Release is handled by
                            // that panel's existing PTT routing (see homeTalkStart).
                            // When unset, keep the legacy power-dialog behavior.
                            when (state.talkShortcut) {
                                com.r1.launcher.voice.VoicePrefs.TALK_HERMES ->
                                    homeTalkStart(com.r1.launcher.voice.VoicePrefs.TALK_HERMES, code)
                                com.r1.launcher.voice.VoicePrefs.TALK_OPENCLAW ->
                                    homeTalkStart(com.r1.launcher.voice.VoicePrefs.TALK_OPENCLAW, code)
                                else -> PowerService.openPowerDialog()
                            }
                        } else if (state.panel == Panel.TERMINAL) {
                            // Push-to-talk dictation. Stop fires on the matching UP
                            // (handled below in the sideLongFired branch).
                            terminalRecordStart()
                        } else if (state.panel == Panel.TRANSLATOR) {
                            // PTT translation. Commit auto-submits to the LLM
                            // (see VoiceSink.TRANSLATOR branch in handleCommittedTranscript).
                            translatorRecordStart()
                        } else if (state.panel == Panel.CHAT) {
                            // Push-to-talk. Release is mirrored on UP below.
                            chatRecordStart()
                        } else if (state.panel == Panel.GALLERY_VIEW) {
                            // Hold-to-talk for the AI image edit. Release is
                            // mirrored in the sideLongFired branch on UP.
                            galleryAiRecordStart()
                        }
                    }
                }
                KeyEvent.ACTION_UP -> {
                    if (sideLongFired) {
                        // Long-press already handled at DOWN-repeat. Mirror the
                        // start side-effect on UP for the terminal PTT path so
                        // recording stops when the user releases.
                        if (state.panel == Panel.TERMINAL) terminalRecordStop()
                        else if (state.panel == Panel.TRANSLATOR) translatorRecordStop()
                        else if (state.panel == Panel.GALLERY_VIEW) galleryAiRecordStop()
                        else if (state.panel == Panel.CHAT) chatRecordStop()
                        sideLongFired = false
                        return true
                    }
                    val now = System.currentTimeMillis()
                    val sincePrev = now - sideLastShortUpMs
                    if (sincePrev < SIDE_DOUBLE_PRESS_MS && pendingSideSingle != null) {
                        // Double-tap: cancel the pending single, go to home/clock.
                        ui.removeCallbacks(pendingSideSingle!!)
                        pendingSideSingle = null
                        sideLastShortUpMs = 0L
                        if (state.panel != Panel.HOME) {
                            haptics.doubleClick()
                            state.goHome()
                        }
                    } else {
                        // Defer single-tap so a follow-up press can convert it to a double.
                        sideLastShortUpMs = now
                        val action = Runnable {
                            pendingSideSingle = null
                            when (state.panel) {
                                Panel.HOME -> lockScreen()
                                // Recording panel uses tap-to-toggle instead of
                                // PTT hold — long meetings make hold-to-record
                                // impractical. Tap also stops a running record.
                                Panel.TRANSCRIBER_RECORDING -> transcriberToggleRecording()
                                else -> state.activate(this)
                            }
                        }
                        pendingSideSingle = action
                        ui.postDelayed(action, SIDE_DOUBLE_PRESS_MS)
                    }
                }
            }
            return true
        }

        if (event.action != KeyEvent.ACTION_DOWN) {
            return if (isHandled(code)) true else super.dispatchKeyEvent(event)
        }

        return when (code) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_PAGE_UP -> { state.wheelUp(this); true }

            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN -> { state.wheelDown(this); true }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_CALL,
            KeyEvent.KEYCODE_ASSIST,
            KeyEvent.KEYCODE_VOICE_ASSIST,
            KeyEvent.KEYCODE_POWER -> { state.activate(this); true }

            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> { state.backPressed(this); true }

            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun handleOpenClawPttKey(event: KeyEvent): Boolean {
        val canceled = (event.flags and KeyEvent.FLAG_CANCELED) != 0
        android.util.Log.d(
            "LauncherActivity",
            "OPENCLAW_PTT code=${event.keyCode} action=${event.action} rpt=${event.repeatCount} " +
                "canceled=$canceled flags=0x${Integer.toHexString(event.flags)} " +
                "down=${event.downTime} event=${event.eventTime}"
        )
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    openClawPttKeyCode = event.keyCode
                    openClawRecordStart()
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (openClawPttKeyCode == event.keyCode) {
                    openClawPttKeyCode = KeyEvent.KEYCODE_UNKNOWN
                    if (!canceled) openClawRecordStop()
                }
                return true
            }
        }
        return true
    }

    private var hermesPttKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN

    private fun handleHermesPttKey(event: KeyEvent): Boolean {
        val canceled = (event.flags and KeyEvent.FLAG_CANCELED) != 0
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    hermesPttKeyCode = event.keyCode
                    hermesRecordStart()
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (hermesPttKeyCode == event.keyCode) {
                    hermesPttKeyCode = KeyEvent.KEYCODE_UNKNOWN
                    if (!canceled) hermesRecordStop()
                }
                return true
            }
        }
        return true
    }

    private fun isOpenClawPttKey(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_BUTTON_1,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_CALL,
        KeyEvent.KEYCODE_ASSIST,
        KeyEvent.KEYCODE_VOICE_ASSIST,
        KeyEvent.KEYCODE_POWER -> true
        else -> false
    }

    private fun isHandled(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_CALL,
        KeyEvent.KEYCODE_ASSIST,
        KeyEvent.KEYCODE_VOICE_ASSIST,
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_ESCAPE -> true
        else -> false
    }


    // --- misc helpers ---

    /** Generic toast — gray edge. Use [toastSuccess] / [toastFail] for outcomes. */
    private fun toast(msg: String) {
        ui.post { state.showToast(msg, ToastKind.INFO) }
    }

    /** Outcome toast — orange edge. */
    private fun toastSuccess(msg: String) {
        ui.post { state.showToast(msg, ToastKind.SUCCESS) }
    }

    /** Outcome toast — red edge. */
    private fun toastFail(msg: String) {
        ui.post { state.showToast(msg, ToastKind.FAIL) }
    }

    /**
     * Gate for the three exported control broadcasts. The receivers stay
     * exported (so `adb shell am broadcast` still works), but the caller must
     * pass `--es secret <NotifPrefs.controlSecret>`. Without it, ANY installed
     * app could set the user's keys, repoint Hermes, or switch on the LAN web
     * server. The secret is shown read-only in Settings → Credentials. We toast
     * on a wrong/missing secret so the legit user knows to add it; constant-time
     * compare so a co-resident app can't byte-probe it (and there's no timing
     * channel over a fire-and-forget broadcast anyway).
     */
    private fun controlSecretOk(i: Intent?): Boolean {
        val provided = i?.getStringExtra("secret").orEmpty()
        val ok = provided.isNotEmpty() &&
            constantTimeEquals(provided, notifPrefs.controlSecret)
        if (!ok) toastFail("control secret required (settings → creds)")
        return ok
    }

    /** Length-independent constant-time string compare for secrets. */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        java.security.MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8),
        )

    // ============================================================
    // Meetings (transcriber) — host implementations
    // ============================================================

    private fun bindTranscriberService() {
        if (transcriberServiceBound) return
        val intent = Intent(this, TranscriberRecordingService::class.java)
        transcriberServiceBound = bindService(intent, transcriberServiceConn, Context.BIND_AUTO_CREATE)
    }

    private fun unbindTranscriberService() {
        if (!transcriberServiceBound) return
        runCatching { unbindService(transcriberServiceConn) }
        transcriberServiceBound = false
        transcriberBinder = null
    }

    private fun refreshTranscriberPrefsCache() {
        state.hasSmtp = transcriberPrefs.hasSmtp()
        state.smtpHostDisplay = transcriberPrefs.smtpHost
        state.smtpPortDisplay = transcriberPrefs.smtpPort
        state.smtpUserDisplay = transcriberPrefs.smtpUser ?: ""
        state.defaultRecipientDisplay = transcriberPrefs.defaultRecipient
    }

    private fun reloadMeetings() {
        val list = meetingStore.listMeetings()
        state.meetings.clear()
        state.meetings.addAll(list)
        // Layout: 0=back, 1=settings, 2=record, 3..N+2=meetings — last valid
        // index is 2 + N. Snap focus back to back-pill if it would point past
        // the end after a deletion.
        val maxIdx = 2 + list.size
        if (state.transcriberListFocus > maxIdx) {
            state.transcriberListFocus = 0
        }
    }

    override fun transcriberOpen() {
        refreshTranscriberPrefsCache()
        reconcileStaleMeetings()
        reloadMeetings()
        bindTranscriberService()
        state.openTranscriberList()
    }

    /**
     * Mark any meeting still flagged RECORDING that isn't the currently-live
     * recording as FAILED. A process kill mid-record (low-mem / force-stop /
     * crash) otherwise leaves the meeting stuck at "recording…" forever with a
     * moov-less, unplayable m4a and no path to play/retry/delete cleanly. This
     * is the reconciler the TranscriberRecordingService doc-comment promised but
     * was never implemented. Runs off-main (file I/O); refreshes the list when
     * it changed anything.
     */
    private fun reconcileStaleMeetings() {
        transcriberExecutor.execute {
            runCatching {
                val livePath = transcriberBinder?.takeIf { it.isRecording }?.activePath
                var changed = false
                meetingStore.listMeetings()
                    .filter { it.status == MeetingStatus.RECORDING }
                    .forEach { entry ->
                        val m = meetingStore.loadMeeting(entry.uuid) ?: return@forEach
                        // Skip the genuinely-live recording (recovery from a
                        // low-mem kill where the FGS survived but the activity
                        // didn't).
                        if (livePath != null && m.audioPath == livePath) return@forEach
                        m.status = MeetingStatus.FAILED
                        m.errorMessage = "recording interrupted"
                        meetingStore.save(m)
                        changed = true
                    }
                if (changed) ui.post {
                    if (state.panel == Panel.TRANSCRIBER_LIST) reloadMeetings()
                }
            }
        }
    }

    override fun transcriberStartRecording() {
        if (!ensureAudioPerm()) return
        ensureNotifPerm() // best-effort — denial doesn't block recording
        // Conflict guard: STT capture for chat/terminal/claude already holds
        // the mic. Refuse rather than silently corrupt both.
        if (voiceCapture != null) {
            toastFail("voice capture active")
            return
        }
        if (transcriberBinder?.isRecording == true) {
            // Already recording — just open the panel.
            state.openTranscriberRecording()
            return
        }
        val key = voicePrefs.elevenlabsKey
        if (key.isNullOrBlank()) {
            toastFail("set elevenlabs key in settings → voice")
            return
        }

        val uuid = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val title = "Meeting " + SimpleDateFormat("yyyy-MM-dd HH:mm", com.r1.launcher.locale.digitFriendlyLocale()).format(Date(now))
        val audioPath = meetingStore.audioFile(uuid).absolutePath
        val meeting = Meeting(
            uuid = uuid,
            title = title,
            createdAtMs = now,
            audioPath = audioPath,
            status = MeetingStatus.RECORDING,
            // Snapshot the API key so a mid-recording Settings → Voice → "clear key"
            // doesn't break the upload after stop.
            apiKeySnapshot = key,
        )
        meetingStore.save(meeting)
        transcriberCurrentMeeting = meeting

        // Kick the FGS via startForegroundService (must call startForeground()
        // within 10s — the service does that synchronously in onStartCommand).
        val intent = TranscriberRecordingService.startIntent(this, audioPath, title)
        ContextCompat.startForegroundService(this, intent)
        bindTranscriberService()

        state.recordingActive = true
        state.recordingElapsedMs = 0L
        state.recordingPeak = 0
        ui.removeCallbacks(transcriberPollRunnable)
        ui.post(transcriberPollRunnable)
        playRecordStartTone()
        reloadMeetings()
        state.openTranscriberRecording()
    }

    override fun transcriberStopRecording() {
        val meeting = transcriberCurrentMeeting
        val binder = transcriberBinder
        if (binder == null || !binder.isRecording) {
            // Nothing recording — defensive cleanup of any stale state.
            state.recordingActive = false
            state.recordingElapsedMs = 0L
            transcriberCurrentMeeting = null
            return
        }
        val elapsed = binder.elapsedMs
        ui.removeCallbacks(transcriberPollRunnable)
        state.recordingActive = false
        state.recordingElapsedMs = 0L
        state.recordingPeak = 0
        playRecordStopTone()
        transcriberCurrentMeeting = null
        // Drop the user back onto the list panel so the new entry is visible.
        if (state.panel == Panel.TRANSCRIBER_RECORDING) {
            state.openTranscriberList()
        }

        if (meeting == null) {
            // No meeting to upload — just tear the FGS down.
            startService(TranscriberRecordingService.stopIntent(this))
            return
        }
        transcriberExecutor.execute {
            // Finalize the MP4 synchronously through the bound service so the
            // moov atom is guaranteed written before we upload. The old code
            // fired startService(stopIntent) then waited a fixed 500ms, but that
            // delivery is an IPC round-trip whose latency can exceed 500ms on a
            // loaded device — losing the race and uploading a truncated file
            // that Scribe rejects (meeting wrongly marked FAILED).
            runCatching { binder.finalizeRecording() }
            // FGS teardown — recorder is already released, so this just drops
            // the notification + stops the service. Marshalled to main.
            ui.post { startService(TranscriberRecordingService.stopIntent(this)) }
            meeting.durationMs = elapsed
            meeting.status = MeetingStatus.QUEUED
            meetingStore.save(meeting)
            ui.post { reloadMeetings() }
            kickTranscription(meeting)
        }
    }

    override fun transcriberToggleRecording() {
        if (transcriberBinder?.isRecording == true) transcriberStopRecording()
        else transcriberStartRecording()
    }

    private fun kickTranscription(meeting: Meeting) {
        val key = meeting.apiKeySnapshot ?: voicePrefs.elevenlabsKey
        if (key.isNullOrBlank()) {
            meeting.status = MeetingStatus.FAILED
            meeting.errorMessage = "no api key"
            meetingStore.save(meeting)
            ui.post {
                reloadMeetings()
                toastFail("no elevenlabs key")
            }
            return
        }
        meeting.status = MeetingStatus.TRANSCRIBING
        meetingStore.save(meeting)
        ui.post {
            reloadMeetings()
            state.transcribeBusy = true
        }
        transcriberExecutor.submit {
            val client = ScribeClient(key)
            val audio = java.io.File(meeting.audioPath)
            val result = client.transcribe(audio)
            ui.post { state.transcribeBusy = false }
            when (result) {
                is ScribeClient.Result.Success -> {
                    val text = TranscriptFormatter.render(result.response, meeting.speakerNames)
                    meeting.transcriptJson = result.rawJson
                    meeting.transcriptText = text
                    meeting.languageCode = result.response.language_code
                    meeting.speakerCount = TranscriptFormatter.distinctSpeakerCount(result.response)
                    meeting.status = MeetingStatus.TRANSCRIBED
                    meeting.errorMessage = null
                    // Strip the API-key snapshot now that the upload succeeded.
                    meeting.apiKeySnapshot = null
                    meetingStore.save(meeting)
                    ui.post {
                        reloadMeetings()
                        toastSuccess("transcribed: ${meeting.speakerCount} speaker${if (meeting.speakerCount == 1) "" else "s"}")
                    }
                }
                is ScribeClient.Result.Failure -> {
                    meeting.status = MeetingStatus.FAILED
                    meeting.errorMessage = "[HTTP ${result.httpCode}] ${result.message}"
                    meetingStore.save(meeting)
                    ui.post {
                        reloadMeetings()
                        toastFail("transcribe: ${result.message}".take(80))
                    }
                }
            }
        }
    }

    override fun transcriberOpenDetail(uuid: String) {
        bindTranscriberService()
        state.openTranscriberDetail(uuid)
    }

    override fun transcriberRetryTranscribe(uuid: String) {
        val m = meetingStore.loadMeeting(uuid) ?: return
        if (m.apiKeySnapshot.isNullOrBlank()) m.apiKeySnapshot = voicePrefs.elevenlabsKey
        kickTranscription(m)
    }

    override fun transcriberDelete(uuid: String) {
        runCatching { transcriberPlayer?.release() }
        transcriberPlayer = null
        state.detailPlaying = false
        meetingStore.delete(uuid)
        if (state.currentMeetingUuid == uuid) state.currentMeetingUuid = null
        reloadMeetings()
        if (state.panel == Panel.TRANSCRIBER_DETAIL) state.openTranscriberList()
    }

    override fun transcriberPlayAudio(uuid: String) {
        val audio = meetingStore.audioFile(uuid)
        if (!audio.exists() || audio.length() == 0L) {
            toastFail("audio missing")
            return
        }
        runCatching { transcriberPlayer?.release() }
        transcriberPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setDataSource(audio.absolutePath)
            // Async prepare — a meeting m4a can be tens of MB; a synchronous
            // prepare() here would block the main thread (ANR) at the moment the
            // user taps play. start() fires from onPrepared instead.
            setOnPreparedListener { it.start() }
            setOnCompletionListener {
                state.detailPlaying = false
                runCatching { it.release() }
                if (transcriberPlayer === it) transcriberPlayer = null
            }
            setOnErrorListener { mp, _, _ ->
                state.detailPlaying = false
                runCatching { mp.release() }
                if (transcriberPlayer === mp) transcriberPlayer = null
                true
            }
            prepareAsync()
        }
        state.detailPlaying = true
    }

    override fun transcriberStopAudio() {
        runCatching { transcriberPlayer?.stop() }
        runCatching { transcriberPlayer?.release() }
        transcriberPlayer = null
        state.detailPlaying = false
    }

    override fun transcriberShareEmail(uuid: String, recipient: String) {
        if (!transcriberPrefs.hasSmtp()) {
            toastFail("set smtp creds in meetings → settings")
            return
        }
        val recipientFinal = recipient.ifBlank { transcriberPrefs.defaultRecipient }
        if (recipientFinal.isBlank()) {
            toastFail("recipient empty")
            return
        }
        val meeting = meetingStore.loadMeeting(uuid) ?: run {
            toastFail("meeting missing")
            return
        }
        val transcript = meeting.transcriptText
            ?: meeting.errorMessage?.let { "(transcription failed: $it)" }
            ?: ""
        state.detailStatus = "sending..."
        transcriberExecutor.submit {
            val sender = SmtpSender(transcriberPrefs)
            val result = sender.send(meeting, recipientFinal, java.io.File(meeting.audioPath), transcript)
            ui.post {
                when (result) {
                    is SmtpSender.Result.Success -> {
                        state.detailStatus = "sent to ${recipientFinal.take(28)}"
                        toastSuccess("email sent")
                    }
                    is SmtpSender.Result.Failure -> {
                        state.detailStatus = "send failed: ${result.message.take(40)}"
                        toastFail("send failed: ${result.message.take(60)}")
                    }
                }
            }
        }
    }

    override fun transcriberOpenSettings() {
        refreshTranscriberPrefsCache()
        state.openTranscriberSettings()
    }

    override fun transcriberSettingsRowActivate(idx: Int) {
        when (idx) {
            0 -> { state.back(); backTone() }
            1 -> openTranscriberKeyboard("host", transcriberPrefs.smtpHost)
            2 -> openTranscriberKeyboard("port", transcriberPrefs.smtpPort.toString())
            3 -> openTranscriberKeyboard("user", transcriberPrefs.smtpUser ?: "")
            4 -> openTranscriberKeyboard("password", "")
            5 -> openTranscriberKeyboard("recipient", transcriberPrefs.defaultRecipient)
            6 -> { transcriberClearSmtp(); toastSuccess("smtp cleared") }
        }
    }

    private fun openTranscriberKeyboard(field: String, current: String) {
        state.transcriberSettingsEditField = field
        state.transcriberSettingsEditInput = current
    }

    override fun transcriberSaveSmtpField(field: String, value: String) {
        when (field) {
            "host" -> transcriberPrefs.smtpHost = value.ifBlank { TranscriberPrefs.DEFAULT_HOST }
            "port" -> transcriberPrefs.smtpPort = value.toIntOrNull()?.coerceIn(1, 65535) ?: TranscriberPrefs.DEFAULT_PORT
            "user" -> transcriberPrefs.smtpUser = value.ifBlank { null }
            "password" -> transcriberPrefs.smtpPassword = value.ifBlank { null }
            "recipient" -> transcriberPrefs.defaultRecipient = value
        }
        refreshTranscriberPrefsCache()
        state.transcriberSettingsEditField = ""
        state.transcriberSettingsEditInput = ""
    }

    override fun transcriberPasteSmtpField(field: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val raw = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty().trim()
        if (raw.isEmpty()) {
            toast("clipboard empty")
            return
        }
        state.transcriberSettingsEditInput = raw
    }

    override fun transcriberClearSmtp() {
        transcriberPrefs.clear()
        refreshTranscriberPrefsCache()
    }

    override fun transcriberPasteRecipient() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val raw = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty().trim()
        if (raw.isEmpty()) {
            toast("clipboard empty")
            return
        }
        state.transcriberRecipientInput = raw
    }

    override fun transcriberOpenDetailMenu() {
        val uuid = state.currentMeetingUuid ?: return
        val meeting = meetingStore.loadMeeting(uuid) ?: return
        val actions = mutableListOf<com.r1.launcher.transcriber.TranscriberDetailAction>()
        when (meeting.status) {
            com.r1.launcher.transcriber.MeetingStatus.TRANSCRIBED -> {
                actions += com.r1.launcher.transcriber.TranscriberDetailAction.PLAY_TOGGLE
                actions += com.r1.launcher.transcriber.TranscriberDetailAction.EMAIL
                actions += com.r1.launcher.transcriber.TranscriberDetailAction.DELETE
            }
            com.r1.launcher.transcriber.MeetingStatus.FAILED -> {
                actions += com.r1.launcher.transcriber.TranscriberDetailAction.RETRY
                actions += com.r1.launcher.transcriber.TranscriberDetailAction.DELETE
            }
            else -> {
                // Recording / queued / transcribing — only safe action is delete
                // (which also stops the FGS via transcriberDelete()'s normal path).
                actions += com.r1.launcher.transcriber.TranscriberDetailAction.DELETE
            }
        }
        actions += com.r1.launcher.transcriber.TranscriberDetailAction.CLOSE
        state.transcriberDetailMenuActions.clear()
        state.transcriberDetailMenuActions.addAll(actions)
        state.transcriberDetailMenuFocus = 0
        state.transcriberDetailMenuOpen = true
    }

    override fun transcriberDetailMenuActivate(
        action: com.r1.launcher.transcriber.TranscriberDetailAction,
    ) {
        val uuid = state.currentMeetingUuid
        when (action) {
            com.r1.launcher.transcriber.TranscriberDetailAction.PLAY_TOGGLE -> {
                if (state.detailPlaying) transcriberStopAudio()
                else uuid?.let { transcriberPlayAudio(it) }
                // Keep the menu open so the user can stop without re-tapping ⋮.
            }
            com.r1.launcher.transcriber.TranscriberDetailAction.EMAIL -> {
                uuid?.let { transcriberShareEmail(it, state.transcriberRecipientInput) }
                state.transcriberDetailMenuOpen = false
            }
            com.r1.launcher.transcriber.TranscriberDetailAction.RETRY -> {
                uuid?.let { transcriberRetryTranscribe(it) }
                state.transcriberDetailMenuOpen = false
            }
            com.r1.launcher.transcriber.TranscriberDetailAction.DELETE -> {
                uuid?.let { transcriberDelete(it) }
                state.transcriberDetailMenuOpen = false
            }
            com.r1.launcher.transcriber.TranscriberDetailAction.CLOSE -> {
                state.transcriberDetailMenuOpen = false
            }
        }
    }

    // --- notifications ---

    override fun notify(
        source: String,
        title: String,
        body: String,
        deeplink: String?,
    ) {
        // Suppress notifications we'd just be telling the user about themselves:
        // when the source matches the current panel, the chime + badge are pure
        // noise. The originating call site usually pre-filters too, but this is
        // a cheap belt to keep the contract honest for new ingress paths.
        val deeplinkPanel = panelForDeeplink(deeplink)
        if (deeplinkPanel != null && deeplinkPanel == state.panel) return
        val id = com.r1.launcher.notifications.NotificationStore.nextId(this)
        val n = com.r1.launcher.notifications.Notification(
            id = id,
            source = source,
            title = title,
            body = body,
            timestamp = System.currentTimeMillis(),
            read = false,
            deeplink = deeplink,
        )
        com.r1.launcher.notifications.NotificationStore.append(this, n)
        // Mutate UI state on the main thread — `notify` can be called from
        // background threads (Hermes/OpenClaw event callbacks, webhook server
        // executor) so we centralize the post here rather than at every site.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyNotificationToState(n)
        } else {
            ui.post { applyNotificationToState(n) }
        }
    }

    private fun applyNotificationToState(n: com.r1.launcher.notifications.Notification) {
        state.notifications.add(n)
        state.notificationsUnread = state.notifications.count { !it.read }
        // Surface a slide-in card on HOME for ~4s. Only meaningful while HOME
        // is the active panel — the HomeScreen composable gates render on that.
        state.notificationBanner = n
        playNotificationChime()
        webServer?.broadcastNotification(n)
    }

    override fun notificationActivate(id: Long) {
        // Mark read first so the unread count drops immediately even if the
        // user backs out of the deeplinked panel without doing anything.
        com.r1.launcher.notifications.NotificationStore.markRead(this, id)
        val idx = state.notifications.indexOfFirst { it.id == id }
        if (idx >= 0) {
            state.notifications[idx] = state.notifications[idx].copy(read = true)
            state.notificationsUnread = state.notifications.count { !it.read }
        }
        val n = state.notifications.getOrNull(idx)
        val target = panelForDeeplink(n?.deeplink)
        if (target == null) {
            // No link — stay on NOTIFICATIONS. The mark-read above is the
            // user-visible effect.
            return
        }
        // Best-effort deeplink. Each app has its own open() helper that
        // resets focus/scroll, so use those rather than directly setting panel.
        when (target) {
            Panel.OPENCLAW_CHAT -> {
                if (openClawPrefs.hasPairing()) {
                    openClawStartSession()
                    state.openOpenClawChat()
                } else {
                    // Not paired yet — drop into QR so the user can fix it.
                    state.openOpenClawQr()
                }
            }
            Panel.HERMES_CHAT -> {
                hydrateHermesStateFromPrefs()
                if (hermesPrefs.hasConfig()) state.openHermesChat()
                else state.openHermesConfig(fromChat = false)
            }
            Panel.MESSAGES -> {
                if (ContextCompat.checkSelfPermission(
                        this, Manifest.permission.READ_SMS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    state.openMessages()
                    loadSmsConversations()
                } else {
                    state.openMessages()
                }
            }
            Panel.NOTIFICATIONS -> { /* already there */ }
            else -> { /* unknown deeplink — leave panel as-is */ }
        }
    }

    override fun notificationsMarkAllRead() {
        com.r1.launcher.notifications.NotificationStore.markAllRead(this)
        for (i in state.notifications.indices) {
            if (!state.notifications[i].read) {
                state.notifications[i] = state.notifications[i].copy(read = true)
            }
        }
        state.notificationsUnread = 0
    }

    override fun notificationsClear() {
        val before = state.notifications.size
        // Stamp the clear time BEFORE wiping anything else — NtfySubscriber's
        // frame handler reads this as a time fence; any frame whose server
        // `time` is older than this gets dropped on the way in. Synchronous
        // commit so a process death between here and the next reconnect
        // can't lose the fence.
        ntfyPrefs.clearedAtMs = System.currentTimeMillis()
        com.r1.launcher.notifications.NotificationStore.clear(this)
        state.notifications.clear()
        state.notificationsUnread = 0
        state.notificationBanner = null
        // Reset wheel focus back to the back-pill: the header-clear row only
        // renders while items.isNotEmpty(), so leaving focus at 1 here would
        // strand the user on a row that no longer paints anything until they
        // wheel away.
        state.notificationsFocus = 0
        // Drop the ntfy resume cursor AND fence the live subscriber: explicit
        // clear is the user's signal that they don't want any more history.
        // Bumping the subscriber's generation cancels the in-flight stream so
        // any backlog frames the dispatcher is mid-parsing get dropped by
        // their stale-gen guard before they re-advance lastMessageId or
        // repost a cleared notification. Falls back to a direct cursor wipe
        // if the subscriber isn't running.
        ntfySubscriber?.resetCursorAndResync() ?: run { ntfyPrefs.lastMessageId = "" }
        android.util.Log.i(
            "NotifClear",
            "cleared=$before stateNow=${state.notifications.size} fenceMs=${ntfyPrefs.clearedAtMs} cursor='${ntfyPrefs.lastMessageId}'",
        )
    }

    override fun toggleNotificationSound(enabled: Boolean) {
        notifPrefs.soundEnabled = enabled
        state.notificationSoundEnabled = enabled
    }

    // --- credentials panel ---

    /** Refresh every credential display mirror in [state] from its backing
     *  store. Cheap — two prefs reads + one token tail. Called from onCreate
     *  + after every credentialsSaveField / credentialsClearField. */
    private fun refreshCredentialsDisplay() {
        // ElevenLabs key — reuse existing hasVoiceKey/voiceKeyTail since
        // refreshVoiceKeyState() already maintains them.
        // Hermes key
        val hk = hermesPrefs.active?.apiKey.orEmpty()
        state.hasHermesKey = hk.isNotBlank()
        state.hermesKeyTail = if (hk.isNotBlank()) hk.takeLast(4) else ""
        // OpenAI key (camera app: transcription + image edit)
        state.hasOpenAiKey = cameraPrefs.hasKey()
        state.openAiKeyTail = cameraPrefs.keyTail()
        // Webhook token — always visible (gen-on-read), no "set" gate.
        state.webhookTokenDisplay = notifPrefs.webhookToken.takeLast(8)
        // Control secret — shown in full (8 chars) so it can be copied into adb
        // commands for the SET_*/TOGGLE_* broadcasts.
        state.controlSecretDisplay = notifPrefs.controlSecret
    }

    /** Short blocking carroot helper for the small probes above. Distinct
     *  from sendToCarrootStreaming because we want the captured stdout for
     *  one-shot commands; the streaming helper is geared toward the
     *  terminal/claude panel's line-by-line UX. */
    private fun sendToCarrootCapture(cmd: String): String {
        return runCatching {
            java.net.Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", 1337), 1500)
                s.soTimeout = 2000
                s.getOutputStream().write((cmd + "\nexit\n").toByteArray())
                s.shutdownOutput()
                s.getInputStream().bufferedReader().readText()
            }
        }.getOrDefault("")
    }

    // ==================== Chat app ====================
    //
    // Generic AI chat: many conversations, streaming replies, push-to-talk,
    // vision input, image generation, and optional spoken replies.
    //
    // Provider-agnostic by construction — ChatClient is an interface and
    // OpenAiChatClient is one implementation. Adding Claude/Gemini means a new
    // client plus a key lookup here, nothing in storage or UI.

    private var chatCall: okhttp3.Call? = null
    private var chatRecorder: com.r1.launcher.voice.StreamingAudioCapture? = null
    private var chatPcm: java.io.ByteArrayOutputStream? = null
    private var chatTtsCall: okhttp3.Call? = null
    private var chatTtsPlayer: android.media.MediaPlayer? = null
    private val chatJobs = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "r1-chat").apply { isDaemon = true }
    }

    private fun hydrateChatPrefs() {
        state.chatSpeak = chatPrefs.speak
        state.chatVoiceAutoSend = chatPrefs.voiceAutoSend
        state.chatSystemPrompt = chatPrefs.systemPrompt
        state.chatTextSize = chatPrefs.fontSize
        state.chatProviderId = chatPrefs.provider.id
        state.chatModel = chatPrefs.model
    }

    private fun reloadChatHistory() {
        val list = com.r1.launcher.chat.ChatStore.list(this)
        state.chatHistory.clear()
        state.chatHistory.addAll(list)
    }

    /** Snapshot the in-memory turns to disk. Called after every completed turn. */
    private fun persistChat() {
        if (state.chatId.isBlank()) return
        val msgs = state.chatMsgs.toList()
        if (msgs.isEmpty()) return
        val convo = com.r1.launcher.chat.Conversation(
            id = state.chatId,
            title = state.chatTitle,
            provider = com.r1.launcher.chat.Provider.byId(state.chatProviderId),
            model = state.chatModel.ifBlank { chatPrefs.model },
            messages = msgs,
            updatedAt = System.currentTimeMillis(),
        )
        chatJobs.execute {
            com.r1.launcher.chat.ChatStore.save(this, convo)
            ui.post { reloadChatHistory() }
        }
    }

    override fun chatNew() {
        chatStop()
        state.chatId = com.r1.launcher.chat.ChatStore.newId()
        state.chatTitle = "new chat"
        state.chatMsgs.clear()
        state.chatInput = ""
        state.chatAttachment = null
        state.chatStreaming = ""
        state.chatResetPhase()
        state.chatModel = chatPrefs.model
        selectTone()
        state.openChat()
    }

    override fun chatOpen(id: String) {
        chatStop()
        val convo = com.r1.launcher.chat.ChatStore.load(this, id)
        if (convo == null) { toastFail("couldn't open chat"); return }
        state.chatId = convo.id
        state.chatTitle = convo.title
        state.chatModel = convo.model
        state.chatMsgs.clear()
        state.chatMsgs.addAll(convo.messages)
        state.chatInput = ""
        state.chatAttachment = null
        state.chatStreaming = ""
        state.chatResetPhase()
        selectTone()
        state.openChat()
    }

    override fun chatDelete(id: String) {
        if (com.r1.launcher.chat.ChatStore.delete(this, id)) {
            if (state.chatId == id) state.chatId = ""
            reloadChatHistory()
            state.chatListFocus = state.chatListFocus.coerceIn(0, state.chatHistory.size + 1)
            toast("deleted")
        } else toastFail("couldn't delete")
    }

    override fun chatToggleImageMode() {
        state.chatImageMode = !state.chatImageMode
        popTone()
    }

    override fun chatToggleSpeak() {
        chatPrefs.speak = !chatPrefs.speak
        state.chatSpeak = chatPrefs.speak
        if (!state.chatSpeak) cancelChatSpeech()
        popTone()
        toast(if (state.chatSpeak) "replies will be spoken" else "speech off")
    }

    /** Attach the newest camera photo. Reuses the Camera app's store so the two
     *  apps share one library instead of each keeping its own. */
    override fun chatAttachNewestPhoto() {
        val newest = com.r1.launcher.camera.PhotoStore.list(this).firstOrNull()
        if (newest == null) { toast("no photos yet — take one first"); return }
        state.chatAttachment = newest.path
        popTone()
        toast("photo attached")
    }

    override fun chatSend() {
        val key = cameraPrefs.openAiKey
        if (key.isNullOrBlank()) {
            state.chatPhase = com.r1.launcher.ChatPhase.ERROR
            state.chatError = "no openai key — settings → creds"
            return
        }
        val text = state.chatInput.trim()
        val attachment = state.chatAttachment
        if (text.isEmpty() && attachment == null) return

        if (state.chatId.isBlank()) state.chatId = com.r1.launcher.chat.ChatStore.newId()
        if (state.chatTitle == "new chat" && text.isNotEmpty()) {
            state.chatTitle = com.r1.launcher.chat.Conversation.titleFrom(text)
        }

        state.chatMsgs.add(
            com.r1.launcher.chat.ChatMsg(
                role = com.r1.launcher.chat.Role.USER,
                text = text,
                imagePath = attachment,
            )
        )
        state.chatInput = ""
        state.chatAttachment = null
        state.chatKbVisible = false
        state.chatPinnedToBottom = true
        state.chatScrollTick++
        cancelChatSpeech()

        if (state.chatImageMode) startChatImage(key, text) else startChatStream(key)
    }

    private fun startChatStream(key: String) {
        state.chatPhase = com.r1.launcher.ChatPhase.WAITING
        state.chatPhaseStartedAt = System.currentTimeMillis()
        state.chatError = ""
        state.chatStreaming = ""

        val model = state.chatModel.ifBlank { chatPrefs.model }
        val history = state.chatMsgs.toList()
        chatCall = com.r1.launcher.chat.OpenAiChatClient.stream(
            apiKey = key,
            model = model,
            systemPrompt = chatPrefs.effectiveSystemPrompt(),
            history = history,
            onDelta = { d ->
                ui.post {
                    if (state.chatPhase == com.r1.launcher.ChatPhase.WAITING) {
                        state.chatPhase = com.r1.launcher.ChatPhase.STREAMING
                    }
                    state.chatStreaming += d
                }
            },
            onDone = {
                ui.post {
                    val full = state.chatStreaming
                    state.chatStreaming = ""
                    chatCall = null
                    if (full.isBlank()) {
                        state.chatPhase = com.r1.launcher.ChatPhase.ERROR
                        state.chatError = "empty reply"
                        return@post
                    }
                    state.chatMsgs.add(
                        com.r1.launcher.chat.ChatMsg(
                            role = com.r1.launcher.chat.Role.ASSISTANT,
                            text = full,
                        )
                    )
                    state.chatResetPhase()
                    state.chatScrollTick++
                    persistChat()
                    if (chatPrefs.speak) speakChat(full)
                }
            },
            onError = { msg ->
                ui.post {
                    chatCall = null
                    // Keep whatever streamed in — a truncated answer still has
                    // value and throwing it away is more annoying than the error.
                    val partial = state.chatStreaming
                    state.chatStreaming = ""
                    if (partial.isNotBlank()) {
                        state.chatMsgs.add(
                            com.r1.launcher.chat.ChatMsg(
                                role = com.r1.launcher.chat.Role.ASSISTANT,
                                text = partial,
                            )
                        )
                        persistChat()
                    }
                    state.chatPhase = com.r1.launcher.ChatPhase.ERROR
                    state.chatError = msg
                }
            },
        )
    }

    private fun startChatImage(key: String, prompt: String) {
        state.chatPhase = com.r1.launcher.ChatPhase.IMAGING
        state.chatPhaseStartedAt = System.currentTimeMillis()
        state.chatError = ""
        chatJobs.execute {
            when (val r = com.r1.launcher.camera.OpenAiClient.generateImage(key, prompt)) {
                is com.r1.launcher.camera.OpenAiClient.Result.Err -> ui.post {
                    state.chatPhase = com.r1.launcher.ChatPhase.ERROR
                    state.chatError = r.message
                }
                is com.r1.launcher.camera.OpenAiClient.Result.Ok -> {
                    val saved = com.r1.launcher.camera.PhotoStore.save(
                        this, r.value, com.r1.launcher.camera.PhotoStore.Kind.AI,
                    )
                    ui.post {
                        if (saved == null) {
                            state.chatPhase = com.r1.launcher.ChatPhase.ERROR
                            state.chatError = "couldn't save image"
                        } else {
                            state.chatMsgs.add(
                                com.r1.launcher.chat.ChatMsg(
                                    role = com.r1.launcher.chat.Role.ASSISTANT,
                                    text = "",
                                    imagePath = saved.path,
                                )
                            )
                            state.chatResetPhase()
                            state.chatImageMode = false
                            state.chatScrollTick++
                            persistChat()
                            launchTone()
                        }
                    }
                }
            }
        }
    }

    override fun chatStop() {
        runCatching { chatCall?.cancel() }
        chatCall = null
        cancelChatSpeech()
        // A cancelled stream keeps whatever arrived, same as an errored one.
        val partial = state.chatStreaming
        if (partial.isNotBlank()) {
            state.chatMsgs.add(
                com.r1.launcher.chat.ChatMsg(
                    role = com.r1.launcher.chat.Role.ASSISTANT,
                    text = partial,
                )
            )
            persistChat()
        }
        state.chatStreaming = ""
        state.chatResetPhase()
    }

    override fun chatRetry() {
        val key = cameraPrefs.openAiKey
        if (key.isNullOrBlank()) { toastFail("no openai key"); return }
        // Drop a failed assistant turn if one got appended, then re-ask.
        while (state.chatMsgs.lastOrNull()?.role == com.r1.launcher.chat.Role.ASSISTANT) {
            state.chatMsgs.removeAt(state.chatMsgs.size - 1)
        }
        if (state.chatMsgs.isEmpty()) { state.chatResetPhase(); return }
        startChatStream(key)
    }

    // ---- push to talk ----

    override fun chatRecordStart() {
        if (state.chatWorking || state.chatPhase == com.r1.launcher.ChatPhase.RECORDING) return
        if (cameraPrefs.openAiKey.isNullOrBlank()) {
            state.chatPhase = com.r1.launcher.ChatPhase.ERROR
            state.chatError = "no openai key — settings → creds"
            return
        }
        if (!ensureAudioPerm()) return
        if (transcriberBinder?.isRecording == true) { toastFail("stop recording first"); return }
        cancelChatSpeech()

        state.chatPhase = com.r1.launcher.ChatPhase.RECORDING
        state.chatPartial = ""
        state.chatMicLevel = 0
        state.chatError = ""

        val sink = java.io.ByteArrayOutputStream()
        chatPcm = sink
        val cap = com.r1.launcher.voice.StreamingAudioCapture()
        chatRecorder = cap
        playRecordStartTone()
        ui.postDelayed({
            if (chatRecorder !== cap) return@postDelayed
            cap.start(object : com.r1.launcher.voice.StreamingAudioCapture.Callback {
                override fun onPcm(chunk: ByteArray) { synchronized(sink) { sink.write(chunk) } }
                override fun onLevel(levelPct: Int) { ui.post { state.chatMicLevel = levelPct } }
                override fun onError(msg: String) {
                    ui.post {
                        state.chatPhase = com.r1.launcher.ChatPhase.ERROR
                        state.chatError = "mic: $msg"
                    }
                }
            })
        }, 200)
    }

    override fun chatRecordStop() {
        val cap = chatRecorder ?: return
        chatRecorder = null
        cap.close()
        playRecordStopTone()
        val sink = chatPcm
        chatPcm = null
        state.chatMicLevel = 0
        ui.postDelayed({
            val pcm = sink?.let { synchronized(it) { it.toByteArray() } } ?: ByteArray(0)
            val key = cameraPrefs.openAiKey
            if (key.isNullOrBlank()) { state.chatResetPhase(); return@postDelayed }
            if (pcm.size < 16_000) {
                state.chatPhase = com.r1.launcher.ChatPhase.ERROR
                state.chatError = "too short — hold longer"
                return@postDelayed
            }
            state.chatPhase = com.r1.launcher.ChatPhase.TRANSCRIBING
            state.chatPhaseStartedAt = System.currentTimeMillis()
            chatJobs.execute {
                when (val t = com.r1.launcher.camera.OpenAiClient.transcribe(key, pcm)) {
                    is com.r1.launcher.camera.OpenAiClient.Result.Err -> ui.post {
                        state.chatPhase = com.r1.launcher.ChatPhase.ERROR
                        state.chatError = t.message
                        state.chatPartial = ""
                    }
                    is com.r1.launcher.camera.OpenAiClient.Result.Ok -> ui.post {
                        state.chatPartial = ""
                        state.chatInput = t.value
                        state.chatResetPhase()
                        if (chatPrefs.voiceAutoSend) chatSend()
                    }
                }
            }
        }, 250)
    }

    // ---- speech ----

    /** Reuses the launcher's ElevenLabs voice so the chat app sounds like every
     *  other spoken surface and inherits Settings → Voice. */
    private fun speakChat(markdown: String) {
        val key = voicePrefs.elevenlabsKey
        if (key.isNullOrBlank()) { toast("set an elevenlabs key for speech"); return }
        val plain = com.r1.launcher.ui.markdownToSpeech(markdown)
        if (plain.isBlank()) return
        state.chatPhase = com.r1.launcher.ChatPhase.SPEAKING
        val out = java.io.File(cacheDir, "chat-speech.mp3")
        chatTtsCall = com.r1.launcher.voice.ElevenLabsTtsClient.synthesize(
            text = plain,
            apiKey = key,
            voiceId = voicePrefs.voiceId,
            model = voicePrefs.model,
            outFile = out,
        ) { bytes, err ->
            ui.post {
                chatTtsCall = null
                if (bytes == null) {
                    if (err != null) toast("speech: $err")
                    if (state.chatPhase == com.r1.launcher.ChatPhase.SPEAKING) state.chatResetPhase()
                    return@post
                }
                runCatching {
                    chatTtsPlayer?.release()
                    val mp = android.media.MediaPlayer()
                    chatTtsPlayer = mp
                    mp.setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    // Path, not a descriptor from a closed stream — see CLAUDE.md.
                    mp.setDataSource(out.absolutePath)
                    mp.setOnCompletionListener {
                        if (state.chatPhase == com.r1.launcher.ChatPhase.SPEAKING) state.chatResetPhase()
                    }
                    mp.prepare()
                    mp.start()
                }.onFailure {
                    toast("speech playback failed")
                    if (state.chatPhase == com.r1.launcher.ChatPhase.SPEAKING) state.chatResetPhase()
                }
            }
        }
    }

    private fun cancelChatSpeech() {
        runCatching { chatTtsCall?.cancel() }
        chatTtsCall = null
        runCatching { chatTtsPlayer?.stop() }
        runCatching { chatTtsPlayer?.release() }
        chatTtsPlayer = null
        if (state.chatPhase == com.r1.launcher.ChatPhase.SPEAKING) state.chatResetPhase()
    }

    // ---- settings rows ----

    override fun chatSettingsActivate(idx: Int) {
        // 99 is the overlay's "commit the open field" sentinel.
        if (idx == com.r1.launcher.ui.SAVE_ROW) {
            val v = state.chatEditInput.trim()
            when (state.chatEditField) {
                "model" -> {
                    val m = v.ifBlank {
                        com.r1.launcher.chat.Provider.byId(state.chatProviderId).defaultModel
                    }
                    chatPrefs.model = m
                    state.chatModel = m
                    toastSuccess("model: $m")
                }
                "system" -> {
                    chatPrefs.systemPrompt = v
                    state.chatSystemPrompt = v
                    toastSuccess("prompt saved")
                }
            }
            state.chatEditField = ""
            state.chatEditInput = ""
            return
        }
        when (idx) {
            0 -> { state.back(); backTone() }
            1 -> {
                // Cycle only through providers that actually have a client.
                val avail = com.r1.launcher.chat.Provider.entries.filter { it.available }
                val cur = com.r1.launcher.chat.Provider.byId(state.chatProviderId)
                val next = avail[(avail.indexOf(cur).coerceAtLeast(0) + 1) % avail.size]
                chatPrefs.provider = next
                chatPrefs.model = next.defaultModel
                state.chatProviderId = next.id
                state.chatModel = next.defaultModel
                if (avail.size == 1) toast("only openai is wired up so far")
                selectTone()
            }
            2 -> {
                state.chatEditField = "model"
                state.chatEditInput = state.chatModel
            }
            3 -> chatToggleSpeak()
            4 -> {
                chatPrefs.voiceAutoSend = !chatPrefs.voiceAutoSend
                state.chatVoiceAutoSend = chatPrefs.voiceAutoSend
                popTone()
            }
            5 -> {
                state.chatEditField = "system"
                state.chatEditInput = state.chatSystemPrompt
            }
            6 -> {
                chatPrefs.resetSystemPrompt()
                state.chatSystemPrompt = chatPrefs.systemPrompt
                toast("prompt reset")
            }
            7 -> {
                val next = when (state.chatTextSize) {
                    14 -> 16; 16 -> 18; 18 -> 20; 20 -> 14; else -> 16
                }
                chatPrefs.fontSize = next
                state.chatTextSize = next
                popTone()
            }
            8 -> {
                com.r1.launcher.chat.ChatStore.deleteAll(this)
                reloadChatHistory()
                state.chatMsgs.clear()
                state.chatId = ""
                toast("all chats deleted")
            }
        }
    }

    // ==================== Camera app ====================
    //
    // Flow:  CAMERA (preview) -> shutter -> PhotoStore -> GALLERY -> GALLERY_VIEW
    //        GALLERY_VIEW + side-button hold -> mic -> OpenAI transcribe ->
    //        OpenAI image edit -> new photo in the gallery.
    //
    // Every network hop runs on `cameraJobs` and reports back through
    // `state.aiStage`, which is what the on-screen status strip renders. The
    // brief was that the user must never wonder whether it's still working,
    // so each stage transition also fires a toast and the strip carries an
    // elapsed counter (generation measures 20-45 s against OpenAI).

    /** Serial so a second hold can't race a job that's still uploading. */
    private val cameraJobs = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "r1-camera-ai").apply { isDaemon = true }
    }
    private var aiRecorder: com.r1.launcher.voice.StreamingAudioCapture? = null
    private var aiPcm: java.io.ByteArrayOutputStream? = null
    /** Bytes of the photo the current job started from, captured up-front so a
     *  delete or a swipe mid-flight can't change what gets sent. */
    private var aiSourceBytes: ByteArray? = null

    private fun reloadPhotos() {
        val list = com.r1.launcher.camera.PhotoStore.list(this)
        state.photos.clear()
        state.photos.addAll(list)
        if (state.galleryIndex > state.photos.lastIndex) {
            state.galleryIndex = state.photos.lastIndex.coerceAtLeast(0)
        }
    }

    override fun cameraShutter() {
        if (!state.cameraReady || state.cameraCapturing) return
        state.cameraCapturing = true
        state.cameraShutterRequest++
    }

    override fun cameraFlip() {
        // One sensor on a gimbal: "flipping" is a motor move. The panel's
        // DisposableEffect(cameraFacing) issues the actual write.
        state.cameraFacing = if (state.cameraFacingIsFront)
            com.r1.launcher.ui.MOTOR_BACK else com.r1.launcher.ui.MOTOR_FACE
        state.cameraError = null
    }

    override fun cameraOpenGallery() {
        reloadPhotos()
        if (state.photos.isEmpty()) {
            toast("no photos yet")
            return
        }
        selectTone()
        state.openGallery()
    }

    /** Called from the panel for every R1CameraView event. */
    override fun onCameraEvent(event: com.r1.launcher.ui.R1CameraView.Event) {
        when (event) {
            is com.r1.launcher.ui.R1CameraView.Event.Ready -> ui.post {
                state.cameraReady = true
                state.cameraError = null
            }
            is com.r1.launcher.ui.R1CameraView.Event.Failed -> ui.post {
                state.cameraCapturing = false
                state.cameraError = event.message
                toastFail(event.message)
            }
            is com.r1.launcher.ui.R1CameraView.Event.Captured -> {
                val bytes = event.jpeg
                cameraJobs.execute {
                    val saved = com.r1.launcher.camera.PhotoStore.save(
                        this, bytes, com.r1.launcher.camera.PhotoStore.Kind.SHOT,
                    )
                    ui.post {
                        state.cameraCapturing = false
                        state.cameraShutterFlash++
                        if (saved == null) {
                            state.cameraError = "couldn't save photo"
                            toastFail("couldn't save photo")
                        } else {
                            state.cameraError = null
                            reloadPhotos()
                            popTone()
                        }
                    }
                }
            }
        }
    }

    override fun galleryDeleteCurrent() {
        val photo = state.galleryCurrent ?: return
        if (com.r1.launcher.camera.PhotoStore.delete(photo)) {
            val wasIndex = state.galleryIndex
            reloadPhotos()
            if (state.photos.isEmpty()) {
                state.openGallery()
            } else {
                state.galleryIndex = wasIndex.coerceIn(0, state.photos.lastIndex)
            }
            toast("deleted")
        } else {
            toastFail("couldn't delete")
        }
    }

    // ---- voice-driven AI edit ----

    override fun galleryAiRecordStart() {
        if (state.aiBusy) return
        val photo = state.galleryCurrent ?: return
        if (cameraPrefs.openAiKey.isNullOrBlank()) {
            state.aiStage = com.r1.launcher.AiStage.FAILED
            state.aiMessage = "no openai key — settings → creds"
            toastFail("no openai key")
            return
        }
        if (!ensureAudioPerm()) return
        // Mic is single-consumer; the meetings foreground service wins.
        if (transcriberBinder?.isRecording == true) {
            toastFail("stop recording first")
            return
        }
        val bytes = com.r1.launcher.camera.PhotoStore.readBytes(photo)
        if (bytes == null) {
            toastFail("couldn't read photo")
            return
        }
        aiSourceBytes = bytes
        state.aiSourcePath = photo.path
        state.aiStage = com.r1.launcher.AiStage.RECORDING
        state.aiMessage = "say what to change"
        state.aiPrompt = ""
        state.aiLevel = 0

        val sink = java.io.ByteArrayOutputStream()
        aiPcm = sink
        val cap = com.r1.launcher.voice.StreamingAudioCapture()
        aiRecorder = cap
        playRecordStartTone()
        // Same 200ms mic-open delay the other voice paths use: AudioRecord on
        // VOICE_RECOGNITION steals the audio path and would clip the cue.
        ui.postDelayed({
            if (aiRecorder !== cap) return@postDelayed
            cap.start(object : com.r1.launcher.voice.StreamingAudioCapture.Callback {
                override fun onPcm(chunk: ByteArray) {
                    synchronized(sink) { sink.write(chunk) }
                }
                override fun onLevel(levelPct: Int) {
                    ui.post { state.aiLevel = levelPct }
                }
                override fun onError(msg: String) {
                    ui.post { failAi("mic: $msg") }
                }
            })
        }, 200)
    }

    override fun galleryAiRecordStop() {
        val cap = aiRecorder ?: return
        aiRecorder = null
        cap.close()
        playRecordStopTone()
        val sink = aiPcm
        aiPcm = null
        // The capture thread may still be draining a final read; give it a
        // beat before snapshotting, otherwise short holds lose their tail.
        ui.postDelayed({
            val pcm = sink?.let { synchronized(it) { it.toByteArray() } } ?: ByteArray(0)
            startAiJob(pcm)
        }, 250)
    }

    private fun startAiJob(pcm: ByteArray) {
        val key = cameraPrefs.openAiKey
        val image = aiSourceBytes
        if (key.isNullOrBlank()) return failAi("no openai key")
        if (image == null) return failAi("photo went away")
        // ~0.5 s at 16 kHz mono PCM-16. Below this it's a mis-tap, not speech.
        if (pcm.size < 16_000) return failAi("too short — hold the button longer")

        state.aiStage = com.r1.launcher.AiStage.TRANSCRIBING
        state.aiMessage = "sending audio…"
        state.aiStartedAtMs = System.currentTimeMillis()

        cameraJobs.execute {
            when (val t = com.r1.launcher.camera.OpenAiClient.transcribe(key, pcm)) {
                is com.r1.launcher.camera.OpenAiClient.Result.Err -> ui.post { failAi(t.message) }
                is com.r1.launcher.camera.OpenAiClient.Result.Ok -> {
                    val prompt = t.value
                    ui.post {
                        state.aiPrompt = prompt
                        state.aiStage = com.r1.launcher.AiStage.GENERATING
                        state.aiMessage = "this usually takes about 20s"
                        state.aiStartedAtMs = System.currentTimeMillis()
                        toast("generating…")
                    }
                    runImageEdit(key, image, prompt)
                }
            }
        }
    }

    /** Second half of the job. Split out so retry can re-enter here without
     *  making the user speak again. */
    private fun runImageEdit(key: String, image: ByteArray, prompt: String) {
        when (val r = com.r1.launcher.camera.OpenAiClient.editImage(key, image, prompt)) {
            is com.r1.launcher.camera.OpenAiClient.Result.Err -> ui.post { failAi(r.message) }
            is com.r1.launcher.camera.OpenAiClient.Result.Ok -> {
                val saved = com.r1.launcher.camera.PhotoStore.save(
                    this, r.value, com.r1.launcher.camera.PhotoStore.Kind.AI,
                )
                ui.post {
                    if (saved == null) {
                        failAi("couldn't save result")
                    } else {
                        reloadPhotos()
                        // Jump to the new image — it's newest, so index 0.
                        state.galleryIndex = 0
                        state.aiStage = com.r1.launcher.AiStage.DONE
                        state.aiMessage = "added to gallery"
                        toastSuccess("image ready")
                        launchTone()
                    }
                }
            }
        }
    }

    override fun galleryAiRetry() {
        val key = cameraPrefs.openAiKey
        val image = aiSourceBytes
        val prompt = state.aiPrompt
        if (key.isNullOrBlank() || image == null || prompt.isBlank()) {
            state.aiReset()
            toast("hold the side button to try again")
            return
        }
        state.aiStage = com.r1.launcher.AiStage.GENERATING
        state.aiMessage = "retrying…"
        state.aiStartedAtMs = System.currentTimeMillis()
        cameraJobs.execute { runImageEdit(key, image, prompt) }
    }

    private fun failAi(message: String) {
        aiRecorder?.close()
        aiRecorder = null
        aiPcm = null
        state.aiStage = com.r1.launcher.AiStage.FAILED
        state.aiMessage = message
        state.aiLevel = 0
        toastFail(message)
    }

    override fun credentialsRowActivate(idx: Int) {
        // Row layout (kept in sync with SettingsCredentialsPanel):
        //   1=elevenlabs, 2=hermes, 3=openai, 4=ntfy_topic, 5=webhook (regen),
        //   6=control secret (regen)
        when (idx) {
            1 -> { state.credentialsEditField = "elevenlabs"; state.credentialsEditInput = "" }
            2 -> { state.credentialsEditField = "hermes"; state.credentialsEditInput = "" }
            3 -> { state.credentialsEditField = "openai"; state.credentialsEditInput = "" }
            4 -> {
                state.credentialsEditField = "ntfy_topic"
                state.credentialsEditInput = ntfyPrefs.topic
            }
            5 -> {
                // Webhook token — regenerate in place, no keyboard.
                regenerateWebhookToken()
                toast("new webhook token: …${state.webhookTokenDisplay}")
            }
            6 -> {
                // Control secret — regenerate in place. Any open adb snippets
                // must switch to the new value.
                state.controlSecretDisplay = notifPrefs.regenerateControlSecret()
                toast("new control secret: ${state.controlSecretDisplay}")
            }
        }
    }

    override fun credentialsSaveField(field: String, value: String) {
        val v = value.trim()
        when (field) {
            "elevenlabs" -> {
                voiceSaveKey(v)
                refreshCredentialsDisplay()
            }
            "hermes" -> {
                hermesSetApiKey(v)
                refreshCredentialsDisplay()
                if (v.isNotBlank()) toast("hermes key saved")
            }
            "openai" -> {
                if (v.isNotBlank() && !com.r1.launcher.camera.CameraPrefs.looksValid(v)) {
                    toastFail("doesn't look like an openai key")
                } else {
                    cameraPrefs.openAiKey = v
                    refreshCredentialsDisplay()
                    if (v.isNotBlank()) toastSuccess("openai key saved")
                }
            }
            "ntfy_topic" -> {
                ntfySetTopic(v)
            }
        }
        state.credentialsEditField = ""
        state.credentialsEditInput = ""
    }

    override fun credentialsPasteField(field: String) {
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
            ?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty().trim()
        if (clip.isEmpty()) {
            toastFail("clipboard empty")
            return
        }
        state.credentialsEditInput = clip
    }

    override fun credentialsClearField(field: String) {
        when (field) {
            "elevenlabs" -> {
                voiceClearKey()
                refreshCredentialsDisplay()
            }
            "hermes" -> {
                hermesSetApiKey("")
                refreshCredentialsDisplay()
                toast("hermes key cleared")
            }
            "ntfy_topic" -> {
                ntfySetTopic("")
            }
        }
        state.credentialsEditField = ""
        state.credentialsEditInput = ""
    }

    override fun regenerateWebhookToken(): String {
        val fresh = notifPrefs.regenerateWebhookToken()
        state.webhookTokenDisplay = fresh.takeLast(8)
        return fresh
    }

    // --- ntfy.sh subscriber ---

    override fun toggleNtfySubscriber(enabled: Boolean) {
        ntfyPrefs.enabled = enabled
        state.ntfySubscriberEnabled = enabled
        if (enabled) {
            if (!ntfyPrefs.isConfigured()) {
                toastFail("set a topic first")
                ntfyPrefs.enabled = false
                state.ntfySubscriberEnabled = false
                state.ntfyStatus = "disabled"
                return
            }
            startNtfySubscriber()
        } else {
            stopNtfySubscriber()
        }
    }

    override fun ntfySetTopic(topic: String) {
        val t = topic.trim()
        val changed = t != ntfyPrefs.topic
        ntfyPrefs.topic = t
        state.ntfyTopic = t
        if (changed) {
            // New topic = new id space, reset the resume cursor so we don't
            // try to `?since=` an id from a different topic.
            ntfyPrefs.lastMessageId = ""
            if (state.ntfySubscriberEnabled && t.isNotBlank()) {
                ntfySubscriber?.applyTopicChange() ?: startNtfySubscriber()
            } else if (t.isBlank()) {
                stopNtfySubscriber()
                state.ntfySubscriberEnabled = false
                ntfyPrefs.enabled = false
            }
        }
    }

    override fun ntfyConfigRowActivate(idx: Int) {
        // Row layout (kept in sync with NtfyConfigPanel):
        //   0=back, 1=enable toggle, 2=topic, 3=status (info; no action)
        when (idx) {
            0 -> { state.back(); backTone() }
            1 -> toggleNtfySubscriber(!state.ntfySubscriberEnabled)
            2 -> {
                // The keyboard overlay that handles credential edits lives
                // inside SettingsCredentialsPanel and only composes when
                // panel == SETTINGS_CREDENTIALS. So instead of trying to
                // surface it from NTFY_CONFIG (where it would silently
                // never render), jump to the credentials panel with the
                // ntfy.sh topic row focused and the keyboard pre-opened.
                // Row order in SettingsCredentialsPanel (post-Termux rework):
                //   0=header, 1=elevenlabs, 2=hermes, 3=ntfy.sh topic,
                //   4=webhook token
                state.openSettingsCredentials()
                state.credentialsFocus = 3
                state.credentialsEditField = "ntfy_topic"
                state.credentialsEditInput = ntfyPrefs.topic
            }
            3 -> { /* status row — info only */ }
        }
    }

    private fun startNtfySubscriber() {
        if (ntfySubscriber != null) return
        val sub = com.r1.launcher.notifications.NtfySubscriber(this, ntfyPrefs)
        sub.onMessage = { title, body ->
            // Mirror the webhook path — same source enum, same deeplink, same
            // downstream notification handling (chime + badge + banner +
            // persistence + web broadcast).
            val finalTitle = title.ifBlank { "ntfy" }
            notify("ntfy", finalTitle, body, deeplink = "notifications")
        }
        sub.onStatusChange = { s ->
            state.ntfyStatus = when (s) {
                com.r1.launcher.notifications.NtfySubscriber.Status.DISABLED -> "disabled"
                com.r1.launcher.notifications.NtfySubscriber.Status.CONNECTING -> "connecting"
                com.r1.launcher.notifications.NtfySubscriber.Status.LIVE -> "live"
                com.r1.launcher.notifications.NtfySubscriber.Status.RETRYING -> "retry…"
                com.r1.launcher.notifications.NtfySubscriber.Status.ERROR -> "error"
            }
        }
        ntfySubscriber = sub
        sub.start()
    }

    private fun stopNtfySubscriber() {
        runCatching { ntfySubscriber?.stop() }
        ntfySubscriber = null
        state.ntfyStatus = "disabled"
    }

    /** Resolve a deeplink string into a real [Panel], or null for unrouted /
     *  unknown deeplinks. Keep this list short — every panel here needs an
     *  open() helper in [notificationActivate]. */
    private fun panelForDeeplink(deeplink: String?): Panel? = when (deeplink) {
        "openclaw_chat" -> Panel.OPENCLAW_CHAT
        "hermes_chat" -> Panel.HERMES_CHAT
        "messages" -> Panel.MESSAGES
        "notifications" -> Panel.NOTIFICATIONS
        else -> null
    }

    /** Notification chime. Skipped when:
     *   - the master toggle is off (Settings → Sound → "notifications")
     *   - we're currently recording (mic open — beeping into the mic is bad)
     *   - TTS is mid-playback (don't trample assistant readback)
     *   - one fired within [NOTIF_SOUND_MIN_GAP_MS] (burst rate-limit)
     *  Uses STREAM_NOTIFICATION so it respects DnD + the user's notification
     *  volume slider, independent of media / system streams.
     *
     *  Acquires a short PARTIAL_WAKE_LOCK so the CPU stays alive end-to-end
     *  through the tone — without it, the device can re-enter idle mid-tone
     *  with the screen off and clip the audio. Released after the tone
     *  finishes. CPU-only — does NOT wake the screen (matches normal Android
     *  notification behavior). */
    private fun playNotificationChime() {
        if (!state.notificationSoundEnabled) return
        val recording = state.chatRecording || state.terminalRecording ||
            state.hermesRecording
        if (recording) return
        // MediaPlayer.isPlaying throws IllegalStateException when the player
        // is in the END / error state (post-release). The TTS slots may hold
        // stale references mid-teardown, so wrap each probe defensively.
        val ttsBusy = openClawTtsCall != null || hermesTtsCall != null ||
            (runCatching { openClawSpeechPlayer?.isPlaying == true }.getOrDefault(false)) ||
            (runCatching { hermesSpeechPlayer?.isPlaying == true }.getOrDefault(false))
        if (ttsBusy) return
        val now = System.currentTimeMillis()
        if (now - lastNotifSoundAtMs < NOTIF_SOUND_MIN_GAP_MS) return
        lastNotifSoundAtMs = now
        val wake = runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            pm?.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "r1.launcher:notif-chime",
            )?.apply {
                setReferenceCounted(false)
                acquire(5000L) // hard timeout — never leak past 5s
            }
        }.getOrNull()
        // Play UIAlert-retro.mp3 via MediaPlayer with USAGE_NOTIFICATION audio
        // attributes — routes through STREAM_NOTIFICATION (respects DnD +
        // the notification-volume slider). MediaPlayer rather than SoundPool
        // because some MTK builds reject SoundPool.load on a pool created
        // with notification attrs ("doLoad: unable to load sound"); MediaPlayer
        // is more permissive and the asset is short enough that the
        // create+prep cost is fine for one-shot playback.
        //
        // Hoisted `player` so onPrepared / onCompletion / onError can all
        // release it without resurrecting it through a lambda capture.
        var player: android.media.MediaPlayer? = null
        try {
            val mp = android.media.MediaPlayer()
            player = mp
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            mp.setAudioAttributes(attrs)
            val afd = assets.openFd("UIAlert-retro.mp3")
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setOnCompletionListener {
                runCatching { it.release() }
                runCatching { wake?.takeIf { it.isHeld }?.release() }
            }
            mp.setOnErrorListener { errPlayer, _, _ ->
                runCatching { errPlayer.release() }
                runCatching { wake?.takeIf { it.isHeld }?.release() }
                true
            }
            mp.prepare()
            mp.start()
        } catch (t: Throwable) {
            android.util.Log.w("LauncherActivity", "notif mp3 playback failed: ${t.message}")
            runCatching { player?.release() }
            // Fallback: synthesized prompt tone so the user always hears
            // *something* even if the asset can't play.
            var tg: ToneGenerator? = null
            try {
                tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 95)
                tg.startTone(ToneGenerator.TONE_PROP_PROMPT, 220)
                val playingTg = tg
                ui.postDelayed({
                    runCatching { playingTg.release() }
                    runCatching { wake?.takeIf { it.isHeld }?.release() }
                }, 400L)
            } catch (t2: Throwable) {
                runCatching { tg?.release() }
                runCatching { wake?.takeIf { it.isHeld }?.release() }
            }
        }
    }

}
