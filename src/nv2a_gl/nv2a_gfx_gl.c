/*
 * The NV2A's back half, in OpenGL 3.3 core.
 *
 * Everything in here was in nv2a_gl.c until the Metal backend arrived, and
 * moved rather than changed: the same calls, the same state caching, the same
 * reasons, now behind the device interface in nv2a_backend.h so that the other
 * API can stand beside it. The front half (nv2a_gl.c) still owns every cache
 * and every decision; this file owns the GL objects.
 *
 * The context is not made here. nv2a_gl.c brings one up (EGL headless, SDL on
 * Linux, CGL on a Mac), makes it current on the pushbuffer thread, and only
 * then calls init(). Presenting to a window stays there too, because it is a
 * property of how the context was made.
 */

#include "nv2a_backend.h"

#define GL_GLEXT_PROTOTYPES 1
#if defined(NV2A_GL_GLES)
#  include <GLES3/gl32.h>
#  define glClearDepth glClearDepthf
#  ifndef GL_DEPTH_CLAMP
#    define GL_DEPTH_CLAMP 0x864F
#  endif
#elif defined(NV2A_GL_USE_CGL) || (defined(NV2A_GL_USE_SDL) && defined(__APPLE__))
#  include <OpenGL/gl3.h>
#  include <OpenGL/gl3ext.h>
#elif defined(_WIN32)
#  include <epoxy/gl.h>     /* opengl32.dll stops at 1.1: see nv2a_gl.c */
#else
#  include <GL/gl.h>
#  include <GL/glext.h>
#endif

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Texture units 0..3 are the combiner stages. Creating or reading a texture
 * binds it somewhere, and it used to be wherever the active unit happened to
 * be -- which is how an upload on stage 1 once rebound stage 0 for that draw.
 * A unit no shader samples keeps that out of the way. */
#define SCRATCH_UNIT 7

/* ---- render targets ------------------------------------------------------ */

#define MAX_TARGETS 64
static struct {
    GLuint   fbo, tex, rb;
    uint32_t pw, ph;
    int      used;
} g_rt[MAX_TARGETS];
static uint32_t g_cur_rt;           /* handle bound for drawing, 0 none */
static GLuint   g_vao, g_vbo;
static GLuint   g_copy_tex;         /* target_snapshot()'s copy */
static uint32_t g_copy_w, g_copy_h;
static int      g_copies;

static int rt_ok(uint32_t h) { return h && h <= MAX_TARGETS && g_rt[h - 1].used; }

static void scratch_bind(GLuint tex)
{
    glActiveTexture(GL_TEXTURE0 + SCRATCH_UNIT);
    glBindTexture(GL_TEXTURE_2D, tex);
}

static uint32_t glx_target_new(uint32_t pw, uint32_t ph)
{
    int i;
    for (i = 0; i < MAX_TARGETS; i++) if (!g_rt[i].used) break;
    if (i == MAX_TARGETS) {
        fprintf(stderr, "  [GL] out of render targets\n");
        return 0;
    }
    glGenFramebuffers(1, &g_rt[i].fbo);
    glGenTextures(1, &g_rt[i].tex);
    glGenRenderbuffers(1, &g_rt[i].rb);
    scratch_bind(g_rt[i].tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, (GLsizei)pw, (GLsizei)ph, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindRenderbuffer(GL_RENDERBUFFER, g_rt[i].rb);
    /* With a stencil: the title keeps its shadow counts there. */
    glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8,
                          (GLsizei)pw, (GLsizei)ph);
    glBindFramebuffer(GL_FRAMEBUFFER, g_rt[i].fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_2D, g_rt[i].tex, 0);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT,
                              GL_RENDERBUFFER, g_rt[i].rb);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        fprintf(stderr, "  [GL] framebuffer incomplete at %ux%u\n", pw, ph);
        glDeleteFramebuffers(1, &g_rt[i].fbo);
        glDeleteTextures(1, &g_rt[i].tex);
        glDeleteRenderbuffers(1, &g_rt[i].rb);
        if (rt_ok(g_cur_rt))
            glBindFramebuffer(GL_FRAMEBUFFER, g_rt[g_cur_rt - 1].fbo);
        return 0;
    }
    g_rt[i].pw = pw; g_rt[i].ph = ph; g_rt[i].used = 1;
    /* Creating it bound it; the target being drawn into stays the target. */
    if (rt_ok(g_cur_rt))
        glBindFramebuffer(GL_FRAMEBUFFER, g_rt[g_cur_rt - 1].fbo);
    return (uint32_t)i + 1;
}

