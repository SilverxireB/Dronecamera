package com.dronecamera.app

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * Kameradan gelen kareleri GPU'da yeniden cizerek, donanimin verdigi zoom
 * tavaninin (bu cihazda 10x) uzerine yazilimsal kirpma-zoom ekler.
 *
 * Kirpma hem onizlemeye hem kayda ayni sekilde uygulanir; ekranda gorulen
 * kaydedilenle aynidir. [zoom] 1.0 iken hicbir sey degismez.
 */
class ZoomSurfaceProcessor : SurfaceProcessor {

    /** Ek yazilim zoom'u (1.0 = kapali). Herhangi bir is parcacigindan yazilabilir. */
    @Volatile
    var zoom: Float = 1f

    private val thread = HandlerThread("SoftZoomGL").apply { start() }
    private val handler = Handler(thread.looper)
    val executor = Executor { command -> handler.post(command) }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var uTexMatrix = 0
    private var uZoom = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var textureId = 0

    private var inputTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private val outputs = mutableMapOf<SurfaceOutput, EGLSurface>()

    private val stMatrix = FloatArray(16)
    private val finalMatrix = FloatArray(16)

    private val vertices: FloatBuffer = floatBuffer(
        floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    )
    private val texCoords: FloatBuffer = floatBuffer(
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    )

    init {
        handler.post { initGl() }
    }

    // ------------------------------------------------------------ SurfaceProcessor

    override fun onInputSurface(request: SurfaceRequest) {
        handler.post {
            releaseInput()
            val texture = SurfaceTexture(textureId).apply {
                setDefaultBufferSize(request.resolution.width, request.resolution.height)
                setOnFrameAvailableListener({ handler.post { drawFrame() } }, handler)
            }
            val surface = Surface(texture)
            inputTexture = texture
            inputSurface = surface
            request.provideSurface(surface, executor) {
                surface.release()
                texture.release()
            }
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        handler.post {
            val surface = surfaceOutput.getSurface(executor) {
                handler.post {
                    outputs.remove(surfaceOutput)?.let { eglSurface ->
                        if (eglSurface != EGL14.EGL_NO_SURFACE) {
                            EGL14.eglDestroySurface(eglDisplay, eglSurface)
                        }
                    }
                    surfaceOutput.close()
                }
            }
            val eglSurface = createWindowSurface(surface)
            if (eglSurface != EGL14.EGL_NO_SURFACE) outputs[surfaceOutput] = eglSurface
        }
    }

    fun release() {
        handler.post {
            releaseInput()
            outputs.values.forEach { EGL14.eglDestroySurface(eglDisplay, it) }
            outputs.keys.forEach { it.close() }
            outputs.clear()
            if (program != 0) GLES20.glDeleteProgram(program)
            if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbuffer)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            thread.quitSafely()
        }
    }

    private fun releaseInput() {
        inputTexture?.setOnFrameAvailableListener(null)
        inputTexture = null
        inputSurface = null
    }

    // ------------------------------------------------------------ Cizim

    private fun drawFrame() {
        val texture = inputTexture ?: return
        if (outputs.isEmpty()) return
        makeCurrent(pbuffer)
        texture.updateTexImage()
        texture.getTransformMatrix(stMatrix)
        val timestamp = texture.timestamp

        val currentZoom = zoom.coerceAtLeast(1f)
        outputs.forEach { (output, eglSurface) ->
            if (!makeCurrent(eglSurface)) return@forEach
            output.updateTransformMatrix(finalMatrix, stMatrix)

            val size = output.size
            GLES20.glViewport(0, 0, size.width, size.height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, finalMatrix, 0)
            GLES20.glUniform1f(uZoom, currentZoom)

            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPosition)
            GLES20.glDisableVertexAttribArray(aTexCoord)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestamp)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }
    }

    // ------------------------------------------------------------ EGL / GL kurulum

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0]

        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        makeCurrent(pbuffer)

        program = buildProgram()
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uZoom = GLES20.glGetUniformLocation(program, "uZoom")
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTextureCoord")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        Matrix.setIdentityM(stMatrix, 0)
        Matrix.setIdentityM(finalMatrix, 0)
    }

    private fun createWindowSurface(surface: Surface): EGLSurface = runCatching {
        EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0
        )
    }.getOrDefault(EGL14.EGL_NO_SURFACE)

    private fun makeCurrent(surface: EGLSurface): Boolean =
        surface != EGL14.EGL_NO_SURFACE &&
            EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)

    private fun buildProgram(): Int {
        val vertexShader = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vertexShader)
        GLES20.glAttachShader(id, fragmentShader)
        GLES20.glLinkProgram(id)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return id
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply {
                put(values)
                position(0)
            }

    private companion object {
        /**
         * Kirpma doku koordinatlarinda yapilir: merkez sabit kalacak sekilde
         * ornekleme alani 1/uZoom oraninda daraltilir.
         */
        const val VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            uniform float uZoom;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vec2 centered = (aTextureCoord.xy - vec2(0.5)) / uZoom + vec2(0.5);
                vTextureCoord = (uTexMatrix * vec4(centered, 0.0, 1.0)).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """
    }
}

/** CameraEffect korumali kuruculu oldugu icin turetiliyor. */
class SoftZoomEffect(
    processor: ZoomSurfaceProcessor,
    onError: (Throwable) -> Unit
) : CameraEffect(
    PREVIEW or VIDEO_CAPTURE,
    processor.executor,
    processor,
    Consumer { onError(it) }
)
