package land.otter.roverd

import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.Build
import android.util.Log
import android.view.Surface
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.pedro.encoder.input.sources.OrientationConfig
import com.pedro.encoder.input.sources.OrientationForced
import com.pedro.encoder.input.sources.video.VideoSource
import com.serenegiant.usb.Size
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct Logitech C920/UVC source.
 *
 * The important lifecycle rule is that UVCAndroid and RootEncoder become ready on different
 * threads. We therefore remember both states and ONLY call addSurface/startPreview once the
 * camera and the Surface are both ready.
 */
class RoverUvcSource(
    private val context: android.content.Context,
    private val requestedFps: Int,
    private val onState: (String) -> Unit = {},
    private val onRestartNeeded: (String) -> Unit = {},
) : VideoSource(), Closeable {
    companion object {
        private const val TAG = "RoverUvcSource"
        private const val FRAME_TYPE_MJPEG = 7
    }

    private val helper = CameraHelper()
    private val closed = AtomicBoolean(false)
    private val lock = Any()

    @Volatile private var selectedDevice: UsbDevice? = null
    @Volatile private var surfaceReady = false
    @Volatile private var cameraOpened = false
    @Volatile private var previewing = false
    @Volatile private var requestedWidth = 640
    @Volatile private var requestedHeight = 480
    @Volatile private var requestedModeFps = requestedFps.coerceAtLeast(1)
    @Volatile private var requestedRotation = 0
    @Volatile private var selectedSize: Size? = null
    @Volatile private var outputSurface: Surface? = null
    @Volatile private var lastError: String? = null

    init {
        helper.setStateCallback(object : ICameraHelper.StateCallback {
            override fun onAttach(device: UsbDevice) {
                if (closed.get() || !isUvc(device)) return
                synchronized(lock) {
                    if (selectedDevice == null) selectedDevice = device
                }
                onState("C920 UVC attached: ${describe(device)}")
                runCatching { helper.selectDevice(device) }
                    .onFailure { fail("USB permission request failed: ${it.message}") }
            }

            override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
                if (closed.get()) return
                selectedDevice = device
                onState("C920 USB device opened")
                runCatching { helper.openCamera() }
                    .onFailure { fail("Could not open C920 UVC camera: ${it.message}") }
            }

            override fun onCameraOpen(device: UsbDevice) {
                if (closed.get()) return
                cameraOpened = true
                onState("C920 UVC camera opened")

                val supported = runCatching { helper.getSupportedSizeList().orEmpty() }
                    .getOrElse {
                        fail("Could not query C920 UVC modes: ${it.message}")
                        return
                    }

                if (supported.isEmpty()) {
                    // Do not fail just because the library did not expose a list. Let UVCAndroid
                    // choose its default mode and try the preview path.
                    selectedSize = null
                    onState("C920 UVC opened; using library default preview mode")
                    tryStartPreview()
                    return
                }

                supported.forEach { s ->
                    Log.i(TAG, "C920 UVC mode ${s.width}x${s.height} fps=${s.fpsList} type=${s.type}")
                }

                val chosen = chooseStableMode(supported, requestedWidth, requestedHeight, requestedModeFps)
                selectedSize = closestFps(chosen, requestedModeFps)

                // A bad manually-constructed Size can make UVCCamera.setPreviewSize fail or race.
                // Use the exact library-provided Size object first; if the device rejects it, leave
                // the library's default mode intact and still attempt preview.
                runCatching {
                    helper.previewSize = selectedSize
                }.onFailure {
                    Log.w(TAG, "C920 rejected requested preview size ${selectedSize}: ${it.message}")
                    onState("C920 rejected requested mode; using UVC default")
                    selectedSize = null
                }

                tryStartPreview()
            }

            override fun onCameraClose(device: UsbDevice) {
                synchronized(lock) {
                    previewing = false
                    cameraOpened = false
                }
                if (!closed.get()) onRestartNeeded("C920 UVC camera closed")
            }

            override fun onDeviceClose(device: UsbDevice) {
                synchronized(lock) {
                    previewing = false
                    cameraOpened = false
                }
                if (!closed.get()) onRestartNeeded("C920 UVC device closed")
            }

            override fun onDetach(device: UsbDevice) {
                if (selectedDevice?.deviceId != device.deviceId) return
                synchronized(lock) {
                    previewing = false
                    cameraOpened = false
                    surfaceReady = false
                }
                selectedDevice = null
                if (!closed.get()) onRestartNeeded("C920 UVC webcam detached")
            }

            override fun onCancel(device: UsbDevice) {
                fail("USB permission cancelled for ${describe(device)}")
            }

            override fun onError(device: UsbDevice, e: CameraException) {
                fail("C920 UVC error: ${e.message}")
            }
        })

        runCatching {
            helper.getDeviceList().orEmpty().firstOrNull(::isUvc)?.let { device ->
                selectedDevice = device
                helper.selectDevice(device)
            }
        }.onFailure { Log.w(TAG, "Initial UVC enumeration failed", it) }
    }

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean {
        check(Build.VERSION.SDK_INT >= 21) { "USB/UVC streaming requires Android 5.0 / API 21+" }
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)

        requestedWidth = width
        requestedHeight = height
        requestedModeFps = fps.coerceAtLeast(1).coerceAtMost(requestedFps.coerceAtLeast(1))
        requestedRotation = rotation
        selectedSize = null
        lastError = null
        onState("C920 UVC requested ${width}x${height}@${requestedModeFps}")

        // Never wait here. USB/UVC opens asynchronously.
        selectedDevice?.let {
            if (!helper.isCameraOpened()) {
                runCatching { helper.openCamera() }
                    .onFailure { fail("Could not open C920 UVC camera: ${it.message}") }
            }
        }
        return true
    }

    override fun start(surfaceTexture: SurfaceTexture) {
        check(!closed.get()) { "C920 UVC source is closed" }
        outputSurface?.release()
        outputSurface = Surface(surfaceTexture)
        surfaceTexture.setDefaultBufferSize(requestedWidth, requestedHeight)

        synchronized(lock) {
            surfaceReady = true
        }

        // RootEncoder considers the source running once its SurfaceTexture is supplied. The actual
        // UVC preview begins later when cameraOpened and surfaceReady are both true.
        tryStartPreview()
    }

    @Synchronized
    private fun tryStartPreview() {
        if (closed.get() || previewing || !cameraOpened || !surfaceReady) return
        if (!helper.isCameraOpened()) return

        val surface = outputSurface ?: return

        // Surface must be added AFTER the camera-open callback. This avoids the race that can leave
        // the C920 LED on while the encoder receives zero frames.
        runCatching {
            helper.stopPreview()
        }

        runCatching {
            helper.addSurface(surface, false)
            helper.startPreview()
            previewing = true
            val s = selectedSize
            if (s != null) {
                onState("C920 UVC streaming ${s.width}x${s.height}@${s.fps}")
            } else {
                onState("C920 UVC streaming using device default mode")
            }
        }.onFailure {
            previewing = false
            fail("C920 UVC preview produced no stream: ${it.message}")
        }
    }

    override fun isRunning(): Boolean = previewing || surfaceReady

    override fun stop() {
        synchronized(lock) {
            previewing = false
            surfaceReady = false
        }
        runCatching { helper.stopPreview() }
        outputSurface?.let { surface ->
            runCatching { helper.removeSurface(surface) }
            surface.release()
        }
        outputSurface = null
        runCatching { helper.closeCamera() }
    }

    override fun release() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            previewing = false
            surfaceReady = false
            cameraOpened = false
        }
        runCatching { helper.stopPreview() }
        outputSurface?.let { surface ->
            runCatching { helper.removeSurface(surface) }
            surface.release()
        }
        outputSurface = null
        runCatching { helper.closeCamera() }
        runCatching { helper.release() }
        selectedDevice = null
        selectedSize = null
    }

    override fun close() = release()

    override fun getOrientationConfig(): OrientationConfig = OrientationConfig(
        cameraOrientation = requestedRotation,
        isPortrait = requestedRotation == 90 || requestedRotation == 270,
        forced = OrientationForced.NONE,
    )

    private fun chooseStableMode(supported: List<Size>, width: Int, height: Int, fps: Int): Size {
        val exact = supported.filter { it.width == width && it.height == height }
        val aspect = supported.filter {
            kotlin.math.abs(it.width.toDouble() / it.height - width.toDouble() / height) < 0.02
        }
        val pool = when {
            exact.isNotEmpty() -> exact
            aspect.isNotEmpty() -> aspect
            else -> supported
        }

        return pool.minWithOrNull(
            compareBy<Size> { if (it.type == FRAME_TYPE_MJPEG) 0 else 1 }
                .thenBy { kotlin.math.abs(it.width.toLong() * it.height - width.toLong() * height) }
                .thenBy { kotlin.math.abs((it.fpsList.maxOrNull() ?: it.fps) - fps) }
        ) ?: supported.first()
    }

    private fun closestFps(size: Size, targetFps: Int): Size {
        val list = size.fpsList.orEmpty().filter { it > 0 }
        if (list.isEmpty()) return size
        val fps = list.minByOrNull { kotlin.math.abs(it - targetFps) } ?: size.fps
        return list.filter { it == fps }.let { _ ->
            Size(size.type, size.width, size.height, fps, ArrayList(list))
        }
    }

    private fun isUvc(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == 14) return true
        }
        return false
    }

    private fun describe(device: UsbDevice): String =
        "${device.deviceName} vid=0x${device.vendorId.toString(16)} pid=0x${device.productId.toString(16)}"

    private fun fail(message: String) {
        lastError = message
        Log.e(TAG, message)
        onState(message)
        if (!closed.get()) onRestartNeeded(message)
    }
}