static void glx_target_free(uint32_t h)
{
    if (!rt_ok(h)) return;
    glDeleteFramebuffers(1, &g_rt[h - 1].fbo);
    glDeleteTextures(1, &g_rt[h - 1].tex);
    glDeleteRenderbuffers(1, &g_rt[h - 1].rb);
    memset(&g_rt[h - 1], 0, sizeof g_rt[0]);
    if (g_cur_rt == h) g_cur_rt = 0;
}

static void glx_target_bind(uint32_t h)
{
    if (!rt_ok(h)) return;
    glBindFramebuffer(GL_FRAMEBUFFER, g_rt[h - 1].fbo);
    glViewport(0, 0, (GLsizei)g_rt[h - 1].pw, (GLsizei)g_rt[h - 1].ph);
    g_cur_rt = h;
}

static uint32_t glx_target_texture(uint32_t h)
{
    return rt_ok(h) ? g_rt[h - 1].tex : 0;
}

/* Sampling the surface being drawn into: GL forbids reading a texture that is
 * attached to the framebuffer being written (a feedback loop, undefined), so
 * the picture as it stands is copied out first and the copy is sampled. */
static uint32_t glx_target_snapshot(uint32_t h)
{
    uint32_t pw, ph;
    if (!rt_ok(h)) return 0;
    pw = g_rt[h - 1].pw; ph = g_rt[h - 1].ph;
    if (!g_copy_tex) glGenTextures(1, &g_copy_tex);
    scratch_bind(g_copy_tex);
    if (g_copy_w != pw || g_copy_h != ph) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, (GLsizei)pw, (GLsizei)ph, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, NULL);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        g_copy_w = pw; g_copy_h = ph;
    }
    glBindFramebuffer(GL_READ_FRAMEBUFFER, g_rt[h - 1].fbo);
    glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, (GLsizei)pw, (GLsizei)ph);
    if (rt_ok(g_cur_rt))
        glBindFramebuffer(GL_FRAMEBUFFER, g_rt[g_cur_rt - 1].fbo);
    g_copies++;
    return g_copy_tex;
}

static int glx_target_read(uint32_t h, uint32_t x, uint32_t y, uint32_t w,
                           uint32_t ht, int bgra, void *out)
{
    if (!rt_ok(h) || !out) return 0;
    glBindFramebuffer(GL_READ_FRAMEBUFFER, g_rt[h - 1].fbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
#if defined(NV2A_GL_GLES)
    if (bgra) {
        size_t pixels = (size_t)w * (size_t)ht;
        uint8_t *rgba;
        size_t i;
        if ((ht && pixels / (size_t)ht != (size_t)w) || pixels > SIZE_MAX / 4u) {
            if (rt_ok(g_cur_rt))
                glBindFramebuffer(GL_READ_FRAMEBUFFER, g_rt[g_cur_rt - 1].fbo);
            return 0;
        }
        rgba = (uint8_t *)malloc(pixels * 4u);
        if (!rgba) {
            if (rt_ok(g_cur_rt))
                glBindFramebuffer(GL_READ_FRAMEBUFFER, g_rt[g_cur_rt - 1].fbo);
            return 0;
        }
        glReadPixels((GLint)x, (GLint)y, (GLsizei)w, (GLsizei)ht,
                     GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        for (i = 0; i < pixels; i++) {
            ((uint8_t *)out)[i * 4u + 0] = rgba[i * 4u + 2];
            ((uint8_t *)out)[i * 4u + 1] = rgba[i * 4u + 1];
            ((uint8_t *)out)[i * 4u + 2] = rgba[i * 4u + 0];
            ((uint8_t *)out)[i * 4u + 3] = rgba[i * 4u + 3];
        }
        free(rgba);
    } else {
        glReadPixels((GLint)x, (GLint)y, (GLsizei)w, (GLsizei)ht,
                     GL_RGBA, GL_UNSIGNED_BYTE, out);
    }
#else
    glReadPixels((GLint)x, (GLint)y, (GLsizei)w, (GLsizei)ht,
                 bgra ? GL_BGRA : GL_RGBA,
                 bgra ? GL_UNSIGNED_INT_8_8_8_8_REV : GL_UNSIGNED_BYTE, out);
#endif
    if (rt_ok(g_cur_rt))
        glBindFramebuffer(GL_READ_FRAMEBUFFER, g_rt[g_cur_rt - 1].fbo);
    return 1;
}

static int glx_target_read_depth(uint32_t h, uint32_t pw, uint32_t ph, float *out)
{
    if (!rt_ok(h) || !out) return 0;
    glBindFramebuffer(GL_READ_FRAMEBUFFER, g_rt[h - 1].fbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, (GLsizei)pw, (GLsizei)ph, GL_DEPTH_COMPONENT, GL_FLOAT, out);
    if (rt_ok(g_cur_rt))
        glBindFramebuffer(GL_READ_FRAMEBUFFER, g_rt[g_cur_rt - 1].fbo);
    return 1;
}

/* ---- textures ------------------------------------------------------------ */

static uint32_t glx_tex_new(uint32_t w, uint32_t h, const void *rgba,
                            const Nv2aSampler *s)
{
    static const GLint wrap_of[3] = { GL_CLAMP_TO_EDGE, GL_REPEAT, GL_MIRRORED_REPEAT };
    GLuint t = 0;
    GLenum mn, mg;

    glGenTextures(1, &t);
    if (!t) return 0;
    scratch_bind(t);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, (GLsizei)w, (GLsizei)h, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    mg = s->mag_linear ? GL_LINEAR : GL_NEAREST;
    if (s->mip) {
        glGenerateMipmap(GL_TEXTURE_2D);
        mn = s->mip == 1 ? (s->min_linear ? GL_LINEAR_MIPMAP_NEAREST : GL_NEAREST_MIPMAP_NEAREST)
                         : (s->min_linear ? GL_LINEAR_MIPMAP_LINEAR : GL_NEAREST_MIPMAP_LINEAR);
        if (s->aniso > 1) {
            /* Asked once: a driver without the extension flags the query as
             * an error, and every upload repeating it would pin errors on
             * the draws the GL error check looks at. */
            static GLfloat cap = -1.0f;
            GLfloat a = (GLfloat)s->aniso;
            if (cap < 0.0f) { cap = 1.0f; glGetFloatv(0x84FF, &cap); }   /* MAX_TEXTURE_MAX_ANISOTROPY */
            if (a > cap) a = cap;
            if (a > 1.0f) glTexParameterf(GL_TEXTURE_2D, 0x84FE, a);
        }
    } else {
        mn = s->min_linear ? GL_LINEAR : GL_NEAREST;
    }
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, (GLint)mn);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, (GLint)mg);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap_of[s->wrap_s < 3 ? s->wrap_s : 0]);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap_of[s->wrap_t < 3 ? s->wrap_t : 0]);
    return t;
}

