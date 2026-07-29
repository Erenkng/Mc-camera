package com.erenkng.mccamera.gl

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.erenkng.mccamera.palette.BlockPalette
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders the live camera feed as a grid of Minecraft blocks.
 *
 * Everything that touches GL state runs on the [GLSurfaceView] render thread;
 * the public setters are safe to call from the UI thread and take effect on the
 * next frame.
 */
class MosaicRenderer(private val callbacks: Callbacks) : GLSurfaceView.Renderer {

    interface Callbacks {
        /** The camera can start pushing frames into this texture. */
        fun onSurfaceTextureReady(surfaceTexture: SurfaceTexture)

        /** A still frame was grabbed off the screen buffer. */
        fun onFrameCaptured(bitmap: Bitmap)

        fun onRendererError(message: String)
    }

    private var cameraProgram = 0
    private var mosaicProgram = 0

    private var oesTexture = 0
    private var atlasTexture = 0
    private var lutTexture = 0
    private var downTexture = 0
    private var frameBuffer = 0

    var surfaceTexture: SurfaceTexture? = null
        private set

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    private val stMatrix = FloatArray(16)
    private var uvMatrix = Mat3.identity()

    private var viewWidth = 0
    private var viewHeight = 0
    private var bufferWidth = 0
    private var bufferHeight = 0
    private var rotationDegrees = 0
    private var mirrored = false

    private var gridWidth = 0
    private var gridHeight = 0
    private var downWidth = 0
    private var downHeight = 0
    private var lod = 0f

    private var hasFrame = false

    @Volatile private var geometryDirty = true
    @Volatile private var frameAvailable = false

    @Volatile private var density = DEFAULT_DENSITY
    @Volatile private var shadeStrength = DEFAULT_SHADE
    @Volatile private var pendingPalette: BlockPalette? = null
    @Volatile private var captureRequested = false

    private var palette: BlockPalette? = null

    // ---------------------------------------------------------------- public

    fun setPalette(newPalette: BlockPalette) {
        pendingPalette = newPalette
    }

    fun setDensity(blocksAcross: Int) {
        if (density != blocksAcross) {
            density = blocksAcross
            geometryDirty = true
        }
    }

    fun setShadeStrength(strength: Float) {
        shadeStrength = strength
    }

    /** Grabs the next rendered frame as a bitmap. */
    fun requestCapture() {
        captureRequested = true
    }

    /** Hook for `SurfaceTexture.setOnFrameAvailableListener`. */
    fun onFrameAvailable() {
        frameAvailable = true
    }

    /**
     * Called from the camera pipeline whenever the feed changes. Runs on the
     * render thread via `GLSurfaceView.queueEvent`.
     */
    fun setCameraInfo(width: Int, height: Int, rotation: Int, mirror: Boolean) {
        bufferWidth = width
        bufferHeight = height
        rotationDegrees = ((rotation % 360) + 360) % 360
        mirrored = mirror
        geometryDirty = true
    }

