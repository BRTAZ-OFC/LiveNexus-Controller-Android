package com.livenexus.controller

import android.graphics.Bitmap
import android.graphics.Canvas
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Compositor de cenários com aceleração de hardware via EGL/OpenGL ES.
 * Conecta o desenho do estúdio/cenário ao Surface de entrada do MediaCodec.
 */
class SceneCompositor(
    private val outputSurface: Surface,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val onFrameRendered: ((Bitmap) -> Unit)? = null
) : AutoCloseable {

    companion object {
        private const val TAG = "SceneCompositor"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }

    @Volatile private var isRunning = false
    private var renderThread: Thread? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTextureLoc = 0
    private var textureId = 0
    private var vertexBuffer: FloatBuffer? = null

    private val compositedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val compositedCanvas = Canvas(compositedBitmap)

    fun start(renderAction: (Canvas, Int, Int) -> Unit) {
        if (isRunning) return
        isRunning = true

        renderThread = Thread({
            var useEgl = false
            try {
                try {
                    initEGL()
                    initGL()
                    useEgl = true
                } catch (eglErr: Throwable) {
                    Log.w(TAG, "EGL indisponível para o Surface; usando Canvas direto no Surface.", eglErr)
                    releaseGL()
                    releaseEGL()
                    useEgl = false
                }

                val frameIntervalMs = (1000L / fps).coerceAtLeast(10L)
                val startNs = System.nanoTime()
                var frameIndex = 0L

                while (isRunning) {
                    val frameStart = System.currentTimeMillis()

                    // Renderiza o cenário no Canvas
                    renderAction(compositedCanvas, width, height)

                    // Notifica prévia local se solicitado (a cada meio segundo)
                    if (frameIndex % (fps / 2).coerceAtLeast(1) == 0L) {
                        try {
                            onFrameRendered?.invoke(compositedBitmap)
                        } catch (_: Exception) {}
                    }

                    if (useEgl) {
                        // Envia o frame renderizado para a textura OpenGL
                        GLES20.glViewport(0, 0, width, height)
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                        GLES20.glUseProgram(program)

                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, compositedBitmap, 0)
                        GLES20.glUniform1i(uTextureLoc, 0)

                        vertexBuffer?.position(0)
                        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, vertexBuffer)
                        GLES20.glEnableVertexAttribArray(aPositionLoc)

                        vertexBuffer?.position(2)
                        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, vertexBuffer)
                        GLES20.glEnableVertexAttribArray(aTexCoordLoc)

                        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                        // Define o timestamp de apresentação para o MediaCodec (em nanos)
                        val ptsNs = System.nanoTime() - startNs
                        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNs)
                        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                    } else if (outputSurface.isValid) {
                        val surfaceCanvas = try {
                            outputSurface.lockHardwareCanvas()
                        } catch (_: Throwable) {
                            try { outputSurface.lockCanvas(null) } catch (_: Throwable) { null }
                        }
                        if (surfaceCanvas != null) {
                            try {
                                surfaceCanvas.drawBitmap(compositedBitmap, 0f, 0f, null)
                            } finally {
                                try { outputSurface.unlockCanvasAndPost(surfaceCanvas) } catch (_: Throwable) {}
                            }
                        }
                    }

                    frameIndex++

                    val elapsed = System.currentTimeMillis() - frameStart
                    val waitTime = frameIntervalMs - elapsed
                    if (waitTime > 0) {
                        try {
                            Thread.sleep(waitTime)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Erro no loop de composição", e)
            } finally {
                if (useEgl) {
                    releaseGL()
                    releaseEGL()
                }
                try {
                    if (!compositedBitmap.isRecycled) {
                        compositedBitmap.recycle()
                    }
                } catch (_: Exception) {}
            }
        }, "LiveNexusSceneCompositor").also { it.start() }
    }

    private fun initEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("EGL display indisponível")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("Falha ao inicializar EGL")
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0) || numConfigs[0] == 0) {
            val fallbackAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            EGL14.eglChooseConfig(eglDisplay, fallbackAttribs, 0, configs, 0, configs.size, numConfigs, 0)
        }
        val eglConfig = configs[0] ?: throw RuntimeException("Nenhuma configuração EGL disponível")

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("Falha ao criar contexto EGL")
        }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, outputSurface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("Falha ao criar superfície EGL para o encoder")
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("Falha no eglMakeCurrent")
        }
    }

    private fun initGL() {
        val vShaderCode = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        val fShaderCode = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vShaderCode)
        val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fShaderCode)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vShader)
        GLES20.glAttachShader(program, fShader)
        GLES20.glLinkProgram(program)

        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        textureId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Quad cobrindo a tela [-1, 1] com coordenadas de textura [0, 1]
        val quad = floatArrayOf(
            -1.0f, -1.0f, 0.0f, 1.0f,
             1.0f, -1.0f, 1.0f, 1.0f,
            -1.0f,  1.0f, 0.0f, 0.0f,
             1.0f,  1.0f, 1.0f, 0.0f
        )
        vertexBuffer = ByteBuffer.allocateDirect(quad.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quad)
    }

    private fun loadShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Falha ao compilar shader: $error")
        }
        return shader
    }

    private fun releaseGL() {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    private fun releaseEGL() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    override fun close() {
        isRunning = false
        renderThread?.interrupt()
        try {
            renderThread?.join(1000L)
        } catch (_: Exception) {}
        renderThread = null
    }
}
