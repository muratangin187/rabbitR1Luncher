package com.r1.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.r1.launcher.LauncherHost
import com.r1.launcher.LauncherState
import com.r1.launcher.Panel
import com.r1.launcher.locale.LocalePrefs

/**
 * Root composable — FrameLayout-style z-stack:
 *   wallpaper + panel-under-focus → topbar → debug key
 *
 * Topbar hides while apps panel is open (it draws its own header).
 */
@Composable
fun LauncherRoot(
    state: LauncherState,
    host: LauncherHost,
) {
    val colors = LocalR1Colors.current
    val ctx = LocalContext.current
    val direction = if (LocalePrefs.isRtl(LocalePrefs.get(ctx).language)) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        // Base: home screen. Visible whenever nothing else is stacked over it.
        // Tapping the unread badge / banner opens the notifications panel.
        HomeScreen(
            state = state,
            onOpenApps = {
                state.openApps()
                host.selectTone()
            },
            onOpenNotifications = {
                state.openNotifications()
                state.notificationBanner = null
                host.selectTone()
            },
        )

        NotificationsPanel(
            state = state,
            onRowClick = { idx ->
                val items = state.notifications.asReversed()
                when {
                    idx == 0 -> { state.back(); host.backTone() }
                    items.isNotEmpty() && idx == 1 -> {
                        host.notificationsClear()
                        host.popTone()
                    }
                    idx - 2 in items.indices -> {
                        val n = items[idx - 2]
                        host.notificationActivate(n.id)
                        host.selectTone()
                    }
                }
            },
        )

        OnboardingPanel(
            state = state,
            onRowClick = { idx ->
                when (state.onboardingStep) {
                    0 -> {
                        // Language picker: idx is the index into LocalePrefs.SUPPORTED
                        val lang = LocalePrefs.SUPPORTED.getOrNull(idx)
                        if (lang != null) {
                            host.setLanguage(lang.code) // recreates activity; onCreate jumps to step 1
                            host.selectTone()
                        }
                    }
                    1 -> { state.advanceOnboarding(); host.selectTone() }
                    2 -> when (idx) {
                        0 -> { host.startWifiScan(); state.openWifiScan(); host.selectTone() }
                        1 -> if (state.simPresent) { state.advanceOnboarding(); host.selectTone() } else host.backTone()
                        2 -> { state.advanceOnboarding(); host.selectTone() }
                    }
                    3 -> when (idx) {
                        0 -> { host.checkForUpdate(); host.selectTone() }
                        1 -> { state.advanceOnboarding(); host.selectTone() }
                        2 -> { state.advanceOnboarding(); host.selectTone() }
                    }
                    else -> { host.onOnboardingDone(); host.selectTone() }
                }
            },
        )

        AppsPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onAppClick = { idx -> host.launchApp(idx) },
        )

        SettingsPanel(
            state = state,
            onRowClick = { idx ->
                // Order must match the rows list in SettingsPanel.kt:
                //   network, display, sound, voice, credentials, device, about
                // (language was promoted into device in v3.32)
                // and the wheel-activate dispatcher in LauncherNav.kt.
                when (idx) {
                    0 -> { state.back(); host.backTone() }
                    1 -> { state.openNetwork(); host.selectTone() }
                    2 -> { state.openSettingsDisplay(); host.selectTone() }
                    3 -> { state.openSettingsSound(); host.selectTone() }
                    4 -> { state.openSettingsVoice(); host.selectTone() }
                    5 -> { state.openSettingsCredentials(); host.selectTone() }
                    6 -> { state.openSettingsDevice(); host.selectTone() }
                    7 -> { state.openSettingsAbout(); host.selectTone() }
                }
            },
        )

        SettingsDisplayPanel(
            state = state,
            onRowClick = { idx ->
                when (idx) {
                    0 -> { state.back(); host.backTone() }
                    1 -> { state.openBrightness(); host.selectTone() }
                }
            },
        )

        SettingsSoundPanel(
            state = state,
            onRowClick = { idx ->
                when (idx) {
                    0 -> { state.back(); host.backTone() }
                    1 -> { host.toggleUiSoundEnabled(!state.uiSoundEnabled); host.popTone() }
                    2 -> { state.openUiVolume(); host.selectTone() }
                    3 -> { state.openVolume(); host.selectTone() }
                    4 -> { host.toggleNotificationSound(!state.notificationSoundEnabled); host.popTone() }
                }
            },
        )

        SettingsDevicePanel(
            state = state,
            onRowClick = { idx ->
                // Order must match SettingsDevicePanel rows + LauncherNav dispatcher:
                //   0=back, 1=haptics, 2=updates, 3=language, 4=reboot, 5=power off,
                //   6=reset camera, 7=factory reset
                when (idx) {
                    0 -> { state.back(); host.backTone() }
                    1 -> { host.toggleHaptics(!state.hapticsEnabled); host.popTone() }
                    2 -> { host.checkForUpdate(); host.selectTone() }
                    3 -> { state.openSettingsLanguage(); host.selectTone() }
                    4 -> { host.rebootDevice(); host.selectTone() }
                    5 -> { host.powerOffDevice(); host.selectTone() }
                    6 -> { host.resetCameraMotor(); host.popTone() }
                    7 -> { state.openFactoryConfirm(); host.selectTone() }
                }
            },
        )

        SettingsAboutPanel(
            state = state,
            onRowClick = { idx ->
                when (idx) {
                    0 -> { state.back(); host.backTone() }
                }
            },
        )

        SettingsVoicePanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onSaveCustomVoiceId = { id -> host.voiceSaveCustomVoiceId(id) },
            onPasteCustomVoiceId = { host.voicePasteCustomVoiceIdFromClipboard() },
            onClearCustomVoiceId = { host.voiceClearCustomVoiceId() },
            onRowClick = { idx -> host.voiceSettingsRowActivate(idx) },
        )

        SettingsCredentialsPanel(
            state = state,
            onRowClick = { idx ->
                if (idx == 0) {
                    state.back(); host.backTone()
                } else {
                    host.credentialsRowActivate(idx)
                    host.selectTone()
                }
            },
            onSaveField = { field, value -> host.credentialsSaveField(field, value) },
            onPasteField = { field -> host.credentialsPasteField(field) },
            onClearField = { field -> host.credentialsClearField(field) },
        )

        NtfyConfigPanel(
            state = state,
            onRowClick = { idx ->
                host.ntfyConfigRowActivate(idx)
                if (idx == 0) host.backTone() else host.popTone()
            },
        )

        SettingsVoiceTuningPanel(
            state = state,
            onRowClick = { idx -> host.voiceTuningRowActivate(idx) },
            onSetStability = { v -> host.voiceSetStability(v) },
            onSetSimilarity = { v -> host.voiceSetSimilarity(v) },
            onSetStyle = { v -> host.voiceSetStyle(v) },
            onSetSpeed = { v -> host.voiceSetSpeed(v) },
        )

        SettingsVoiceSubscriptionPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onRefresh = { host.voiceFetchSubscription(force = true) },
        )

        SettingsLanguagePanel(
            state = state,
            onRowClick = { idx ->
                if (idx == 0) {
                    state.back(); host.backTone()
                } else {
                    val lang = LocalePrefs.SUPPORTED.getOrNull(idx - 1)
                    if (lang != null) {
                        host.setLanguage(lang.code) // recreates activity
                        host.selectTone()
                    }
                }
            },
        )

        NetworkPanel(
            state = state,
            onRowClick = { idx ->
                when (idx) {
                    0 -> { state.back(); host.backTone() }
                    1 -> { host.toggleWifi(!state.wifiEnabled); host.popTone() }
                    2 -> { host.toggleCellular(!state.cellularOn); host.popTone() }
                    3 -> { state.openBtScan(); host.startBtScan(); host.selectTone() }
                    4 -> { state.openWifiShare(); host.selectTone() }
                    5 -> { state.openRemotePanel(); host.selectTone() }
                    6 -> { state.openNtfyConfig(); host.selectTone() }
                    7 -> { host.startWifiScan(); state.openWifiScan(); host.selectTone() }
                }
            },
        )

        RemotePanelSettings(
            state = state,
            onRowClick = { idx ->
                when (idx) {
                    0 -> { state.back(); host.backTone() }
                    1 -> { host.toggleWebServer(!state.webServerEnabled); host.popTone() }
                    2 -> { state.openPanelPasscodeEditor(); host.selectTone() }
                    3 -> { host.setCaptureMicEnabled(!state.captureMicEnabled); host.popTone() }
                    4 -> { host.setCapturePlaybackEnabled(!state.capturePlaybackEnabled); host.popTone() }
                    5 -> { host.setWebTerminalEnabled(!state.webTerminalEnabled); host.popTone() }
                }
            },
        )

        FactoryConfirmPanel(
            state = state,
            onRowClick = { idx ->
                when (idx) {
                    0 -> { state.back(); host.backTone() }
                    1 -> { host.factoryReset(); host.selectTone() }
                }
            },
        )

        WifiScanPanel(
            state = state,
            onRowClick = { idx ->
                if (idx == 0) {
                    state.back(); host.backTone()
                } else {
                    val ssid = state.wifiScanResults.getOrNull(idx - 1)
                    if (ssid != null) {
                        state.openWifiPassword(ssid)
                        host.selectTone()
                    }
                }
            },
        )

        BluetoothScanPanel(
            state = state,
            onRowClick = { idx ->
                when (idx) {
                    0 -> { host.stopBtScan(); state.back(); host.backTone() }
                    1 -> { host.toggleBluetooth(!state.btOn); host.popTone() }
                    else -> {
                        val dev = state.btDevices.getOrNull(idx - 2)
                        if (dev != null) { host.pairBtDevice(dev.address); host.selectTone() }
                    }
                }
            },
        )

        WifiPasswordPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onSubmit = {
                host.connectToWifi(state.wifiSelectedSsid, state.wifiPasswordInput)
            }
        )

        WifiSharePanel(
            state = state,
            onRowClick = { idx ->
                when (idx) {
                    0 -> { state.back(); host.backTone() }
                    1 -> { host.toggleWifiShare(!state.wifiShareEnabled); host.popTone() }
                    2 -> { state.openWifiShareEdit(com.r1.launcher.WifiShareEditTarget.SSID); host.selectTone() }
                    3 -> { state.openWifiShareEdit(com.r1.launcher.WifiShareEditTarget.PASSWORD); host.selectTone() }
                    4 -> {
                        if (state.wifiShareConnectedClients.isNotEmpty()) {
                            state.wifiShareClientsExpanded = !state.wifiShareClientsExpanded
                            host.popTone()
                        }
                    }
                    5 -> { host.wifiShareCycleTimer(); host.popTone() }
                }
            },
        )

        WifiShareEditPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onSubmit = { host.wifiShareSaveEdit() },
        )

        PanelPasscodeEditor(
            state = state,
            onBack = { state.back(); host.backTone() },
            onSubmit = { code ->
                host.panelPasscodeSave(code)
                state.back(); host.selectTone()
            },
        )

        BrightnessPanel(
            state = state,
            onScrimClick = { state.back(); host.backTone() },
        )

        VolumePanel(
            state = state,
            onScrimClick = { state.back(); host.backTone() },
        )

        UiVolumePanel(
            state = state,
            onScrimClick = { state.back(); host.backTone() },
        )

        OpenClawQrPanel(
            state = state,
            onScanned = { raw -> host.openClawScanned(raw) },
            onBack = { host.openClawCloseSession(); state.back(); host.backTone() },
        )

        OpenClawChatPanel(
            state = state,
            onBack = { host.openClawCloseSession(); state.back(); host.backTone() },
            onSend = { text -> host.openClawSendText(text) },
            onOpenSettings = { state.openOpenClawSettings(); host.selectTone() },
            onSwitchSession = { key -> host.openClawSwitchSession(key); host.selectTone() },
            onOpenSessions = {
                state.openOpenClawSessions()
                host.openClawRefreshSessions() // best-effort fresh fetch on entry
                host.selectTone()
            },
            onOpenCamera = {
                host.openClawOpenCameraAsk()
                host.selectTone()
            },
            onCopyCode = { code -> host.copyToClipboard(code, "openclaw-code"); host.popTone() },
            onCompactContext = { host.openClawCompactSession(); host.popTone() },
            onClearContext = { host.openClawClearContext(); host.popTone() },
            getClipboardText = { host.getClipboardText() },
            onMicStart = { host.openClawRecordStart() },
            onMicStop = { host.openClawRecordStop() },
        )

        OpenClawCameraPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onCaptured = { bytes -> host.openClawCameraCaptured(bytes) },
            onRetake = { host.openClawCameraRetake() },
            onSend = { prompt -> host.openClawCameraSend(prompt) },
        )

        OpenClawSessionsPanel(
            state = state,
            onRowClick = { idx -> host.openClawSessionsRowActivate(idx) },
        )

        OpenClawSettingsPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onRowClick = { idx -> host.openClawSettingsRowActivate(idx) },
            onFontSizeChange = { size -> host.openClawSetFontSize(size) },
        )

        MessagesPanel(
            state = state,
            onRowClick = { idx ->
                if (idx == 0) {
                    state.back(); host.backTone()
                } else {
                    val conv = state.smsConversations.getOrNull(idx - 1)
                    if (conv != null) {
                        host.openSmsThread(conv.address, conv.displayName)
                        host.selectTone()
                    }
                }
            },
        )

        MessagesThreadPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
        )

        ChatListPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onNew = { host.chatNew() },
            onOpen = { id -> host.chatOpen(id) },
            onDelete = { id -> host.chatDelete(id) },
        )

        ChatPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onSend = { host.chatSend() },
            onStop = { host.chatStop() },
            onRetry = { host.chatRetry() },
            onSettings = { state.openChatSettings(); host.selectTone() },
            onToggleKb = { state.chatKbVisible = !state.chatKbVisible; host.popTone() },
            onKeyPress = { s2 -> state.chatInput += s2 },
            onBackspace = { if (state.chatInput.isNotEmpty()) state.chatInput = state.chatInput.dropLast(1) },
            onToggleImageMode = { host.chatToggleImageMode() },
            onAttach = { host.chatAttachNewestPhoto() },
            onClearAttachment = { state.chatAttachment = null },
            onSpeakToggle = { host.chatToggleSpeak() },
        )

        ChatSettingsPanel(
            state = state,
            onRowClick = { idx -> host.chatSettingsActivate(idx) },
        )

        CameraPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onShutter = { host.cameraShutter() },
            onFlip = { host.cameraFlip(); host.popTone() },
            onOpenGallery = { host.cameraOpenGallery() },
            onCameraEvent = { ev -> host.onCameraEvent(ev) },
        )

        GalleryPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onOpen = { idx -> state.openGalleryView(idx); host.selectTone() },
        )

        GalleryViewPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onDelete = { host.galleryDeleteCurrent() },
            onRetry = { host.galleryAiRetry() },
            onDismissStatus = { state.aiReset() },
        )

        TestingPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onTap = { state.testingCount++; host.popTone() },
        )

        TerminalPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onKeyPress = { ch -> state.terminalInput += ch },
            onBackspace = {
                if (state.terminalInput.isNotEmpty()) {
                    state.terminalInput = state.terminalInput.dropLast(1)
                }
            },
            onSubmit = {
                val cmd = state.terminalInput.trim()
                if (cmd.isNotEmpty()) {
                    host.terminalRun(cmd)
                    host.popTone()
                }
            },
            onClear = { host.terminalClear(); host.popTone() },
            onToggleKb = {
                state.terminalKbVisible = !state.terminalKbVisible
                host.popTone()
            },
            onPaste = { host.terminalPasteFromClipboard(); host.popTone() },
            onAppendInput = { text ->
                state.terminalInput = if (state.terminalInput.isBlank()) text
                    else state.terminalInput.trimEnd() + " " + text
                host.popTone()
            },
            getClipboardText = { host.getClipboardText() },
            onMicStart = { host.terminalRecordStart() },
            onMicStop = { host.terminalRecordStop() },
        )

        HermesChatPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onSend = { text -> host.hermesSendText(text); host.popTone() },
            onClear = { host.hermesClearHistory(); host.popTone() },
            onOpenConfig = { state.openHermesConfig(fromChat = true); host.selectTone() },
            getClipboardText = { host.getClipboardText() },
            onCopyMessage = { text -> host.copyToClipboard(text, "hermes-message"); host.popTone() },
            onMicStart = { host.hermesRecordStart() },
            onMicStop = { host.hermesRecordStop() },
        )

        HermesConfigPanel(
            state = state,
            onRowClick = { idx -> host.hermesConfigRowActivate(idx) },
        )

        HermesQrPanel(
            state = state,
            onScanned = { raw -> host.hermesScanned(raw) },
            onBack = { state.back(); host.backTone() },
        )

        HermesConnectionEditPanel(
            state = state,
            onRowClick = { idx -> host.hermesConnectionEditRowActivate(idx) },
            onSaveUrl = { value -> host.hermesConnectionEditSaveUrl(value) },
            onSaveKey = { value -> host.hermesConnectionEditSaveKey(value) },
            onPasteUrl = { host.hermesConnectionEditPasteUrl() },
            onPasteKey = { host.hermesConnectionEditPasteKey() },
        )

        TranslatorOnboardingPanel(
            state = state,
            onPickSource = { code -> host.translatorOnboardingPickSource(code) },
            onPickTarget = { code -> host.translatorOnboardingPickTarget(code) },
            onEnablePhoneKey = { host.translatorOnboardingEnablePhoneKey() },
            onPasteKey = { host.translatorOnboardingPasteKey() },
            onSkipKey = { host.translatorOnboardingSkipKey() },
            onFinish = { host.translatorOnboardingFinish() },
        )

        TranslatorPanel(
            state = state,
            onBack = { state.back(); host.backTone() },
            onSend = { text -> host.translatorSendText(text) },
            onCycleSource = { delta -> host.translatorCycleSource(delta) },
            onCycleTarget = { delta -> host.translatorCycleTarget(delta) },
            onPickSource = { code -> host.translatorSetSource(code) },
            onPickTarget = { code -> host.translatorSetTarget(code) },
            onSwapLangs = { host.translatorSwapLangs(); host.popTone() },
            onReplay = { id -> host.translatorReplay(id) },
            onCopySource = { text -> host.copyToClipboard(text, "translator-source"); host.popTone() },
            onClear = { host.translatorClearHistory(); host.popTone() },
            onOpenSettings = { state.openTranslatorSettings(); host.selectTone() },
            onToggleHideInput = { host.translatorToggleHideInput(); host.popTone() },
            getClipboardText = { host.getClipboardText() },
            onMicStart = { host.translatorRecordStart() },
            onMicStop = { host.translatorRecordStop() },
        )

        TranslatorSettingsPanel(
            state = state,
            onRowClick = { idx ->
                if (idx == 0) { state.back(); host.backTone() }
                else host.translatorSettingsRowActivate(idx)
            },
            onSaveKey = { provider, value -> host.translatorSaveKey(provider, value) },
            onPasteKey = { provider -> host.translatorPasteKey(provider) },
            onClearKey = { provider -> host.translatorClearKey(provider) },
            onPickSource = { code -> host.translatorSetSource(code) },
            onPickTarget = { code -> host.translatorSetTarget(code) },
        )

        TranscriberListPanel(
            state = state,
            onRowClick = { idx ->
                // Layout: 0=back, 1=+ (record), 2=settings (gear), 3..N+2=meetings.
                when {
                    idx == 0 -> { state.back(); host.backTone() }
                    idx == 1 -> { host.transcriberStartRecording(); host.popTone() }
                    idx == 2 -> { host.transcriberOpenSettings(); host.selectTone() }
                    idx - 3 in state.meetings.indices -> {
                        val m = state.meetings[idx - 3]
                        host.transcriberOpenDetail(m.uuid)
                        host.selectTone()
                    }
                }
            },
        )

        TranscriberRecordingPanel(
            state = state,
            onBack = {
                host.transcriberStopRecording()
                state.back(); host.backTone()
            },
            onStop = { host.transcriberStopRecording(); host.popTone() },
        )

        TranscriberDetailPanel(
            state = state,
            onBack = {
                if (state.transcriberDetailMenuOpen) {
                    state.transcriberDetailMenuOpen = false
                    state.transcriberDetailMenuFocus = 0
                    host.backTone()
                } else {
                    state.back()
                    host.backTone()
                }
            },
            onMenuOpen = { host.transcriberOpenDetailMenu(); host.selectTone() },
            onMenuItemClick = { action ->
                host.transcriberDetailMenuActivate(action)
                host.popTone()
            },
            onMenuClose = {
                state.transcriberDetailMenuOpen = false
                state.transcriberDetailMenuFocus = 0
                host.backTone()
            },
        )

        TranscriberSettingsPanel(
            state = state,
            onRowClick = { idx -> host.transcriberSettingsRowActivate(idx) },
            onSaveField = { field, value -> host.transcriberSaveSmtpField(field, value) },
            onPasteField = { field -> host.transcriberPasteSmtpField(field) },
            onCloseKeyboard = {
                state.transcriberSettingsEditField = ""
                state.transcriberSettingsEditInput = ""
            },
        )

        // Topbar overlay — only on the clock screen; every other panel
        // either draws its own header or is a full-screen takeover.
        val topbarVisible = state.panel == Panel.HOME
        AnimatedVisibility(
            visible = topbarVisible,
            enter = fadeIn(tween(ANIM_OPEN_MS, easing = EnterEasing)),
            exit = fadeOut(tween(ANIM_CLOSE_MS, easing = ExitEasing)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Topbar(state = state)
        }

        // Notification banner — floats above every panel except NOTIFICATIONS
        // itself. Tap to jump to the notifications list. Self-dismisses ~4s.
        NotificationBanner(
            state = state,
            onClick = {
                state.openNotifications()
                state.notificationBanner = null
                host.selectTone()
            },
        )

        // Toast overlay — top of the z-stack so it floats above every panel
        // and the topbar. Self-dismissing; see ToastOverlay.kt.
        ToastOverlay(state = state)

    }
    }
}
