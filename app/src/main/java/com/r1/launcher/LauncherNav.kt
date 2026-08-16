package com.r1.launcher

/**
 * Wheel / activate routing. Lives outside LauncherState so the state container
 * stays focused on "what to render"; this file handles "what button does what".
 *
 * `host` provides the side-effecting pieces (tone feedback, launching apps,
 * triggering updater/store, opening settings, rebooting). The state machine
 * itself is pure: panel + focus indices only.
 */
interface LauncherHost {
    fun launchApp(idx: Int)
    fun checkForUpdate()
    fun onOnboardingDone()
    fun setBrightness(level: Int)
    fun setVolume(level: Int)
    fun setUiVolume(level: Int)
    fun toggleUiSoundEnabled(enabled: Boolean)
    fun toggleHaptics(enabled: Boolean)
    fun toggleWifi(enable: Boolean)
    fun toggleCellular(enable: Boolean)
    fun toggleBluetooth(enable: Boolean)
    fun factoryReset()
    fun rebootDevice()
    fun powerOffDevice()
    fun resetCameraMotor()

    // --- camera app ---
    fun cameraShutter()
    fun cameraFlip()
    fun cameraOpenGallery()
    fun galleryDeleteCurrent()
    /** Side-button hold in GALLERY_VIEW: open mic for an edit instruction. */
    fun galleryAiRecordStart()
    fun galleryAiRecordStop()
    /** Re-run the last failed job with the same photo + transcript. */
    fun galleryAiRetry()

    // --- chat app ---
    fun chatNew()
    fun chatOpen(id: String)
    fun chatDelete(id: String)
    fun chatSend()
    fun chatStop()
    fun chatRetry()
    fun chatRecordStart()
    fun chatRecordStop()
    fun chatToggleSpeak()
    fun chatToggleImageMode()
    fun chatAttachNewestPhoto()
    fun chatPickPhoto(index: Int)
    fun chatSettingsActivate(idx: Int)
    /** Forwarded from the panel's R1CameraView callback. */
    fun onCameraEvent(event: com.r1.launcher.ui.R1CameraView.Event)
    fun startWifiScan()
    fun connectToWifi(ssid: String, pass: String)
    fun toggleWifiShare(enable: Boolean)
    fun wifiShareSaveEdit()
    fun wifiShareCycleTimer()
    fun openAirplaneSettings()
    fun openDateSettings()
    fun lockScreen()
    fun navTone()
    fun selectTone()
    fun backTone()
    fun popTone()
    fun openClawScanned(raw: String)
    fun openClawToggleRecord()
    fun openClawRecordStart()
    fun openClawRecordStop()
    fun openClawSendText(text: String)
    fun voiceToggleEnabled()
    fun voiceCycleVoiceId()
    /** Set the catalog voice to the exact id [id]. No-ops with a warning log
     *  when [id] is not in [com.r1.launcher.voice.VoicePrefs.VOICES]. Used by
     *  the web companion's credentials view where the user picks a voice from
     *  a dropdown instead of cycling. */
    fun voiceSetVoiceId(id: String)
    fun voiceSaveKey(key: String)
    fun voiceClearKey()
    fun voicePasteKeyFromClipboard()
    fun voiceSettingsRowActivate(idx: Int)
    /** Cycle the clock-screen long-press talk target: off → hermes → openclaw. */
    fun voiceCycleTalkShortcut()
    /** Custom voice id (cloned / professional / shared). Save validates non-blank. */
    fun voiceSaveCustomVoiceId(id: String)
    fun voiceClearCustomVoiceId()
    fun voicePasteCustomVoiceIdFromClipboard()
    /** Cycle TTS model: flash → turbo → multilingual → flash. */
    fun voiceCycleModel()
    /** Tuning sliders (clamped to 0..1, speed clamped to 0.7..1.2). */
    fun voiceSetStability(value: Float)
    fun voiceSetSimilarity(value: Float)
    fun voiceSetStyle(value: Float)
    fun voiceSetSpeed(value: Float)
    fun voiceToggleSpeakerBoost()
    /** Reset model + all tuning knobs to factory defaults. */
    fun voiceResetTuning()
    /** Synthesize a fixed sample with the current settings and play it. */
    fun voiceTestSynthesize()
    /** Fetch ElevenLabs `/v1/user/subscription` and update the state cache.
     *  [force] = true bypasses the 60s in-memory cache. Runs on a background
     *  thread; UI thread reads `voiceSubLoading` / `voiceSub*` for display. */
    fun voiceFetchSubscription(force: Boolean = false)
    /** Activate handler for SETTINGS_VOICE_TUNING rows. */
    fun voiceTuningRowActivate(idx: Int)
    fun openClawScrollUp()
    fun openClawScrollDown()
    fun openClawCloseSession()
    fun openClawSettingsRowActivate(idx: Int)
    fun openClawClearHistory()
    fun openClawDisconnect()
    fun openClawSetFontSize(size: Int)
    fun openClawSwitchSession(key: String)
    fun openClawRefreshSessions()
    fun openClawSessionsRowActivate(idx: Int)
    fun openClawCompactSession()
    fun openClawClearContext()
    fun openClawOpenCameraAsk()
    fun openClawCameraCaptured(jpegBytes: ByteArray)
    fun openClawCameraRetake()
    fun openClawCameraSend(prompt: String)
    fun openClawCameraMotorNudge(delta: Int)
    fun loadSmsConversations()
    fun openSmsThread(address: String, displayName: String)
    fun toggleWebServer(enable: Boolean)
    fun setWebTerminalEnabled(enable: Boolean)
    /** Toggle whether screen recordings include the device microphone. */
    fun setCaptureMicEnabled(enable: Boolean)
    /** Toggle whether screen recordings include system/playback audio
     *  (REMOTE_SUBMIX). */
    fun setCapturePlaybackEnabled(enable: Boolean)
    /** Persist a new 4-digit web-panel passcode. Mirrors into LauncherState
     *  for the Settings UI and rotates [NotifPrefs.panelToken] so any already-
     *  authenticated browsers get bounced — passcode change is a logout. */
    fun panelPasscodeSave(passcode: String)
    fun terminalRun(cmd: String)
    fun terminalClear()
    fun terminalRecordStart()
    fun terminalRecordStop()
    fun terminalPasteFromClipboard()
    // --- hermes agent ---
    fun hermesSendText(text: String)
    fun hermesRecordStart()
    fun hermesRecordStop()
    fun hermesScrollUp()
    fun hermesScrollDown()
    fun hermesClearHistory()
    fun hermesTestConnection()
    fun hermesConfigRowActivate(idx: Int)
    fun hermesSetServerUrl(value: String)
    fun hermesSetApiKey(value: String)
    fun hermesPasteServerUrlFromClipboard()
    fun hermesPasteApiKeyFromClipboard()
    /** Open the Hermes QR scanner (camera-perm gated, mirrors openClawScanned flow). */
    fun openHermesQr()
    /** Consume a raw QR scan: decode, save url/key to HermesPrefs, navigate back
     *  to HERMES_CONFIG, auto-probe /health. */
    fun hermesScanned(raw: String)
    fun hermesSetActiveConnection(id: String)
    fun hermesAddConnection(url: String, key: String): com.r1.launcher.hermes.HermesConnection?
    fun hermesUpdateConnection(id: String, url: String? = null, key: String? = null)
    fun hermesDeleteConnection(id: String)
    fun hermesRotateSession(id: String)
    fun hermesConnectionEditRowActivate(idx: Int)
    fun hermesConnectionEditSaveUrl(value: String)
    fun hermesConnectionEditSaveKey(value: String)
    fun hermesConnectionEditPasteUrl()
    fun hermesConnectionEditPasteKey()
    // --- translator ---
    fun translatorSendText(text: String)
    fun translatorRecordStart()
    fun translatorRecordStop()
    fun translatorCycleSource(delta: Int)
    fun translatorCycleTarget(delta: Int)
    fun translatorSetProvider(provider: String)
    fun translatorSetSource(code: String)
    fun translatorSetTarget(code: String)
    fun translatorSwapLangs()
    fun translatorReplay(messageId: String)
    fun translatorClearHistory()
    fun translatorSettingsRowActivate(idx: Int)
    fun translatorToggleHideInput()
    fun translatorSaveKey(provider: String, value: String)
    fun translatorPasteKey(provider: String)
    fun translatorClearKey(provider: String)
    // --- translator onboarding ---
    fun translatorOnboardingPickSource(code: String)
    fun translatorOnboardingPickTarget(code: String)
    fun translatorOnboardingEnablePhoneKey()
    fun translatorOnboardingPasteKey()
    fun translatorOnboardingSkipKey()
    fun translatorOnboardingFinish()

