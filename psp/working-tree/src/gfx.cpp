#include "gfx.h"
#include <pspkernel.h>
#include <pspdisplay.h>
#include <pspdebug.h>
#include <pspgu.h>
#include <pspgum.h>
#include <pspge.h>
#include <malloc.h>
#include <string.h>
#include <math.h>

namespace gfx {

static const int BUF_W = 512;
static const int FRAME = BUF_W * 272 * 4;          // 0x88000

static unsigned int __attribute__((aligned(16))) dlist[65536];

struct Vtx { uint32_t c; float x, y, z; };          // GU_COLOR_8888 | GU_VERTEX_32BITF
static const int VTYPE = GU_COLOR_8888 | GU_VERTEX_32BITF | GU_TRANSFORM_3D;
static const int MAXV = 300000;                    // ~4.8 MB, se dibuja todo de una vez por frame
static Vtx* vb = 0;
static int vn = 0;
static void (*postHook)() = 0;

struct TCmd { short x, y; int sc; uint32_t c; char s[72]; };
static const int MAXT = 700;
static TCmd tcs[MAXT];
static int tcn = 0;

// Atlas de la fuente 8x8 de pspDebugScreen, copiado una vez a VRAM y dibujado con el GU
// (escribir texto con la CPU sobre el framebuffer no se ve en PPSSPP cuando el frame lo dibuja el GU).
static const int ATLAS_OFF = FRAME * 3;            // justo despues del depth buffer
static const int ATLAS_W = 512, ATLAS_H = 64;      // 16 columnas x 6 filas de glifos 8x8 (ASCII 32..127)
struct TVtx { float u, v; uint32_t c; float x, y, z; };   // GU_TEXTURE_32BITF | GU_COLOR_8888 | GU_VERTEX_32BITF
static const int TVTYPE = GU_TEXTURE_32BITF | GU_COLOR_8888 | GU_VERTEX_32BITF | GU_TRANSFORM_3D;
static const int MAXTV = 120000;
static TVtx* tvb = 0;

static inline uint32_t abgr(uint32_t rgb, int a) {
    return ((uint32_t)a << 24) | ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF);
}

static inline float cl(float v) { return v < -1500.f ? -1500.f : (v > 1500.f ? 1500.f : v); }

static inline void pv(float x, float y, uint32_t c) {
    Vtx& t = vb[vn++];
    t.c = c; t.x = x; t.y = y; t.z = 0.f;
}

void init() {
    vb = (Vtx*)memalign(16, MAXV * sizeof(Vtx));
    tvb = (TVtx*)memalign(16, MAXTV * sizeof(TVtx));

    sceGuInit();
    sceGuStart(GU_DIRECT, dlist);
    sceGuDrawBuffer(GU_PSM_8888, (void*)0, BUF_W);
    sceGuDispBuffer(SCR_W, SCR_H, (void*)FRAME, BUF_W);
    sceGuDepthBuffer((void*)(FRAME * 2), BUF_W);
    sceGuOffset(2048 - SCR_W / 2, 2048 - SCR_H / 2);
    sceGuDepthRange(65535, 0);
    sceGuViewport(2048, 2048, SCR_W, SCR_H);
    sceGuScissor(0, 0, SCR_W, SCR_H);
    sceGuEnable(GU_SCISSOR_TEST);
    sceGuDisable(GU_DEPTH_TEST);
    sceGuDisable(GU_CULL_FACE);
    sceGuDisable(GU_TEXTURE_2D);
    sceGuEnable(GU_BLEND);
    sceGuBlendFunc(GU_ADD, GU_SRC_ALPHA, GU_ONE_MINUS_SRC_ALPHA, 0, 0);
    sceGuFinish();
    sceGuSync(0, 0);
    sceDisplayWaitVblankStart();
    sceGuDisplay(GU_TRUE);

    pspDebugScreenInitEx(NULL, PSP_DISPLAY_PIXEL_FORMAT_8888, 0);   // 0 = no tocar el modo de video
    pspDebugScreenEnableBackColor(0);                                // texto sin fondo

    // volcar los glifos al atlas en VRAM (fondo transparente, glifo blanco)
    uint32_t vramU = (uint32_t)sceGeEdramGetAddr() | 0x40000000;
    memset((void*)(vramU + ATLAS_OFF), 0, ATLAS_W * ATLAS_H * 4);
    pspDebugScreenSetOffset(ATLAS_OFF);
    for (int i = 0; i < 96; i++)
        pspDebugScreenPutChar((i % 16) * 8, (i / 16) * 8, 0xFFFFFFFFu, 32 + i);
}

