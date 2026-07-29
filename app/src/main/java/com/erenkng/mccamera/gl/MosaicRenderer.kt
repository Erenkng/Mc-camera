package com.erenkng.mccamera.gl

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.erenkng.mccamera.palette.BlockPalette
import com.erenkng.mccamera.video.EncoderSurface
import com.erenkng.mccamera.video.VideoRecorder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders a camera feed — or an imported photo — as a grid of Minecraft blocks.
 *
 * Everything that touches GL state runs on the [GLSurfaceView] render thread;
 * the public setters are safe to call from the UI thread and take effect on the
 * next frame.
 */
class MosaicRenderer(private val callbacks: Callbacks) : GLSurfaceView.Renderer {

    interface Callbacks {
        /** The camera can start pushing frames into this texture. */
        fun onSurfaceTextureReady(surfaceTexture: SurfaceTexture)

        /** A still frame was rendered and read back. */
        fun onFrameCaptured(bitmap: Bitmap)

        fun onRecordingStarted(started: Boolean)

        fun onRecordingStopped(uri: Uri?)

        fun onRendererError(message: String)
    }

    /**
     * A shooting mode. Beyond how a cell is painted, each one biases the colour
     * that goes into the block lookup, so a mode changes which blocks the scene
     * is built from — not just how the result is tinted.
     */
    enum class Mode(
        val paint: Int,
        val tint: FloatArray,
        val lift: Float,
        val vignette: Float,
        val glow: Float,
        val wave: Float,
    ) {
        /** Straight block textures. */
        BLOCKS(PAINT_TEXTURE, floatArrayOf(1f, 1f, 1f), 0f, 0f, 0f, 0f),

        /** Flat block colours, the look of a Minecraft map. */
        MAP_ART(PAINT_FLAT, floatArrayOf(1f, 1f, 1f), 0f, 0f, 0f, 0f),

        /** No palette at all, just the averaged cells. */
        PIXELS(PAINT_RAW, floatArrayOf(1f, 1f, 1f), 0f, 0f, 0f, 0f),

        /** Night vision potion: shadows lifted hard, everything greened. */
        NIGHT_VISION(PAINT_TEXTURE, floatArrayOf(0.72f, 1.28f, 0.78f), 0.85f, 0.18f, 0.20f, 0f),

        /** The Nether: netherrack reds, heavy corners, glowing highlights. */
        NETHER(PAINT_TEXTURE, floatArrayOf(1.35f, 0.82f, 0.68f), 0.12f, 0.38f, 0.30f, 0f),

        /** The End: purpur and endstone, deep vignette. */
        END(PAINT_TEXTURE, floatArrayOf(1.05f, 0.82f, 1.32f), 0.22f, 0.45f, 0.22f, 0f),

        /** Glowstone: bright cells bloom like light sources. */
        GLOWSTONE(PAINT_TEXTURE, floatArrayOf(1.18f, 1.06f, 0.78f), 0.30f, 0.10f, 0.80f, 0f),

        /** Underwater: prismarine blues with a live ripple. */
        UNDERWATER(PAINT_TEXTURE, floatArrayOf(0.70f, 1.02f, 1.28f), 0.20f, 0.32f, 0.12f, 1f),
    }

    private var cameraProgram: Program? = null
    private var stillProgram: Program? = null
    private var mosaicProgram: Program? = null

    private var oesTexture = 0
    private var stillTexture = 0
    private var atlasTexture = 0
    private var lutTexture = 0
    private var paletteTexture = 0
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

    /** The letterboxed region the mosaic is drawn into, in view pixels. */
    private var frameLeft = 0
    private var frameTop = 0
    private var frameWidth = 0
    private var frameHeight = 0

    private var gridWidth = 0
    private var gridHeight = 0
    private var downWidth = 0
    private var downHeight = 0
    private var lod = 0f

    private var hasFrame = false
    private var startNanos = 0L

    @Volatile private var geometryDirty = true
    @Volatile private var frameAvailable = false
    @Volatile private var frozen = false