static void glx_tex_free(uint32_t t)
{
    GLuint n = t;
    if (n) glDeleteTextures(1, &n);
}

static int glx_tex_read(uint32_t t, uint32_t w, uint32_t h, void *bgra)
{
    if (!t || !bgra) return 0;
#if defined(NV2A_GL_GLES)
    {
        GLuint fbo = 0;
        GLint old_fbo = 0;
        size_t pixels = (size_t)w * (size_t)h;
        uint8_t *rgba;
        size_t i;
        if ((h && pixels / (size_t)h != (size_t)w) || pixels > SIZE_MAX / 4u)
            return 0;
        rgba = (uint8_t *)malloc(pixels * 4u);
        if (!rgba) return 0;
        glGetIntegerv(GL_FRAMEBUFFER_BINDING, &old_fbo);
        glGenFramebuffers(1, &fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                               GL_TEXTURE_2D, (GLuint)t, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            glBindFramebuffer(GL_FRAMEBUFFER, (GLuint)old_fbo);
            glDeleteFramebuffers(1, &fbo);
            free(rgba);
            return 0;
        }
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, (GLsizei)w, (GLsizei)h, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        for (i = 0; i < pixels; i++) {
            ((uint8_t *)bgra)[i * 4u + 0] = rgba[i * 4u + 2];
            ((uint8_t *)bgra)[i * 4u + 1] = rgba[i * 4u + 1];
            ((uint8_t *)bgra)[i * 4u + 2] = rgba[i * 4u + 0];
            ((uint8_t *)bgra)[i * 4u + 3] = rgba[i * 4u + 3];
        }
        glBindFramebuffer(GL_FRAMEBUFFER, (GLuint)old_fbo);
        glDeleteFramebuffers(1, &fbo);
        free(rgba);
    }
#else
    (void)w; (void)h;
    scratch_bind(t);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glGetTexImage(GL_TEXTURE_2D, 0, GL_BGRA, GL_UNSIGNED_BYTE, bgra);
#endif
    return 1;
}

/* ---- programs and uniforms ----------------------------------------------- */

#define MAX_PROGS 4096
static struct {
    GLuint prog;
    GLint  loc[NV2A_U_COUNT];
    int    used;
} *g_prog;
static uint32_t g_cur_prog;      /* handle in use, 0 none */

