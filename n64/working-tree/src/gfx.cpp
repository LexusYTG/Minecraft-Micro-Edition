#include "gfx.h"
#include <libdragon.h>
#include <string.h>
#include <math.h>

namespace gfx {

static const uint8_t FONT_ID = 1;
static rdpq_font_t* font = 0;

// modos del RDP: se cambian solo cuando hace falta
enum { M_NONE = 0, M_FILL, M_BLEND };
static int curMode = M_NONE;
static uint32_t curColor = 0xFFFFFFFFu;     // ultimo color puesto (RGBA empaquetado)
static int curAlpha = -1;

struct TCmd { short x, y; uint32_t c; bool bold; char s[72]; };
static const int MAXT = 400;
static TCmd tcs[MAXT];
static int tcn = 0;

static const float TEXT_BASE = 7.f;          // baseline respecto al borde superior del glifo (ajustar si el texto queda corrido)

static inline float cl(float v) { return v < -1000.f ? -1000.f : (v > 1000.f ? 1000.f : v); }
static inline color_t col(uint32_t rgb, int a) {
    return RGBA32((uint8_t)((rgb >> 16) & 0xFF), (uint8_t)((rgb >> 8) & 0xFF), (uint8_t)(rgb & 0xFF), (uint8_t)a);
}

void init() {
    display_init(RESOLUTION_320x240, DEPTH_16_BPP, 2, GAMMA_NONE, FILTERS_RESAMPLE);
    rdpq_init();
    font = rdpq_font_load_builtin(FONT_BUILTIN_DEBUG_MONO);
    rdpq_text_register_font(FONT_ID, font);
}

void shutdown() {
    rdpq_close();
    display_close();
}

void begin() {
    surface_t* fb = display_get();
    rdpq_attach(fb, NULL);
    curMode = M_NONE;
    curAlpha = -1;
    tcn = 0;
}

static void setFill(uint32_t rgb) {
    uint32_t key = rgb & 0xFFFFFF;
    if (curMode != M_FILL) {
        rdpq_set_mode_fill(col(rgb, 255));
        curMode = M_FILL;
    } else if (key != curColor) {
        rdpq_set_fill_color(col(rgb, 255));
    }
    curColor = key;
}

static void setBlend(uint32_t rgb, int a) {
    if (curMode != M_BLEND) {
        rdpq_set_mode_standard();
        rdpq_mode_combiner(RDPQ_COMBINER_FLAT);
        rdpq_mode_blender(RDPQ_BLENDER_MULTIPLY);
        curMode = M_BLEND;
        curAlpha = -1;
    }
    uint32_t key = rgb & 0xFFFFFF;
    if (key != curColor || a != curAlpha) rdpq_set_prim_color(col(rgb, a));
    curColor = key;
    curAlpha = a;
}

void clear(uint32_t rgb) {
    rdpq_clear(col(rgb, 255));
    curMode = M_NONE;
}

void end() {
    // texto encima de todo: la fuente usa su propio modo, asi que se reinicia el estado
    if (tcn > 0) {
        curMode = M_NONE;
        for (int i = 0; i < tcn; i++) {
            const TCmd& t = tcs[i];
            if (t.y < 0 || t.y + 8 > SCR_H) continue;
            rdpq_fontstyle_t st;
            memset(&st, 0, sizeof(st));
            st.color = col(t.c, 255);
            st.outline_color = RGBA32(0, 0, 0, 255);
            rdpq_font_style(font, 0, &st);
            rdpq_text_print(NULL, FONT_ID, (float)t.x, (float)t.y + TEXT_BASE, t.s);
            if (t.bold) rdpq_text_print(NULL, FONT_ID, (float)t.x + 1.f, (float)t.y + TEXT_BASE, t.s);
        }
    }
    rdpq_detach_show();
}

void tri(float x0, float y0, float x1, float y1, float x2, float y2, uint32_t rgb, int a) {
    if (a <= 0) return;
    float v0[2] = { cl(x0), cl(y0) }, v1[2] = { cl(x1), cl(y1) }, v2[2] = { cl(x2), cl(y2) };
    if (a >= 255) setFill(rgb); else setBlend(rgb, a);
    rdpq_triangle(&TRIFMT_FILL, v0, v1, v2);
}

void rect(int x, int y, int w, int h, uint32_t rgb, int a) {
    if (w <= 0 || h <= 0 || a <= 0) return;
    if (x >= SCR_W || y >= SCR_H || x + w <= 0 || y + h <= 0) return;
    int x0 = x < 0 ? 0 : x, y0 = y < 0 ? 0 : y;
    int x1 = x + w > SCR_W ? SCR_W : x + w, y1 = y + h > SCR_H ? SCR_H : y + h;
    if (a >= 255) setFill(rgb); else setBlend(rgb, a);
    rdpq_fill_rectangle(x0, y0, x1, y1);
}

void frame(int x, int y, int w, int h, uint32_t rgb) {
    rect(x, y, w, 1, rgb);
    rect(x, y + h - 1, w, 1, rgb);
    rect(x, y + 1, 1, h - 2, rgb);
    rect(x + w - 1, y + 1, 1, h - 2, rgb);
}

// linea de ~1 px como un quad fino
void line(float x0, float y0, float x1, float y1, uint32_t rgb) {
    x0 = cl(x0); y0 = cl(y0); x1 = cl(x1); y1 = cl(y1);
    float dx = x1 - x0, dy = y1 - y0;
    float len = sqrtf(dx * dx + dy * dy);
    float nx, ny;
    if (len < 0.001f) { nx = 0.5f; ny = 0.f; x1 = x0 + 1.f; y1 = y0; }
    else { nx = -dy / len * 0.5f; ny = dx / len * 0.5f; }
    tri(x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, rgb, 255);
    tri(x0 + nx, y0 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny, rgb, 255);
}

int textW(const char* s) { return (int)strlen(s) * 8; }

void text(const char* s, int x, int y, uint32_t rgb, int anchor) { textS(s, x, y, rgb, anchor, 1); }

void textS(const char* s, int x, int y, uint32_t rgb, int anchor, int scale) {
    if (tcn >= MAXT || !s[0]) return;
    int w = textW(s);
    int ha = anchor & 3;
    if (ha == TA_HCENTER) x -= w / 2;
    else if (ha == TA_RIGHT) x -= w;
    if (anchor & TA_BOTTOM) y -= FONT_H;
    TCmd& t = tcs[tcn++];
    t.x = (short)x; t.y = (short)y; t.c = rgb; t.bold = scale > 1;
    strncpy(t.s, s, sizeof(t.s) - 1);
    t.s[sizeof(t.s) - 1] = 0;
}

} // namespace gfx
