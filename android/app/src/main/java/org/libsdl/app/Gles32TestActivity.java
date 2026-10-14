package org.libsdl.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.opengl.GLES32;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

/**
 * Device-side GLES test for the Android port.
 *
 * This validates EGL context creation, GLES 3.2 shader compilation and a
 * continuously rendered animated scene. It does not emulate the Xbox GPU and
 * is not a gameplay test.
 */
public final class Gles32TestActivity extends Activity {
    private GLSurfaceView glView;
    private TextView status;
    private GlesRenderer renderer;

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(7, 12, 24));

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(3);
        glView.setEGLConfigChooser(new Es3ConfigChooser());
        glView.setEGLContextFactory(new Es32ContextFactory());
        glView.setPreserveEGLContextOnPause(true);
        renderer = new GlesRenderer(new GlesRenderer.Listener() {
            @Override public void onStatus(String message) {
                runOnUiThread(() -> status.setText(message));
            }
            @Override public void onFailure(String message) {
                runOnUiThread(() -> {
                    status.setText("FALHA GLES\n" + message);
                    status.setTextColor(Color.rgb(255, 112, 112));
                });
            }
        });
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(glView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(14), dp(10));
        panel.setBackgroundColor(0xCC090E1B);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        panelParams.setMargins(dp(12), dp(12), dp(12), 0);

        TextView title = new TextView(this);
        title.setText("JSRF UNLEASHED · TESTE GRÁFICO");
        title.setTextColor(Color.rgb(105, 220, 255));
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        panel.addView(title);

        status = new TextView(this);
        status.setText("Criando contexto OpenGL ES 3.2…");
        status.setTextColor(Color.WHITE);
        status.setTextSize(13);
        status.setPadding(0, dp(6), 0, dp(6));
        panel.addView(status);

        Button back = new Button(this);
        back.setText("Voltar");
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        panel.addView(back);
        root.addView(panel, panelParams);

        TextView hint = new TextView(this);
        hint.setText("Cena animada de diagnóstico · Não é gameplay");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(8), dp(5), dp(8), dp(5));
        hint.setBackgroundColor(0x99090E1B);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hintParams.bottomMargin = dp(16);
        root.addView(hint, hintParams);
        setContentView(root);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (glView != null) glView.onResume();
    }

    @Override
    protected void onPause() {
        if (glView != null) glView.onPause();
        super.onPause();
    }

    /** Config chooser requiring the ES3 EGL config bit. */
    private static final class Es3ConfigChooser implements GLSurfaceView.EGLConfigChooser {
        private static final int EGL_OPENGL_ES3_BIT_KHR = 0x0040;
        private static final int EGL_RENDERABLE_TYPE = 0x3040;

        @Override
        public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
            int[] spec = {
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 16,
                    EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                    EGL10.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!egl.eglChooseConfig(display, spec, configs, 1, count) || count[0] == 0) {
                // Some older EGL implementations omit the ES3 bit while still
                // supporting the versioned context request below.
                int[] fallback = {
                        EGL10.EGL_RED_SIZE, 8,
                        EGL10.EGL_GREEN_SIZE, 8,
                        EGL10.EGL_BLUE_SIZE, 8,
                        EGL10.EGL_ALPHA_SIZE, 8,
                        EGL10.EGL_DEPTH_SIZE, 16,
                        EGL_RENDERABLE_TYPE, 0x00000004,
                        EGL10.EGL_NONE
                };
                if (!egl.eglChooseConfig(display, fallback, configs, 1, count)
                        || count[0] == 0) {
                    throw new IllegalArgumentException("Nenhuma configuração EGL compatível encontrada.");
                }
            }
            return configs[0];
        }
    }

    /** Try an explicit ES 3.2 context, then let shader validation report mismatch. */
    private static final class Es32ContextFactory implements GLSurfaceView.EGLContextFactory {
        private static final int EGL_CONTEXT_MAJOR_VERSION_KHR = 0x3098;
        private static final int EGL_CONTEXT_MINOR_VERSION_KHR = 0x30FB;

        @Override
        public EGLContext createContext(EGL10 egl, EGLDisplay display, EGLConfig config) {
            int[] es32 = {
                    EGL_CONTEXT_MAJOR_VERSION_KHR, 3,
                    EGL_CONTEXT_MINOR_VERSION_KHR, 2,
                    EGL10.EGL_NONE
            };
            EGLContext context = egl.eglCreateContext(
                    display, config, EGL10.EGL_NO_CONTEXT, es32);
            if (context == null || context == EGL10.EGL_NO_CONTEXT) {
                int[] es3 = { EGL_CONTEXT_MAJOR_VERSION_KHR, 3, EGL10.EGL_NONE };
                context = egl.eglCreateContext(
                        display, config, EGL10.EGL_NO_CONTEXT, es3);
            }
            return context;
        }

        @Override
        public void destroyContext(EGL10 egl, EGLDisplay display, EGLContext context) {
            egl.eglDestroyContext(display, context);
        }
    }

    private static final class GlesRenderer implements GLSurfaceView.Renderer {
        interface Listener {
            void onStatus(String message);
            void onFailure(String message);
        }

        private static final String VERTEX_SHADER =
                "#version 320 es\n" +
                "precision highp float;\n" +
                "layout(location=0) in vec2 aPosition;\n" +
                "layout(location=1) in vec3 aColor;\n" +
                "uniform float uAngle;\n" +
                "out vec3 vColor;\n" +
                "void main(){ float c=cos(uAngle); float s=sin(uAngle);" +
                " mat2 r=mat2(c,s,-s,c); gl_Position=vec4(r*aPosition,0.0,1.0);" +
                " vColor=aColor; }";

        private static final String FRAGMENT_SHADER =
                "#version 320 es\n" +
                "precision mediump float;\n" +
                "in vec3 vColor;\n" +
                "uniform float uTime;\n" +
                "out vec4 fragColor;\n" +
                "void main(){ float glow=0.82+0.18*sin(uTime+vColor.g*4.0);" +
                " fragColor=vec4(vColor*glow,1.0); }";

        private final Listener listener;
        private final FloatBuffer vertices;
        private int program;
        private int angleLocation;
        private int timeLocation;
        private long startNs;
        private long fpsWindowNs;
        private int fpsFrames;
        private String gpuName = "GPU desconhecida";
        private boolean failed;

        GlesRenderer(Listener listener) {
            this.listener = listener;
            float[] data = {
                    0.0f, 0.72f, 0.10f, 0.88f, 1.00f,
                   -0.68f,-0.48f, 1.00f, 0.22f, 0.42f,
                    0.68f,-0.48f, 1.00f, 0.72f, 0.10f
            };
            vertices = ByteBuffer.allocateDirect(data.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            vertices.put(data).position(0);
        }

        @Override
        public void onSurfaceCreated(GL10 ignored, javax.microedition.khronos.egl.EGLConfig config) {
            startNs = System.nanoTime();
            fpsWindowNs = startNs;
            fpsFrames = 0;
            String version = GLES32.glGetString(GLES32.GL_VERSION);
            String vendor = GLES32.glGetString(GLES32.GL_VENDOR);
            gpuName = GLES32.glGetString(GLES32.GL_RENDERER);
            if (version == null || !isEs32(version)) {
                fail("O driver abriu '" + version + "', mas esta cena exige OpenGL ES 3.2.");
                return;
            }

            int vertex = compileShader(GLES32.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fragment = compileShader(GLES32.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
            if (vertex == 0 || fragment == 0) return;

            program = GLES32.glCreateProgram();
            GLES32.glAttachShader(program, vertex);
            GLES32.glAttachShader(program, fragment);
            GLES32.glLinkProgram(program);
            int[] linked = new int[1];
            GLES32.glGetProgramiv(program, GLES32.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) {
                fail("Link do shader falhou: " + GLES32.glGetProgramInfoLog(program));
                GLES32.glDeleteProgram(program);
                program = 0;
                return;
            }
            GLES32.glDeleteShader(vertex);
            GLES32.glDeleteShader(fragment);
            angleLocation = GLES32.glGetUniformLocation(program, "uAngle");
            timeLocation = GLES32.glGetUniformLocation(program, "uTime");
            GLES32.glClearColor(0.025f, 0.04f, 0.085f, 1.0f);
            listener.onStatus("OpenGL: " + version + "\nGPU: " + gpuName
                    + "\nFabricante: " + vendor + "\nCompilando shaders: OK");
        }

        private static boolean isEs32(String version) {
            Matcher matcher = Pattern.compile("OpenGL ES (\\d+)\\.(\\d+)")
                    .matcher(version);
            if (!matcher.find()) return false;
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            return major > 3 || (major == 3 && minor >= 2);
        }

        private int compileShader(int type, String source) {
            int shader = GLES32.glCreateShader(type);
            GLES32.glShaderSource(shader, source);
            GLES32.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES32.glGetShaderiv(shader, GLES32.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                fail("Compilação do shader falhou: " + GLES32.glGetShaderInfoLog(shader));
                GLES32.glDeleteShader(shader);
                return 0;
            }
            return shader;
        }

        private void fail(String message) {
            if (failed) return;
            failed = true;
            listener.onFailure(message);
        }

        @Override
        public void onSurfaceChanged(GL10 ignored, int width, int height) {
            GLES32.glViewport(0, 0, width, height);
        }

        @Override
        public void onDrawFrame(GL10 ignored) {
            GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT | GLES32.GL_DEPTH_BUFFER_BIT);
            if (program == 0 || failed) return;

            long now = System.nanoTime();
            float seconds = (now - startNs) / 1_000_000_000.0f;
            GLES32.glUseProgram(program);
            GLES32.glUniform1f(angleLocation, seconds * 0.85f);
            GLES32.glUniform1f(timeLocation, seconds);

            vertices.position(0);
            GLES32.glVertexAttribPointer(0, 2, GLES32.GL_FLOAT, false, 5 * 4, vertices);
            GLES32.glEnableVertexAttribArray(0);
            vertices.position(2);
            GLES32.glVertexAttribPointer(1, 3, GLES32.GL_FLOAT, false, 5 * 4, vertices);
            GLES32.glEnableVertexAttribArray(1);
            GLES32.glDrawArrays(GLES32.GL_TRIANGLES, 0, 3);
            GLES32.glDisableVertexAttribArray(0);
            GLES32.glDisableVertexAttribArray(1);

            fpsFrames++;
            long elapsed = now - fpsWindowNs;
            if (elapsed >= 1_000_000_000L) {
                float fps = fpsFrames * 1_000_000_000.0f / elapsed;
                fpsFrames = 0;
                fpsWindowNs = now;
                listener.onStatus("OpenGL ES 3.2: OK\nGPU: " + gpuName
                        + String.format(Locale.US, "\nFPS do teste: %.1f", fps)
                        + "\nShaders e desenho contínuo: OK");
            }
        }
    }
}
