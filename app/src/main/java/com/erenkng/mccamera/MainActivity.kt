package com.erenkng.mccamera

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.erenkng.mccamera.camera.CameraController
import com.erenkng.mccamera.gl.MosaicRenderer
import com.erenkng.mccamera.palette.BlockPalette
import com.erenkng.mccamera.palette.DefaultPack
import com.erenkng.mccamera.palette.PaletteStore
import com.erenkng.mccamera.palette.ResourcePackLoader
import com.erenkng.mccamera.ui.SettingsSheet
import com.erenkng.mccamera.util.ImageSaver
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), MosaicRenderer.Callbacks, CameraController.Listener {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: MosaicRenderer
    private lateinit var camera: CameraController
    private lateinit var prefs: SharedPreferences

    private lateinit var permissionOverlay: View
    private lateinit var permissionMessage: TextView
    private lateinit var progress: ProgressBar
    private lateinit var controls: View

    private var palette: BlockPalette? = null

    /**
     * The texture the camera may currently draw into. Cleared on pause because
     * GLSurfaceView drops the EGL context and hands out a fresh one on resume.
     */
    private var activeTexture: SurfaceTexture? = null

    private var density = MosaicRenderer.DEFAULT_DENSITY
    private var shadeEnabled = true
    private var permissionAsked = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                permissionOverlay.visibility = View.GONE
                activeTexture?.let { camera.start(it) }
            } else {
                showPermissionOverlay()
            }
        }

    private val packLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importPack(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        density = prefs.getInt(KEY_DENSITY, MosaicRenderer.DEFAULT_DENSITY)
        shadeEnabled = prefs.getBoolean(KEY_SHADE, true)

        permissionOverlay = findViewById(R.id.permissionOverlay)
        permissionMessage = findViewById(R.id.permissionMessage)
        progress = findViewById(R.id.progress)
        controls = findViewById(R.id.controls)

        renderer = MosaicRenderer(this)
        renderer.setDensity(density)
        renderer.setShadeStrength(if (shadeEnabled) MosaicRenderer.DEFAULT_SHADE else 0f)

        glView = findViewById(R.id.glView)
        glView.setEGLContextClientVersion(3)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        camera = CameraController(this, this, this)

        findViewById<ImageButton>(R.id.shutter).setOnClickListener {
            renderer.requestCapture()
            glView.requestRender()
        }
        findViewById<ImageButton>(R.id.settings).setOnClickListener { openSettings() }
        findViewById<ImageButton>(R.id.switchCamera).setOnClickListener {
            if (camera.hasFrontCamera) camera.switchLens() else toast(getString(R.string.no_front_camera))
        }
        findViewById<MaterialButton>(R.id.grantPermission).setOnClickListener {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        loadInitialPalette()
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
            else -> showPermissionOverlay()
        }
    }

    override fun onPause() {
        camera.stop()
        activeTexture = null
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        renderer.releaseSurfaceTexture()
        super.onDestroy()
    }

    // ------------------------------------------------------------- rendering

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
            bitmap.recycle()
            toast(if (uri != null) getString(R.string.saved) else getString(R.string.save_failed))
        }
    }

    override fun onRendererError(message: String) {
        runOnUiThread { toast(message) }
    }

    // ---------------------------------------------------------------- camera

    override fun onCameraTransform(width: Int, height: Int, rotationDegrees: Int, mirror: Boolean) {
        glView.queueEvent {
            renderer.setCameraInfo(width, height, rotationDegrees, mirror)
        }
        glView.requestRender()
    }

    override fun onCameraError(message: String) {
        runOnUiThread { toast(message) }
    }

    // --------------------------------------------------------------- palette

    private fun loadInitialPalette() {
        setBusy(true)
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.Default) {
                PaletteStore.load(this@MainActivity) ?: DefaultPack.create()
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
                    PaletteStore.save(this@MainActivity, imported)
                    imported
                }
            }
            setBusy(false)
            result
                .onSuccess {
                    applyPalette(it)
                    toast(getString(R.string.pack_loaded, it.name, it.blockCount))
                }
                .onFailure { toast(it.message ?: getString(R.string.pack_failed)) }
        }
    }

    private fun resetPack() {
        setBusy(true)
        lifecycleScope.launch {
            val fallback = withContext(Dispatchers.Default) {
                PaletteStore.clear(this@MainActivity)
                DefaultPack.create()
            }
            applyPalette(fallback)
            setBusy(false)
            toast(getString(R.string.pack_reset))
        }
    }

    private fun applyPalette(newPalette: BlockPalette) {
        palette = newPalette
        renderer.setPalette(newPalette)
        glView.requestRender()
    }

    // -------------------------------------------------------------------- ui

    private fun openSettings() {
        val current = palette
        SettingsSheet(
            this,
            SettingsSheet.State(
                density = density,
                shadeEnabled = shadeEnabled,
                packName = current?.name ?: DefaultPack.NAME,
                blockCount = current?.blockCount ?: 0,
            ),
            object : SettingsSheet.Callbacks {
                override fun onDensityChanged(density: Int) {
                    this@MainActivity.density = density
                    prefs.edit().putInt(KEY_DENSITY, density).apply()
                    renderer.setDensity(density)
                    glView.requestRender()
                }

                override fun onShadeChanged(enabled: Boolean) {
                    shadeEnabled = enabled
                    prefs.edit().putBoolean(KEY_SHADE, enabled).apply()
                    renderer.setShadeStrength(if (enabled) MosaicRenderer.DEFAULT_SHADE else 0f)
                    glView.requestRender()
                }

                override fun onImportPack() {
                    packLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                }

                override fun onResetPack() = resetPack()
            },
        ).show()
    }

    private fun showPermissionOverlay() {
        permissionOverlay.visibility = View.VISIBLE
        permissionMessage.setText(R.string.permission_rationale)
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        controls.alpha = if (busy) 0.4f else 1f
        controls.isEnabled = !busy
        findViewById<ImageButton>(R.id.shutter).isEnabled = !busy
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val PREFS = "mccamera"
        const val KEY_DENSITY = "density"
        const val KEY_SHADE = "shade"
    }
}
