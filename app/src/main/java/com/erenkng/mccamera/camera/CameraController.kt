package com.erenkng.mccamera.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DisplayOrientedMeteringPointFactory
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.TimeUnit

/**
 * Feeds CameraX preview frames straight into the renderer's [SurfaceTexture].
 *
 * No `PreviewView` is involved: the mosaic shader is the preview, so the frames
 * never touch a regular view hierarchy.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val listener: Listener,
) {

    interface Listener {
        /** Geometry of the incoming buffer, so the renderer can orient it. */
        fun onCameraTransform(width: Int, height: Int, rotationDegrees: Int, mirror: Boolean)

        /** Capabilities of the camera that just bound. */
        fun onCameraReady(hasFlash: Boolean, hasFront: Boolean, zoomRatio: Float)

        fun onZoomChanged(ratio: Float)

        fun onCameraError(message: String)
    }

    private val executor = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var preview: Preview? = null
    private var camera: Camera? = null

    var lensFacing: Int = CameraSelector.LENS_FACING_BACK
        private set

    var hasFrontCamera = false
        private set

    var hasFlash = false
        private set

    var torchOn = false
        private set

    private var targetRotation = Surface.ROTATION_0
    private var bufferSize: Size? = null

    /** Safe to call again after a resume or a surface recreation. */
    fun start(texture: SurfaceTexture) {
        surfaceTexture = texture
        provider?.let {
            bind()
            return
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                hasFrontCamera = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                bind()
            } catch (e: Exception) {
                Log.e(TAG, "Kamera açılamadı", e)
                listener.onCameraError("Kamera açılamadı: ${e.message}")
            }
        }, executor)
    }

    /** Flips between the front and back camera. Returns the new lens facing. */
    fun switchLens(): Int {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        torchOn = false
        bind()
        return lensFacing
    }

    fun stop() {
        provider?.unbindAll()
        camera = null
        surface?.release()
        surface = null
        torchOn = false
    }

    /** Keeps the preview upright when the device rotates. */
    fun setDeviceRotation(rotation: Int) {
        targetRotation = rotation
        preview?.targetRotation = rotation
        publishTransform()
    }

    /**
     * Tells the renderer how the incoming buffer has to be turned to look
     * upright on screen.
     *
     * The rotation comes from [androidx.camera.core.CameraInfo] rather than only
     * from the surface request's transformation callback: that callback is
     * asynchronous and, if it never arrives, the renderer is left with an
     * identity transform, which shows the sensor's landscape frame stretched
     * across a portrait screen.
     */
    private fun publishTransform() {
        val size = bufferSize ?: return
        val info = camera?.cameraInfo ?: return
        val mirror = lensFacing == CameraSelector.LENS_FACING_FRONT
        listener.onCameraTransform(
            size.width,
            size.height,
            info.getSensorRotationDegrees(targetRotation),
            mirror,
        )
    }

    fun toggleTorch(): Boolean {
        val control = camera?.cameraControl ?: return false
        if (!hasFlash) return false
        torchOn = !torchOn
        control.enableTorch(torchOn)
        return torchOn
    }

    /** Current zoom as a ratio, 1.0 being no zoom. */
    fun zoomRatio(): Float = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f

    /** Multiplies the current zoom, e.g. by a pinch gesture's scale factor. */
    fun pinchZoom(scaleFactor: Float) {
        val info = camera?.cameraInfo ?: return
        val control = camera?.cameraControl ?: return
        val state = info.zoomState.value ?: return
        val target = (state.zoomRatio * scaleFactor)
            .coerceIn(state.minZoomRatio, state.maxZoomRatio)
        control.setZoomRatio(target)
        listener.onZoomChanged(target)
    }

    /** Focus and expose for the point the user tapped, in view coordinates. */
    fun focusAt(display: Display, x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        val active = camera ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return
        val factory = DisplayOrientedMeteringPointFactory(
            display, active.cameraInfo, viewWidth.toFloat(), viewHeight.toFloat(),
        )
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()
        try {
            active.cameraControl.startFocusAndMetering(action)
        } catch (e: Exception) {
            Log.w(TAG, "Odaklama başarısız", e)
        }
    }

    private fun bind() {
        val cameraProvider = provider ?: return
        val texture = surfaceTexture ?: return

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        if (!cameraProvider.hasCamera(selector)) {
            listener.onCameraError("Bu cihazda istenen kamera yok.")
            return
        }

        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                )
            )
            .build()

        val newPreview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(targetRotation)
            .build()
        preview = newPreview

        val bound = try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, selector, newPreview)
        } catch (e: Exception) {
            Log.e(TAG, "bindToLifecycle başarısız", e)
            listener.onCameraError("Kamera bağlanamadı: ${e.message}")
            return
        }

        camera = bound
        hasFlash = bound.cameraInfo.hasFlashUnit()
        torchOn = false
        listener.onCameraReady(hasFlash, hasFrontCamera, zoomRatio())

        // Bound before the provider is attached, so the callback below can read
        // the camera's sensor orientation straight away.
        newPreview.setSurfaceProvider(executor) { request ->
            val resolution = request.resolution
            texture.setDefaultBufferSize(resolution.width, resolution.height)
            bufferSize = resolution
            publishTransform()

            surface?.release()
            val newSurface = Surface(texture)
            surface = newSurface

            request.provideSurface(newSurface, executor) { result ->
                if (surface === newSurface) surface = null
                newSurface.release()
                Log.d(TAG, "Surface bırakıldı: ${result.resultCode}")
            }
        }
    }

    private companion object {
        const val TAG = "CameraController"
    }
}
