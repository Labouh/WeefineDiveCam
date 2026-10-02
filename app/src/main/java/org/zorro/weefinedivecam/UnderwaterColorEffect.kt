package org.zorro.weefinedivecam

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * Applies the depth colour correction on the GPU to the preview and video streams, so
 * the diver sees on screen exactly what gets recorded.
 * Photos are corrected at full resolution in [saveCorrectedPhoto] instead.
 */
class UnderwaterColorEffect(processor: UnderwaterColorProcessor) : CameraEffect(
    PREVIEW or VIDEO_CAPTURE,
    processor.glExecutor,
    processor,
    { Log.e("UnderwaterColorEffect", "Effect error", it) }
)

class UnderwaterColorProcessor : SurfaceProcessor {

    private val glThread = HandlerThread("UnderwaterGL").apply { start() }
    private val glHandler = Handler(glThread.looper)
    val glExecutor = Executor { glHandler.post(it) }

    /** Updated from the UI thread, read on the GL thread for every frame. */
    @Volatile
    var gains: ColorGains = ColorGains.IDENTITY

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var uGains = 0

    private var input: Input? = null
    private val outputs = mutableMapOf<SurfaceOutput, EGLSurface>()
    private val textureMatrix = FloatArray(16)
    private val outputMatrix = FloatArray(16)
    private var released = false

    private class Input(val texture: SurfaceTexture, val surface: Surface, val textureId: Int)

    init {
        glHandler.post { initGl() }
    }

    override fun onInputSurface(request: SurfaceRequest) {
        if (released) {
            request.willNotProvideSurface()
            return
        }
        val textureId = createExternalTexture()
        val texture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(request.resolution.width, request.resolution.height)
        }
        val surface = Surface(texture)
        val newInput = Input(texture, surface, textureId)
        texture.setOnFrameAvailableListener({ drawFrame(newInput) }, glHandler)

        request.provideSurface(surface, glExecutor) {
            texture.setOnFrameAvailableListener(null)
            texture.release()
            surface.release()
            if (!released) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            if (input === newInput) input = null
        }
        input = newInput
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (released) {
            surfaceOutput.close()
            return
        }
        val surface = surfaceOutput.getSurface(glExecutor) {
            // EVENT_REQUEST_CLOSE: CameraX no longer needs this output.
            outputs.remove(surfaceOutput)?.let { EGL14.eglDestroySurface(eglDisplay, it) }
            surfaceOutput.close()
        }
        val eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0
        )
        outputs[surfaceOutput] = eglSurface
    }

    fun release() {
        glHandler.post {
            released = true
            outputs.forEach { (output, eglSurface) ->
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                output.close()
            }
            outputs.clear()
            if (program != 0) GLES20.glDeleteProgram(program)
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            glThread.quitSafely()
        }
    }

    private fun drawFrame(frameInput: Input) {
        if (released || frameInput !== input) return
        // Always latch the frame so the camera never stalls, even with no outputs attached.
        frameInput.texture.updateTexImage()
        frameInput.texture.getTransformMatrix(textureMatrix)
        val g = gains

        for ((output, eglSurface) in outputs) {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) continue
            output.updateTransformMatrix(outputMatrix, textureMatrix)

            GLES20.glViewport(0, 0, output.size.width, output.size.height)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, frameInput.textureId)
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, outputMatrix, 0)
            GLES20.glUniform3f(uGains, g.red, g.green, g.blue)

            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, QUAD_POSITIONS)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, QUAD_TEX_COORDS)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // The video encoder needs the camera timestamp to produce correct frame timing.
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, frameInput.texture.timestamp)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }
        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
    }

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1, // required to render into a MediaCodec input surface
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
            "No suitable EGL config"
        }
        eglConfig = configs[0]

        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        pbufferSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uGains = GLES20.glGetUniformLocation(program, "uGains")
        GLES20.glUseProgram(program)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)
    }

    private fun createExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
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
        check(status[0] == GLES20.GL_TRUE) { "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            uniform vec3 uGains;
            varying vec2 vTexCoord;
            void main() {
                vec4 color = texture2D(sTexture, vTexCoord);
                gl_FragColor = vec4(clamp(color.rgb * uGains, 0.0, 1.0), color.a);
            }
        """

        val QUAD_POSITIONS = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val QUAD_TEX_COORDS = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        fun floatBuffer(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }
    }
}
