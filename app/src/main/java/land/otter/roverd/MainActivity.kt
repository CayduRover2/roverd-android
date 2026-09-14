package land.otter.roverd

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity() {
    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 2
    }

    private enum class Page(val label: String) {
        SYSTEM("SYSTEM"),
        ROOMBA("ROOMBA"),
        CAMERA("CAMERA"),
        MIC("MIC"),
        AUDIO("AUDIO"),
        LOG("LOG"),
    }

    private data class RotationOption(val value: Int, val label: String)

    private lateinit var statusView: TextView
    private lateinit var pageHost: FrameLayout
    private var currentPage = Page.SYSTEM
    private val tabButtons = mutableMapOf<Page, Button>()

    private var systemDiagnosticsView: TextView? = null
    private var roombaDiagnosticsView: TextView? = null
    private var cameraSummaryView: TextView? = null
    private var cameraRuntimeView: TextView? = null
    private var cameraInventoryView: TextView? = null
    private var logView: TextView? = null

    private var nameView: EditText? = null
    private var serverView: EditText? = null

    private var baudView: EditText? = null
    private var speedView: EditText? = null
    private var brcLineView: Spinner? = null
    private var brcActiveLowView: CheckBox? = null
    private var brcEveryView: EditText? = null
    private var brcWidthView: EditText? = null
    private var autoSideBrushEnabledView: CheckBox? = null
    private var autoSideBrushSpeedView: EditText? = null

    private var cameraEnabledView: CheckBox? = null
    private var cameraSourceUsbView: CheckBox? = null
    private var cameraIdView: Spinner? = null
    private var cameraSizeView: Spinner? = null
    private var cameraFpsView: Spinner? = null
    private var cameraRotationView: Spinner? = null

    private var cameraInvertView: CheckBox? = null
    private var cameraExposureView: Spinner? = null
    private var cameraEncoderView: Spinner? = null
    private var cameraBitrateView: EditText? = null
    private var cameraPublishUrlView: EditText? = null
    private var headlightEnabledView: CheckBox? = null
    private var headlightInitialOnView: CheckBox? = null

    private var cameraChoices: List<CameraChoice> = emptyList()
    private var cameraCatalog: CameraModeCatalog? = null
    private var cameraSizes: List<CameraSizeOption> = emptyList()
    private var cameraFpsOptions: List<CameraFpsOption> = emptyList()
    private var cameraRotationOptions: List<RotationOption> = emptyList()
    private var cameraExposureValues: List<Int> = emptyList()
    private var cameraEncoderNames: List<String> = emptyList()
    private var updatingCameraControls = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshVisiblePage()
            uiHandler.postDelayed(this, 500)
        }
    }

    private val statusListener: (String) -> Unit = { value ->
        runOnUiThread {
            if (::statusView.isInitialized) statusView.text = "STATUS: $value"
        }
    }

    private val logListener: () -> Unit = {
        runOnUiThread {
            if (currentPage == Page.LOG) refreshLog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RoverRuntimeState.initialize(this)
        setContentView(buildShell())
        showPage(Page.SYSTEM)
        requestNotificationPermission()
        startRoverService()
        startConfiguredCameraIfPossible()
        startConfiguredMicIfPossible()
        startConfiguredAudioPlayback()
        requestBatteryOptimizationExemption(userInitiated = false)
    }

    override fun onStart() {
        super.onStart()
        RoverRuntimeState.addListener(statusListener)
        RoverRuntimeState.addLogListener(logListener)
        uiHandler.post(refreshRunnable)
    }

    override fun onStop() {
        uiHandler.removeCallbacks(refreshRunnable)
        RoverRuntimeState.removeLogListener(logListener)
        RoverRuntimeState.removeListener(statusListener)
        super.onStop()
    }

    private fun buildShell(): View {
        val density = resources.displayMetrics.density
        val pad = (8 * density).toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        statusView = TextView(this).apply {
            text = "STATUS: Starting"
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }
        root.addView(
            statusView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val tabScroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = true }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (page in Page.entries) {
            val button = Button(this).apply {
                text = page.label
                setOnClickListener { showPage(page) }
            }
            tabButtons[page] = button
            tabRow.addView(button)
        }
        tabScroller.addView(tabRow)
        root.addView(
            tabScroller,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        pageHost = FrameLayout(this)
        root.addView(
            pageHost,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        return root
    }

    private fun showPage(page: Page) {
        currentPage = page
        tabButtons.forEach { (p, button) -> button.isEnabled = p != page }

        systemDiagnosticsView = null
        roombaDiagnosticsView = null
        cameraSummaryView = null
        cameraRuntimeView = null
        cameraInventoryView = null
        logView = null
        nameView = null
        serverView = null
        baudView = null
        speedView = null
        brcLineView = null
        brcActiveLowView = null
        brcEveryView = null
        brcWidthView = null
        autoSideBrushEnabledView = null
        autoSideBrushSpeedView = null
        clearCameraUiReferences()

        pageHost.removeAllViews()
        val view = when (page) {
            Page.SYSTEM -> SystemSettingsView(this)
            Page.ROOMBA -> RoombaSettingsView(this)
            Page.CAMERA -> buildCameraPage()
            Page.MIC -> buildMicPage()
            Page.AUDIO -> buildAudioPage()
            Page.LOG -> buildLogPage()
        }
        pageHost.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        refreshVisiblePage()
    }

    private fun clearCameraUiReferences() {
        cameraEnabledView = null
        cameraSourceUsbView = null
        cameraIdView = null
        cameraSizeView = null
        cameraFpsView = null
        cameraRotationView = null

        cameraInvertView = null
        cameraExposureView = null
        cameraEncoderView = null
        cameraBitrateView = null
        cameraPublishUrlView = null
        headlightEnabledView = null
        headlightInitialOnView = null
        cameraChoices = emptyList()
        cameraCatalog = null
        cameraSizes = emptyList()
        cameraFpsOptions = emptyList()
        cameraRotationOptions = emptyList()
        cameraExposureValues = emptyList()
        cameraEncoderNames = emptyList()
        updatingCameraControls = false
    }

    // Retained for internal diagnostics/backward compatibility. The visible SYSTEM page is
    // SystemSettingsView, which exposes the complete rover identity/media config.
    private fun buildSystemPage(): View = scrollPage { root, _ ->
        pageTitle(root, "SYSTEM / ROVER SERVER")
        systemDiagnosticsView = diagnosticText(12f).also { root.addView(it) }
        section(root, "RUNTIME")
        addButton(root, "RESTART FULL ROVER RUNTIME") { sendServiceAction(RoverService.ACTION_RESTART) }
        addButton(root, "REQUEST BATTERY / DOZE EXEMPTION") { requestBatteryOptimizationExemption(userInitiated = true) }
        addButton(root, "COPY ALL DIAGNOSTICS + LOG") { copyAllDiagnostics() }
        section(root, "SETTINGS")
        val cfg = RoverSettings.load(this)
        nameView = edit(root, "Rover name").apply { setText(cfg.name) }
        serverView = edit(root, "Server WebSocket URL").apply { setText(cfg.serverUrl) }
        addButton(root, "SAVE SYSTEM SETTINGS + RESTART") { saveSystemSettings() }
    }

    // Retained for internal diagnostics/backward compatibility. The visible ROOMBA page is
    // RoombaSettingsView, which exposes USB selection, battery thresholds and private safety.
    private fun buildRoombaPage(): View = scrollPage { root, _ ->
        pageTitle(root, "ROOMBA / USB SERIAL / OI")
        roombaDiagnosticsView = diagnosticText(12f).also { root.addView(it) }
        section(root, "SERIAL / OI RECOVERY")
        addButton(root, "RECONNECT USB SERIAL") { sendServiceAction(RoverService.ACTION_RECONNECT_USB) }
        addButton(root, "RESTART SENSOR STREAM") { sendServiceAction(RoverService.ACTION_RESTART_SENSOR_STREAM) }
        addButton(root, "PULSE BRC NOW") { sendServiceAction(RoverService.ACTION_PULSE_BRC) }
        section(root, "ROOMBA SETTINGS")
        val cfg = RoverSettings.load(this)
        baudView = edit(root, "Serial baud").apply { setText(cfg.baud.toString()) }
        speedView = edit(root, "Max wheel speed (mm/s)").apply { setText(cfg.maxWheelSpeed.toString()) }
        autoSideBrushEnabledView = CheckBox(this).apply {
            text = "Automatic side brush while driving"
            isChecked = cfg.autoSideBrushEnabled
        }
        root.addView(autoSideBrushEnabledView)
        autoSideBrushSpeedView = edit(root, "Automatic side brush PWM (-127..127)").apply { setText(cfg.autoSideBrushSpeed.toString()) }
        root.addView(TextView(this).apply { text = "BRC control line" })
        brcLineView = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, BrcLine.entries.map { it.name })
            setSelection(BrcLine.entries.indexOf(cfg.brcLine).coerceAtLeast(0))
        }
        root.addView(brcLineView)
        brcActiveLowView = CheckBox(this).apply {
            text = "BRC active state is LOW / control-line false"
            isChecked = cfg.brcActiveLow
        }
        root.addView(brcActiveLowView)
        brcEveryView = edit(root, "BRC pulse interval (ms)").apply { setText(cfg.brcPulseEveryMs.toString()) }
        brcWidthView = edit(root, "BRC pulse width (ms)").apply { setText(cfg.brcPulseWidthMs.toString()) }
        addButton(root, "SAVE ROOMBA SETTINGS + RESTART") { saveRoombaSettings() }
    }

    private fun buildCameraPage(): View = scrollPage { root, _ ->
        pageTitle(root, "CAMERA")

        cameraSummaryView = diagnosticText(12f).apply { text = cameraSummarySnapshot() }.also { root.addView(it) }
        section(root, "CAMERA / CAPTURE SETTINGS")
        val cfg = RoverSettings.load(this)

        cameraEnabledView = CheckBox(this).apply {
            text = "Enable camera H.264 -> MediaMTX publishing"
            isChecked = cfg.cameraEnabled
        }
        root.addView(cameraEnabledView)

        cameraSourceUsbView = CheckBox(this).apply {
            text = "USB Webcam Mode (auto-detect Logitech C920 / UVC camera via OTG)"
            isChecked = cfg.cameraSource == "USB_WEBCAM"
        }
        root.addView(cameraSourceUsbView)
        root.addView(diagnosticText(10f).apply {
            text = "USB Webcam: direct USB/UVC mode. The C920 is opened through the USB host stack, not Camera2.\n" +
                "Enable above, SAVE + APPLY, and grant the Android USB permission if prompted."
        })

        cameraChoices = readCameraChoices(cfg.cameraId)
        cameraIdView = addSpinner(root, "Camera source", cameraChoices.map { it.label })
        val initialCameraId = if (cfg.cameraSource == "USB_WEBCAM") "USB_UVC" else cfg.cameraId
        cameraIdView?.setSelection(cameraChoices.indexOfFirst { it.id == initialCameraId }.coerceAtLeast(0))
        cameraIdView?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingCameraControls) return
                val selected = cameraChoices.getOrNull(position)?.id ?: return
                populateCameraModeControls(selected, RoverSettings.load(this@MainActivity), preserveSaved = selected == cfg.cameraId)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        cameraSizeView = addSpinner(root, "Capture resolution", emptyList())
        cameraFpsView = addSpinner(root, "AE target FPS range", emptyList())
        cameraRotationView = addSpinner(root, "Output rotation", emptyList())
        cameraInvertView = CheckBox(this).apply {
            text = "INVERT VIDEO (180Â°)"
            isChecked = cfg.cameraRotation == 180
            setOnCheckedChangeListener { _, checked ->
                if (updatingCameraControls) return@setOnCheckedChangeListener
                val target = if (checked) 180 else 0
                val index = cameraRotationOptions.indexOfFirst { it.value == target }
                if (index >= 0) cameraRotationView?.setSelection(index)
            }
        }
        root.addView(cameraInvertView)
        cameraExposureView = addSpinner(root, "AE exposure compensation", emptyList())

        cameraEncoderNames = listOf("AUTO") + runCatching { CameraDiagnostics.h264SurfaceEncoders() }
            .getOrElse {
                RoverRuntimeState.log("CAMERA encoder enumeration failed: ${it.stackTraceToString()}")
                emptyList()
            }
        cameraEncoderView = addSpinner(root, "H.264 Surface encoder", cameraEncoderNames)
        cameraEncoderView?.setSelection(cameraEncoderNames.indexOf(cfg.cameraEncoderName).takeIf { it >= 0 } ?: 0)

        cameraBitrateView = edit(root, "H.264 bitrate (bits/sec)").apply { setText(cfg.cameraBitrate.toString()) }
        root.addView(diagnosticText(10f).apply { text = "Shared MediaMTX RTSP port: ${cfg.mediaRtspPort} (SYSTEM tab)" })
        cameraPublishUrlView = edit(root, "RTSP publish URL override (blank = derive from server host + rover name)").apply {
            setText(cfg.cameraPublishUrl)
        }

        root.addView(diagnosticText(10f).apply {
            text = "Video path    : Camera2 -> SurfaceTexture -> GLES -> MediaCodec H.264 -> RTSP/TCP\n" +
                "effective URL : ${effectiveCameraPublishUrl(cfg)}\n" +
                "AUTO rotation : uses the selected camera SENSOR_ORIENTATION; phone UI rotation does not control the video."
        })

        populateCameraModeControls(cfg.cameraId, cfg, preserveSaved = true)

        addButton(root, "REFRESH CAMERA / MODE LISTS") { refreshCameraList() }
        addButton(root, "REQUEST CAMERA PERMISSION") { requestCameraPermission() }

        section(root, "PHONE FLASHLIGHT / ROVER HEADLIGHT")
        headlightEnabledView = CheckBox(this).apply {
            text = "Expose phone flashlight as rover headlight / night vision"
            isChecked = cfg.headlightEnabled
        }
        root.addView(headlightEnabledView)
        headlightInitialOnView = CheckBox(this).apply {
            text = "Headlight initially ON when roverd starts"
            isChecked = cfg.headlightInitialOn
        }
        root.addView(headlightInitialOnView)
        root.addView(diagnosticText(10f).apply { text = HeadlightController.snapshot() })

        addButton(root, "SAVE CAMERA / HEADLIGHT SETTINGS + APPLY") { saveCameraSettings() }

        section(root, "VIDEO ENCODER / STREAM")
        cameraRuntimeView = diagnosticText(11f).apply { text = cameraRuntimeSnapshot() }.also { root.addView(it) }
        addButton(root, "REFRESH STREAM STATUS") { refreshCameraRuntime() }
        addButton(root, "START / RESTART CAMERA STREAM") { saveCameraSettings(startEvenIfUnchecked = true) }
        addButton(root, "STOP CAMERA STREAM") {
            stopCameraPublisher()
            RoverRuntimeState.setCameraPipelineState(running = false, state = "Stopped from UI", error = "")
            refreshCameraRuntime()
        }

        section(root, "RAW CAMERA INVENTORY / CAPABILITIES")
        addButton(root, "REFRESH RAW CAMERA INVENTORY") { refreshCameraInventory() }
        cameraInventoryView = diagnosticText(10f).apply {
            text = "Not read yet. Press REFRESH RAW CAMERA INVENTORY.\nCamera hardware is not opened by this inventory button."
        }.also { root.addView(it) }
    }

    private fun buildMicPage(): View = MicSettingsView(this)

    private fun buildAudioPage(): View = AudioSettingsView(this)

    private fun buildLogPage(): View = scrollPage { root, pad ->
        pageTitle(root, "LOG / CRASH OUTPUT")
        addButton(root, "COPY ALL DIAGNOSTICS + LOG") { copyAllDiagnostics() }
        addButton(root, "CLEAR LOG") { RoverRuntimeState.clearLog() }
        logView = diagnosticText(10f).apply { setPadding(0, pad / 2, 0, pad * 2) }.also { root.addView(it) }
        refreshLog()
    }

    private fun scrollPage(builder: (LinearLayout, Int) -> Unit): View {
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        builder(root, pad)
        scroll.addView(root)
        return scroll
    }

    private fun pageTitle(root: LinearLayout, label: String) {
        root.addView(TextView(this).apply {
            text = "ROVERD ANDROID / $label"
            textSize = 21f
            typeface = Typeface.MONOSPACE
        })
    }

    private fun section(root: LinearLayout, label: String) {
        root.addView(TextView(this).apply {
            text = "\n--- $label ---"
            typeface = Typeface.MONOSPACE
            textSize = 16f
        })
    }

    private fun diagnosticText(size: Float): TextView = TextView(this).apply {
        textSize = size
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }

    private fun addButton(root: LinearLayout, label: String, action: () -> Unit) {
        root.addView(Button(this).apply {
            text = label
            setOnClickListener { action() }
        })
    }

    private fun edit(root: LinearLayout, label: String): EditText {
        root.addView(TextView(this).apply { text = label })
        return EditText(this).also { root.addView(it) }
    }

    private fun addSpinner(root: LinearLayout, label: String, items: List<String>): Spinner {
        root.addView(TextView(this).apply { text = label })
        return Spinner(this).also { spinner ->
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
            root.addView(spinner)
        }
    }

    private fun refreshVisiblePage() {
        when (currentPage) {
            Page.CAMERA -> Unit
            Page.LOG -> refreshLog()
            else -> Unit
        }
    }

    private fun setTextIfChanged(view: TextView?, value: String) {
        if (view != null && view.text.toString() != value) view.text = value
    }

    private fun systemSnapshot(): String {
        val now = System.currentTimeMillis()
        val commandAge = if (RoverRuntimeState.lastCommandAtMs == 0L) "never" else "${now - RoverRuntimeState.lastCommandAtMs} ms ago"
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOptimization = if (Build.VERSION.SDK_INT >= 23) {
            if (power.isIgnoringBatteryOptimizations(packageName)) "EXEMPT" else "ACTIVE"
        } else "n/a (< API 23)"
        val powerSaver = if (Build.VERSION.SDK_INT >= 21) power.isPowerSaveMode.toString() else "n/a"
        val deviceIdle = if (Build.VERSION.SDK_INT >= 23) power.isDeviceIdleMode.toString() else "n/a"
        val cfg = RoverSettings.load(this)

        return buildString {
            appendLine("device        : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android       : ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("rover name    : ${cfg.name}")
            appendLine("description   : ${cfg.description.ifBlank { "-" }}")
            appendLine("color         : ${cfg.color.ifBlank { "-" }}")
            appendLine("server URL    : ${cfg.serverUrl}")
            appendLine("MediaMTX RTSP : ${cfg.mediaRtspPort}")
            appendLine("WS connected  : ${RoverRuntimeState.serverConnected}")
            appendLine("CPU wake lock : ${RoverRuntimeState.wakeLockHeld}")
            appendLine("Wi-Fi lock    : ${RoverRuntimeState.wifiLockHeld}")
            appendLine("battery opt   : $batteryOptimization")
            appendLine("power saver   : $powerSaver")
            appendLine("device idle   : $deviceIdle")
            appendLine("commands      : ${RoverRuntimeState.commandCount}")
            appendLine("last command  : $commandAge")
            append("command JSON  : ${RoverRuntimeState.lastCommand.ifBlank { "-" }}")
        }
    }

    private fun roombaSnapshot(): String {
        val now = System.currentTimeMillis()
        val sensorAge = if (RoverRuntimeState.lastSensorAtMs == 0L) "never" else "${now - RoverRuntimeState.lastSensorAtMs} ms ago"
        val autoChargeAge = if (RoverRuntimeState.lastAutoChargeAtMs == 0L) "never" else "${now - RoverRuntimeState.lastAutoChargeAtMs} ms ago"
        val chargeSourcesText = if (RoverRuntimeState.chargeSources < 0) "-" else "0x%02X".format(RoverRuntimeState.chargeSources)
        val chargingStateText = if (RoverRuntimeState.chargingState < 0) "-" else RoverRuntimeState.chargingState.toString()
        val cfg = RoverSettings.load(this)
        val s = cfg.privateSafety
        return buildString {
            appendLine("USB connected : ${RoverRuntimeState.usbConnected}")
            appendLine("USB selection : ${cfg.usbSerialPreference}")
            appendLine("USB device    : ${RoverRuntimeState.usbDevice.ifBlank { "-" }}")
            appendLine("serial        : ${cfg.baud} 8N1")
            appendLine("max wheel     : ${cfg.maxWheelSpeed} mm/s")
            appendLine("battery cfg   : full=${cfg.batteryFull} warn=${cfg.batteryWarn} urgent=${cfg.batteryUrgent}")
            appendLine("auto side     : enabled=${cfg.autoSideBrushEnabled} speed=${cfg.autoSideBrushSpeed}")
            appendLine("BRC           : ${cfg.brcLine} activeLow=${cfg.brcActiveLow} every=${cfg.brcPulseEveryMs}ms width=${cfg.brcPulseWidthMs}ms")
            appendLine("private       : ${cfg.privateEnabled}")
            appendLine("private speed : ${s.speedLimitEnabled}/${s.speedLimitMaxWheelSpeed}")
            appendLine("private OC    : ${s.hardOvercurrentEnabled}/${s.overcurrentStopMs}ms")
            appendLine("private bump  : ${s.hardBumpEnabled}/${s.bumpBackoffSpeed}/${s.bumpBackoffMs}ms")
            appendLine("private cliff : ${s.cliffEnabled}/${s.cliffBackoffSpeed}/${s.cliffBackoffMs}ms")
            appendLine("private wall  : ${s.virtualWallEnabled}/${s.virtualWallBackoffSpeed}/${s.virtualWallBackoffMs}ms cd=${s.triggerCooldownMs}")
            appendLine("charge state  : $chargingStateText")
            appendLine("charge sources: $chargeSourcesText")
            appendLine("home base     : ${RoverRuntimeState.homeBaseDetected}")
            appendLine("charging      : ${RoverRuntimeState.roombaCharging}")
            appendLine("AutoCharge    : ${RoverRuntimeState.autoChargeState}")
            appendLine("AC timer      : ${RoverRuntimeState.autoChargeTimerActive}")
            appendLine("AC seek count : ${RoverRuntimeState.autoChargeSeekCount}")
            appendLine("last AC seek  : $autoChargeAge")
            appendLine("sensor frames : ${RoverRuntimeState.sensorFrames}")
            appendLine("sensor bytes  : ${RoverRuntimeState.sensorBytes}")
            appendLine("last sensor   : $sensorAge")
            append("last frame    : ${RoverRuntimeState.lastSensorHex.ifBlank { "-" }}")
        }
    }

    private fun cameraSummarySnapshot(): String {
        val cfg = RoverSettings.load(this)
        val permission = if (Build.VERSION.SDK_INT < 23) {
            "install-time permission"
        } else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "GRANTED"
        } else "NOT GRANTED"
        val rotation = if (cfg.cameraRotation < 0) "AUTO(sensor)" else "${cfg.cameraRotation}°"
        return buildString {
            appendLine("enabled       : ${cfg.cameraEnabled}")
            appendLine("selected ID   : ${cfg.cameraId}")
            appendLine("permission    : $permission")
            appendLine("capture mode  : ${cfg.cameraWidth}x${cfg.cameraHeight} @ ${cfg.cameraFpsMin}-${cfg.cameraFpsMax}")
            appendLine("rotation      : $rotation")
            appendLine("AE comp raw   : ${cfg.cameraExposureCompensation}")
            appendLine("encoder pref  : ${cfg.cameraEncoderName}")
            appendLine("MediaMTX RTSP : ${cfg.mediaRtspPort}")
            appendLine("headlight cfg : enabled=${cfg.headlightEnabled} initialOn=${cfg.headlightInitialOn} current=${HeadlightController.isOn()}")
            appendLine("camera API    : ${if (cfg.cameraSource == "USB_WEBCAM" || cfg.cameraId == "USB_UVC") "Direct USB/UVC + RootEncoder" else if (Build.VERSION.SDK_INT >= 21) "Camera2 + GLES streaming" else "legacy diagnostics only"}")
            append("publish URL   : ${effectiveCameraPublishUrl(cfg)}")
        }
    }

    private fun cameraRuntimeSnapshot(): String {
        val now = System.currentTimeMillis()
        fun age(value: Long): String = if (value == 0L) "never" else "${now - value} ms ago"
        return buildString {
            appendLine("pipeline      : ${RoverRuntimeState.cameraState}")
            appendLine("running       : ${RoverRuntimeState.cameraRunning}")
            appendLine("camera ID     : ${RoverRuntimeState.cameraId.ifBlank { "-" }}")
            appendLine("encoder       : ${RoverRuntimeState.cameraEncoderName.ifBlank { "-" }}")
            appendLine("encoded format: ${RoverRuntimeState.cameraWidth}x${RoverRuntimeState.cameraHeight} @ ${RoverRuntimeState.cameraFps} fps")
            appendLine("bitrate cfg   : ${RoverRuntimeState.cameraBitrate} bps")
            appendLine("RTSP state    : ${RoverRuntimeState.cameraPublisherState}")
            appendLine("RTSP connected: ${RoverRuntimeState.cameraPublisherConnected}")
            appendLine("encoded       : ${RoverRuntimeState.cameraEncodedFrames} frames / ${RoverRuntimeState.cameraEncodedBytes} bytes")
            appendLine("published     : ${RoverRuntimeState.cameraPublishedFrames} frames / ${RoverRuntimeState.cameraPublishedBytes} bytes")
            appendLine("last encoded  : ${age(RoverRuntimeState.lastCameraEncodedAtMs)}")
            appendLine("last published: ${age(RoverRuntimeState.lastCameraPublishedAtMs)}")
            appendLine("last keyframe : ${age(RoverRuntimeState.lastCameraKeyFrameAtMs)}")
            appendLine("dropped queue : ${RoverRuntimeState.cameraDroppedFrames}")
            appendLine("RTSP reconnect: ${RoverRuntimeState.cameraReconnects}")
            appendLine("publish URL   : ${RoverRuntimeState.cameraPublishUrl.ifBlank { effectiveCameraPublishUrl(RoverSettings.load(this@MainActivity)) }}")
            append("last error    : ${RoverRuntimeState.cameraLastError.ifBlank { "-" }}")
        }
    }

    private fun effectiveCameraPublishUrl(cfg: RoverConfig): String =
        runCatching { MediaUrl.videoPublishUrl(cfg) }.getOrElse { "ERROR: ${it.message}" }

    private fun readCameraChoices(savedId: String): List<CameraChoice> {
        val choices = runCatching { CameraDiagnostics.cameraChoices(this) }
            .getOrElse {
                RoverRuntimeState.log("CAMERA ID enumeration failed: ${it.stackTraceToString()}")
                emptyList()
            }
            .toMutableList()
        // This is a real source selector, not a Camera2 ID. Direct UVC is deliberately listed
        // even when Android's CameraManager reports zero external cameras.
        choices.add(0, CameraChoice("USB_UVC", "USB UVC — Logitech C920 / UVC webcam (direct)"))
        if (savedId.isNotBlank() && choices.none { it.id == savedId }) {
            choices.add(0, CameraChoice(savedId, "$savedId — saved source (not currently enumerated)"))
        }
        return choices.distinctBy { it.id }
    }

    private fun populateCameraModeControls(cameraId: String, cfg: RoverConfig, preserveSaved: Boolean) {
        if (currentPage != Page.CAMERA) return
        updatingCameraControls = true
        try {
            if (cameraId == "USB_UVC") {
                cameraCatalog = null
                cameraSizes = listOf(
                    CameraSizeOption(640, 480),
                    CameraSizeOption(1280, 720),
                    CameraSizeOption(1920, 1080),
                )
                cameraSizeView?.adapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_dropdown_item,
                    cameraSizes.map {
                        when {
                            it.width * 3 == it.height * 4 -> "${it.label} (4:3)"
                            it.width * 9 == it.height * 16 -> "${it.label} (16:9)"
                            else -> it.label
                        }
                    },
                )
                val sizeIndex = cameraSizes.indexOfFirst { it.width == cfg.cameraWidth && it.height == cfg.cameraHeight }
                cameraSizeView?.setSelection((if (preserveSaved && sizeIndex >= 0) sizeIndex else 0).coerceAtLeast(0))

                cameraFpsOptions = listOf(
                    CameraFpsOption(15, 15),
                    CameraFpsOption(30, 30),
                )
                cameraFpsView?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cameraFpsOptions.map { it.label })
                val fpsIndex = cameraFpsOptions.indexOfFirst { it.min == cfg.cameraFpsMin && it.max == cfg.cameraFpsMax }
                cameraFpsView?.setSelection((if (preserveSaved && fpsIndex >= 0) fpsIndex else 1).coerceAtLeast(0))

                cameraRotationOptions = listOf(
                    RotationOption(0, "0° (USB camera native orientation)"),
                    RotationOption(90, "90° clockwise"),
                    RotationOption(180, "180°"),
                    RotationOption(270, "270° clockwise"),
                )
                cameraRotationView?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cameraRotationOptions.map { it.label })
                val rotationIndex = if (preserveSaved) cameraRotationOptions.indexOfFirst { it.value == cfg.cameraRotation } else 0
                cameraRotationView?.setSelection(rotationIndex.coerceAtLeast(0))


                cameraInvertView?.isChecked = (if (preserveSaved) cfg.cameraRotation else 0) == 180
                cameraExposureValues = listOf(0)
                cameraExposureView?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("0 (UVC controls handled by webcam)"))
                cameraExposureView?.setSelection(0)
                RoverRuntimeState.log("CAMERA UI selected direct USB/UVC source")
                return
            }
            cameraCatalog = runCatching { CameraDiagnostics.modeCatalog(this, cameraId) }
                .onFailure { RoverRuntimeState.log("CAMERA mode catalog failed id=$cameraId: ${it.stackTraceToString()}") }
                .getOrNull()
            val catalog = cameraCatalog

            cameraSizes = catalog?.sizes.orEmpty().ifEmpty {
                listOf(CameraSizeOption(cfg.cameraWidth, cfg.cameraHeight))
            }
            cameraSizeView?.adapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_dropdown_item,
                    cameraSizes.map {
                        when {
                            it.width * 3 == it.height * 4 -> "${it.label} (4:3)"
                            it.width * 9 == it.height * 16 -> "${it.label} (16:9)"
                            else -> it.label
                        }
                    },
                )
            val sizeIndex = if (preserveSaved) {
                cameraSizes.indexOfFirst { it.width == cfg.cameraWidth && it.height == cfg.cameraHeight }
            } else {
                cameraSizes.indexOfFirst { it.width == 640 && it.height == 480 }
            }
            cameraSizeView?.setSelection((if (sizeIndex >= 0) sizeIndex else closestSizeIndex(cameraSizes, 640, 480)).coerceAtLeast(0))

            cameraFpsOptions = catalog?.fpsRanges.orEmpty().ifEmpty {
                listOf(CameraFpsOption(cfg.cameraFpsMin.coerceAtLeast(1), cfg.cameraFpsMax.coerceAtLeast(1)))
            }
            cameraFpsView?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cameraFpsOptions.map { it.label })
            val savedFps = if (preserveSaved) {
                cameraFpsOptions.indexOfFirst { it.min == cfg.cameraFpsMin && it.max == cfg.cameraFpsMax }
            } else -1
            val preferredFps = if (savedFps >= 0) savedFps else preferredFpsIndex(cameraFpsOptions, 30)
            cameraFpsView?.setSelection(preferredFps.coerceAtLeast(0))

            val sensorOrientation = catalog?.sensorOrientation ?: 0
            cameraRotationOptions = listOf(
                RotationOption(-1, "AUTO — sensor orientation ${sensorOrientation}°"),
                RotationOption(0, "0°"),
                RotationOption(90, "90° clockwise"),
                RotationOption(180, "180°"),
                RotationOption(270, "270° clockwise"),
            )
            cameraRotationView?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cameraRotationOptions.map { it.label })
            val rotationIndex = if (preserveSaved) cameraRotationOptions.indexOfFirst { it.value == cfg.cameraRotation } else 0
            cameraRotationView?.setSelection(rotationIndex.coerceAtLeast(0))


                cameraInvertView?.isChecked = (if (preserveSaved) cfg.cameraRotation else 0) == 180
            val minComp = catalog?.exposureCompMin ?: 0
            val maxComp = catalog?.exposureCompMax ?: 0
            cameraExposureValues = if (minComp <= maxComp) (minComp..maxComp).toList() else listOf(0)
            val step = catalog?.exposureCompStep ?: 0f
            val exposureLabels = cameraExposureValues.map { raw ->
                val ev = raw * step
                if (step > 0f) String.format(Locale.US, "%+d  (%+.2f EV)", raw, ev) else raw.toString()
            }
            cameraExposureView?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, exposureLabels)
            val exposureIndex = if (preserveSaved) cameraExposureValues.indexOf(cfg.cameraExposureCompensation) else cameraExposureValues.indexOf(0)
            cameraExposureView?.setSelection(exposureIndex.coerceAtLeast(0))

            RoverRuntimeState.log(
                "CAMERA UI catalog id=$cameraId facing=${catalog?.facing} sensorOrientation=$sensorOrientation " +
                    "sizes=${cameraSizes.size} fpsRanges=${cameraFpsOptions.size} exposure=$minComp..$maxComp step=$step effects=${catalog?.effectModes}",
            )
        } finally {
            updatingCameraControls = false
        }
    }

    private fun closestSizeIndex(sizes: List<CameraSizeOption>, width: Int, height: Int): Int {
        if (sizes.isEmpty()) return 0
        val targetArea = width.toLong() * height.toLong()
        val targetAspect = width.toDouble() / height.coerceAtLeast(1)
        return sizes.indices.minByOrNull { i ->
            val s = sizes[i]
            val area = s.width.toLong() * s.height.toLong()
            val aspect = s.width.toDouble() / s.height.coerceAtLeast(1)
            (abs(area - targetArea) + (abs(aspect - targetAspect) * 10_000_000.0).toLong())
        } ?: 0
    }

    private fun preferredFpsIndex(options: List<CameraFpsOption>, targetMax: Int): Int {
        if (options.isEmpty()) return 0
        return options.indices.minByOrNull { i ->
            val r = options[i]
            abs(r.max - targetMax) * 10_000 + r.min
        } ?: 0
    }

    private fun refreshCameraList() {
        if (currentPage != Page.CAMERA) return
        val currentId = selectedCameraId()
        val cfg = RoverSettings.load(this)
        cameraChoices = readCameraChoices(currentId)
        updatingCameraControls = true
        try {
            cameraIdView?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cameraChoices.map { it.label })
            cameraIdView?.setSelection(cameraChoices.indexOfFirst { it.id == currentId }.coerceAtLeast(0))
        } finally {
            updatingCameraControls = false
        }
        populateCameraModeControls(currentId, cfg, preserveSaved = currentId == cfg.cameraId)
        cameraEncoderNames = listOf("AUTO") + CameraDiagnostics.h264SurfaceEncoders()
        cameraEncoderView?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cameraEncoderNames)
        cameraEncoderView?.setSelection(cameraEncoderNames.indexOf(cfg.cameraEncoderName).takeIf { it >= 0 } ?: 0)
        RoverRuntimeState.log("CAMERA list refreshed ids=${cameraChoices.joinToString { it.id }}")
    }

    private fun refreshCameraInventory() {
        val output = cameraInventoryView ?: return
        val selected = selectedCameraId()
        output.text = "Reading Android camera inventory..."
        Thread {
            val text = runCatching { CameraDiagnostics.snapshot(this, selected) }
                .getOrElse { "CAMERA diagnostics failed: ${it.stackTraceToString()}" }
            runOnUiThread {
                if (currentPage == Page.CAMERA) cameraInventoryView?.text = text
            }
        }.start()
    }

    private fun selectedCameraId(): String {
        val index = cameraIdView?.selectedItemPosition ?: -1
        return cameraChoices.getOrNull(index)?.id ?: RoverSettings.load(this).cameraId
    }

    private fun selectedCameraSize(cfg: RoverConfig): CameraSizeOption =
        cameraSizes.getOrNull(cameraSizeView?.selectedItemPosition ?: -1)
            ?: CameraSizeOption(cfg.cameraWidth, cfg.cameraHeight)

    private fun selectedCameraFps(cfg: RoverConfig): CameraFpsOption =
        cameraFpsOptions.getOrNull(cameraFpsView?.selectedItemPosition ?: -1)
            ?: CameraFpsOption(cfg.cameraFpsMin.coerceAtLeast(1), cfg.cameraFpsMax.coerceAtLeast(1))

    private fun selectedCameraRotation(cfg: RoverConfig): Int =
        cameraRotationOptions.getOrNull(cameraRotationView?.selectedItemPosition ?: -1)?.value ?: cfg.cameraRotation

    private fun selectedCameraExposure(cfg: RoverConfig): Int =
        cameraExposureValues.getOrNull(cameraExposureView?.selectedItemPosition ?: -1) ?: cfg.cameraExposureCompensation

    private fun selectedCameraEncoder(cfg: RoverConfig): String =
        cameraEncoderNames.getOrNull(cameraEncoderView?.selectedItemPosition ?: -1) ?: cfg.cameraEncoderName

    private fun refreshCameraSummary() {
        setTextIfChanged(cameraSummaryView, cameraSummarySnapshot())
    }

    private fun refreshCameraRuntime() {
        setTextIfChanged(cameraRuntimeView, cameraRuntimeSnapshot())
    }

    private fun refreshLog() {
        logView?.text = RoverRuntimeState.logSnapshot()
    }

    private fun saveSystemSettings() {
        val old = RoverSettings.load(this)
        val cfg = old.copy(
            name = nameView?.text?.toString()?.trim().orEmpty().ifEmpty { "android-rover" },
            serverUrl = serverView?.text?.toString()?.trim().orEmpty().ifEmpty { old.serverUrl },
        )
        RoverSettings.save(this, cfg)
        RoverRuntimeState.log("UI saved SYSTEM settings")
        sendServiceAction(RoverService.ACTION_RESTART)
        refreshVisiblePage()
    }

    private fun saveRoombaSettings() {
        val old = RoverSettings.load(this)
        val line = BrcLine.entries.getOrElse(brcLineView?.selectedItemPosition ?: 0) { BrcLine.RTS }
        val cfg = old.copy(
            baud = baudView?.text?.toString()?.toIntOrNull() ?: old.baud,
            maxWheelSpeed = speedView?.text?.toString()?.toIntOrNull() ?: old.maxWheelSpeed,
            brcLine = line,
            brcActiveLow = brcActiveLowView?.isChecked ?: old.brcActiveLow,
            brcPulseEveryMs = brcEveryView?.text?.toString()?.toLongOrNull() ?: old.brcPulseEveryMs,
            brcPulseWidthMs = brcWidthView?.text?.toString()?.toLongOrNull() ?: old.brcPulseWidthMs,
            autoSideBrushEnabled = autoSideBrushEnabledView?.isChecked ?: old.autoSideBrushEnabled,
            autoSideBrushSpeed = (autoSideBrushSpeedView?.text?.toString()?.toIntOrNull() ?: old.autoSideBrushSpeed).coerceIn(-127, 127),
        )
        RoverSettings.save(this, cfg)
        RoverRuntimeState.log("UI saved ROOMBA settings autoSide=${cfg.autoSideBrushEnabled}/${cfg.autoSideBrushSpeed}")
        sendServiceAction(RoverService.ACTION_RESTART)
        refreshVisiblePage()
    }

    private fun saveCameraSettings(startEvenIfUnchecked: Boolean = false) {
        val old = RoverSettings.load(this)
        val enabled = if (startEvenIfUnchecked) true else cameraEnabledView?.isChecked ?: old.cameraEnabled
        if (startEvenIfUnchecked) cameraEnabledView?.isChecked = true
        val size = selectedCameraSize(old)
        val fps = selectedCameraFps(old)
        val useUsbWebcam = cameraSourceUsbView?.isChecked ?: (old.cameraSource == "USB_WEBCAM")
        val cfg = old.copy(
            cameraId = if (useUsbWebcam) "USB_UVC" else selectedCameraId().ifBlank { old.cameraId },
            cameraEnabled = enabled,
            cameraSource = if (useUsbWebcam) "USB_WEBCAM" else "PHONE",
            cameraWidth = size.width,
            cameraHeight = size.height,
            cameraFpsMin = fps.min,
            cameraFpsMax = fps.max,
            cameraBitrate = (cameraBitrateView?.text?.toString()?.toIntOrNull() ?: old.cameraBitrate).coerceIn(64_000, 100_000_000),
            cameraRotation = selectedCameraRotation(old),
            cameraExposureCompensation = selectedCameraExposure(old),
            cameraEncoderName = selectedCameraEncoder(old),
            cameraPublishUrl = cameraPublishUrlView?.text?.toString()?.trim().orEmpty(),
            headlightEnabled = headlightEnabledView?.isChecked ?: old.headlightEnabled,
            headlightInitialOn = headlightInitialOnView?.isChecked ?: old.headlightInitialOn,
        )
        RoverSettings.save(this, cfg)
        RoverRuntimeState.log(
            "UI saved CAMERA settings enabled=${cfg.cameraEnabled} id=${cfg.cameraId} " +
                "capture=${cfg.cameraWidth}x${cfg.cameraHeight} fps=${cfg.cameraFpsMin}-${cfg.cameraFpsMax} " +
                "rotation=${cfg.cameraRotation} exposureComp=${cfg.cameraExposureCompensation} encoder=${cfg.cameraEncoderName} " +
                "bitrate=${cfg.cameraBitrate} rtspPort=${cfg.mediaRtspPort} headlight=${cfg.headlightEnabled}/${cfg.headlightInitialOn} " +
                "effectiveUrl=${effectiveCameraPublishUrl(cfg)}",
        )

        if (old.headlightEnabled != cfg.headlightEnabled || old.headlightInitialOn != cfg.headlightInitialOn) {
            runCatching { HeadlightController.applyConfiguredState(cfg) }
                .onFailure { RoverRuntimeState.log("HEADLIGHT applying saved config failed: ${it.stackTraceToString()}") }
        }

        sendServiceAction(RoverService.ACTION_RECONNECT_SERVER)

        if (cfg.cameraEnabled) {
            if (Build.VERSION.SDK_INT < 21) {
                RoverRuntimeState.setCameraPipelineState(
                    running = false,
                    state = "Unsupported Android version",
                    error = "Camera streaming currently requires API 21+",
                )
            } else if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestCameraPermission()
            } else {
                restartCameraPublisher()
            }
        } else {
            stopCameraPublisher()
        }
        refreshCameraSummary()
        refreshCameraRuntime()
    }

    private fun copyAllDiagnostics() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val cameraInventory = cameraInventoryView?.text?.toString() ?: "not read in this UI session"
        val text = buildString {
            appendLine("=== SYSTEM ===")
            appendLine(systemSnapshot())
            appendLine()
            appendLine("=== ROOMBA ===")
            appendLine(roombaSnapshot())
            appendLine()
            appendLine("=== CAMERA ===")
            appendLine(cameraSummarySnapshot())
            appendLine(cameraRuntimeSnapshot())
            appendLine(cameraInventory)
            appendLine()
            appendLine("=== HEADLIGHT ===")
            appendLine(HeadlightController.snapshot())
            appendLine()
            appendLine("=== MIC ===")
            appendLine(MicRuntimeState.snapshot())
            appendLine()
            appendLine("=== AUDIO ===")
            appendLine(AudioPlaybackRuntimeState.snapshot())
            appendLine(RoverAudioController.snapshot())
            appendLine()
            appendLine("=== LOG ===")
            append(RoverRuntimeState.logSnapshot())
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("roverd diagnostics", text))
        RoverRuntimeState.log("UI all diagnostics copied to clipboard")
    }

    private fun startRoverService() {
        startServiceCompat(Intent(this, RoverService::class.java))
    }

    private fun sendServiceAction(action: String) {
        startServiceCompat(Intent(this, RoverService::class.java).setAction(action))
    }

    private fun startCameraPublisher() {
        if (Build.VERSION.SDK_INT < 21) return
        RoverRuntimeState.log("UI ensuring camera publisher foreground service is running")
        startServiceCompat(Intent(this, CameraPublisherService::class.java).setAction(CameraPublisherService.ACTION_START))
    }

    private fun restartCameraPublisher() {
        if (Build.VERSION.SDK_INT < 21) return
        RoverRuntimeState.log("UI restarting camera publisher foreground service")
        startServiceCompat(Intent(this, CameraPublisherService::class.java).setAction(CameraPublisherService.ACTION_RESTART))
    }

    private fun stopCameraPublisher() {
        RoverRuntimeState.log("UI stopping camera publisher service")
        stopService(Intent(this, CameraPublisherService::class.java))
    }

    private fun startConfiguredCameraIfPossible() {
        val cfg = RoverSettings.load(this)
        if (!cfg.cameraEnabled || Build.VERSION.SDK_INT < 21) return
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        startCameraPublisher()
    }

    private fun startConfiguredMicIfPossible() {
        val cfg = RoverSettings.load(this)
        if (!cfg.micEnabled) return
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            RoverRuntimeState.log("MIC autostart waiting for RECORD_AUDIO permission")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MicSettingsView.MIC_PERMISSION_REQUEST)
            return
        }
        RoverRuntimeState.log("MIC autostart from saved enabled setting")
        startServiceCompat(Intent(this, MicPublisherService::class.java).setAction(MicPublisherService.ACTION_START))
    }

    private fun startConfiguredAudioPlayback() {
        val cfg = RoverSettings.load(this)
        if (!cfg.audioPlaybackEnabled) return
        RoverRuntimeState.log("AUDIO reverse playback autostart from saved enabled setting")
        startServiceCompat(Intent(this, AudioPlaybackService::class.java).setAction(AudioPlaybackService.ACTION_START))
    }

    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent)
        else startService(intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun requestCameraPermission() {
        if (Build.VERSION.SDK_INT < 23) {
            RoverRuntimeState.log("CAMERA permission is install-time on API ${Build.VERSION.SDK_INT}")
            refreshCameraSummary()
            refreshCameraList()
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            RoverRuntimeState.log("CAMERA permission already granted")
            refreshCameraSummary()
            refreshCameraList()
            if (RoverSettings.load(this).cameraEnabled) startCameraPublisher()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST -> {
                val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                RoverRuntimeState.log("CAMERA permission result=$granted")
                refreshCameraSummary()
                refreshCameraList()
                if (granted && RoverSettings.load(this).cameraEnabled) startCameraPublisher()
            }
            MicSettingsView.MIC_PERMISSION_REQUEST -> {
                val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                RoverRuntimeState.log("MIC permission result=$granted")
                if (granted && RoverSettings.load(this).micEnabled) {
                    startServiceCompat(Intent(this, MicPublisherService::class.java).setAction(MicPublisherService.ACTION_START))
                }
            }
        }
    }

    private fun requestBatteryOptimizationExemption(userInitiated: Boolean) {
        if (Build.VERSION.SDK_INT < 23) {
            if (userInitiated) RoverRuntimeState.log("POWER Doze exemption not applicable on API ${Build.VERSION.SDK_INT}")
            return
        }

        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            if (userInitiated) RoverRuntimeState.log("POWER battery optimization exemption already granted")
            return
        }

        RoverRuntimeState.log("POWER requesting battery optimization / Doze exemption")
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching {
            startActivity(direct)
        }.onFailure { directFailure ->
            RoverRuntimeState.log("POWER direct exemption request failed: ${directFailure.message}; opening battery optimization settings")
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.onFailure { settingsFailure ->
                RoverRuntimeState.log("POWER battery optimization settings failed: ${settingsFailure.stackTraceToString()}")
            }
        }
    }
}
