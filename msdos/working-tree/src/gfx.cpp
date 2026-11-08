#include <string.h>
#include <stdlib.h>
#include <time.h>
#include <dpmi.h>
#include <go32.h>
#include <pc.h>
#include <sys/farptr.h>
#include <sys/movedata.h>
#include "gfx.h"

uint64_t ticksUs() { return (uint64_t)uclock() * 1000000ULL / 1193182ULL; }

static int sq[1025];

void fxInit() {
    for (int i = 0; i <= 1024; i++) {
        long long x = (long long)i * 1686629713LL / 1024;
        long long x2 = (x * x) >> 30;
        long long t = x, s = x;
        for (int k = 3; k <= 13; k += 2) {
            t = -((t * x2) >> 30) / ((long long)k * (k - 1));
            s += t;
        }
        sq[i] = (int)(s >> 14);
    }
}

static inline int sidx(int i) {
    int q = (i >> 10) & 3, r = i & 1023;
    switch (q) {
    case 0: return sq[r];
    case 1: return sq[1024 - r];
    case 2: return -sq[r];
    default: return -sq[1024 - r];
    }
}

static inline int aidx(Fx a) { return (int)(((long long)a.v * 42722827LL) >> 32); }

Fx fxsin(Fx a) { return Fx::raw(sidx(aidx(a))); }
Fx fxcos(Fx a) { return Fx::raw(sidx(aidx(a) + 1024)); }
Fx fxtan(Fx a) { return fxsin(a) / fxcos(a); }
Fx fxceil(Fx a) { return Fx::raw((a.v + 65535) & ~65535); }
Fx fxabs(Fx a) { return Fx::raw(a.v < 0 ? -a.v : a.v); }

Fx fxsqrt(Fx a) {
    if (a.v <= 0) return Fx::raw(0);
    unsigned long long n = (unsigned long long)a.v << 16, r = 0, bit = 1ULL << 46;
    while (bit > n) bit >>= 2;
    while (bit) {
        if (n >= r + bit) { n -= r + bit; r = (r >> 1) + bit; }
        else r >>= 1;
        bit >>= 2;
    }
    return Fx::raw((int)r);
}

Fx fxatan2(Fx y, Fx x) {
    int ax = x.v < 0 ? -x.v : x.v, ay = y.v < 0 ? -y.v : y.v;
    if (!ax && !ay) return Fx();
    Fx a;
    if (ax >= ay) {
        Fx z = Fx::raw((int)((((long long)ay) << 16) / ax));
        a = z * (Fx(0.7853982f) + Fx(0.273f) * (Fx(1) - z));
    } else {
        Fx z = Fx::raw((int)((((long long)ax) << 16) / ay));
        a = Fx(1.5707963f) - z * (Fx(0.7853982f) + Fx(0.273f) * (Fx(1) - z));
    }
    if (x.v < 0) a = Fx(3.1415927f) - a;
    if (y.v < 0) a = -a;
    return a;
}

