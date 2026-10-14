/*
 * jsrf_android.c -- the engine inside the Android app.
 *
 * SDL's Java side (launcher/android: JSRFActivity, an SDLActivity) loads
 * libmain.so and calls its SDL_main (main.c), which comes here with
 *   argv[1]  the game folder (default.xbe and Media/), in the app's storage
 *   argv[2]  the app's own folder (getExternalFilesDir): Logs/, the settings,
 *            save/ (the cache and the disk images; the game's saves are in the
 *            game folder, under UDATA, as on every platform)
 *   argv[3]  its cache folder, for temporary files
 *
 * What the Linux launcher and the iPhone app do before the engine starts is
 * done here: the environment (as the library is loaded), the log
 * (Logs/last-run.log, the one before kept as previous-run.log), the folders.
 * Then host_main runs on a thread with a 16 MB stack -- SDL's own is a Java
 * thread's.
 *
 * The setup screen (SetupActivity) unpacks a disc image through
 * nativeProbe / nativeUnpack below, over port/ios/xiso.c.
 */
#include <jni.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/stat.h>
#include <android/log.h>

#ifndef JSRF_VERSION
#define JSRF_VERSION "dev"
#endif

#define TAG "JSRF"

static void mkdir_p(const char *path)
{
    char buf[1024];
    char *p;
    snprintf(buf, sizeof buf, "%s", path);
    for (p = buf + 1; *p; p++)
        if (*p == '/') { *p = 0; mkdir(buf, 0755); *p = '/'; }
    mkdir(buf, 0755);
}

/* Logs/last-run.log, the one before it kept as previous-run.log. Both
 * streams append to the same file, unbuffered. */
static void open_log(const char *data)
{
    char logs[1024], last[1100], prev[1100];
    FILE *f;
    snprintf(logs, sizeof logs, "%s/Logs", data);
    mkdir_p(logs);
    snprintf(last, sizeof last, "%s/last-run.log", logs);
    snprintf(prev, sizeof prev, "%s/previous-run.log", logs);
    rename(last, prev);
    if ((f = fopen(last, "w")) != NULL) fclose(f);
    if (!freopen(last, "a", stdout) || !freopen(last, "a", stderr))
        __android_log_print(ANDROID_LOG_ERROR, TAG, "cannot open %s: %s", last, strerror(errno));
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
}

/* key=value lines (the file the engine's F9/F10 keys write to as well). */
static int cfg_get_int(const char *file, const char *key, int def)
{
    char line[512];
    size_t kl = strlen(key);
    int v = def;
    FILE *f = fopen(file, "r");
    if (!f) return def;
    while (fgets(line, sizeof line, f))
        if (!strncmp(line, key, kl) && line[kl] == '=') v = atoi(line + kl + 1);
    fclose(f);
    return v;
}

/* The same settings as the Linux and Windows launchers'; full screen always.
 * Then SDL's: Back is the game's (the Xbox Back button, nv2a_gl.c) rather than
 * a way out of it, and the phone's motion sensor is not a controller. */
static const char *const k_env[][2] = {
    { "RECOMP_USB", "1" }, { "RECOMP_GL", "1" }, { "RECOMP_PB_EXEC", "1" },
    { "RECOMP_VBLANK", "1" }, { "RECOMP_AC97_READY_NOTRAP", "1" },
    { "RECOMP_TRACE_BUDGET", "0" }, { "RECOMP_GL_READBACK", "0" }, { "RECOMP_FPS", "60" },
    { "RECOMP_ABI_RESTORE", "1" }, { "RECOMP_LOCK_IDLE", "1" },
    { "RECOMP_GL_CURTAIN", "skip" }, { "RECOMP_MUTE", "0" }, { "RECOMP_WINDOW", "1" },
    { "RECOMP_FULLSCREEN", "1" },
    { "SDL_ANDROID_TRAP_BACK_BUTTON", "1" }, { "SDL_ACCELEROMETER_AS_JOYSTICK", "0" },
};

/* Set when the library is loaded, before the runtime's own constructors run
 * (priority 101 comes first): some of them read these, and on a computer the
 * launcher has set them before the engine starts. Anything already set wins. */
__attribute__((constructor(101))) static void android_env(void)
{
    size_t i;
    for (i = 0; i < sizeof k_env / sizeof k_env[0]; i++)
        setenv(k_env[i][0], k_env[i][1], 0);
}

struct host_call { int (*host)(void); int rc; };

static void *host_thread(void *p)
{
    struct host_call *c = (struct host_call *)p;
    c->rc = c->host();
    return NULL;
}