    fun copyToClipboard(text: String, label: String = "r1-launcher")
    /** Read the current Android system clipboard as a plain string (or empty
     *  if nothing is available). Used by the long-press paste popup in chat
     *  panels — pure read, no insertion. */
    fun getClipboardText(): String
    fun setLanguage(code: String)

    // --- meetings (transcriber) ---
    fun transcriberOpen()
    fun transcriberStartRecording()
    fun transcriberStopRecording()
    fun transcriberToggleRecording()
    fun transcriberOpenDetail(uuid: String)
    fun transcriberRetryTranscribe(uuid: String)
    fun transcriberDelete(uuid: String)
    fun transcriberPlayAudio(uuid: String)
    fun transcriberStopAudio()
    fun transcriberShareEmail(uuid: String, recipient: String)
    fun transcriberOpenSettings()
    fun transcriberSettingsRowActivate(idx: Int)
    fun transcriberSaveSmtpField(field: String, value: String)
    fun transcriberPasteSmtpField(field: String)
    fun transcriberClearSmtp()
    fun transcriberPasteRecipient()
    /** Open the ⋮ action overlay on the detail panel. Computes the action
     *  set from the current meeting's status and writes it into
     *  `state.transcriberDetailMenuActions`, then flips the menu open. */
    fun transcriberOpenDetailMenu()
    /** Dispatch a single ⋮ menu action. The activity reads the current meeting
     *  uuid from state. */
    fun transcriberDetailMenuActivate(action: com.r1.launcher.transcriber.TranscriberDetailAction)

    // --- notifications ---
    /** Push a notification into the local center. Persists to JSON, updates
     *  the unread badge, fires the chime (guards permitting), and emits a
     *  `notification` event on the web companion socket. Called by the
     *  OpenClaw chat ingress, the Hermes onDone ingress, the `POST /api/notify`
     *  webhook, and any future local cron jobs. */
    fun notify(
        source: String,
        title: String,
        body: String,
        deeplink: String? = null,
    )
    /** Wheel-activate on a notification row: mark read, then jump to the
     *  deeplinked panel (or stay on NOTIFICATIONS if the notif has no link). */
    fun notificationActivate(id: Long)
    fun notificationsMarkAllRead()
    fun notificationsClear()
    /** Toggle the master chime — surfaced in Settings → Sound. */
    fun toggleNotificationSound(enabled: Boolean)

    // --- credentials (global API keys) ---
    /** Row-activate dispatcher for SETTINGS_CREDENTIALS. The activity opens
     *  the appropriate keyboard overlay (or fires the "regenerate" action
     *  on the webhook token row). */
    fun credentialsRowActivate(idx: Int)
    /** Persist a typed credential. `field` is the credentialsEditField name:
     *  "anthropic" | "elevenlabs" | "hermes" | "ntfy_topic". Empty value
     *  clears the field. */
    fun credentialsSaveField(field: String, value: String)
    fun credentialsPasteField(field: String)
    fun credentialsClearField(field: String)
    /** Regenerate the webhook bearer token. Returns the new value so the
     *  panel can refresh its display mirror without an extra read. */
    fun regenerateWebhookToken(): String

    // --- ntfy.sh subscriber ---
    fun toggleNtfySubscriber(enabled: Boolean)
    fun ntfySetTopic(topic: String)
    fun ntfyConfigRowActivate(idx: Int)

    // --- bluetooth scan ---
    fun startBtScan()
    fun stopBtScan()
    fun pairBtDevice(address: String)

    // --- media capture (web companion only) ---
    fun mediaCaptureScreenshot(): Result<com.r1.launcher.media.CaptureItem>
    fun mediaStartVideo(): Result<Long>
    fun mediaStopVideo(): Result<com.r1.launcher.media.CaptureItem>
    fun mediaList(limit: Int): List<com.r1.launcher.media.CaptureItem>
    fun mediaTotalBytes(): Long
    fun mediaCount(): Int
    fun mediaIsRecording(): Boolean
    fun mediaDelete(name: String): Boolean
    fun mediaClear(): Int
}