void shutdown() {
    sceGuTerm();
    if (vb) { free(vb); vb = 0; }
    if (tvb) { free(tvb); tvb = 0; }
}

void begin() {
    vn = 0;
    tcn = 0;
    sceGuStart(GU_DIRECT, dlist);
    sceGumMatrixMode(GU_PROJECTION);
    sceGumLoadIdentity();
    sceGumOrtho(0.f, (float)SCR_W, (float)SCR_H, 0.f, -1.f, 1.f);
    sceGumMatrixMode(GU_VIEW);
    sceGumLoadIdentity();
    sceGumMatrixMode(GU_MODEL);
    sceGumLoadIdentity();
    sceGumUpdateMatrix();                       // sceGuDrawArray no sube las matrices de Gum por si solo
}

void clear(uint32_t rgb) {
    sceGuClearColor(abgr(rgb, 255));
    sceGuClear(GU_COLOR_BUFFER_BIT);
}

void end() {
    if (vn > 0) {
        sceKernelDcacheWritebackRange(vb, vn * sizeof(Vtx));
        int done = 0;
        while (done < vn) {
            int n = vn - done;
            if (n > 65532) n = 65532;               // multiplo de 3, limite de 16 bits del GE
            sceGuDrawArray(GU_TRIANGLES, VTYPE, n, 0, vb + done);
            done += n;
        }
    }
    // texto: quads texturizados con el atlas, encima de todo
    if (tcn > 0) {
        int n = 0;
        for (int i = 0; i < tcn; i++) {
            const TCmd& t = tcs[i];
            int g = 8 * t.sc;
            if (t.y < 0 || t.y + g > SCR_H) continue;
            int x = t.x;
            for (const char* p = t.s; *p; p++, x += g) {
                int ch = (unsigned char)*p;
                if (ch == ' ') continue;
                if (ch < 32 || ch > 127) ch = '?';
                if (x < 0 || x + g > SCR_W || n + 6 > MAXTV) continue;
                int idx = ch - 32;
                float u0 = (float)((idx % 16) * 8) / ATLAS_W, u1 = u0 + 8.f / ATLAS_W;
                float v0 = (float)((idx / 16) * 8) / ATLAS_H, v1 = v0 + 8.f / ATLAS_H;
                float x0 = (float)x, y0 = (float)t.y, x1 = x0 + (float)g, y1 = y0 + (float)g;
                TVtx* q = tvb + n;
                q[0].u = u0; q[0].v = v0; q[0].x = x0; q[0].y = y0;
                q[1].u = u1; q[1].v = v0; q[1].x = x1; q[1].y = y0;
                q[2].u = u1; q[2].v = v1; q[2].x = x1; q[2].y = y1;
                q[3] = q[0];
                q[4] = q[2];
                q[5].u = u0; q[5].v = v1; q[5].x = x0; q[5].y = y1;
                for (int k = 0; k < 6; k++) { q[k].c = t.c; q[k].z = 0.f; }
                n += 6;
            }
        }
        if (n > 0) {
            sceKernelDcacheWritebackRange(tvb, n * sizeof(TVtx));
            sceGuEnable(GU_TEXTURE_2D);
            sceGuTexMode(GU_PSM_8888, 0, 0, 0);
            sceGuTexImage(0, ATLAS_W, ATLAS_H, ATLAS_W, (void*)((uint32_t)sceGeEdramGetAddr() + ATLAS_OFF));
            sceGuTexFunc(GU_TFX_MODULATE, GU_TCC_RGBA);
            sceGuTexFilter(GU_NEAREST, GU_NEAREST);
            sceGuTexWrap(GU_CLAMP, GU_CLAMP);
            sceGuTexScale(1.f, 1.f);
            sceGuTexOffset(0.f, 0.f);
            sceGuTexFlush();
            int done = 0;
            while (done < n) {
                int m = n - done;
                if (m > 65532) m = 65532;
                sceGuDrawArray(GU_TRIANGLES, TVTYPE, m, 0, tvb + done);
                done += m;
            }
            sceGuDisable(GU_TEXTURE_2D);
        }
    }

    sceGuFinish();
    sceGuSync(0, 0);
    if (postHook) postHook();

    sceDisplayWaitVblankStart();
    sceGuSwapBuffers();
}