namespace gfx {

static uint8_t fb[SCR_W * SCR_H];
static uint8_t prgb[256][3];
static uint8_t font[1024];

struct TCmd { short x, y; uint32_t c; bool bold; char s[72]; };
static const int MAXT = 400;
static TCmd tcs[MAXT];
static int tcn = 0;

static inline uint8_t ci(uint32_t rgb) {
    return (uint8_t)(((rgb >> 16) & 0xE0) | ((rgb >> 11) & 0x1C) | ((rgb >> 6) & 3));
}

void init() {
    __dpmi_regs r;
    memset(&r, 0, sizeof(r));
    r.x.ax = 0x13;
    __dpmi_int(0x10, &r);
    outportb(0x3C8, 0);
    for (int i = 0; i < 256; i++) {
        int rr = (i >> 5) & 7, gg = (i >> 2) & 7, bb = i & 3;
        outportb(0x3C9, rr * 63 / 7);
        outportb(0x3C9, gg * 63 / 7);
        outportb(0x3C9, bb * 63 / 3);
        prgb[i][0] = (uint8_t)(rr * 255 / 7);
        prgb[i][1] = (uint8_t)(gg * 255 / 7);
        prgb[i][2] = (uint8_t)(bb * 255 / 3);
    }
    for (int i = 0; i < 1024; i++) font[i] = (uint8_t)_farpeekb(_dos_ds, 0xFFA6E + i);
    fxInit();
    memset(fb, 0, sizeof(fb));
}

void shutdown() {
    __dpmi_regs r;
    memset(&r, 0, sizeof(r));
    r.x.ax = 3;
    __dpmi_int(0x10, &r);
}

void begin() { tcn = 0; }

void clear(uint32_t rgb) { memset(fb, ci(rgb), sizeof(fb)); }

static inline int ceilx(int v) { return (v + 32767) >> 16; }

static void span(int y, int xa, int xb, uint8_t c, int a, uint32_t rgb) {
    if (y < 0 || y >= SCR_H) return;
    if (xa < 0) xa = 0;
    if (xb > SCR_W) xb = SCR_W;
    if (xa >= xb) return;
    uint8_t* p = fb + y * SCR_W + xa;
    if (a >= 255) { memset(p, c, xb - xa); return; }
    int sr = (rgb >> 16) & 255, sg = (rgb >> 8) & 255, sb = rgb & 255, ia = 255 - a;
    for (int x = xa; x < xb; x++, p++) {
        const uint8_t* d = prgb[*p];
        int r = (sr * a + d[0] * ia) >> 8;
        int g = (sg * a + d[1] * ia) >> 8;
        int b = (sb * a + d[2] * ia) >> 8;
        *p = (uint8_t)((r & 0xE0) | ((g & 0xE0) >> 3) | (b >> 6));
    }
}

void tri(Fx fx0, Fx fy0, Fx fx1, Fx fy1, Fx fx2, Fx fy2, uint32_t rgb, int a) {
    if (a <= 0) return;
    int x[3] = { fx0.v >> 16, fx1.v >> 16, fx2.v >> 16 }, y[3] = { fy0.v >> 16, fy1.v >> 16, fy2.v >> 16 };
    int t;
    if (y[0] > y[1]) { t = y[0]; y[0] = y[1]; y[1] = t; t = x[0]; x[0] = x[1]; x[1] = t; }
    if (y[1] > y[2]) { t = y[1]; y[1] = y[2]; y[2] = t; t = x[1]; x[1] = x[2]; x[2] = t; }
    if (y[0] > y[1]) { t = y[0]; y[0] = y[1]; y[1] = t; t = x[0]; x[0] = x[1]; x[1] = t; }
    int y0 = y[0], y1 = y[1], y2 = y[2];
    if (y0 == y2) return;
    int rs = y0 < 0 ? 0 : y0, re = y2 > SCR_H ? SCR_H : y2;
    if (rs >= re) return;
    int sl = ((x[2] - x[0]) << 16) / (y2 - y0);
    int s01 = y1 > y0 ? ((x[1] - x[0]) << 16) / (y1 - y0) : 0;
    int s12 = y2 > y1 ? ((x[2] - x[1]) << 16) / (y2 - y1) : 0;
    int xl = (x[0] << 16) + sl * (rs - y0) + (sl >> 1);
    int xs, r = rs, rend = y1 < re ? y1 : re;
    uint8_t c = ci(rgb);
    if (r < rend) {
        xs = (x[0] << 16) + s01 * (r - y0) + (s01 >> 1);
        for (; r < rend; r++, xl += sl, xs += s01) {
            if (xl < xs) span(r, (xl + 32767) >> 16, (xs + 32767) >> 16, c, a, rgb);
            else span(r, (xs + 32767) >> 16, (xl + 32767) >> 16, c, a, rgb);
        }
    }
    if (r < re) {
        xs = (x[1] << 16) + s12 * (r - y1) + (s12 >> 1);
        for (; r < re; r++, xl += sl, xs += s12) {
            if (xl < xs) span(r, (xl + 32767) >> 16, (xs + 32767) >> 16, c, a, rgb);
            else span(r, (xs + 32767) >> 16, (xl + 32767) >> 16, c, a, rgb);
        }
    }
}

void rect(int x, int y, int w, int h, uint32_t rgb, int a) {
    if (w <= 0 || h <= 0 || a <= 0) return;
    if (x >= SCR_W || y >= SCR_H || x + w <= 0 || y + h <= 0) return;
    int y0 = y < 0 ? 0 : y, y1 = y + h > SCR_H ? SCR_H : y + h;
    uint8_t c = ci(rgb);
    for (int yy = y0; yy < y1; yy++) span(yy, x, x + w, c, a, rgb);
}

void frame(int x, int y, int w, int h, uint32_t rgb) {
    rect(x, y, w, 1, rgb);
    rect(x, y + h - 1, w, 1, rgb);
    rect(x, y + 1, 1, h - 2, rgb);
    rect(x + w - 1, y + 1, 1, h - 2, rgb);
}

void line(Fx fx0, Fx fy0, Fx fx1, Fx fy1, uint32_t rgb) {
    int x0 = fx0.v >> 16, y0 = fy0.v >> 16, x1 = fx1.v >> 16, y1 = fy1.v >> 16;
    uint8_t c = ci(rgb);
    int dx = abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
    int dy = -abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
    int e = dx + dy;
    for (;;) {
        if ((unsigned)x0 < (unsigned)SCR_W && (unsigned)y0 < (unsigned)SCR_H) fb[y0 * SCR_W + x0] = c;
        if (x0 == x1 && y0 == y1) break;
        int e2 = 2 * e;
        if (e2 >= dy) { e += dy; x0 += sx; }
        if (e2 <= dx) { e += dx; y0 += sy; }
    }
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

static void glyph(int x, int y, unsigned char ch, uint8_t c) {
    const uint8_t* g = font + (ch & 127) * 8;
    for (int r = 0; r < 8; r++) {
        int py = y + r;
        if ((unsigned)py >= (unsigned)SCR_H) continue;
        uint8_t bits = g[r];
        for (int b = 0; b < 8; b++) {
            if (!(bits & (0x80 >> b))) continue;
            int px = x + b;
            if ((unsigned)px < (unsigned)SCR_W) fb[py * SCR_W + px] = c;
        }
    }
}

static void str(int x, int y, const char* s, uint8_t c) {
    for (; *s; s++, x += 8) glyph(x, y, (unsigned char)*s, c);
}

void end() {
    for (int i = 0; i < tcn; i++) {
        const TCmd& t = tcs[i];
        if (t.y < 0 || t.y + 8 > SCR_H) continue;
        uint8_t c = ci(t.c);
        str(t.x + 1, t.y + 1, t.s, 0);
        str(t.x, t.y, t.s, c);
        if (t.bold) str(t.x + 1, t.y, t.s, c);
    }
    dosmemput(fb, SCR_W * SCR_H, 0xA0000);
}

}

#include <unistd.h>
static char* ramBase = (char*)sbrk(0);
int ramFreeK() {
    __dpmi_free_mem_info m;
    __dpmi_get_free_memory_information(&m);
    unsigned long b = m.largest_available_free_block_in_bytes;
    if (b == 0xFFFFFFFFUL) b = m.total_number_of_free_pages * 4096UL;
    return (int)(b >> 10);
}
int ramUsedK() { return (int)(((char*)sbrk(0) - ramBase) >> 10); }
