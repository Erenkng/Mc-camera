package com.erenkng.mccamera.video

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/**
 * An EGL window surface wrapping the encoder's input [Surface], sharing the GL
 * context that is current when this is constructed.
 *
 * Because it borrows the caller's context, everything here must run on the
 * renderer thread: make it current, draw the same mosaic again at video
 * resolution, hand the timestamp to EGL, swap, then switch back to the screen.
 */
class EncoderSurface(surface: Surface) {

    private val display: EGLDisplay = EGL14.eglGetCurrentDisplay()
    private val context = EGL14.eglGetCurrentContext()
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var savedDraw: EGLSurface = EGL14.EGL_NO_SURFACE
    private var savedRead: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        require(display != EGL14.EGL_NO_DISPLAY && context != EGL14.EGL_NO_CONTEXT) {
            "EncoderSurface renderer iş parçacığında oluşturulmalı"
        }
        val config = chooseConfig()
        eglSurface = EGL14.eglCreateWindowSurface(
            display, config, surface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        checkError("eglCreateWindowSurface")
    }

    fun makeCurrent() {
        savedDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        savedRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
        checkError("eglMakeCurrent(encoder)")
    }

    fun restore() {
        EGL14.eglMakeCurrent(display, savedDraw, savedRead, context)
    }

    fun setPresentationTime(nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, nanos)
    }

    fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(display, eglSurface)

    fun release() {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun chooseConfig(): EGLConfig {
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val ok = EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0)
        check(ok && count[0] > 0) { "Kayıt için uygun EGL yapılandırması bulunamadı" }
        return configs[0]!!
    }

    private fun checkError(where: String) {
        val error = EGL14.eglGetError()
        check(error == EGL14.EGL_SUCCESS) { "$where başarısız: 0x${error.toString(16)}" }
    }

    private companion object {
        /** EGL_RECORDABLE_ANDROID, not exposed by the EGL14 constants. */
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