    @Volatile private var density = DEFAULT_DENSITY
    @Volatile private var mode = Mode.BLOCKS
    @Volatile private var aspect = AspectFormat.FULL
    @Volatile private var shadeStrength = DEFAULT_SHADE
    @Volatile private var ditherStrength = DEFAULT_DITHER
    @Volatile private var bevelStrength = 0f
    @Volatile private var outlineStrength = 0f
    @Volatile private var brightness = 0f
    @Volatile private var contrast = 1f
    @Volatile private var saturation = 1f

    @Volatile private var pendingPalette: BlockPalette? = null
    @Volatile private var pendingStill: Bitmap? = null
    @Volatile private var clearStill = false
    @Volatile private var captureRequest = 0

    private var palette: BlockPalette? = null
    private var stillWidth = 0
    private var stillHeight = 0
    private var stillActive = false

    private var recorder: VideoRecorder? = null
    private var encoderSurface: EncoderSurface? = null

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

    fun setRenderMode(newMode: Mode) {
        mode = newMode
    }

    fun setAspect(format: AspectFormat) {
        if (aspect != format) {
            aspect = format
            geometryDirty = true
        }
    }

    fun setShadeStrength(strength: Float) {
        shadeStrength = strength.coerceIn(0f, 1f)
    }

    fun setDitherStrength(strength: Float) {
        ditherStrength = strength.coerceIn(0f, 0.4f)
    }

    fun setBevelStrength(strength: Float) {
        bevelStrength = strength.coerceIn(0f, 1f)
    }

    fun setOutlineStrength(strength: Float) {
        outlineStrength = strength.coerceIn(0f, 1f)
    }

    fun setGrade(brightness: Float, contrast: Float, saturation: Float) {
        this.brightness = brightness.coerceIn(-0.5f, 0.5f)
        this.contrast = contrast.coerceIn(0.5f, 2f)
        this.saturation = saturation.coerceIn(0f, 2f)
    }

    fun setFrozen(value: Boolean) {
        frozen = value
    }

    /** Renders an imported photo instead of the camera until [clearStillImage]. */
    fun setStillImage(bitmap: Bitmap) {
        pendingStill = bitmap
        clearStill = false
    }

    fun clearStillImage() {
        clearStill = true
        pendingStill = null
    }

    /**
     * Grabs the next frame as a bitmap. [pixelsPerBlock] of 0 captures at screen
     * resolution; 8 or 16 render the mosaic off-screen so every block gets that
     * many pixels, which is how you get a wallpaper-sized image out of a phone
     * screen.
     */
    fun requestCapture(pixelsPerBlock: Int) {
        captureRequest = pixelsPerBlock.coerceIn(0, BlockPalette.TILE_SIZE)
        if (captureRequest == 0) captureRequest = -1
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

    /** Must be queued onto the render thread: needs the live EGL context. */
    fun beginRecording(newRecorder: VideoRecorder, shortSide: Int) {
        if (recorder != null) return
        val (w, h) = VideoRecorder.sizeFor(shortSide, frameWidth, frameHeight)
        if (!newRecorder.start(w, h)) {
            callbacks.onRecordingStarted(false)
            return
        }
        val surface = newRecorder.inputSurface
        if (surface == null) {
            newRecorder.stop()
            callbacks.onRecordingStarted(false)
            return
        }
        encoderSurface = try {
            EncoderSurface(surface)
        } catch (e: Exception) {
            Log.e(TAG, "Kayıt yüzeyi oluşturulamadı", e)
            newRecorder.stop()
            callbacks.onRecordingStarted(false)
            return
        }
        recorder = newRecorder
        callbacks.onRecordingStarted(true)
    }

    /** Must be queued onto the render thread. */
    fun endRecording() {
        val active = recorder ?: return
        recorder = null
        val uri = active.stop()
        encoderSurface?.release()
        encoderSurface = null
        callbacks.onRecordingStopped(uri)
    }

    // -------------------------------------------------------------- renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            cameraProgram = Program(Shaders.SOURCE_VERTEX, Shaders.CAMERA_FRAGMENT)
            stillProgram = Program(Shaders.SOURCE_VERTEX, Shaders.STILL_FRAGMENT)
            mosaicProgram = Program(Shaders.MOSAIC_VERTEX, Shaders.MOSAIC_FRAGMENT)
        } catch (e: RuntimeException) {
            callbacks.onRendererError(e.message ?: "Shader hatası")
            return
        }