void setPostHook(void (*fn)()) { postHook = fn; }

void tri(float x0, float y0, float x1, float y1, float x2, float y2, uint32_t rgb, int a) {
    if (vn + 3 > MAXV) return;
    uint32_t c = abgr(rgb, a);
    pv(cl(x0), cl(y0), c); pv(cl(x1), cl(y1), c); pv(cl(x2), cl(y2), c);
}

void rect(int x, int y, int w, int h, uint32_t rgb, int a) {
    if (w <= 0 || h <= 0 || vn + 6 > MAXV) return;
    if (x >= SCR_W || y >= SCR_H || x + w <= 0 || y + h <= 0) return;
    uint32_t c = abgr(rgb, a);
    float x0 = (float)x, y0 = (float)y, x1 = (float)(x + w), y1 = (float)(y + h);
    pv(x0, y0, c); pv(x1, y0, c); pv(x1, y1, c);
    pv(x0, y0, c); pv(x1, y1, c); pv(x0, y1, c);
}

void frame(int x, int y, int w, int h, uint32_t rgb) {
    rect(x, y, w, 1, rgb);
    rect(x, y + h - 1, w, 1, rgb);
    rect(x, y + 1, 1, h - 2, rgb);
    rect(x + w - 1, y + 1, 1, h - 2, rgb);
}

// linea de ~1 px como un quad fino
void line(float x0, float y0, float x1, float y1, uint32_t rgb) {
    if (vn + 6 > MAXV) return;
    x0 = cl(x0); y0 = cl(y0); x1 = cl(x1); y1 = cl(y1);
    float dx = x1 - x0, dy = y1 - y0;
    float len = sqrtf(dx * dx + dy * dy);
    uint32_t c = abgr(rgb, 255);
    float nx, ny;
    if (len < 0.001f) { nx = 0.5f; ny = 0.f; x1 = x0 + 1.f; y1 = y0; }
    else { nx = -dy / len * 0.5f; ny = dx / len * 0.5f; }
    pv(x0 + nx, y0 + ny, c); pv(x1 + nx, y1 + ny, c); pv(x1 - nx, y1 - ny, c);
    pv(x0 + nx, y0 + ny, c); pv(x1 - nx, y1 - ny, c); pv(x0 - nx, y0 - ny, c);
}

int textW(const char* s) { return (int)strlen(s) * 8; }

void text(const char* s, int x, int y, uint32_t rgb, int anchor) { textS(s, x, y, rgb, anchor, 1); }

void textS(const char* s, int x, int y, uint32_t rgb, int anchor, int scale) {
    if (tcn >= MAXT || !s[0]) return;
    if (scale < 1) scale = 1;
    int w = textW(s) * scale;
    int h = anchor & TA_BOTTOM;
    int ha = anchor & 3;
    if (ha == TA_HCENTER) x -= w / 2;
    else if (ha == TA_RIGHT) x -= w;
    if (h) y -= FONT_H * scale;
    TCmd& t = tcs[tcn++];
    t.x = (short)x; t.y = (short)y; t.sc = scale; t.c = abgr(rgb, 255);
    strncpy(t.s, s, sizeof(t.s) - 1);
    t.s[sizeof(t.s) - 1] = 0;
}

} // namespace gfx