int nv_android_main(int argc, char **argv, int (*host)(void));
int nv_android_main(int argc, char **argv, int (*host)(void))
{
    const char *game  = argc > 1 ? argv[1] : NULL;
    const char *data  = argc > 2 ? argv[2] : NULL;
    const char *cache = argc > 3 ? argv[3] : NULL;
    char save[1024], cfg[1024], scale[8];
    struct host_call call;
    pthread_attr_t a;
    pthread_t t;

    if (!game || !data) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "started without the game and data folders");
        return 1;
    }
    open_log(data);
    snprintf(save, sizeof save, "%s/save", data);
    snprintf(cfg, sizeof cfg, "%s/settings", data);
    mkdir_p(save);
    { int s = cfg_get_int(cfg, "scale", 2);
      snprintf(scale, sizeof scale, "%d", s >= 1 && s <= 4 ? s : 2);
      setenv("RECOMP_GL_SCALE", scale, 0); }
    setenv("JSRF_GAME_DIR", game, 1);
    setenv("JSRF_SAVE_DIR", save, 1);
    setenv("JSRF_SETTINGS_FILE", cfg, 1);
    if (cache) setenv("TMPDIR", cache, 1);
    if (chdir(game) != 0)
        fprintf(stderr, "[ANDROID] cannot enter %s: %s\n", game, strerror(errno));
    fprintf(stderr, "[ANDROID] JSRF Unleashed %s\n[ANDROID] game: %s\n[ANDROID] data: %s\n",
            JSRF_VERSION, game, data);

    call.host = host;
    call.rc = 1;
    pthread_attr_init(&a);
    pthread_attr_setstacksize(&a, 16u << 20);
    if (pthread_create(&t, &a, host_thread, &call) != 0) {
        fprintf(stderr, "[ANDROID] no thread for the engine\n");
        pthread_attr_destroy(&a);
        return 1;
    }
    pthread_attr_destroy(&a);
    pthread_join(t, NULL);
    fprintf(stderr, "[ANDROID] the engine returned %d\n", call.rc);
    return call.rc;
}

/* ---- the on-screen controls (JsrfActivity's TouchPadView) ---------------- */

/* What the controls hold: the keyboard's bits (Start, Back, and the d-pad
 * the stick gives, which menus read), the buttons as analog values (A B X Y
 * Black White LT RT), the stick, and whether the controls are showing. */
static volatile unsigned short g_touch_buttons;
static volatile unsigned char  g_touch_analog[8];
static volatile short g_touch_x, g_touch_y;
static volatile int   g_touch_stick, g_touch_shown;

JNIEXPORT void JNICALL
Java_org_libsdl_app_JSRFActivity_nativeTouch(JNIEnv *env, jclass cls, jint buttons,
                                                       jint analog, jint x, jint y,
                                                       jboolean stick, jboolean shown)
{
    int i;
    (void)env; (void)cls;
    for (i = 0; i < 8; i++) g_touch_analog[i] = ((analog >> i) & 1) ? 255 : 0;
    g_touch_x = (short)x;
    g_touch_y = (short)y;
    g_touch_stick = stick ? 1 : 0;
    g_touch_buttons = (unsigned short)buttons;
    g_touch_shown = shown ? 1 : 0;
}

/* Merged in by nv2a_gl.c's nv_window_keys; 1 while the controls show. */
int nv_window_keys(unsigned short *buttons, unsigned char *analog);
int nv_window_keys(unsigned short *buttons, unsigned char *analog)
{
    int i, any = g_touch_shown || g_touch_buttons;
    *buttons = g_touch_buttons;
    for (i = 0; i < 8; i++) {
        analog[i] = g_touch_analog[i];
        if (analog[i]) any = 1;
    }
    return any;
}

/* xinput_device.c: the on-screen stick moves the left stick, while a thumb
 * is on it (this replaces its weak "none"). */
int nv_window_touch_stick(short *x, short *y);
int nv_window_touch_stick(short *x, short *y)
{
    if (!g_touch_stick) return 0;
    *x = g_touch_x;
    *y = g_touch_y;
    return 1;
}

/* The settings: widescreen now (by the window's thread, as F10 does it). */
int  nv2a_widescreen(void);
void nv2a_set_widescreen(int on);

JNIEXPORT jboolean JNICALL
Java_org_libsdl_app_JSRFActivity_nativeWidescreen(JNIEnv *env, jclass cls)
{
    (void)env; (void)cls;
    return nv2a_widescreen() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_libsdl_app_JSRFActivity_nativeSetWidescreen(JNIEnv *env, jclass cls, jboolean on)
{
    (void)env; (void)cls;
    nv2a_set_widescreen(on ? 1 : 0);
}

JNIEXPORT jstring JNICALL
Java_org_libsdl_app_JSRFActivity_nativeVersion(JNIEnv *env, jclass cls)
{
    (void)cls;
    return (*env)->NewStringUTF(env, JSRF_VERSION);
}