fun LauncherState.wheelUp(host: LauncherHost) {
    when (panel) {
        Panel.APPS -> {
            if (appsFocus <= 0) {
                back(); host.backTone()
            } else {
                appsFocus--; host.navTone()
            }
        }
        Panel.ONBOARDING -> {
            if (onboardingFocus <= 0) {
                if (onboardingStep > 0) {
                    onboardingStep--
                    onboardingFocus = 0
                    host.backTone()
                }
                // step 0: no-op (welcome has no back)
            } else {
                onboardingFocus--; host.navTone()
            }
        }
        Panel.SETTINGS -> {
            if (settingsFocus <= 0) {
                back(); host.backTone()
            } else {
                settingsFocus--; host.navTone()
            }
        }
        Panel.SETTINGS_DISPLAY -> {
            if (settingsDisplayFocus <= 0) {
                back(); host.backTone()
            } else {
                settingsDisplayFocus--; host.navTone()
            }
        }
        Panel.SETTINGS_SOUND -> {
            if (settingsSoundFocus <= 0) {
                back(); host.backTone()
            } else {
                settingsSoundFocus--; host.navTone()
            }
        }
        Panel.SETTINGS_DEVICE -> {
            if (settingsDeviceFocus <= 0) {
                back(); host.backTone()
            } else {
                settingsDeviceFocus--; host.navTone()
            }
        }
        Panel.SETTINGS_ABOUT -> {
            if (settingsAboutFocus <= 0) {
                back(); host.backTone()
            } else {
                settingsAboutFocus--; host.navTone()
            }
        }
        Panel.SETTINGS_VOICE -> {
            if (voiceFocus <= 0) {
                back(); host.backTone()
            } else {
                voiceFocus--; host.navTone()
            }
        }
        Panel.SETTINGS_VOICE_TUNING -> {
            if (voiceTuningFocus <= 0) {
                back(); host.backTone()
            } else {
                voiceTuningFocus--; host.navTone()
            }
        }
        Panel.SETTINGS_VOICE_SUBSCRIPTION -> {
            if (voiceSubFocus <= 0) {
                back(); host.backTone()
            } else {
                voiceSubFocus--; host.navTone()
            }
        }
        Panel.SETTINGS_LANGUAGE -> {
            if (settingsLanguageFocus <= 0) {
                back(); host.backTone()
            } else {
                settingsLanguageFocus--; host.navTone()
            }
        }
        Panel.NETWORK -> {
            if (networkFocus <= 0) {
                back(); host.backTone()
            } else {
                networkFocus--; host.navTone()
            }
        }
        Panel.FACTORY_CONFIRM -> {
            if (factoryConfirmFocus <= 0) {
                back(); host.backTone()
            } else {
                factoryConfirmFocus--; host.navTone()
            }
        }
        Panel.WIFI_SCAN -> {
            if (wifiScanFocus <= 0) {
                back(); host.backTone()
            } else {
                wifiScanFocus--; host.navTone()
            }
        }
        Panel.BT_SCAN -> {
            if (btScanFocus <= 0) {
                host.stopBtScan(); back(); host.backTone()
            } else {
                btScanFocus--; host.navTone()
            }
        }
        Panel.WIFI_PASSWORD -> { /* camera/keyboard handles input */ }
        Panel.WIFI_SHARE -> {
            if (wifiShareFocus <= 0) {
                back(); host.backTone()
            } else {
                wifiShareFocus--; host.navTone()
            }
        }
        Panel.WIFI_SHARE_EDIT -> { /* keyboard handles input */ }
        Panel.PANEL_PASSCODE -> { /* numeric keypad handles input */ }
        Panel.REMOTE_PANEL -> {
            if (remotePanelFocus <= 0) {
                back(); host.backTone()
            } else {
                remotePanelFocus--; host.navTone()
            }
        }
        Panel.BRIGHTNESS -> {
            val prev = brightnessLevel
            brightnessLevel = (brightnessLevel - 16).coerceAtLeast(1)
            if (prev != brightnessLevel) {
                host.setBrightness(brightnessLevel)
                host.navTone()
            }
        }
        Panel.VOLUME -> {
            val prev = volumeLevel
            volumeLevel = (volumeLevel - 1).coerceAtLeast(0)
            if (prev != volumeLevel) {
                host.setVolume(volumeLevel)
                host.navTone()
            }
        }
        Panel.UI_VOLUME -> {
            val prev = uiVolumeLevel
            uiVolumeLevel = (uiVolumeLevel - 1).coerceAtLeast(0)
            if (prev != uiVolumeLevel) {
                host.setUiVolume(uiVolumeLevel)
                host.navTone()
            }
        }
        Panel.OPENCLAW_QR -> { /* camera handles input */ }
        Panel.HERMES_QR -> { /* camera handles input */ }
        Panel.HERMES_CONNECTION_EDIT -> {
            val isNew = hermesConnectionEditId == null
            val maxIdx = if (isNew) 2 else 4
            if (hermesConnectionEditFocus <= 0) { back(); host.backTone() }
            else { hermesConnectionEditFocus--; host.navTone() }
        }
        Panel.OPENCLAW_CHAT -> { host.openClawScrollUp(); host.navTone() }
        Panel.OPENCLAW_CAMERA -> { host.openClawCameraMotorNudge(-15) }
        Panel.OPENCLAW_SETTINGS -> {
            if (openClawSettingsFocus <= 0) {
                back(); host.backTone()
            } else {
                openClawSettingsFocus--; host.navTone()
            }
        }
        Panel.OPENCLAW_SESSIONS -> {
            if (openClawSessionsFocus <= 0) {
                back(); host.backTone()
            } else {
                openClawSessionsFocus--; host.navTone()
            }
        }
        Panel.MESSAGES -> {
            if (messagesFocus <= 0) {
                back(); host.backTone()
            } else {
                messagesFocus--; host.navTone()
            }
        }
        Panel.MESSAGES_THREAD -> {
            if (smsThreadFocus <= 0) {
                back(); host.backTone()
            } else {
                smsThreadFocus--; host.navTone()
            }
        }
        // Wheel up/down is the documented way to swap which way the lens
        // points. It's a motor move, not a camera-id switch — see R1CameraView.
        Panel.CHAT_LIST -> {
            if (chatListFocus <= 0) { back(); host.backTone() }
            else { chatListFocus--; host.navTone() }
        }
        // Wheel scrolls the transcript; leaving the tail turns off autoscroll
        // so a long reply doesn't drag the user back down mid-read.
        Panel.CHAT -> { chatPinnedToBottom = false; chatScrollDir = -1; chatScrollSeq++ }
        Panel.CHAT_SETTINGS -> {
            if (chatSettingsFocus <= 0) { back(); host.backTone() }
            else { chatSettingsFocus--; host.navTone() }
        }
        Panel.CAMERA -> {
            if (!cameraFacingIsFront) { host.cameraFlip(); host.navTone() }
        }
        Panel.GALLERY -> {
            if (galleryFocus <= 0) { back(); host.backTone() }
            else { galleryFocus--; host.navTone() }
        }
        Panel.GALLERY_VIEW -> {
            if (galleryIndex > 0) { galleryIndex--; host.navTone() }
        }
        Panel.TESTING -> {
            val prev = testingFocus
            testingFocus = (testingFocus - 1).coerceAtLeast(0)
            if (prev != testingFocus) host.navTone()
        }
        Panel.TERMINAL -> { terminalScrollIndex++; host.navTone() }
        Panel.HERMES_CHAT -> { host.hermesScrollUp(); host.navTone() }
        Panel.HERMES_CONFIG -> {
            val c = hermesConnections.size
            val canAdd = c < com.r1.launcher.hermes.HermesPrefs.MAX_CONNECTIONS
            val totalRows = (if (canAdd) c + 2 else c + 1) + 4
            if (hermesConfigFocus <= 0) {
                back(); host.backTone()
            } else {
                hermesConfigFocus = (hermesConfigFocus - 1).coerceAtLeast(0); host.navTone()
            }
        }
        Panel.TRANSLATOR_ONBOARDING -> {
            if (translatorOnboardingFocus > 0) {
                translatorOnboardingFocus--; host.navTone()
            } else if (translatorOnboardingStep > 0) {
                // Step back to the previous wizard step.
                translatorOnboardingStep--
                translatorOnboardingFocus = 0
                translatorOnboardingWaitingForKey = false
                host.backTone()
            } else {
                back(); host.backTone()
            }
        }
        Panel.TRANSLATOR -> {
            // Wheel-up = cycle target language (one step toward index 0).
            host.translatorCycleTarget(-1); host.navTone()
        }
        Panel.TRANSLATOR_SETTINGS -> {
            if (translatorSettingsFocus <= 0) {
                back(); host.backTone()
            } else {
                translatorSettingsFocus--; host.navTone()
            }
        }
        Panel.TRANSCRIBER_LIST -> {
            if (transcriberListFocus <= 0) {
                back(); host.backTone()
            } else {
                transcriberListFocus--; host.navTone()
            }
        }
        // Recording panel: wheel-up cancels back to list (recording continues
        // in the FGS — user can stop it from the list row by re-entering).
        // Activate (side tap) is the canonical stop gesture.
        Panel.TRANSCRIBER_RECORDING -> { /* no scroll target */ }
        Panel.TRANSCRIBER_DETAIL -> {
            if (transcriberDetailMenuOpen) {
                if (transcriberDetailMenuFocus <= 0) {
                    transcriberDetailMenuFocus = 0
                    host.backTone()
                } else {
                    transcriberDetailMenuFocus--; host.navTone()
                }
            } else if (transcriberDetailFocus <= 0) {
                back(); host.backTone()
            } else {
                transcriberDetailFocus--; host.navTone()
            }
        }
        Panel.TRANSCRIBER_SETTINGS -> {
            if (transcriberSettingsFocus <= 0) {
                back(); host.backTone()
            } else {
                transcriberSettingsFocus--; host.navTone()
            }
        }
        Panel.NOTIFICATIONS -> {
            if (notificationsFocus <= 0) {
                back(); host.backTone()
            } else {
                notificationsFocus--; host.navTone()
            }
        }
        Panel.SETTINGS_CREDENTIALS -> {
            if (credentialsFocus <= 0) {
                back(); host.backTone()
            } else {
                credentialsFocus--; host.navTone()
            }
        }
        Panel.NTFY_CONFIG -> {
            if (ntfyConfigFocus <= 0) {
                back(); host.backTone()
            } else {
                ntfyConfigFocus--; host.navTone()
            }
        }
        Panel.HOME -> { /* clock screen — no list to scroll */ }
    }
}