        oesTexture = GlUtils.createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_LINEAR)
        stillTexture = GlUtils.createTexture(GLES20.GL_TEXTURE_2D, GLES20.GL_LINEAR)
        atlasTexture = GlUtils.createTexture(GLES20.GL_TEXTURE_2D, GLES20.GL_NEAREST)
        lutTexture = GlUtils.createTexture(GLES20.GL_TEXTURE_2D, GLES20.GL_NEAREST)
        paletteTexture = GlUtils.createTexture(GLES20.GL_TEXTURE_2D, GLES20.GL_NEAREST)

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
        stillActive = false
        startNanos = System.nanoTime()

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
        if (mosaicProgram == null) return

        pendingPalette?.let { uploadPalette(it) }
        pendingStill?.let { uploadStill(it) }
        if (clearStill) {
            clearStill = false
            stillActive = false
            geometryDirty = true
        }

        if (!stillActive) consumeCameraFrame()

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (!hasFrame || palette == null || viewWidth == 0 || viewHeight == 0) return

        if (geometryDirty) {
            updateGeometry()
            geometryDirty = false
        }

        drawSourceToBuffer()

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        // glViewport counts from the bottom, the frame is positioned from the top.
        GLES20.glViewport(
            frameLeft,
            viewHeight - frameTop - frameHeight,
            frameWidth,
            frameHeight,
        )
        drawMosaic()

        recordFrame()

        val request = captureRequest
        if (request != 0) {
            captureRequest = 0
            capture(request)
        }
    }

    // --------------------------------------------------------------- private

    private fun consumeCameraFrame() {
        val texture = surfaceTexture ?: return
        if (!frameAvailable) return
        frameAvailable = false
        if (frozen && hasFrame) return
        try {
            texture.updateTexImage()
            texture.getTransformMatrix(stMatrix)
            hasFrame = true
        } catch (_: RuntimeException) {
            // The producer went away between the callback and this frame.
        }
    }

    private fun updateGeometry() {
        updateFrame()

        gridWidth = density.coerceAtLeast(MIN_DENSITY)
        gridHeight = max(1, (gridWidth.toFloat() * frameHeight / frameWidth).roundToInt())

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

    /** Letterboxes the requested aspect into the view, biased toward the top. */
    private fun updateFrame() {
        val target = aspect.ratio
        if (target <= 0f) {
            frameLeft = 0
            frameTop = 0
            frameWidth = viewWidth
            frameHeight = viewHeight
            return
        }

        val viewAspect = viewWidth.toFloat() / viewHeight
        if (viewAspect > target) {
            frameHeight = viewHeight
            frameWidth = (viewHeight * target).roundToInt().coerceAtMost(viewWidth)
        } else {
            frameWidth = viewWidth
            frameHeight = (viewWidth / target).roundToInt().coerceAtMost(viewHeight)
        }
        frameLeft = (viewWidth - frameWidth) / 2
        // Sit above centre so the controls do not crowd the shot.
        frameTop = ((viewHeight - frameHeight) * FRAME_TOP_BIAS).roundToInt()
    }

    /** Maps frame UV (y-down) to source buffer UV (y-down). */
    private fun buildUvMatrix(): FloatArray {
        val sourceWidth = if (stillActive) stillWidth else bufferWidth
        val sourceHeight = if (stillActive) stillHeight else bufferHeight
        if (sourceWidth == 0 || sourceHeight == 0 || frameWidth == 0 || frameHeight == 0) {
            return Mat3.identity()
        }

        val rotation = if (stillActive) 0 else rotationDegrees
        val swapped = rotation == 90 || rotation == 270
        val displayAspect = if (swapped) {
            sourceHeight.toFloat() / sourceWidth
        } else {
            sourceWidth.toFloat() / sourceHeight
        }
        val targetAspect = frameWidth.toFloat() / frameHeight

        // Centre-crop so the preview fills the frame without stretching.
        var sx = 1f
        var sy = 1f
        if (displayAspect > targetAspect) {
            sx = targetAspect / displayAspect
        } else {
            sy = displayAspect / targetAspect
        }

        val crop = Mat3.ofRows(
            sx, 0f, 0.5f - 0.5f * sx,
            0f, sy, 0.5f - 0.5f * sy,
        )
        val mirror = if (!stillActive && mirrored) {
            Mat3.ofRows(-1f, 0f, 1f, 0f, 1f, 0f)
        } else {
            Mat3.identity()
        }
        // Display space -> buffer space. Rotating the buffer clockwise by
        // `rotation` is what makes it upright, so this is that rotation inverted.
        val rotate = when (rotation) {
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

        val strip = newPalette.buildPaletteStrip()
        GlUtils.uploadBitmap(paletteTexture, strip)
        strip.recycle()

        palette = newPalette
        pendingPalette = null
        GlUtils.checkError("uploadPalette")
    }

    private fun uploadStill(bitmap: Bitmap) {
        GlUtils.uploadBitmap(stillTexture, bitmap)
        stillWidth = bitmap.width
        stillHeight = bitmap.height
        stillActive = true
        hasFrame = true
        geometryDirty = true
        pendingStill = null
        bitmap.recycle()
    }

    private fun drawSourceToBuffer() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            downTexture,
            0,
        )
        GLES20.glViewport(0, 0, downWidth, downHeight)

        val program = (if (stillActive) stillProgram else cameraProgram) ?: return
        program.use()

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (stillActive) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stillTexture)
        } else {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        }
        GLES20.glUniform1i(program.uniform("sCam"), 0)
        GLES20.glUniformMatrix4fv(
            program.uniform("uTexMatrix"), 1, false,
            if (stillActive) STILL_TEX_MATRIX else stMatrix, 0,
        )
        GLES20.glUniformMatrix3fv(program.uniform("uUvMatrix"), 1, false, uvMatrix, 0)
        GLES20.glUniform1f(program.uniform("uBrightness"), brightness)
        GLES20.glUniform1f(program.uniform("uContrast"), contrast)
        GLES20.glUniform1f(program.uniform("uSaturation"), saturation)

        drawQuad(program)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, downTexture)
        GLES30.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
    }

    private fun drawMosaic() {
        val program = mosaicProgram ?: return
        val active = mode
        program.use()

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, downTexture)
        GLES20.glUniform1i(program.uniform("sDown"), 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture)
        GLES20.glUniform1i(program.uniform("sLut"), 1)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexture)
        GLES20.glUniform1i(program.uniform("sAtlas"), 2)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, paletteTexture)
        GLES20.glUniform1i(program.uniform("sPalette"), 3)

        GLES20.glUniform2f(program.uniform("uGrid"), gridWidth.toFloat(), gridHeight.toFloat())
        GLES20.glUniform1f(program.uniform("uLod"), lod)
        GLES20.glUniform1f(program.uniform("uTiles"), BlockPalette.TILES_PER_ROW.toFloat())
        GLES20.glUniform1f(program.uniform("uPaletteSize"), BlockPalette.MAX_BLOCKS.toFloat())
        GLES20.glUniform1f(program.uniform("uShade"), shadeStrength)
        GLES20.glUniform1f(program.uniform("uDither"), ditherStrength)
        GLES20.glUniform1f(program.uniform("uBevel"), bevelStrength)
        GLES20.glUniform1f(program.uniform("uOutline"), outlineStrength)

        GLES20.glUniform1i(program.uniform("uPaint"), active.paint)
        GLES20.glUniform3f(
            program.uniform("uTint"),
            active.tint[0], active.tint[1], active.tint[2],
        )
        GLES20.glUniform1f(program.uniform("uLift"), active.lift)
        GLES20.glUniform1f(program.uniform("uVignette"), active.vignette)
        GLES20.glUniform1f(program.uniform("uGlow"), active.glow)
        GLES20.glUniform1f(program.uniform("uWave"), active.wave)
        GLES20.glUniform1f(
            program.uniform("uTime"),
            (System.nanoTime() - startNanos) / 1_000_000_000f,
        )

        val frameAspect = if (frameHeight > 0) frameWidth.toFloat() / frameHeight else 1f
        GLES20.glUniform1f(program.uniform("uFrameAspect"), frameAspect)
        GLES20.glUniform1f(
            program.uniform("uCorner"),
            if (aspect == AspectFormat.FULL) 0f else CORNER_RADIUS,
        )

        drawQuad(program)
    }

    private fun drawQuad(program: Program) {
        val position = program.attribute("aPos")
        quad.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
    }

    private fun recordFrame() {
        val active = recorder ?: return
        val surface = encoderSurface ?: return
        try {
            active.drain(false)
            surface.makeCurrent()
            GLES20.glViewport(0, 0, active.width, active.height)
            drawMosaic()
            surface.setPresentationTime(active.timestampFor(System.nanoTime()))
            surface.swapBuffers()
        } catch (e: Exception) {
            Log.e(TAG, "Kare kaydedilemedi", e)
        } finally {
            surface.restore()
        }
    }

    /**
     * [pixelsPerBlock] of -1 means "whatever the screen is"; anything else
     * renders the mosaic into an off-screen buffer at block-native resolution.
     */
    private fun capture(pixelsPerBlock: Int) {
        var width: Int
        var height: Int
        if (pixelsPerBlock <= 0) {
            width = frameWidth
            height = frameHeight
        } else {
            width = gridWidth * pixelsPerBlock
            height = gridHeight * pixelsPerBlock
            val longest = max(width, height)
            if (longest > MAX_CAPTURE_SIDE) {
                val scale = MAX_CAPTURE_SIDE.toFloat() / longest
                width = (width * scale).toInt()
                height = (height * scale).toInt()
            }
        }
        width = width.coerceAtLeast(1)
        height = height.coerceAtLeast(1)

        val ids = IntArray(2)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glGenFramebuffers(1, ids, 1)
        val texture = ids[0]
        val fbo = ids[1]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, texture, 0,
        )
        GLES20.glViewport(0, 0, width, height)
        drawMosaic()
        readPixels(fbo, width, height)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteFramebuffers(1, ids, 1)
        GLES20.glDeleteTextures(1, ids, 0)
    }

    private fun readPixels(framebuffer: Int, width: Int, height: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
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
        private const val TAG = "MosaicRenderer"

        const val PAINT_TEXTURE = 0
        const val PAINT_FLAT = 1
        const val PAINT_RAW = 2

        const val MIN_DENSITY = 8
        const val DEFAULT_DENSITY = 48
        const val DEFAULT_SHADE = 0.5f
        const val DEFAULT_DITHER = 0.05f

        /** Off-screen buffer width we aim for before mipmapping down to the grid. */
        private const val DOWNSAMPLE_TARGET = 512

        private const val MAX_CAPTURE_SIDE = 4096

        /** Where a letterboxed frame sits in the free vertical space. */
        private const val FRAME_TOP_BIAS = 0.32f

        /** Corner rounding of a letterboxed frame, in half-height units. */
        private const val CORNER_RADIUS = 0.08f

        val DENSITY_STEPS = intArrayOf(8, 12, 16, 24, 32, 48, 64, 96, 128, 192)

        /** Photo detail levels: screen, 8 px per block, full 16 px per block. */
        val CAPTURE_STEPS = intArrayOf(0, 8, BlockPalette.TILE_SIZE)

        /** Flips an imported bitmap so its first row lands at the top. */
        private val STILL_TEX_MATRIX = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f,
        )
    }
}
