package com.erenkng.mccamera.gl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log

object GlUtils {

    private const val TAG = "GlUtils"

    fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)

        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Program bağlanamadı: $log")
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader derlenemedi: $log")
        }
        return shader
    }

    fun createTexture(target: Int, filter: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(target, 0)
        return ids[0]
    }

    fun uploadBitmap(textureId: Int, bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun checkError(where: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) Log.w(TAG, "GL hatası ($where): 0x${error.toString(16)}")
    }
}

/** Minimal column-major 3x3 helpers for the camera UV transform. */
object Mat3 {

    fun identity(): FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /**
     * Builds a column-major matrix from row-major coefficients, which is how
     * the affine transforms below are easiest to read.
     */
    fun ofRows(
        a: Float, b: Float, c: Float,
        d: Float, e: Float, f: Float,
    ): FloatArray = floatArrayOf(a, d, 0f, b, e, 0f, c, f, 1f)

    /** Returns `left * right`. */
    fun multiply(left: FloatArray, right: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (col in 0..2) {
            for (row in 0..2) {
                var sum = 0f
                for (k in 0..2) sum += left[k * 3 + row] * right[col * 3 + k]
                out[col * 3 + row] = sum
            }
        }
        return out
    }
}