fun LauncherState.wheelDown(host: LauncherHost) {
    when (panel) {
        Panel.APPS -> {
            val max = (apps.size - 1).coerceAtLeast(0)
            val prev = appsFocus
            appsFocus = (appsFocus + 1).coerceAtMost(max)
            if (prev != appsFocus) host.navTone()
        }
        Panel.ONBOARDING -> {
            val max = when (onboardingStep) {
                0 -> com.r1.launcher.locale.LocalePrefs.SUPPORTED.size - 1 // language picker
                1 -> 0          // welcome: only continue
                2 -> 2          // network: connect wi-fi, use cellular, skip
                3 -> 2          // updates: check now, skip, continue
                4 -> 0          // done: only finish
                else -> 0
            }
            val prev = onboardingFocus
            onboardingFocus = (onboardingFocus + 1).coerceAtMost(max)
            if (prev != onboardingFocus) host.navTone()
        }
        Panel.SETTINGS -> {
            val prev = settingsFocus
            // back, network, display, sound, voice, credentials, device, about
            settingsFocus = (settingsFocus + 1).coerceAtMost(7)
            if (prev != settingsFocus) host.navTone()
        }
        Panel.SETTINGS_DISPLAY -> {
            val prev = settingsDisplayFocus
            settingsDisplayFocus = (settingsDisplayFocus + 1).coerceAtMost(1) // back, brightness
            if (prev != settingsDisplayFocus) host.navTone()
        }
        Panel.SETTINGS_SOUND -> {
            val prev = settingsSoundFocus
            // back, system-toggle, system-volume, sound, notifications
            settingsSoundFocus = (settingsSoundFocus + 1).coerceAtMost(4)
            if (prev != settingsSoundFocus) host.navTone()
        }
        Panel.SETTINGS_DEVICE -> {
            val prev = settingsDeviceFocus
            // back, updates, language, reboot, power off, reset camera, factory reset
            settingsDeviceFocus = (settingsDeviceFocus + 1).coerceAtMost(7)
            if (prev != settingsDeviceFocus) host.navTone()
        }
        Panel.SETTINGS_ABOUT -> {
            // 18 LazyColumn items (3 section headers + 15 info rows); focus 0
            // keeps the back pill highlighted, focus 1..18 scrolls the list.
            val prev = settingsAboutFocus
            settingsAboutFocus = (settingsAboutFocus + 1).coerceAtMost(18)
            if (prev != settingsAboutFocus) host.navTone()
        }
        Panel.SETTINGS_VOICE -> {
            val prev = voiceFocus
            // back, on/off, subscription, voice picker, custom-id, test, tuning,
            // talk shortcut  (elevenlabs key + clear key moved to Settings → Credentials)
            voiceFocus = (voiceFocus + 1).coerceAtMost(7)
            if (prev != voiceFocus) host.navTone()
        }
        Panel.SETTINGS_VOICE_TUNING -> {
            val prev = voiceTuningFocus
            // back, model, stability, similarity, style, speed, speaker-boost, test, reset
            voiceTuningFocus = (voiceTuningFocus + 1).coerceAtMost(8)
            if (prev != voiceTuningFocus) host.navTone()
        }
        Panel.SETTINGS_VOICE_SUBSCRIPTION -> {
            val prev = voiceSubFocus
            // back, refresh
            voiceSubFocus = (voiceSubFocus + 1).coerceAtMost(1)
            if (prev != voiceSubFocus) host.navTone()
        }
        Panel.SETTINGS_LANGUAGE -> {
            // back + N supported languages → max idx = N
            val max = com.r1.launcher.locale.LocalePrefs.SUPPORTED.size
            val prev = settingsLanguageFocus
            settingsLanguageFocus = (settingsLanguageFocus + 1).coerceAtMost(max)
            if (prev != settingsLanguageFocus) host.navTone()
        }
        Panel.NETWORK -> {
            val prev = networkFocus
            // back, wifi, cellular, bluetooth, share, remote-panel (deeper), ntfy, wifi-scan
            networkFocus = (networkFocus + 1).coerceAtMost(7)
            if (prev != networkFocus) host.navTone()
        }
        Panel.FACTORY_CONFIRM -> {
            val prev = factoryConfirmFocus
            factoryConfirmFocus = (factoryConfirmFocus + 1).coerceAtMost(1)
            if (prev != factoryConfirmFocus) host.navTone()
        }
        Panel.WIFI_SCAN -> {
            val max = (wifiScanResults.size).coerceAtLeast(0) // back + N items
            val prev = wifiScanFocus
            wifiScanFocus = (wifiScanFocus + 1).coerceAtMost(max)
            if (prev != wifiScanFocus) host.navTone()
        }
        Panel.BT_SCAN -> {
            // 0=back, 1=toggle, 2..N+1=device rows
            val max = (1 + btDevices.size).coerceAtLeast(1)
            val prev = btScanFocus
            btScanFocus = (btScanFocus + 1).coerceAtMost(max)
            if (prev != btScanFocus) host.navTone()
        }
        Panel.WIFI_PASSWORD -> { /* camera/keyboard handles input */ }
        Panel.WIFI_SHARE -> {
            val prev = wifiShareFocus
            wifiShareFocus = (wifiShareFocus + 1).coerceAtMost(5) // back, enable, name, password, connected, auto-off
            if (prev != wifiShareFocus) host.navTone()
        }
        Panel.WIFI_SHARE_EDIT -> { /* keyboard handles input */ }
        Panel.PANEL_PASSCODE -> { /* numeric keypad handles input */ }
        Panel.REMOTE_PANEL -> {
            val prev = remotePanelFocus
            // back, server, passcode, mic, playback, terminal
            remotePanelFocus = (remotePanelFocus + 1).coerceAtMost(5)
            if (prev != remotePanelFocus) host.navTone()
        }
        Panel.BRIGHTNESS -> {
            val prev = brightnessLevel
            brightnessLevel = (brightnessLevel + 16).coerceAtMost(255)
            if (prev != brightnessLevel) {
                host.setBrightness(brightnessLevel)
                host.navTone()
            }
        }
        Panel.VOLUME -> {
            val prev = volumeLevel
            volumeLevel = (volumeLevel + 1).coerceAtMost(volumeMax)
            if (prev != volumeLevel) {
                host.setVolume(volumeLevel)
                host.navTone()
            }
        }
        Panel.UI_VOLUME -> {
            val prev = uiVolumeLevel
            uiVolumeLevel = (uiVolumeLevel + 1).coerceAtMost(uiVolumeMax)
            if (prev != uiVolumeLevel) {
                host.setUiVolume(uiVolumeLevel)
                host.navTone()
            }
        }
        Panel.OPENCLAW_QR -> { /* camera handles input */ }
        Panel.HERMES_QR -> { /* camera handles input */ }
        Panel.HERMES_CONNECTION_EDIT -> {
            val isNew = hermesConnectionEditId == null
            val maxIdx = if (isNew) 2 else 4
            val prev = hermesConnectionEditFocus
            hermesConnectionEditFocus = (hermesConnectionEditFocus + 1).coerceAtMost(maxIdx)
            if (prev != hermesConnectionEditFocus) host.navTone()
        }
        Panel.OPENCLAW_CHAT -> { host.openClawScrollDown(); host.navTone() }
        Panel.OPENCLAW_CAMERA -> { host.openClawCameraMotorNudge(+15) }
        Panel.OPENCLAW_SETTINGS -> {
            val prev = openClawSettingsFocus
            openClawSettingsFocus = (openClawSettingsFocus + 1).coerceAtMost(5)
            if (prev != openClawSettingsFocus) host.navTone()
        }
        Panel.OPENCLAW_SESSIONS -> {
            // Row layout matches OpenClawSessionsPanel:
            //   0             "< back"
            //   1             "+ new thread"
            //   2..choices+1  one row per resolveSessionChoices entry
            //                (or a single placeholder row when choices is empty)
            //   choices+2     "refresh"
            val choiceCount = com.r1.launcher.openclaw.resolveSessionChoices(
                currentSessionKey = selectedSessionKey,
                sessions = chatSessions.toList(),
                mainSessionKey = mainSessionKey,
            ).size.coerceAtLeast(1)
            val max = 2 + choiceCount // back + new + choices + refresh; last index = 2+choices
            val prev = openClawSessionsFocus
            openClawSessionsFocus = (openClawSessionsFocus + 1).coerceAtMost(max)
            if (prev != openClawSessionsFocus) host.navTone()
        }
        Panel.MESSAGES -> {
            // Row 0 = back, then one row per conversation. Empty list still has
            // a single "no messages" row, but it isn't selectable — keep focus on back.
            val maxRow = smsConversations.size // back + N convs; last index = N
            val prev = messagesFocus
            messagesFocus = (messagesFocus + 1).coerceAtMost(maxRow)
            if (prev != messagesFocus) host.navTone()
        }
        Panel.MESSAGES_THREAD -> {
            val maxRow = smsThreadMessages.size // back + N items; last index = N
            val prev = smsThreadFocus
            smsThreadFocus = (smsThreadFocus + 1).coerceAtMost(maxRow)
            if (prev != smsThreadFocus) host.navTone()
        }
        Panel.CHAT_LIST -> {
            if (chatListFocus < chatHistory.size + 1) { chatListFocus++; host.navTone() }
        }
        Panel.CHAT -> { chatScrollDir = 1; chatScrollSeq++ }
        Panel.CHAT_SETTINGS -> {
            if (chatSettingsFocus < 8) { chatSettingsFocus++; host.navTone() }
        }
        Panel.CAMERA -> {
            if (cameraFacingIsFront) { host.cameraFlip(); host.navTone() }
        }
        Panel.GALLERY -> {
            if (galleryFocus < photos.size) { galleryFocus++; host.navTone() }
        }
        Panel.GALLERY_VIEW -> {
            if (galleryIndex < photos.lastIndex) { galleryIndex++; host.navTone() }
        }
        Panel.TESTING -> {
            val prev = testingFocus
            testingFocus = (testingFocus + 1).coerceAtMost(1)
            if (prev != testingFocus) host.navTone()
        }
        Panel.TERMINAL -> {
            val prev = terminalScrollIndex
            terminalScrollIndex = (terminalScrollIndex - 1).coerceAtLeast(0)
            if (prev != terminalScrollIndex) host.navTone()
        }
        Panel.HERMES_CHAT -> { host.hermesScrollDown(); host.navTone() }
        Panel.HERMES_CONFIG -> {
            val c = hermesConnections.size
            val canAdd = c < com.r1.launcher.hermes.HermesPrefs.MAX_CONNECTIONS
            val totalRows = (if (canAdd) c + 2 else c + 1) + 4
            val prev = hermesConfigFocus
            hermesConfigFocus = (hermesConfigFocus + 1).coerceAtMost(totalRows - 1)
            if (prev != hermesConfigFocus) host.navTone()
        }
        Panel.TRANSLATOR_ONBOARDING -> {
            val max = when (translatorOnboardingStep) {
                0 -> com.r1.launcher.translator.Languages.ALL.size // [auto] + langs → idx 0..ALL.size
                1 -> com.r1.launcher.translator.Languages.ALL.size - 1 // langs only
                else -> 2 // set-from-phone, paste, skip
            }
            val prev = translatorOnboardingFocus
            translatorOnboardingFocus = (translatorOnboardingFocus + 1).coerceAtMost(max)
            if (prev != translatorOnboardingFocus) host.navTone()
        }
        Panel.TRANSLATOR -> {
            // Wheel-down = cycle target language (one step toward end of list).
            host.translatorCycleTarget(+1); host.navTone()
        }
        Panel.TRANSLATOR_SETTINGS -> {
            val prev = translatorSettingsFocus
            // back, provider, gemini, openai, claude, source, target, auto-detect,
            // auto-speak, hide-input, clear
            translatorSettingsFocus = (translatorSettingsFocus + 1).coerceAtMost(10)
            if (prev != translatorSettingsFocus) host.navTone()
        }
        Panel.TRANSCRIBER_LIST -> {
            // 0=back, 1="+ new recording", 2..=meetings ; +1 for settings shortcut at the end
            val max = 2 + meetings.size  // back + new + N meetings + settings = last idx N+2
            val prev = transcriberListFocus
            transcriberListFocus = (transcriberListFocus + 1).coerceAtMost(max)
            if (prev != transcriberListFocus) host.navTone()
        }
        Panel.TRANSCRIBER_RECORDING -> { /* no scroll target */ }
        Panel.TRANSCRIBER_DETAIL -> {
            if (transcriberDetailMenuOpen) {
                val max = (transcriberDetailMenuActions.size - 1).coerceAtLeast(0)
                val prev = transcriberDetailMenuFocus
                transcriberDetailMenuFocus = (transcriberDetailMenuFocus + 1).coerceAtMost(max)
                if (prev != transcriberDetailMenuFocus) host.navTone()
            } else {
                val prev = transcriberDetailFocus
                // back, ⋮ — actions are now hidden behind the menu
                transcriberDetailFocus = (transcriberDetailFocus + 1).coerceAtMost(1)
                if (prev != transcriberDetailFocus) host.navTone()
            }
        }
        Panel.TRANSCRIBER_SETTINGS -> {
            val prev = transcriberSettingsFocus
            // back, host, port, user, password, recipient, clear
            transcriberSettingsFocus = (transcriberSettingsFocus + 1).coerceAtMost(6)
            if (prev != transcriberSettingsFocus) host.navTone()
        }
        Panel.NOTIFICATIONS -> {
            // 0=back, 1=header-clear (only when items exist), 2..N+1=items.
            // No bottom clear-all row — it lives in the header now.
            val itemsCount = notifications.size
            val maxRow = if (itemsCount == 0) 0 else itemsCount + 1
            val prev = notificationsFocus
            notificationsFocus = (notificationsFocus + 1).coerceAtMost(maxRow)
            if (prev != notificationsFocus) host.navTone()
        }
        Panel.SETTINGS_CREDENTIALS -> {
            // back, elevenlabs, hermes, ntfy topic, webhook token, control secret
            val prev = credentialsFocus
            credentialsFocus = (credentialsFocus + 1).coerceAtMost(5)
            if (prev != credentialsFocus) host.navTone()
        }
        Panel.NTFY_CONFIG -> {
            // back, enable toggle, topic row, status (info)
            val prev = ntfyConfigFocus
            ntfyConfigFocus = (ntfyConfigFocus + 1).coerceAtMost(3)
            if (prev != ntfyConfigFocus) host.navTone()
        }
        Panel.HOME -> {
            openApps()
            host.selectTone()
        }
    }
}