static GLuint compile(GLenum stage, const char *src, const char *what)
{
    GLuint s = glCreateShader(stage);
    GLint ok = 0;
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[4096];
        GLsizei n = 0;
        glGetShaderInfoLog(s, sizeof log, &n, log);
        fprintf(stderr, "  [GL] %s failed to compile:\n%.*s\n", what, (int)n, log);
        if (getenv("RECOMP_GL_DUMP_SHADER"))
            fprintf(stderr, "----- source -----\n%s\n------------------\n", src);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

static uint32_t glx_prog_new(const char *vsrc, const char *fsrc, const char *what)
{
    GLuint vs, fs, prog;
    GLint ok = 0;
    int i, id;

    if (!g_prog) {
        g_prog = calloc(MAX_PROGS, sizeof *g_prog);
        if (!g_prog) return 0;
    }
    for (i = 0; i < MAX_PROGS; i++) if (!g_prog[i].used) break;
    if (i == MAX_PROGS) { fprintf(stderr, "  [GL] out of program slots\n"); return 0; }

    vs = compile(GL_VERTEX_SHADER, vsrc, what);
    if (!vs) return 0;
    fs = compile(GL_FRAGMENT_SHADER, fsrc, what);
    if (!fs) { glDeleteShader(vs); return 0; }
    prog = glCreateProgram();
    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    glLinkProgram(prog);
    glGetProgramiv(prog, GL_LINK_STATUS, &ok);
    glDeleteShader(vs);
    glDeleteShader(fs);
    if (!ok) {
        char log[4096]; GLsizei n = 0;
        glGetProgramInfoLog(prog, sizeof log, &n, log);
        fprintf(stderr, "  [GL] %s: link failed:\n%.*s\n", what, (int)n, log);
        glDeleteProgram(prog);
        return 0;
    }
    g_prog[i].prog = prog;
    g_prog[i].used = 1;
    /* Where this program keeps each uniform, looked up once. A name looked
     * up in one place and not another is a uniform that silently stays at
     * zero, so there is exactly one list: nv2a_uniform_desc. */
    for (id = 0; id < NV2A_U_COUNT; id++)
        g_prog[i].loc[id] = glGetUniformLocation(prog, nv2a_uniform_desc[id].name);
    return (uint32_t)i + 1;
}

static int prog_ok(uint32_t h) { return g_prog && h && h <= MAX_PROGS && g_prog[h - 1].used; }

static void glx_prog_free(uint32_t h)
{
    if (!prog_ok(h)) return;
    glDeleteProgram(g_prog[h - 1].prog);
    g_prog[h - 1].used = 0;
    g_prog[h - 1].prog = 0;
    if (g_cur_prog == h) g_cur_prog = 0;
}

static int glx_prog_uses(uint32_t h, int id)
{
    return prog_ok(h) && id >= 0 && id < NV2A_U_COUNT && g_prog[h - 1].loc[id] >= 0;
}

static void glx_prog_use(uint32_t h)
{
    if (!prog_ok(h)) return;
    glUseProgram(g_prog[h - 1].prog);
    g_cur_prog = h;
}

static void glx_uniform_f(int id, const float *v, int n)
{
    GLint loc;
    if (!prog_ok(g_cur_prog) || id < 0 || id >= NV2A_U_COUNT) return;
    loc = g_prog[g_cur_prog - 1].loc[id];
    if (loc < 0) return;
    switch (nv2a_uniform_desc[id].type) {
    case NV2A_UT_FLOAT: glUniform1fv(loc, n, v); break;
    case NV2A_UT_VEC2:  glUniform2fv(loc, n, v); break;
    case NV2A_UT_VEC3:  glUniform3fv(loc, n, v); break;
    case NV2A_UT_VEC4:  glUniform4fv(loc, n, v); break;
    case NV2A_UT_MAT2:  glUniformMatrix2fv(loc, n, GL_FALSE, v); break;
    default: break;
    }
}

static void glx_uniform_i(int id, int v)
{
    GLint loc;
    if (!prog_ok(g_cur_prog) || id < 0 || id >= NV2A_U_COUNT) return;
    loc = g_prog[g_cur_prog - 1].loc[id];
    if (loc >= 0) glUniform1i(loc, v);
}

static void glx_texture(int unit, uint32_t tex)
{
    glActiveTexture((GLenum)(GL_TEXTURE0 + unit));
    glBindTexture(GL_TEXTURE_2D, (GLuint)tex);
}

/* ---- state --------------------------------------------------------------- */

/* Last state actually pushed to GL.
 *
 * A title issues thousands of small batches a frame and changes almost
 * nothing between them, so the honest cost of a draw here is not the
 * triangles, it is the hundred redundant GL calls around them: sixteen
 * attribute pointers, three kilobytes of constants and half a dozen state
 * toggles, per draw, all identical to the last one. Remembering what was set
 * turns that into a handful of calls. */
static struct {
    uint32_t layout;        /* which arrays are enabled, and where each sits */
    uint32_t const_mask;    /* attributes currently set as constants */
    float    const_attr[16][4];   /* and the values they were set to */
    int      depth_test, depth_func, depth_mask;
    int      blend_enable, blend_src, blend_dst;
    int      valid;
} g_last;
static int g_last_cm_reset;        /* a clear forced the colour mask on */
static int g_last_stencil_reset;   /* a clear changed the stencil write mask */
static int g_last_clamp = -1;      /* GL_DEPTH_CLAMP as last set */

static GLenum gl_blend(uint32_t f)
{
    switch (f) {
    case 0x0000: return GL_ZERO;
    case 0x0001: return GL_ONE;
    case 0x0300: return GL_SRC_COLOR;
    case 0x0301: return GL_ONE_MINUS_SRC_COLOR;
    case 0x0302: return GL_SRC_ALPHA;
    case 0x0303: return GL_ONE_MINUS_SRC_ALPHA;
    case 0x0304: return GL_DST_ALPHA;
    case 0x0305: return GL_ONE_MINUS_DST_ALPHA;
    case 0x0306: return GL_DST_COLOR;
    case 0x0307: return GL_ONE_MINUS_DST_COLOR;
    case 0x0308: return GL_SRC_ALPHA_SATURATE;
    default:     return GL_ONE;
    }
}

static GLenum gl_depth_func(uint32_t f)
{
    switch (f) {
    case 0x0200: return GL_NEVER;   case 0x0201: return GL_LESS;
    case 0x0202: return GL_EQUAL;   case 0x0203: return GL_LEQUAL;
    case 0x0204: return GL_GREATER; case 0x0205: return GL_NOTEQUAL;
    case 0x0206: return GL_GEQUAL;  case 0x0207: return GL_ALWAYS;
    default:     return GL_LEQUAL;
    }
}

/* NV097_SET_STENCIL_OP_V_*: OpenGL's own values (INCRSAT and DECRSAT are
 * GL_INCR and GL_DECR, which saturate). Anything else keeps. */
static GLenum gl_stencil_op(uint32_t v)
{
    switch (v) {
    case 0x0000: case 0x1E00: case 0x1E01: case 0x1E02: case 0x1E03:
    case 0x150A: case 0x8507: case 0x8508:
        return (GLenum)v;
    default:
        return GL_KEEP;
    }
}

static void glx_state_invalidate(void)
{
    g_last.depth_test = g_last.depth_func = g_last.depth_mask = -1;
    g_last.blend_enable = g_last.blend_src = g_last.blend_dst = -1;
    g_last_cm_reset = 1;
    g_last_stencil_reset = 1;
    g_last_clamp = -1;
}

/* The per-draw state, still compared against what was last set rather than
 * set outright: these are the calls a title makes thousands of times a frame
 * with the same values, and OpenGL charges for each one. */
static void glx_state(const Nv2aRenderState *rs)
{
    if (rs->depth_test != g_last.depth_test || rs->depth_func != g_last.depth_func) {
        if (rs->depth_test) {
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(gl_depth_func((uint32_t)rs->depth_func));
        } else glDisable(GL_DEPTH_TEST);
        g_last.depth_test = rs->depth_test;
        g_last.depth_func = rs->depth_func;
    }
    if (rs->depth_write != g_last.depth_mask) {
        glDepthMask(rs->depth_write ? GL_TRUE : GL_FALSE);
        g_last.depth_mask = rs->depth_write;
    }
    /* Past the far plane: clamp or cull, as the title asked -- with
     * RECOMP_GL_DEPTHCLAMP=1/0 to force it either way for the experiment
     * that matters: does the distant road come back when nothing beyond the
     * far plane is thrown away. */
    { static int force = -2;
      int clamp;
      if (force == -2) { const char *v = getenv("RECOMP_GL_DEPTHCLAMP");
                         force = v ? atoi(v) : -1; }
      /* A w-buffered draw must not be clipped on the program's z either:
       * its depth comes from w in the fragment stage, and the console's
       * range test is against w, which this title's clip range never
       * rejects. Clamp instead, and let the fragment shader overwrite. */
      clamp = force >= 0 ? force
            : ((rs->zclamp & 0xF0u) || rs->w_buffer) ? 1 : 0;
      if (clamp != g_last_clamp) {
#if !defined(NV2A_GL_GLES)
          if (clamp) glEnable(GL_DEPTH_CLAMP); else glDisable(GL_DEPTH_CLAMP);
#endif
          g_last_clamp = clamp;
      } }
    { static uint32_t last_cm = 0xFFFFFFFFu;
      uint32_t cm = rs->color_mask ? rs->color_mask : 0x01010101u;
      if (g_last_cm_reset) { last_cm = 0xFFFFFFFFu; g_last_cm_reset = 0; }
      if (cm != last_cm) {
          glColorMask((cm >> 16) & 1 ? GL_TRUE : GL_FALSE,
                      (cm >>  8) & 1 ? GL_TRUE : GL_FALSE,
                      (cm      ) & 1 ? GL_TRUE : GL_FALSE,
                      (cm >> 24) & 1 ? GL_TRUE : GL_FALSE);
          last_cm = cm;
      } }
    { static int nob = -1;
      if (nob < 0) nob = getenv("RECOMP_GL_NOBLEND") ? 1 : 0;
      if (nob) { glDisable(GL_BLEND); g_last.blend_enable = -1; } }
    if (rs->blend_enable != g_last.blend_enable
     || rs->blend_src != g_last.blend_src || rs->blend_dst != g_last.blend_dst) {
        if (rs->blend_enable) {
            glEnable(GL_BLEND);
            glBlendFunc(gl_blend((uint32_t)rs->blend_src),
                        gl_blend((uint32_t)rs->blend_dst));
        } else glDisable(GL_BLEND);
        g_last.blend_enable = rs->blend_enable;
        g_last.blend_src = rs->blend_src;
        g_last.blend_dst = rs->blend_dst;
    }
    /*
     * Stencil.
     *
     * JSRF draws its shadows with stencil volumes: each shadow volume twice
     * with colour writes off (blend ZERO/ONE), depth GEQUAL, incrementing
     * the stencil on one pass and decrementing it on the other; then one
     * full-screen black quad at alpha ~0.98 that the stencil test lets
     * through only where the count is non-zero. The surfaces carry an 8-bit
     * stencil (depth24/stencil8) and the state is applied here.
     */
    { static int valid; static Nv2aRenderState last;
      if (g_last_stencil_reset) { valid = 0; g_last_stencil_reset = 0; }
      if (!valid || rs->stencil_test != last.stencil_test
       || (rs->stencil_test
           && (rs->stencil_func != last.stencil_func || rs->stencil_ref != last.stencil_ref
            || rs->stencil_rmask != last.stencil_rmask || rs->stencil_wmask != last.stencil_wmask
            || rs->stencil_op[0] != last.stencil_op[0] || rs->stencil_op[1] != last.stencil_op[1]
            || rs->stencil_op[2] != last.stencil_op[2]))) {
          if (rs->stencil_test) {
              uint32_t f = rs->stencil_func;
              if (f < 0x200 || f > 0x207) f = 0x207;           /* ALWAYS */
              glEnable(GL_STENCIL_TEST);
              glStencilFunc((GLenum)f, (GLint)(rs->stencil_ref & 0xFFu),
                            (GLuint)(rs->stencil_rmask & 0xFFu));
              glStencilOp(gl_stencil_op(rs->stencil_op[0]),
                          gl_stencil_op(rs->stencil_op[1]),
                          gl_stencil_op(rs->stencil_op[2]));
              glStencilMask((GLuint)(rs->stencil_wmask & 0xFFu));
          } else {
              glDisable(GL_STENCIL_TEST);
          }
          last = *rs; valid = 1;
      } }

    g_last.valid = 1;

    /*
     * Culling. RECOMP_GL_NOCULL turns it off, because "geometry is missing"
     * and "geometry that should be hidden is not" look nothing alike, and
     * having both pictures separates them in one run; RECOMP_GL_CULLFLIP
     * turns the winding over.
     *
     * The registers hold OpenGL's own enumerants: 0x900 is GL_CW, 0x901
     * GL_CCW, 0x404 GL_FRONT, 0x405 GL_BACK, 0x408 GL_FRONT_AND_BACK. The
     * NV2A is a GeForce 3 and its driver spoke GL, so the values pass
     * straight through -- the winding included: the transform mirrors y, but
     * the console's own viewport transform has the same negative scale, so
     * the hardware sees the reflected winding too and the register already
     * accounts for it. Correcting again culled the visible side of every
     * surface, which left the city as the dark insides of its own buildings.
     */
    {
        static int cull = -1, flip = -1;
        if (cull < 0) { cull = getenv("RECOMP_GL_NOCULL") ? 0 : 1;
                        flip = getenv("RECOMP_GL_CULLFLIP") ? 1 : 0; }
        { static uint32_t seen_en[2], seen_cf[8], seen_ff[4]; static uint64_t n;
          static int on = -2;
          if (on == -2) on = getenv("RECOMP_GL_CULL_MAP") ? 1 : 0;
          if (on) {
              seen_en[rs->cull_enable ? 1 : 0]++;
              seen_cf[(rs->cull_face - 0x404) & 7]++;
              seen_ff[(rs->front_face - 0x900) & 3]++;
              if (++n % 50000 == 0)
                  fprintf(stderr, "  [GL] cull: off %u on %u | face F%u B%u "
                          "FB%u other%u | front CW%u CCW%u other %u/%u\n",
                          seen_en[0], seen_en[1], seen_cf[0], seen_cf[1],
                          seen_cf[4], seen_cf[2] + seen_cf[3] + seen_cf[5]
                          + seen_cf[6] + seen_cf[7],
                          seen_ff[0], seen_ff[1], seen_ff[2], seen_ff[3]);
          } }
        if (!cull || !rs->cull_enable) {
            glDisable(GL_CULL_FACE);
        } else {
            int ccw = (rs->front_face == 0x901);
            glEnable(GL_CULL_FACE);
            if (flip) ccw = !ccw;
            glFrontFace(ccw ? GL_CCW : GL_CW);
            glCullFace(rs->cull_face == 0x404 ? GL_FRONT
                     : rs->cull_face == 0x408 ? GL_FRONT_AND_BACK : GL_BACK);
        }
    }

    /* The window clip, as a scissor. The rectangle arrives top-down; GL's
     * run bottom-up, so the y is turned over against the target's height. */
    if (rs->scissor_enable && rt_ok(g_cur_rt)) {
        GLint ph = (GLint)g_rt[g_cur_rt - 1].ph;
        GLint gy = ph - (GLint)rs->scissor[3];
        if (gy < 0) gy = 0;
        glEnable(GL_SCISSOR_TEST);
        glScissor((GLint)rs->scissor[0], gy,
                  (GLsizei)(rs->scissor[2] - rs->scissor[0]),
                  (GLsizei)(rs->scissor[3] - rs->scissor[1]));
    } else {
        glDisable(GL_SCISSOR_TEST);
    }
}

/* ---- vertices and draws -------------------------------------------------- */

static void glx_vertices(const float *v, uint32_t nverts, uint32_t stride_floats,
                         const int attr_at[16], uint32_t const_mask,
                         const float const_attr[16][4])
{
    uint32_t layout_key, i;

    glBindVertexArray(g_vao);
    glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
    glBufferData(GL_ARRAY_BUFFER,
                 (GLsizeiptr)nverts * stride_floats * sizeof(float),
                 v, GL_STREAM_DRAW);
    /* The buffer is re-created every draw, so the pointers have to be re-
     * specified only when the layout changes -- the offsets and stride are a
     * property of the layout, not of the buffer. The layout is the set of
     * attributes that came from arrays and where each one sits, plus which
     * are constants: the same program drawn twice with a different attribute
     * supplied as a constant is a different layout. */
    layout_key = stride_floats * 2654435761u;
    for (i = 0; i < 16; i++)
        layout_key = layout_key * 17u + (uint32_t)(attr_at[i] + 1);
    layout_key = layout_key * 65599u + const_mask;
    if (!g_last.valid || g_last.layout != layout_key) {
        for (i = 0; i < 16; i++) {
            if (attr_at[i] >= 0) {
                glEnableVertexAttribArray(i);
                glVertexAttribPointer(i, 4, GL_FLOAT, GL_FALSE,
                    (GLsizei)(stride_floats * sizeof(float)),
                    (const void *)(uintptr_t)((uint32_t)attr_at[i] * sizeof(float)));
            } else {
                glDisableVertexAttribArray(i);
            }
        }
        g_last.layout = layout_key;
        g_last.valid = 1;
        g_last.const_mask = ~const_mask;   /* force the constants below */
    }
    /* An attribute the program reads with no array behind it is one value
     * for the whole draw. GL has a per-attribute constant for exactly this,
     * so it costs one call rather than four floats in every vertex. Values
     * are compared per attribute: the sticky attributes move with every
     * draw, and comparing the whole table re-sent every constant. */
    if (const_mask) {
        int all = (g_last.const_mask != const_mask);
        for (i = 0; i < 16; i++)
            if ((const_mask & (1u << i))
                && (all || memcmp(g_last.const_attr[i], const_attr[i],
                                  sizeof const_attr[i]))) {
                glVertexAttrib4fv(i, const_attr[i]);
                memcpy(g_last.const_attr[i], const_attr[i], sizeof const_attr[i]);
            }
        g_last.const_mask = const_mask;
    }
}

static GLenum gl_prim_of(int p)
{
    switch (p) {
    case NV2A_PRIM_POINTS:         return GL_POINTS;
    case NV2A_PRIM_LINES:          return GL_LINES;
    case NV2A_PRIM_LINE_LOOP:      return GL_LINE_LOOP;
    case NV2A_PRIM_LINE_STRIP:     return GL_LINE_STRIP;
    case NV2A_PRIM_TRIANGLE_STRIP: return GL_TRIANGLE_STRIP;
    case NV2A_PRIM_TRIANGLE_FAN:   return GL_TRIANGLE_FAN;
    default:                       return GL_TRIANGLES;
    }
}

static void glx_draw(int prim, uint32_t count)
{
    glDrawArrays(gl_prim_of(prim), 0, (GLsizei)count);
}

/* ---- clears -------------------------------------------------------------- */

static void glx_clear(unsigned mask, const float rgba[4], float depth,
                      uint32_t stencil, const uint32_t rect[4])
{
    GLbitfield bits = 0;

    if (mask & 1u) {
        glClearColor(rgba[0], rgba[1], rgba[2], rgba[3]);
        /* The console's clear picks its channels from the CLEAR_SURFACE mask
         * and ignores COLOR_MASK; GL's honours the write mask. Force it on
         * for the clear and let the next draw re-apply the title's. */
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        g_last_cm_reset = 1;
        bits |= GL_COLOR_BUFFER_BIT;
    }
    if (mask & 2u) {
        glClearDepth((double)depth);
        glDepthMask(GL_TRUE);
        g_last.depth_mask = -1;   /* the clear forced it; re-apply next draw */
        bits |= GL_DEPTH_BUFFER_BIT;
    }
    if (mask & 4u) {
        glClearStencil((GLint)(stencil & 0xFFu));
        glStencilMask(0xFFu);
        g_last_stencil_reset = 1; /* the write mask; re-apply next draw */
        bits |= GL_STENCIL_BUFFER_BIT;
    }
    if (!bits) return;

    /* A clear is bounded by the clear rectangle, not by the surface: titles
     * clear sub-rectangles constantly -- a letterbox bar, a panel behind some
     * text -- and ignoring the rectangle wipes the whole frame every time.
     * It arrives top-down; GL's scissor runs bottom-up. */
    if (rect && rt_ok(g_cur_rt)) {
        GLint ph = (GLint)g_rt[g_cur_rt - 1].ph;
        GLint x = (GLint)rect[0];
        GLint y = ph - (GLint)rect[3];
        GLsizei w = (GLsizei)rect[2] - (GLsizei)rect[0];
        GLsizei h = (GLsizei)rect[3] - (GLsizei)rect[1];
        if (y < 0) { h += y; y = 0; }
        glEnable(GL_SCISSOR_TEST);
        glScissor(x, y, w > 0 ? w : 0, h > 0 ? h : 0);
    } else {
        glDisable(GL_SCISSOR_TEST);
    }
    glClear(bits);
    glDisable(GL_SCISSOR_TEST);
}

/* ---- the rest ------------------------------------------------------------ */

static int glx_init(void)
{
    glGenVertexArrays(1, &g_vao);
    glGenBuffers(1, &g_vbo);
    glBindVertexArray(g_vao);
    glx_state_invalidate();
    return 1;
}

/* The window is fed by nv2a_gl.c, which knows how the context was made. */
static void glx_present(uint32_t rt, uint32_t pw, uint32_t ph)
{
    (void)rt; (void)pw; (void)ph;
}

static void glx_report(void)
{
    if (g_copies)
        fprintf(stderr, "  [GL] %d copies of a surface sampled while drawn into\n", g_copies);
}

/* For nv2a_gl.c's GL-only paths (the SDL window's blit, the occlusion-query
 * probe): the framebuffer object behind a target. */
unsigned nv2a_gfx_gl_fbo(uint32_t rt)
{
    return rt_ok(rt) ? g_rt[rt - 1].fbo : 0;
}

static const Nv2aGfx g_gl = {
    "opengl", NV2A_LANG_GLSL, 1,
    glx_init,
    glx_target_new, glx_target_free, glx_target_bind, glx_target_texture,
    glx_target_snapshot, glx_target_read, glx_target_read_depth,
    glx_tex_new, glx_tex_free, glx_tex_read,
    glx_prog_new, glx_prog_free, glx_prog_uses, glx_prog_use,
    glx_uniform_f, glx_uniform_i,
    glx_texture, glx_state, glx_state_invalidate,
    glx_vertices, glx_draw, glx_clear, glx_present, glx_report,
};

const Nv2aGfx *nv2a_gfx_gl(void) { return &g_gl; }
