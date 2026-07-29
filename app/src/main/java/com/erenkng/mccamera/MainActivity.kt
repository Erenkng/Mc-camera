package com.erenkng.mccamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.erenkng.mccamera.camera.CameraController
import com.erenkng.mccamera.gl.MosaicRenderer
import com.erenkng.mccamera.palette.BlockPalette
import com.erenkng.mccamera.palette.DefaultPack
import com.erenkng.mccamera.palette.PackLibrary
import com.erenkng.mccamera.palette.PaletteBuilder
import com.erenkng.mccamera.palette.ResourcePackLoader
import com.erenkng.mccamera.ui.AppSettings
import com.erenkng.mccamera.ui.SettingsSheet
import com.erenkng.mccamera.util.ImageLoader
import com.erenkng.mccamera.util.ImageSaver
import com.erenkng.mccamera.video.VideoRecorder
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity :
    AppCompatActivity(),
    MosaicRenderer.Callbacks,
    CameraController.Listener,
    SettingsSheet.Callbacks {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: MosaicRenderer
    private lateinit var camera: CameraController
    private lateinit var settings: AppSettings

    private lateinit var root: View
    private lateinit var permissionOverlay: View
    private lateinit var progress: ProgressBar
    private lateinit var controls: View
    private lateinit var stillBar: View
    private lateinit var recordBadge: View
    private lateinit var recordTime: TextView
    private lateinit var countdown: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var hint: TextView
    private lateinit var shutter: ImageButton
    private lateinit var torchButton: ImageButton
    private lateinit var timerButton: ImageButton
    private lateinit var freezeButton: ImageButton

    private val handler = Handler(Looper.getMainLooper())

    private var palette: BlockPalette? = null
    private var activeTexture: SurfaceTexture? = null
    private var permissionAsked = false
    private var appliedBlockLimit = -1

    private var videoMode = false
    private var recording = false
    private var recordStartedAt = 0L
    private var countdownRemaining = 0
    private var stillMode = false
    private var busy = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                permissionOverlay.visibility = View.GONE
                activeTexture?.let { camera.start(it) }
            } else {
                permissionOverlay.visibility = View.VISIBLE
            }
        }

    private val packLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importPack(uri)
        }

    private val photoLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) importPhoto(uri)
        }

    // ----------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = AppSettings(this)

        root = findViewById(R.id.root)
        permissionOverlay = findViewById(R.id.permissionOverlay)
        progress = findViewById(R.id.progress)
        controls = findViewById(R.id.controlsContainer)
        stillBar = findViewById(R.id.stillBar)
        recordBadge = findViewById(R.id.recordBadge)
        recordTime = findViewById(R.id.recordTime)
        countdown = findViewById(R.id.countdown)
        zoomLabel = findViewById(R.id.zoomLabel)
        hint = findViewById(R.id.hint)
        shutter = findViewById(R.id.shutter)
        torchButton = findViewById(R.id.torch)
        timerButton = findViewById(R.id.timer)
        freezeButton = findViewById(R.id.freeze)

        renderer = MosaicRenderer(this)
        applyRenderSettings()

        glView = findViewById(R.id.glView)
        glView.setEGLContextClientVersion(3)
        // Matching the encoder's config keeps video recording off the slow path.
        glView.setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        camera = CameraController(this, this, this)
        camera.setDeviceRotation(ContextCompat.getDisplayOrDefault(this).rotation)

        wireControls()
        setUpGestures()
        loadPalette()

        if (!settings.hintShown) {
            hint.visibility = View.VISIBLE
            settings.hintShown = true
            handler.postDelayed({ hint.visibility = View.GONE }, 6_000)
        }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        when {
            hasCameraPermission() -> {
                permissionOverlay.visibility = View.GONE
                activeTexture?.let { camera.start(it) }
            }
            // Ask once automatically; after that the overlay's button drives it,
            // otherwise a permanently denied permission would loop the dialog.
            !permissionAsked -> {
                permissionAsked = true
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> permissionOverlay.visibility = View.VISIBLE
        }
    }

    override fun onPause() {
        if (recording) stopRecording()
        cancelCountdown()
        camera.stop()
        activeTexture = null
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        renderer.releaseSurfaceTexture()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        camera.setDeviceRotation(ContextCompat.getDisplayOrDefault(this).rotation)
    }

    // ------------------------------------------------------------- controls

    private fun wireControls() {
        shutter.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onShutter()
        }

        findViewById<ImageButton>(R.id.settings).setOnClickListener {
            SettingsSheet(this, settings, this).show()
        }

        findViewById<ImageButton>(R.id.switchCamera).setOnClickListener {
            if (recording) {
                toast(getString(R.string.busy_recording))
            } else if (camera.hasFrontCamera) {
                camera.switchLens()
            } else {
                toast(getString(R.string.no_front_camera))
            }
        }

        findViewById<ImageButton>(R.id.gallery).setOnClickListener {
            photoLauncher.launch(
                PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    .build()
            )
        }

        torchButton.setOnClickListener {
            if (!camera.hasFlash) {
                toast(getString(R.string.no_flash))
            } else {
                val on = camera.toggleTorch()
                torchButton.setImageResource(if (on) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
            }
        }

        timerButton.setOnClickListener {
            settings.timerSeconds = when (settings.timerSeconds) {
                0 -> 3
                3 -> 10
                else -> 0
            }
            updateTimerButton()
        }
        updateTimerButton()

        freezeButton.setOnClickListener {
            val frozen = freezeButton.isSelected.not()
            freezeButton.isSelected = frozen
            renderer.setFrozen(frozen)
            glView.requestRender()
            toast(getString(if (frozen) R.string.frozen else R.string.unfrozen))
        }

        findViewById<MaterialButtonToggleGroup>(R.id.modeGroup).apply {
            check(if (videoMode) R.id.modeVideo else R.id.modePhoto)
            addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked || recording) return@addOnButtonCheckedListener
                videoMode = checkedId == R.id.modeVideo
                shutter.setImageResource(
                    if (videoMode) R.drawable.ic_record else R.drawable.ic_shutter
                )
            }
        }

        findViewById<MaterialButton>(R.id.stillSave).setOnClickListener { capture() }
        findViewById<MaterialButton>(R.id.stillClose).setOnClickListener { exitStillMode() }
        findViewById<MaterialButton>(R.id.grantPermission).setOnClickListener {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setUpGestures() {
        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    camera.pinchZoom(detector.scaleFactor)
                    return true
                }
            },
        )

        var downX = 0f
        var downY = 0f
        var downAt = 0L

        glView.setOnTouchListener { view, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downAt = SystemClock.uptimeMillis()
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.hypot(event.x - downX, event.y - downY)
                    val quick = SystemClock.uptimeMillis() - downAt < 250
                    if (quick && moved < 24f && !scaleDetector.isInProgress && !stillMode) {
                        view.performClick()
                        camera.focusAt(
                            ContextCompat.getDisplayOrDefault(this),
                            event.x, event.y, view.width, view.height,
                        )
                    }
                }
            }
            true
        }
    }

    private fun onShutter() {
        if (busy) return
        if (stillMode) {
            capture()
            return
        }
        if (videoMode) {
            if (recording) stopRecording() else startRecording()
            return
        }
        val seconds = settings.timerSeconds
        if (seconds > 0) startCountdown(seconds) else capture()
    }

    // --------------------------------------------------------------- timer

    private fun startCountdown(seconds: Int) {
        countdownRemaining = seconds
        countdown.visibility = View.VISIBLE
        tickCountdown()
    }

    private fun tickCountdown() {
        if (countdownRemaining <= 0) {
            countdown.visibility = View.GONE
            capture()
            return
        }
        countdown.text = countdownRemaining.toString()
        countdownRemaining--
        handler.postDelayed({ tickCountdown() }, 1_000)
    }

    private fun cancelCountdown() {
        countdownRemaining = 0
        countdown.visibility = View.GONE
    }

    // -------------------------------------------------------------- capture

    private fun capture() {
        renderer.requestCapture(settings.captureDetail)
        glView.requestRender()
    }

    private fun startRecording() {
        val recorder = VideoRecorder(this)
        val quality = settings.videoQuality
        glView.queueEvent { renderer.beginRecording(recorder, quality) }
        glView.requestRender()
    }

    private fun stopRecording() {
        recording = false
        handler.removeCallbacks(recordTicker)
        recordBadge.visibility = View.GONE
        glView.queueEvent { renderer.endRecording() }
        glView.requestRender()
    }

    private val recordTicker = object : Runnable {
        override fun run() {
            val elapsed = (SystemClock.elapsedRealtime() - recordStartedAt) / 1000
            recordTime.text = String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60)
            handler.postDelayed(this, 500)
        }
    }

    // ------------------------------------------------------------- renderer

    override fun onSurfaceTextureReady(surfaceTexture: SurfaceTexture) {
        surfaceTexture.setOnFrameAvailableListener {
            renderer.onFrameAvailable()
            glView.requestRender()
        }
        runOnUiThread {
            activeTexture = surfaceTexture
            if (hasCameraPermission()) camera.start(surfaceTexture)
        }
    }

    override fun onFrameCaptured(bitmap: Bitmap) {
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) { ImageSaver.save(this@MainActivity, bitmap) }
            val size = "${bitmap.width}x${bitmap.height}"
            bitmap.recycle()
            if (uri != null) {
                showSaved(getString(R.string.saved_photo, size), uri, "image/*")
            } else {
                toast(getString(R.string.save_failed))
            }
        }
    }

    override fun onRecordingStarted(started: Boolean) {
        runOnUiThread {
            if (!started) {
                toast(getString(R.string.record_failed))
                return@runOnUiThread
            }
            recording = true
            recordStartedAt = SystemClock.elapsedRealtime()
            recordBadge.visibility = View.VISIBLE
            handler.post(recordTicker)
        }
    }

    override fun onRecordingStopped(uri: Uri?) {
        runOnUiThread {
            if (uri != null) {
                showSaved(getString(R.string.saved_video), uri, "video/*")
            } else {
                toast(getString(R.string.save_failed))
            }
        }
    }

    override fun onRendererError(message: String) {
        runOnUiThread { toast(message) }
    }

    // --------------------------------------------------------------- camera

    override fun onCameraTransform(width: Int, height: Int, rotationDegrees: Int, mirror: Boolean) {
        glView.queueEvent { renderer.setCameraInfo(width, height, rotationDegrees, mirror) }
        glView.requestRender()
    }

    override fun onCameraReady(hasFlash: Boolean, hasFront: Boolean, zoomRatio: Float) {
        torchButton.alpha = if (hasFlash) 1f else 0.35f
        torchButton.setImageResource(R.drawable.ic_flash_off)
        onZoomChanged(zoomRatio)
    }

    override fun onZoomChanged(ratio: Float) {
        zoomLabel.text = String.format(Locale.US, "%.1fx", ratio)
        zoomLabel.visibility = if (ratio > 1.05f) View.VISIBLE else View.GONE
    }

    override fun onCameraError(message: String) {
        runOnUiThread { toast(message) }
    }

    // -------------------------------------------------------------- palette

    private fun loadPalette() {
        setBusy(true)
        val packId = settings.selectedPackId
        val limit = settings.blockLimit
        appliedBlockLimit = limit
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.Default) {
                val stored = PackLibrary.load(this@MainActivity, packId)
                if (stored != null) PaletteBuilder.subset(stored, limit) else DefaultPack.create(limit)
            }
            applyPalette(loaded)
            setBusy(false)
        }
    }

    private fun importPack(uri: Uri) {
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val imported = ResourcePackLoader.load(this@MainActivity, uri)
                    val id = PackLibrary.save(this@MainActivity, imported)
                    id to PaletteBuilder.subset(imported, settings.blockLimit)
                }
            }
            setBusy(false)
            result
                .onSuccess { (id, loaded) ->
                    if (id != null) settings.selectedPackId = id
                    appliedBlockLimit = settings.blockLimit
                    applyPalette(loaded)
                    toast(getString(R.string.pack_loaded, loaded.name, loaded.blockCount))
                }
                .onFailure { toast(it.message ?: getString(R.string.pack_failed)) }
        }
    }

    private fun applyPalette(newPalette: BlockPalette) {
        palette = newPalette
        renderer.setPalette(newPalette)
        glView.requestRender()
    }

    // ----------------------------------------------------------- still mode

    private fun importPhoto(uri: Uri) {
        setBusy(true)
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { ImageLoader.load(this@MainActivity, uri) }
            setBusy(false)
            if (bitmap == null) {
                toast(getString(R.string.photo_failed))
                return@launch
            }
            stillMode = true
            camera.stop()
            renderer.setStillImage(bitmap)
            glView.requestRender()
            controls.visibility = View.GONE
            stillBar.visibility = View.VISIBLE
        }
    }

    private fun exitStillMode() {
        stillMode = false
        renderer.clearStillImage()
        stillBar.visibility = View.GONE
        controls.visibility = View.VISIBLE
        activeTexture?.let { if (hasCameraPermission()) camera.start(it) }
        glView.requestRender()
    }

    // ------------------------------------------------------ settings sheet

    override fun onSettingsChanged() {
        applyRenderSettings()
        glView.requestRender()
    }

    override fun onSelectPack(id: String) {
        loadPalette()
    }

    override fun onDeletePack(id: String) {
        PackLibrary.delete(this, id)
        if (settings.selectedPackId == id) settings.selectedPackId = PackLibrary.BUILT_IN_ID
        loadPalette()
        toast(getString(R.string.pack_deleted))
    }

    override fun onImportPack() {
        packLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    private fun applyRenderSettings() {
        renderer.setDensity(settings.density)
        renderer.setRenderMode(settings.mode)
        renderer.setShadeStrength(settings.shade)
        renderer.setDitherStrength(settings.dither)
        renderer.setBevelStrength(if (settings.bevel) 1f else 0f)
        renderer.setOutlineStrength(if (settings.outline) 1f else 0f)
        renderer.setGrade(settings.brightness, settings.contrast, settings.saturation)

        // The block limit rebuilds the palette rather than flipping a uniform,
        // so only act when it actually moved.
        if (settings.blockLimit != appliedBlockLimit) loadPalette()
    }

    // ------------------------------------------------------------------- ui

    private fun showSaved(message: String, uri: Uri, mime: String) {
        Snackbar.make(root, message, Snackbar.LENGTH_LONG)
            .setAction(R.string.show) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { startActivity(intent) }
                    .onFailure { toast(getString(R.string.no_viewer)) }
            }
            .show()
    }

    private fun updateTimerButton() {
        timerButton.setImageResource(
            when (settings.timerSeconds) {
                3 -> R.drawable.ic_timer_3
                10 -> R.drawable.ic_timer_10
                else -> R.drawable.ic_timer_off
            }
        )
    }

    private fun setBusy(value: Boolean) {
        busy = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
        controls.alpha = if (value) 0.4f else 1f
        shutter.isEnabled = !value
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