    // -------------------------------------------------------------- renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            cameraProgram = GlUtils.buildProgram(Shaders.CAMERA_VERTEX, Shaders.CAMERA_FRAGMENT)
            mosaicProgram = GlUtils.buildProgram(Shaders.MOSAIC_VERTEX, Shaders.MOSAIC_FRAGMENT)
        } catch (e: RuntimeException) {
            callbacks.onRendererError(e.message ?: "Shader hatası")
            return
        }

        oesTexture = GlUtils.createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_LINEAR)
        atlasTexture = GlUtils.createTexture(GLES20.GL_TEXTURE_2D, GLES20.GL_NEAREST)
        lutTexture = GlUtils.createTexture(GLES20.GL_TEXTURE_2D, GLES20.GL_NEAREST)

        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        frameBuffer = ids[0]

        downTexture = GlUtils.createTexture(GLES20.GL_TEXTURE_2D, GLES20.GL_LINEAR)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, downTexture)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR_MIPMAP_NEAREST,
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        hasFrame = false
        frameAvailable = false
        geometryDirty = true
        downWidth = 0
        downHeight = 0

        // The context is brand new, so whatever palette we had must be re-sent.
        palette?.let { pendingPalette = it }

        surfaceTexture?.release()
        val texture = SurfaceTexture(oesTexture)
        surfaceTexture = texture
        callbacks.onSurfaceTextureReady(texture)

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        geometryDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        if (mosaicProgram == 0) return

        pendingPalette?.let { uploadPalette(it) }

        val texture = surfaceTexture ?: return
        if (frameAvailable) {
            frameAvailable = false
            try {
                texture.updateTexImage()
                texture.getTransformMatrix(stMatrix)
                hasFrame = true
            } catch (_: RuntimeException) {
                // The producer went away between the callback and this frame.
            }
        }

        if (!hasFrame || palette == null || viewWidth == 0 || viewHeight == 0) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }

        if (geometryDirty) {
            updateGeometry()
            geometryDirty = false
        }

        drawCameraToBuffer()
        drawMosaic()

        if (captureRequested) {
            captureRequested = false
            capture()
        }
    }

    // --------------------------------------------------------------- private

    private fun updateGeometry() {
        gridWidth = density.coerceAtLeast(MIN_DENSITY)
        gridHeight = max(1, (gridWidth.toFloat() * viewHeight / viewWidth).roundToInt())

        // Pick the smallest power-of-two upscale that gives us a buffer wide
        // enough to keep detail, so the mip level we sample is an exact integer.
        var levels = 0
        while ((gridWidth shl levels) < DOWNSAMPLE_TARGET && levels < 6) levels++
        lod = levels.toFloat()

        val newWidth = gridWidth shl levels
        val newHeight = gridHeight shl levels
        if (newWidth != downWidth || newHeight != downHeight) {
            downWidth = newWidth
            downHeight = newHeight
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, downTexture)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                downWidth, downHeight, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }

        uvMatrix = buildUvMatrix()
    }

    /** Maps screen UV (y-down) to camera buffer UV (y-down). */
    private fun buildUvMatrix(): FloatArray {
        if (bufferWidth == 0 || bufferHeight == 0) return Mat3.identity()

        val swapped = rotationDegrees == 90 || rotationDegrees == 270
        val displayAspect = if (swapped) {
            bufferHeight.toFloat() / bufferWidth
        } else {
            bufferWidth.toFloat() / bufferHeight
        }
        val viewAspect = viewWidth.toFloat() / viewHeight

        // Centre-crop so the preview fills the screen without stretching.
        var sx = 1f
        var sy = 1f
        if (displayAspect > viewAspect) sx = viewAspect / displayAspect else sy = displayAspect / viewAspect

        val crop = Mat3.ofRows(
            sx, 0f, 0.5f - 0.5f * sx,
            0f, sy, 0.5f - 0.5f * sy,
        )
        val mirror = if (mirrored) Mat3.ofRows(-1f, 0f, 1f, 0f, 1f, 0f) else Mat3.identity()
        val rotate = when (rotationDegrees) {
            90 -> Mat3.ofRows(0f, 1f, 0f, -1f, 0f, 1f)
            180 -> Mat3.ofRows(-1f, 0f, 1f, 0f, -1f, 1f)
            270 -> Mat3.ofRows(0f, -1f, 1f, 1f, 0f, 0f)
            else -> Mat3.identity()
        }
        return Mat3.multiply(rotate, Mat3.multiply(crop, mirror))
    }

    private fun uploadPalette(newPalette: BlockPalette) {
        GlUtils.uploadBitmap(atlasTexture, newPalette.atlas)
        val lut = newPalette.buildLut()
        GlUtils.uploadBitmap(lutTexture, lut)
        lut.recycle()
        palette = newPalette
        pendingPalette = null
        GlUtils.checkError("uploadPalette")
    }

    private fun drawCameraToBuffer() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            downTexture,
            0,
        )
        GLES20.glViewport(0, 0, downWidth, downHeight)

        GLES20.glUseProgram(cameraProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(cameraProgram, "sCam"), 0)
        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(cameraProgram, "uTexMatrix"), 1, false, stMatrix, 0,
        )
        GLES20.glUniformMatrix3fv(
            GLES20.glGetUniformLocation(cameraProgram, "uUvMatrix"), 1, false, uvMatrix, 0,
        )
        drawQuad(cameraProgram)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, downTexture)
        GLES30.glGenerateMipmap(GLES20.GL_TEXTURE_2D)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun drawMosaic() {
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        GLES20.glUseProgram(mosaicProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, downTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(mosaicProgram, "sDown"), 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(mosaicProgram, "sLut"), 1)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(mosaicProgram, "sAtlas"), 2)

        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(mosaicProgram, "uGrid"),
            gridWidth.toFloat(), gridHeight.toFloat(),
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(mosaicProgram, "uLod"), lod)
        GLES20.glUniform1f(
            GLES20.glGetUniformLocation(mosaicProgram, "uTiles"),
            BlockPalette.TILES_PER_ROW.toFloat(),
        )
        GLES20.glUniform1f(GLES20.glGetUniformLocation(mosaicProgram, "uShade"), shadeStrength)

        drawQuad(mosaicProgram)
    }

    private fun drawQuad(program: Int) {
        val position = GLES20.glGetAttribLocation(program, "aPos")
        quad.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
    }

    private fun capture() {
        val width = viewWidth
        val height = viewHeight
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        buffer.rewind()

        val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buffer)

        // glReadPixels hands back rows bottom-up.
        val flip = Matrix().apply { postScale(1f, -1f) }
        val bitmap = Bitmap.createBitmap(raw, 0, 0, width, height, flip, false)
        raw.recycle()

        callbacks.onFrameCaptured(bitmap)
    }

    /**
     * Drops the camera texture. The GL objects themselves die with the EGL
     * context, which GLSurfaceView tears down for us.
     */
    fun releaseSurfaceTexture() {
        surfaceTexture?.release()
        surfaceTexture = null
    }

    companion object {
        const val MIN_DENSITY = 8
        const val DEFAULT_DENSITY = 48
        const val DEFAULT_SHADE = 0.5f

        /** Off-screen buffer width we aim for before mipmapping down to the grid. */
        private const val DOWNSAMPLE_TARGET = 512

        val DENSITY_STEPS = intArrayOf(16, 24, 32, 48, 64, 96, 128)
    }
}
