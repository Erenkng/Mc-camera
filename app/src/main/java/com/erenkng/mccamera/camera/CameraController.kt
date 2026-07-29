package com.erenkng.mccamera.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

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

        fun onCameraError(message: String)
    }

    private val executor = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    var lensFacing: Int = CameraSelector.LENS_FACING_BACK
        private set

    var hasFrontCamera = false
        private set

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
        bind()
        return lensFacing
    }

    fun stop() {
        provider?.unbindAll()
        surface?.release()
        surface = null
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

        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()

        val mirror = lensFacing == CameraSelector.LENS_FACING_FRONT
        preview.setSurfaceProvider(executor) { request ->
            val resolution = request.resolution
            texture.setDefaultBufferSize(resolution.width, resolution.height)

            surface?.release()
            val newSurface = Surface(texture)
            surface = newSurface

            request.setTransformationInfoListener(executor) { info ->
                listener.onCameraTransform(
                    resolution.width,
                    resolution.height,
                    info.rotationDegrees,
                    mirror,
                )
            }
            request.provideSurface(newSurface, executor) { result ->
                if (surface === newSurface) surface = null
                newSurface.release()
                Log.d(TAG, "Surface bırakıldı: ${result.resultCode}")
            }
        }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview)
        } catch (e: Exception) {
            Log.e(TAG, "bindToLifecycle başarısız", e)
            listener.onCameraError("Kamera bağlanamadı: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "CameraController"
    }
}