fun LauncherState.activate(host: LauncherHost) {
    when (panel) {
        // Don't launch here — bump the press trigger so the focused AppCard
        // runs its dip-and-bounce animation, then the AppCard's LaunchedEffect
        // calls launchApp() at the tail. Matches the touch-tap experience.
        Panel.APPS -> appsPressTrigger++
        Panel.ONBOARDING -> when (onboardingStep) {
            0 -> {
                // Language picker. Picking sets pref + recreate(); after recreate
                // onCreate jumps to step 1 (welcome) automatically. Network requests
                // remain blocked because isOnboarding stays true.
                val lang = com.r1.launcher.locale.LocalePrefs.SUPPORTED.getOrNull(onboardingFocus)
                if (lang != null) {
                    host.setLanguage(lang.code)
                    host.selectTone()
                }
            }
            1 -> { advanceOnboarding(); host.selectTone() }
            2 -> when (onboardingFocus) {
                0 -> { openWifiScan(); host.startWifiScan(); host.selectTone() }
                1 -> if (simPresent) { advanceOnboarding(); host.selectTone() } else host.backTone()
                2 -> { advanceOnboarding(); host.selectTone() }
            }
            3 -> when (onboardingFocus) {
                0 -> { host.checkForUpdate(); host.selectTone() }
                1 -> { advanceOnboarding(); host.selectTone() }
                2 -> { advanceOnboarding(); host.selectTone() }
            }
            4 -> { host.onOnboardingDone(); host.selectTone() }
        }
        Panel.SETTINGS -> when (settingsFocus) {
            0 -> { back(); host.backTone() }
            1 -> { openNetwork(); host.selectTone() }
            2 -> { openSettingsDisplay(); host.selectTone() }
            3 -> { openSettingsSound(); host.selectTone() }
            4 -> { openSettingsVoice(); host.selectTone() }
            5 -> { openSettingsCredentials(); host.selectTone() }
            6 -> { openSettingsDevice(); host.selectTone() }
            7 -> { openSettingsAbout(); host.selectTone() }
        }
        Panel.SETTINGS_LANGUAGE -> {
            if (settingsLanguageFocus == 0) {
                back(); host.backTone()
            } else {
                val lang = com.r1.launcher.locale.LocalePrefs.SUPPORTED
                    .getOrNull(settingsLanguageFocus - 1)
                if (lang != null) {
                    host.setLanguage(lang.code) // triggers recreate(); state.back() not needed
                    host.selectTone()
                }
            }
        }
        Panel.SETTINGS_VOICE -> { host.voiceSettingsRowActivate(voiceFocus); host.selectTone() }
        Panel.SETTINGS_VOICE_TUNING -> { host.voiceTuningRowActivate(voiceTuningFocus); host.selectTone() }
        Panel.SETTINGS_VOICE_SUBSCRIPTION -> when (voiceSubFocus) {
            0 -> { back(); host.backTone() }
            1 -> { host.voiceFetchSubscription(force = true); host.popTone() }
        }
        Panel.SETTINGS_DISPLAY -> when (settingsDisplayFocus) {
            0 -> { back(); host.backTone() }
            1 -> { openBrightness(); host.selectTone() }
        }
        Panel.SETTINGS_SOUND -> when (settingsSoundFocus) {
            0 -> { back(); host.backTone() }
            1 -> { host.toggleUiSoundEnabled(!uiSoundEnabled); host.popTone() }
            2 -> { openUiVolume(); host.selectTone() }
            3 -> { openVolume(); host.selectTone() }
            4 -> { host.toggleNotificationSound(!notificationSoundEnabled); host.popTone() }
        }
        Panel.SETTINGS_DEVICE -> when (settingsDeviceFocus) {
            0 -> { back(); host.backTone() }
            1 -> { host.toggleHaptics(!hapticsEnabled); host.popTone() }
            2 -> { host.checkForUpdate(); host.selectTone() }
            3 -> { openSettingsLanguage(); host.selectTone() }
            4 -> { host.rebootDevice(); host.selectTone() }
            5 -> { host.powerOffDevice(); host.selectTone() }
            6 -> { host.resetCameraMotor(); host.popTone() }
            7 -> { openFactoryConfirm(); host.selectTone() }
        }
        Panel.SETTINGS_ABOUT -> { back(); host.backTone() }
        Panel.NETWORK -> when (networkFocus) {
            0 -> { back(); host.backTone() }
            1 -> { host.toggleWifi(!wifiEnabled); host.popTone() }
            2 -> { host.toggleCellular(!cellularOn); host.popTone() }
            3 -> { openBtScan(); host.startBtScan(); host.selectTone() }
            4 -> { openWifiShare(); host.selectTone() }
            5 -> { openRemotePanel(); host.selectTone() }
            6 -> { openNtfyConfig(); host.selectTone() }
            7 -> { host.startWifiScan(); openWifiScan(); host.selectTone() }
        }
        Panel.REMOTE_PANEL -> when (remotePanelFocus) {
            0 -> { back(); host.backTone() }
            1 -> { host.toggleWebServer(!webServerEnabled); host.popTone() }
            2 -> { openPanelPasscodeEditor(); host.selectTone() }
            3 -> { host.setCaptureMicEnabled(!captureMicEnabled); host.popTone() }
            4 -> { host.setCapturePlaybackEnabled(!capturePlaybackEnabled); host.popTone() }
            5 -> { host.setWebTerminalEnabled(!webTerminalEnabled); host.popTone() }
        }
        Panel.WIFI_SHARE -> when (wifiShareFocus) {
            0 -> { back(); host.backTone() }
            1 -> { host.toggleWifiShare(!wifiShareEnabled); host.popTone() }
            2 -> { openWifiShareEdit(WifiShareEditTarget.SSID); host.selectTone() }
            3 -> { openWifiShareEdit(WifiShareEditTarget.PASSWORD); host.selectTone() }
            4 -> {
                if (wifiShareConnectedClients.isNotEmpty()) {
                    wifiShareClientsExpanded = !wifiShareClientsExpanded
                    host.popTone()
                }
            }
            5 -> { host.wifiShareCycleTimer(); host.popTone() }
        }
        Panel.WIFI_SHARE_EDIT -> { /* RetroKeyboard handles input */ }
        Panel.PANEL_PASSCODE -> { /* numeric keypad handles input */ }
        Panel.FACTORY_CONFIRM -> when (factoryConfirmFocus) {
            0 -> { back(); host.backTone() }
            1 -> { host.factoryReset(); host.selectTone() }
        }
        Panel.WIFI_SCAN -> {
            if (wifiScanFocus == 0) {
                back(); host.backTone()
            } else {
                val ssid = wifiScanResults.getOrNull(wifiScanFocus - 1)
                if (ssid != null) {
                    openWifiPassword(ssid)
                    host.selectTone()
                }
            }
        }
        Panel.BT_SCAN -> when {
            btScanFocus == 0 -> { host.stopBtScan(); back(); host.backTone() }
            btScanFocus == 1 -> { host.toggleBluetooth(!btOn); host.popTone() }
            else -> {
                val dev = btDevices.getOrNull(btScanFocus - 2)
                if (dev != null) { host.pairBtDevice(dev.address); host.selectTone() }
            }
        }
        Panel.WIFI_PASSWORD -> { /* RetroKeyboard handles input */ }
        Panel.BRIGHTNESS, Panel.VOLUME, Panel.UI_VOLUME -> { back(); host.selectTone() }
        Panel.OPENCLAW_QR -> { /* camera scan auto-completes; activate is no-op */ }
        Panel.HERMES_QR -> { /* camera scan auto-completes; activate is no-op */ }
        Panel.HERMES_CONNECTION_EDIT -> host.hermesConnectionEditRowActivate(hermesConnectionEditFocus)
        Panel.OPENCLAW_CHAT -> { host.openClawToggleRecord(); host.popTone() }
        Panel.OPENCLAW_CAMERA -> { /* touch-first capture/ask surface */ }
        Panel.OPENCLAW_SETTINGS -> {
            host.openClawSettingsRowActivate(openClawSettingsFocus)
            host.selectTone()
        }
        Panel.OPENCLAW_SESSIONS -> {
            host.openClawSessionsRowActivate(openClawSessionsFocus)
            host.selectTone()
        }
        Panel.MESSAGES -> {
            if (messagesFocus == 0) {
                back(); host.backTone()
            } else {
                val conv = smsConversations.getOrNull(messagesFocus - 1)
                if (conv != null) {
                    host.openSmsThread(conv.address, conv.displayName)
                    host.selectTone()
                }
            }
        }
        Panel.MESSAGES_THREAD -> {
            // Only the back row at idx 0 is actionable; bubbles are read-only.
            if (smsThreadFocus == 0) { back(); host.backTone() }
        }
        // Wheel press == shutter, so the side button's single-tap (which
        // routes here for every non-HOME panel) takes a photo for free.
        Panel.CHAT_LIST -> when (chatListFocus) {
            0 -> { back(); host.backTone() }
            1 -> host.chatNew()
            else -> chatHistory.getOrNull(chatListFocus - 2)?.let { host.chatOpen(it.id) }
        }
        Panel.CHAT -> if (chatInput.isNotBlank() || chatAttachment != null) host.chatSend()
        Panel.CHAT_SETTINGS -> host.chatSettingsActivate(chatSettingsFocus)
        Panel.CAMERA -> host.cameraShutter()
        Panel.GALLERY -> {
            if (galleryFocus == 0) { back(); host.backTone() }
            else openGalleryView(galleryFocus - 1)
        }
        Panel.GALLERY_VIEW -> { /* wheel scrolls photos; actions are on-screen + side-button hold */ }
        Panel.TESTING -> {
            if (testingFocus == 0) { back(); host.backTone() }
            else { testingCount++; host.popTone() }
        }
        Panel.TERMINAL -> {
            val cmd = terminalInput.trim()
            if (cmd.isNotEmpty()) {
                host.terminalRun(cmd)
                host.popTone()
            }
        }
        Panel.HERMES_CHAT -> { /* push-to-talk handled in side-button dispatcher; wheel press is no-op */ }
        Panel.HERMES_CONFIG -> {
            host.hermesConfigRowActivate(hermesConfigFocus)
            host.selectTone()
        }
        Panel.TRANSLATOR_ONBOARDING -> {
            val langs = com.r1.launcher.translator.Languages.ALL
            when (translatorOnboardingStep) {
                0 -> {
                    // 0 = auto-detect, 1..N = langs[focus-1]
                    if (translatorOnboardingFocus == 0) {
                        host.translatorOnboardingPickSource(com.r1.launcher.translator.Languages.AUTO)
                    } else {
                        langs.getOrNull(translatorOnboardingFocus - 1)?.let {
                            host.translatorOnboardingPickSource(it.code)
                        }
                    }
                    host.selectTone()
                }
                1 -> {
                    langs.getOrNull(translatorOnboardingFocus)?.let {
                        host.translatorOnboardingPickTarget(it.code)
                    }
                    host.selectTone()
                }
                else -> when (translatorOnboardingFocus) {
                    0 -> { host.translatorOnboardingEnablePhoneKey(); host.selectTone() }
                    1 -> { host.translatorOnboardingPasteKey(); host.popTone() }
                    2 -> { host.translatorOnboardingSkipKey(); host.selectTone() }
                }
            }
        }
        Panel.TRANSLATOR -> {
            // Wheel press in translator: if there's draft text, submit it.
            // Otherwise no-op (PTT is the primary input gesture).
            val draft = translatorInputText.trim()
            if (draft.isNotEmpty()) {
                host.translatorSendText(draft)
                translatorInputText = ""
                host.popTone()
            }
        }
        Panel.TRANSLATOR_SETTINGS -> {
            host.translatorSettingsRowActivate(translatorSettingsFocus)
            host.selectTone()
        }
        // Wheel press on the clock screen jumps straight to the apps grid.
        Panel.HOME -> { openApps(); host.selectTone() }
        Panel.TRANSCRIBER_LIST -> when {
            // 0=back, 1=settings, 2=record, 3..N+2=meetings
            transcriberListFocus == 0 -> { back(); host.backTone() }
            transcriberListFocus == 1 -> { host.transcriberStartRecording(); host.popTone() }
            transcriberListFocus == 2 -> { host.transcriberOpenSettings(); host.selectTone() }
            transcriberListFocus - 3 in meetings.indices -> {
                val m = meetings[transcriberListFocus - 3]
                host.transcriberOpenDetail(m.uuid)
                host.selectTone()
            }
        }
        Panel.TRANSCRIBER_RECORDING -> { host.transcriberStopRecording(); host.popTone() }
        Panel.TRANSCRIBER_DETAIL -> {
            if (transcriberDetailMenuOpen) {
                val action = transcriberDetailMenuActions.getOrNull(transcriberDetailMenuFocus)
                if (action != null) {
                    host.transcriberDetailMenuActivate(action)
                    host.popTone()
                }
            } else when (transcriberDetailFocus) {
                0 -> { back(); host.backTone() }
                1 -> { host.transcriberOpenDetailMenu(); host.selectTone() }
            }
        }
        Panel.TRANSCRIBER_SETTINGS -> {
            host.transcriberSettingsRowActivate(transcriberSettingsFocus)
            host.selectTone()
        }
        Panel.NOTIFICATIONS -> {
            // 0=back, 1=header-clear (only when items exist), 2..N+1=items.
            // Match the panel + touch path: the list renders newest-first via
            // asReversed(), so wheel activation has to index the same view or
            // the focused (top) card opens the wrong (oldest) entry.
            val items = notifications.asReversed()
            when {
                notificationsFocus == 0 -> { back(); host.backTone() }
                items.isNotEmpty() && notificationsFocus == 1 -> {
                    host.notificationsClear()
                    host.popTone()
                }
                notificationsFocus - 2 in items.indices -> {
                    val n = items[notificationsFocus - 2]
                    host.notificationActivate(n.id)
                    host.selectTone()
                }
            }
        }
        Panel.SETTINGS_CREDENTIALS -> when (credentialsFocus) {
            0 -> { back(); host.backTone() }
            else -> { host.credentialsRowActivate(credentialsFocus); host.selectTone() }
        }
        Panel.NTFY_CONFIG -> { host.ntfyConfigRowActivate(ntfyConfigFocus); host.selectTone() }
    }
}

fun LauncherState.backPressed(host: LauncherHost) {
    if (panel == Panel.BT_SCAN) {
        host.stopBtScan(); back(); host.backTone()
        return
    }
    if (panel == Panel.OPENCLAW_CHAT || panel == Panel.OPENCLAW_QR) {
        host.openClawCloseSession()
        back(); host.backTone()
        return
    }
    if (panel == Panel.OPENCLAW_CAMERA) {
        back(); host.backTone()
        return
    }
    // Back from the recording panel always stops the FGS — leaving a recording
    // running while the user navigates away is almost never what they want
    // (and would chew battery indefinitely).
    if (panel == Panel.TRANSCRIBER_RECORDING) {
        host.transcriberStopRecording()
        back(); host.backTone()
        return
    }
    // On the detail page, back first closes the ⋮ overlay if it's open. Only
    // a second back unwinds to the list.
    if (panel == Panel.TRANSCRIBER_DETAIL && transcriberDetailMenuOpen) {
        transcriberDetailMenuOpen = false
        transcriberDetailMenuFocus = 0
        host.backTone()
        return
    }
    if (panel != Panel.HOME) {
        back(); host.backTone()
    }
}
