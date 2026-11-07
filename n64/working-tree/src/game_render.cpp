#include "game.h"
#include <timer.h>
#include <n64sys.h>
#include <malloc.h>
#include <unistd.h>

using namespace gfx;

extern volatile unsigned g_dbgRaw;           // main.cpp: botones crudos del mando
extern volatile int g_dbgCx, g_dbgCy;        // main.cpp: lo que llega como camara (botones C)

// ---------------------------------------------------------------- modelos de mobs
static const Part sheepP[] = {
    { 0.f, 0.65f, 0.f, 0.28f, 0.27f, 0.42f, 0xEEEEEE },
    { 0.f, 0.85f, 0.52f, 0.16f, 0.16f, 0.16f, 0xD2B48C },
    { 0.17f, 0.2f, 0.28f, 0.06f, 0.2f, 0.06f, 0xBFA982 },
    { -0.17f, 0.2f, 0.28f, 0.06f, 0.2f, 0.06f, 0xBFA982 },
    { 0.17f, 0.2f, -0.28f, 0.06f, 0.2f, 0.06f, 0xBFA982 },
    { -0.17f, 0.2f, -0.28f, 0.06f, 0.2f, 0.06f, 0xBFA982 }
};
static const Part chickenP[] = {
    { 0.f, 0.33f, 0.f, 0.14f, 0.14f, 0.2f, 0xF4F4F4 },
    { 0.f, 0.55f, 0.18f, 0.07f, 0.09f, 0.07f, 0xF4F4F4 },
    { 0.f, 0.52f, 0.27f, 0.03f, 0.02f, 0.04f, 0xF0C020 },
    { 0.f, 0.66f, 0.18f, 0.02f, 0.03f, 0.04f, 0xD02020 },
    { 0.05f, 0.1f, 0.f, 0.02f, 0.1f, 0.02f, 0xF0C020 },
    { -0.05f, 0.1f, 0.f, 0.02f, 0.1f, 0.02f, 0xF0C020 }
};
static const Part cowP[] = {
    { 0.f, 0.75f, 0.f, 0.30f, 0.30f, 0.50f, 0x4A3A2A },
    { 0.f, 0.80f, 0.05f, 0.31f, 0.10f, 0.22f, 0xEFEFEF },
    { 0.f, 0.95f, 0.60f, 0.18f, 0.18f, 0.18f, 0x5A4A3A },
    { 0.f, 0.86f, 0.80f, 0.12f, 0.07f, 0.04f, 0xD8B8A0 },
    { 0.18f, 0.25f, 0.35f, 0.07f, 0.25f, 0.07f, 0x3A2E22 },
    { -0.18f, 0.25f, 0.35f, 0.07f, 0.25f, 0.07f, 0x3A2E22 },
    { 0.18f, 0.25f, -0.35f, 0.07f, 0.25f, 0.07f, 0x3A2E22 },
    { -0.18f, 0.25f, -0.35f, 0.07f, 0.25f, 0.07f, 0x3A2E22 }
};
static const Part zombieP[] = {
    { 0.12f, 0.4f, 0.f, 0.10f, 0.40f, 0.10f, 0x2F3F8F },
    { -0.12f, 0.4f, 0.f, 0.10f, 0.40f, 0.10f, 0x2F3F8F },
    { 0.f, 1.1f, 0.f, 0.22f, 0.35f, 0.12f, 0x3FA0A0 },
    { 0.f, 1.65f, 0.f, 0.20f, 0.20f, 0.20f, 0x4A8A4A },
    { 0.30f, 1.25f, 0.25f, 0.08f, 0.08f, 0.30f, 0x4A8A4A },
    { -0.30f, 1.25f, 0.25f, 0.08f, 0.08f, 0.30f, 0x4A8A4A }
};
static const Part pigmanP[] = {
    { 0.12f, 0.4f, 0.f, 0.10f, 0.40f, 0.10f, 0x7A4A32 },
    { -0.12f, 0.4f, 0.f, 0.10f, 0.40f, 0.10f, 0x7A4A32 },
    { 0.f, 1.1f, 0.f, 0.22f, 0.35f, 0.12f, 0xB86868 },
    { 0.f, 1.65f, 0.f, 0.20f, 0.20f, 0.20f, 0xD89090 },
    { 0.f, 1.6f, 0.22f, 0.08f, 0.06f, 0.04f, 0xB06060 },
    { 0.30f, 1.25f, 0.25f, 0.08f, 0.08f, 0.30f, 0xD89090 },
    { -0.30f, 1.25f, 0.25f, 0.08f, 0.08f, 0.30f, 0xD89090 }
};
static const Part tntP[] = { { 0.f, 0.5f, 0.f, 0.49f, 0.49f, 0.49f, 0xC03030 } };
static const Part creeperP[] = {
    { 0.12f, 0.2f, 0.12f, 0.10f, 0.20f, 0.10f, 0x3A8A3A },
    { -0.12f, 0.2f, 0.12f, 0.10f, 0.20f, 0.10f, 0x3A8A3A },
    { 0.12f, 0.2f, -0.12f, 0.10f, 0.20f, 0.10f, 0x3A8A3A },
    { -0.12f, 0.2f, -0.12f, 0.10f, 0.20f, 0.10f, 0x3A8A3A },
    { 0.f, 0.8f, 0.f, 0.20f, 0.40f, 0.12f, 0x4AAA4A },
    { 0.f, 1.4f, 0.f, 0.20f, 0.20f, 0.20f, 0x5ABA5A },
    { 0.08f, 1.45f, 0.20f, 0.06f, 0.06f, 0.01f, 0x101010 },
    { -0.08f, 1.45f, 0.20f, 0.06f, 0.06f, 0.01f, 0x101010 },
    { 0.f, 1.30f, 0.20f, 0.05f, 0.07f, 0.01f, 0x101010 }
};
#define PS(a) { a, (int)(sizeof(a) / sizeof(a[0])) }
static const PartSet P_SHEEP = PS(sheepP), P_CHICKEN = PS(chickenP), P_COW = PS(cowP), P_ZOMBIE = PS(zombieP),
                     P_PIGMAN = PS(pigmanP), P_TNT = PS(tntP), P_CREEPER = PS(creeperP);

// ---------------------------------------------------------------- render 3D
void Game::toCam(float x, float y, float z, float* o, int i) {
    float dx = x - camX, dy = y - camY, dz = z - camZ;
    float rx = dx * cosY - dz * sinY;
    float rz = dx * sinY + dz * cosY;
    o[i] = rx;
    o[i + 1] = dy * cosP - rz * sinP;
    o[i + 2] = dy * sinP + rz * cosP;
}

// ---------------------------------------------------------------- frustum culling por software
// Se arma una vez por frame en paintWorld() con la base de la camara (F = adelante, R = derecha, U = arriba)
// y las tangentes del FOV. Probar una esfera cuesta ~10 multiplicaciones y sin sqrt (la raiz va precalculada).
static float gFx, gFy, gFz;                  // adelante
static float gRx, gRz;                       // derecha (su Y es 0)
static float gUx, gUy, gUz;                  // arriba
static float gTH, gTV, gKH, gKV;             // tan(medio FOV) horizontal / vertical y sqrt(1 + tan^2)
static int gCulCol, gCulBlk, gCulMob;        // contadores para la pantalla de depuracion (F3)

// d = centro de la esfera relativo a la camara, r = radio. false = esta totalmente fuera de la piramide de vision.
static inline bool sphereVisible(float dx, float dy, float dz, float r) {
    float z = dx * gFx + dy * gFy + dz * gFz;
    if (z < -r) return false;                                            // detras de la camara
    float xr = dx * gRx + dz * gRz;
    if (xr < 0.f) xr = -xr;
    if (xr - gTH * z > r * gKH) return false;                            // fuera por izquierda o derecha
    float yu = dx * gUx + dy * gUy + dz * gUz;
    if (yu < 0.f) yu = -yu;
    if (yu - gTV * z > r * gKV) return false;                            // fuera por arriba o abajo
    return true;
}

int Game::clip(const float* in, int n, float* out) {
    int m = 0;
    for (int i = 0; i < n; i++) {
        int j = (i + 1) % n;
        float az = in[i * 3 + 2], bz = in[j * 3 + 2];
        bool ain = az >= NEAR_Z, bin = bz >= NEAR_Z;
        if (ain) {
            out[m * 3] = in[i * 3]; out[m * 3 + 1] = in[i * 3 + 1]; out[m * 3 + 2] = az;
            m++;
        }
        if (ain != bin) {
            float t = (NEAR_Z - az) / (bz - az);
            out[m * 3] = in[i * 3] + (in[j * 3] - in[i * 3]) * t;
            out[m * 3 + 1] = in[i * 3 + 1] + (in[j * 3 + 1] - in[i * 3 + 1]) * t;
            out[m * 3 + 2] = NEAR_Z;
            m++;
        }
    }
    return m;
}

void Game::fillPoly(const float* v, int n, int color) {
    polyOk = false;
    int m = n;
    const float* p = v;
    bool behind = false;
    for (int i = 0; i < n; i++) {
        if (v[i * 3 + 2] < NEAR_Z) { behind = true; break; }
    }
    if (behind) {
        m = clip(v, n, clipA);
        if (m < 3) return;
        p = clipA;
    }
    int minx = 100000, maxx = -100000, miny = 100000, maxy = -100000;
    for (int i = 0; i < m; i++) {
        float iz = 1.f / p[i * 3 + 2];
        int sx = (int)(halfW + p[i * 3] * iz * focal);
        int sy = (int)(halfH - p[i * 3 + 1] * iz * focal);
        if (sx < -3000) sx = -3000;
        if (sx > 3000) sx = 3000;
        if (sy < -3000) sy = -3000;
        if (sy > 3000) sy = 3000;
        sxs[i] = sx; sys[i] = sy;
        if (sx < minx) minx = sx;
        if (sx > maxx) maxx = sx;
        if (sy < miny) miny = sy;
        if (sy > maxy) maxy = sy;
    }
    if (maxx < 0 || minx > sw || maxy < 0 || miny > sh) return;
    polyOk = !behind;
    for (int k = 1; k < m - 1; k++)
        tri((float)sxs[0], (float)sys[0], (float)sxs[k], (float)sys[k], (float)sxs[k + 1], (float)sys[k + 1], color);
    if (p[2] < 14.f) {
        int oc = shade(color, 72);
        for (int i = 0; i < m; i++) {
            int j = (i + 1) % m;
            line((float)sxs[i], (float)sys[i], (float)sxs[j], (float)sys[j], oc);
        }
    }
}

bool Game::opaque(int b) {
    return b != 0 && b != B_SAPLING && b != B_TORCH && b != B_WATER && !(b >= B_DOOR_LC && b <= B_DOOR_UO)
        && b != B_BED && b != B_LAVA && b != B_PORTAL;
}

void Game::faceBlock(int x, int y, int z, int f, int color, int b) {
    for (int i = 0; i < 4; i++) {
        int c = FACE[f][i];
        toCam((float)(x + (c & 1)), (float)(y + ((c >> 1) & 1)), (float)(z + ((c >> 2) & 1)), quadv, i * 3);
    }
    int p = litPct(x + 0.5f + NX[f], y + 0.5f + NY[f], z + 0.5f + NZ[f]);
    if (b == B_LAVA || b == B_GLOWSTONE) p = 100;   // brillan solos
    fillPoly(quadv, 4, p >= 100 ? color : shade(color, p));
    if (polyOk && b != B_WATER && b != B_SNOW) {
        float ddx = x + 0.5f - camX, ddy = y + 0.5f - camY, ddz = z + 0.5f - camZ;
        if (ddx * ddx + ddy * ddy + ddz * ddz < 42.f) detail(b, f, p);
    }
}

// ---------------------------------------------------------------- texturas procedurales
// Marcas dibujadas sobre la cara ya proyectada: u = 0..1 izquierda->derecha, v = 0..1 arriba->abajo
void Game::detailInit(int f, int lp) {
    dSh = FSH[f] * lp / 100;
    if (f == 2 || f == 3) { qa = 0; qb = 1; qc = 2; qd = 3; return; }
    int i0 = 0;
    for (int i = 0; i < 4; i++) {
        if (((FACE[f][i] >> 1) & 1) == 0 && ((FACE[f][(i + 1) & 3] >> 1) & 1) == 0) { i0 = i; break; }
    }
    qd = i0; qc = (i0 + 1) & 3; qb = (i0 + 2) & 3; qa = (i0 + 3) & 3;
}

void Game::pt(float u, float v) {
    float ux = sxs[qa] + (sxs[qb] - sxs[qa]) * u, uy = sys[qa] + (sys[qb] - sys[qa]) * u;
    float wx = sxs[qd] + (sxs[qc] - sxs[qd]) * u, wy = sys[qd] + (sys[qc] - sys[qd]) * u;
    gx = (int)(ux + (wx - ux) * v);
    gy = (int)(uy + (wy - uy) * v);
}

void Game::dLine(float u0, float v0, float u1, float v1, int col) {
    pt(u0, v0);
    int xa = gx, ya = gy;
    pt(u1, v1);
    line((float)xa, (float)ya, (float)gx, (float)gy, col);
}

void Game::dRect(float u0, float v0, float u1, float v1, int col) {
    pt(u0, v0); int xa = gx, ya = gy;
    pt(u1, v0); int xb = gx, yb = gy;
    pt(u1, v1); int xc = gx, yc = gy;
    pt(u0, v1);
    tri((float)xa, (float)ya, (float)xb, (float)yb, (float)xc, (float)yc, col);
    tri((float)xa, (float)ya, (float)xc, (float)yc, (float)gx, (float)gy, col);
}

void Game::dFrame(float u0, float v0, float u1, float v1, int col) {
    dLine(u0, v0, u1, v0, col);
    dLine(u1, v0, u1, v1, col);
    dLine(u1, v1, u0, v1, col);
    dLine(u0, v1, u0, v0, col);
}

void Game::detail(int b, int f, int lp) {
    detailInit(f, lp);
    bool side = (f < 2 || f > 3);
    if (b == B_PLANKS) {
        int d = mk(0x7A5A2E, 100);
        dLine(0.f, 0.25f, 1.f, 0.25f, d); dLine(0.f, 0.5f, 1.f, 0.5f, d); dLine(0.f, 0.75f, 1.f, 0.75f, d);
        dLine(0.7f, 0.f, 0.7f, 0.25f, d); dLine(0.25f, 0.25f, 0.25f, 0.5f, d);
        dLine(0.7f, 0.5f, 0.7f, 0.75f, d); dLine(0.25f, 0.75f, 0.25f, 1.f, d);
    } else if (b == B_LOG) {
        if (side) {
            int d = mk(0x34240F, 100), l = mk(0x7C6038, 100);
            dLine(0.16f, 0.f, 0.16f, 1.f, d); dLine(0.40f, 0.f, 0.40f, 1.f, d);
            dLine(0.64f, 0.f, 0.64f, 1.f, d); dLine(0.86f, 0.f, 0.86f, 1.f, d);
            dLine(0.28f, 0.f, 0.28f, 1.f, l); dLine(0.75f, 0.f, 0.75f, 1.f, l);
        } else {
            dFrame(0.12f, 0.12f, 0.88f, 0.88f, mk(0x5E4426, 100));
            dFrame(0.28f, 0.28f, 0.72f, 0.72f, mk(0x7A5C2E, 100));
            dRect(0.43f, 0.43f, 0.57f, 0.57f, mk(0x7A5C2E, 100));
        }
    } else if (b == B_TABLE) {
        if (f == 2) {
            int d = mk(0x4A3018, 100);
            dFrame(0.06f, 0.06f, 0.94f, 0.94f, d);
            dLine(0.5f, 0.06f, 0.5f, 0.94f, d); dLine(0.06f, 0.5f, 0.94f, 0.5f, d);
        } else if (side) {
            int d = mk(0x4A3018, 100), g = mk(0xC8C8C8, 100);
            dRect(0.f, 0.f, 1.f, 0.2f, d);
            dLine(0.05f, 0.2f, 0.05f, 1.f, d); dLine(0.95f, 0.2f, 0.95f, 1.f, d);
            dRect(0.18f, 0.42f, 0.40f, 0.52f, g); dRect(0.18f, 0.52f, 0.24f, 0.66f, mk(0x3A220E, 100));
            dRect(0.52f, 0.40f, 0.78f, 0.50f, g); dRect(0.62f, 0.50f, 0.68f, 0.86f, mk(0x3A220E, 100));
        }
    } else if (b == B_COBBLE) {
        int d = mk(0x3A3A3A, 100);
        dLine(0.f, 0.33f, 1.f, 0.33f, d); dLine(0.f, 0.66f, 1.f, 0.66f, d);
        dLine(0.5f, 0.f, 0.5f, 0.33f, d); dLine(0.25f, 0.33f, 0.25f, 0.66f, d);
        dLine(0.75f, 0.33f, 0.75f, 0.66f, d); dLine(0.5f, 0.66f, 0.5f, 1.f, d);
    } else if (b == B_STONE) {
        int d = mk(0x5A5A5A, 100), l = mk(0x959595, 100);
        dRect(0.15f, 0.2f, 0.27f, 0.3f, d); dRect(0.62f, 0.12f, 0.74f, 0.22f, d);
        dRect(0.4f, 0.62f, 0.52f, 0.72f, d); dRect(0.78f, 0.7f, 0.9f, 0.8f, d);
        dRect(0.3f, 0.4f, 0.4f, 0.48f, l); dRect(0.7f, 0.45f, 0.8f, 0.53f, l);
    } else if (b == B_FURNACE) {
        if (f == 2) {
            dRect(0.28f, 0.28f, 0.72f, 0.72f, mk(0x151515, 100));
            dFrame(0.2f, 0.2f, 0.8f, 0.8f, mk(0x555555, 100));
        } else if (side) {
            dRect(0.2f, 0.12f, 0.8f, 0.24f, mk(0x2A2A2A, 100));
            dRect(0.2f, 0.45f, 0.8f, 0.88f, mk(0x151515, 100));
            dRect(0.3f, 0.68f, 0.7f, 0.82f, mk(0xE07020, 100));
            dFrame(0.2f, 0.45f, 0.8f, 0.88f, mk(0x555555, 100));
        }
    } else if (b == B_GRASS) {
        if (side) {
            int g = mk(cTop[B_GRASS], 100);
            dRect(0.f, 0.f, 1.f, 0.2f, g);
            dRect(0.08f, 0.2f, 0.3f, 0.32f, g); dRect(0.55f, 0.2f, 0.82f, 0.29f, g);
        } else if (f == 2) {
            dRect(0.2f, 0.25f, 0.28f, 0.33f, mk(0x3E8A22, 100)); dRect(0.65f, 0.6f, 0.73f, 0.68f, mk(0x6EC03E, 100));
        }
    } else if (b == B_DIRT) {
        int d = mk(0x5A4020, 100), l = mk(0x9A7444, 100);
        dRect(0.15f, 0.2f, 0.27f, 0.3f, d); dRect(0.62f, 0.15f, 0.72f, 0.25f, l);
        dRect(0.4f, 0.55f, 0.52f, 0.65f, d); dRect(0.75f, 0.7f, 0.87f, 0.8f, l);
    } else if (b == B_SAND) {
        int d = mk(0xB8AC70, 100);
        dRect(0.2f, 0.25f, 0.27f, 0.31f, d); dRect(0.6f, 0.5f, 0.67f, 0.56f, d); dRect(0.35f, 0.8f, 0.42f, 0.86f, d);
    } else if (b == B_SANDSTONE) {
        if (side) {
            int d = mk(0xAFA268, 100);
            dLine(0.f, 0.3f, 1.f, 0.3f, d); dLine(0.f, 0.62f, 1.f, 0.62f, d);
        }
    } else if (b == B_GRAVEL) {
        int d = mk(0x56524F, 100), l = mk(0xB0AAA6, 100);
        dRect(0.15f, 0.2f, 0.28f, 0.32f, d); dRect(0.6f, 0.15f, 0.72f, 0.27f, l);
        dRect(0.4f, 0.5f, 0.52f, 0.62f, d); dRect(0.75f, 0.6f, 0.88f, 0.72f, l); dRect(0.2f, 0.75f, 0.32f, 0.87f, l);
    } else if (b == B_CACTUS) {
        if (side) {
            int d = mk(0x1E5A1E, 100), l = mk(0xD8D8A0, 100);
            dLine(0.25f, 0.f, 0.25f, 1.f, d); dLine(0.5f, 0.f, 0.5f, 1.f, d); dLine(0.75f, 0.f, 0.75f, 1.f, d);
            dRect(0.35f, 0.3f, 0.4f, 0.35f, l); dRect(0.6f, 0.55f, 0.65f, 0.6f, l); dRect(0.12f, 0.75f, 0.17f, 0.8f, l);
        } else {
            dFrame(0.1f, 0.1f, 0.9f, 0.9f, mk(0x1E5A1E, 100));
            dRect(0.3f, 0.3f, 0.7f, 0.7f, mk(0x4FA04A, 100));
        }
    } else if (b == B_TNT) {
        if (side) {
            int w = mk(0xE8E8E8, 100), d = mk(0x202020, 100);
            dRect(0.f, 0.34f, 1.f, 0.66f, w);
            for (int i = 0; i < 3; i++) {
                float u0 = 0.12f + i * 0.28f;
                if (i == 1) {
                    dLine(u0, 0.42f, u0, 0.58f, d); dLine(u0 + 0.2f, 0.42f, u0 + 0.2f, 0.58f, d);
                    dLine(u0, 0.42f, u0 + 0.2f, 0.58f, d);
                } else {
                    dRect(u0, 0.42f, u0 + 0.2f, 0.46f, d); dRect(u0 + 0.08f, 0.42f, u0 + 0.12f, 0.58f, d);
                }
            }
        } else if (f == 2) {
            dRect(0.4f, 0.4f, 0.6f, 0.6f, mk(0x303030, 100));
        }
    } else if (b == B_LEAVES) {
        int d = mk(0x1E5A1C, 100), l = mk(0x58B84A, 100);
        dRect(0.15f, 0.2f, 0.3f, 0.33f, d); dRect(0.6f, 0.12f, 0.75f, 0.25f, d);
        dRect(0.35f, 0.55f, 0.5f, 0.68f, d); dRect(0.72f, 0.62f, 0.87f, 0.75f, d);
        dRect(0.2f, 0.78f, 0.33f, 0.9f, l); dRect(0.5f, 0.3f, 0.6f, 0.4f, l);
    } else if (b >= B_COAL_ORE && b <= B_DIAM_ORE) {
        int oc = (b == B_COAL_ORE) ? 0x1E1E1E : (b == B_IRON_ORE ? 0xD8A878 : (b == B_GOLD_ORE ? 0xF0D030 : 0x5AE8E0));
        int c0 = mk(oc, 100);
        dRect(0.12f, 0.15f, 0.32f, 0.32f, c0); dRect(0.55f, 0.1f, 0.75f, 0.26f, c0);
        dRect(0.35f, 0.5f, 0.55f, 0.66f, c0); dRect(0.72f, 0.55f, 0.9f, 0.72f, c0);
        dRect(0.1f, 0.7f, 0.28f, 0.86f, c0);
    } else if (b >= B_IRON_BLK && b <= B_DIAM_BLK) {
        dFrame(0.05f, 0.05f, 0.95f, 0.95f, mk(cSide[b], 70));
        dFrame(0.14f, 0.14f, 0.86f, 0.86f, mk(cSide[b], 118));
        dLine(0.25f, 0.75f, 0.75f, 0.25f, mk(cSide[b], 135));
    } else if (b == B_NETHERRACK) {
        int d = mk(0x4A1616, 100), l = mk(0x9A4040, 100);
        dRect(0.12f, 0.2f, 0.3f, 0.34f, d); dRect(0.6f, 0.12f, 0.8f, 0.28f, d); dRect(0.3f, 0.6f, 0.5f, 0.74f, d);
        dRect(0.7f, 0.55f, 0.88f, 0.7f, l); dRect(0.15f, 0.78f, 0.3f, 0.9f, l);
    } else if (b == B_LAVA) {
        int y0 = mk(0xFFD040, 100);
        dRect(0.15f, 0.2f, 0.4f, 0.35f, y0); dRect(0.6f, 0.15f, 0.85f, 0.3f, y0);
        dRect(0.3f, 0.6f, 0.55f, 0.75f, y0); dRect(0.7f, 0.65f, 0.9f, 0.8f, y0);
    } else if (b == B_OBSIDIAN) {
        int pv = mk(0x6A3FA0, 100);
        dLine(0.1f, 0.8f, 0.5f, 0.3f, pv); dLine(0.4f, 0.9f, 0.9f, 0.2f, pv); dLine(0.6f, 0.7f, 0.85f, 0.55f, pv);
    } else if (b == B_SOULSAND) {
        int d = mk(0x2E2218, 100);
        dRect(0.15f, 0.2f, 0.3f, 0.32f, d); dRect(0.55f, 0.15f, 0.7f, 0.27f, d);
        dRect(0.3f, 0.55f, 0.45f, 0.67f, d); dRect(0.7f, 0.6f, 0.85f, 0.72f, d);
    } else if (b == B_GLOWSTONE) {
        int l = mk(0xFFF0B0, 100), d = mk(0xC09A40, 100);
        dRect(0.12f, 0.15f, 0.3f, 0.3f, l); dRect(0.6f, 0.1f, 0.78f, 0.25f, l); dRect(0.35f, 0.55f, 0.55f, 0.7f, l);
        dRect(0.7f, 0.6f, 0.88f, 0.75f, d); dRect(0.15f, 0.7f, 0.3f, 0.85f, d);
    } else if (b == B_BEDROCK) {
        int d = mk(0x1A1A1A, 100), l = mk(0x6A6A6A, 100);
        dRect(0.12f, 0.2f, 0.3f, 0.34f, d); dRect(0.6f, 0.12f, 0.8f, 0.28f, d); dRect(0.3f, 0.6f, 0.5f, 0.74f, d);
        dRect(0.7f, 0.55f, 0.88f, 0.7f, l);
    } else if (b == B_ICE) {
        int l = mk(0xE8F6FF, 100);
        dLine(0.15f, 0.8f, 0.7f, 0.2f, l); dLine(0.35f, 0.9f, 0.85f, 0.4f, l);
    }
}

int Game::plankPx(int u, int v, int var) {
    if (v % 4 == 3) return tint(0x7A5A2E, 100);
    if (((u + ((v / 4) & 1) * 4) & 7) == 0) return tint(0x8A6A38, 100);
    return tint(0xB8945A, var);
}

// pixel 8x8 de un bloque: face 0 = arriba, 1 = costado, 2 = abajo
int Game::texBlock(int b, int face, int u, int v) {
    int n = tn(u, v, b * 7 + face);
    int base = face == 0 ? cTop[b] : (face == 2 ? cBot[b] : cSide[b]);
    int var = 88 + n / 8;
    int dark = (n > 205) ? 70 : var;
    if (b == B_GRASS) {
        if (face == 0 || (face == 1 && (v < 2 || (v == 2 && n > 110)))) return tint(cTop[B_GRASS], var);
        return tint(0x7B5B34, dark);
    }
    if (b == B_DIRT || b == B_STONE) return tint(base, dark);
    if (b == B_COBBLE) {
        bool mortar = (v % 4 == 3) || ((u + (v / 4) * 3) % 6 == 5);
        return mortar ? tint(base, 62) : tint(base, var + 8);
    }
    if (b == B_LOG) {
        if (face != 1) {
            int d = abs(2 * u - 7) > abs(2 * v - 7) ? abs(2 * u - 7) : abs(2 * v - 7);
            if (d >= 7) return tint(0x5E4426, var);
            if (d == 3) return tint(0x8E6F3C, var);
            return tint(0xB8945A, var);
        }
        return tint(0x5E4426, (u % 3 == 0) ? 70 + n / 16 : var);
    }
    if (b == B_LEAVES) return (n > 170) ? 0x1E5A1C : tint(0x3F9A36, var);
    if (b == B_PLANKS) return plankPx(u, v, var);
    if (b == B_TABLE) {
        if (face == 0) {
            if (u == 0 || v == 0 || u == 7 || v == 7) return tint(0x5E3E1E, var);
            if (u == 3 || v == 3) return tint(0x7A5A2E, 100);
            return tint(0xA47C44, var);
        }
        if (face == 2) return plankPx(u, v, var);
        if (v < 2 || u == 0 || u == 7) return tint(0x5E3E1E, var);
        if (v >= 3 && v <= 4 && u >= 2 && u <= 3) return 0xC8C8C8;
        if (u == 5 && v >= 3 && v <= 6) return 0x3A220E;
        if (v == 3 && u == 6) return 0xC8C8C8;
        return tint(base, var);
    }
    if (b == B_FURNACE) {
        if (face == 0) {
            if (u >= 2 && u <= 5 && v >= 2 && v <= 5) return 0x151515;
            return tint(0x8A8A8A, var);
        }
        if (face == 2) return tint(0x6A6A6A, var);
        if (u >= 2 && u <= 5 && v >= 3 && v <= 6) return (v >= 6) ? 0xE07020 : 0x181818;
        if (v < 2) return tint(0x3A3A3A, var);
        return tint(0x707070, (u == 0 || u == 7) ? 75 : var);
    }
    if (b == B_SAND) return tint(base, 92 + n / 12);
    if (b == B_SANDSTONE) return (face == 1 && (v == 2 || v == 5)) ? tint(base, 80) : tint(base, var + 6);
    if (b == B_CACTUS) {
        if (face != 1) return (u == 0 || v == 0 || u == 7 || v == 7) ? tint(base, 70) : tint(0x4FA04A, var);
        if (n > 235) return 0xD8D8A0;
        return ((u & 1) == 0) ? tint(base, 76) : tint(base, var);
    }
    if (b == B_TNT) {
        if (face == 0) return (u >= 3 && u <= 4 && v >= 3 && v <= 4) ? 0x303030 : tint(0xC8C8C8, var);
        if (face == 2) return tint(0x888888, var);
        if (v == 3) return ((u & 1) == 1) ? 0x303030 : 0xE8E8E8;
        if (v == 4) return (u == 2 || u == 5) ? 0x303030 : 0xE8E8E8;
        if (v == 2 || v == 5) return 0xE8E8E8;
        return tint(0xB22A2A, ((u & 1) == 0) ? var : var - 8);
    }
    if (b >= B_COAL_ORE && b <= B_DIAM_ORE) {
        int oc = (b == B_COAL_ORE) ? 0x1E1E1E : (b == B_IRON_ORE ? 0xD8A878 : (b == B_GOLD_ORE ? 0xF0D030 : 0x5AE8E0));
        if (tn(u >> 1, v, 77) > 185) return oc;
        return tint(0x7D7D7D, dark);
    }
    if (b >= B_IRON_BLK && b <= B_DIAM_BLK) {
        if (u == 0 || v == 0 || u == 7 || v == 7) return tint(base, 78);
        if (u == 1 || v == 1) return tint(base, 114);
        return tint(base, 96 + n / 32);
    }
    if (b == B_NETHERRACK) return (n > 200) ? 0x4A1616 : ((n < 40) ? 0x9A4040 : tint(base, var));
    if (b == B_LAVA) return ((u + v * 2 + n / 64) % 5 == 0) ? 0xFFD040 : tint(0xE0601A, var + 10);
    if (b == B_OBSIDIAN) return (n > 215) ? 0x6A3FA0 : tint(base, var - 6);
    if (b == B_SOULSAND) return (((u + v) % 3 == 0) && n > 100) ? 0x2E2218 : tint(base, var);
    if (b == B_GLOWSTONE) return (n > 160) ? 0xFFF0B0 : ((n < 60) ? 0xC09A40 : tint(base, var + 8));
    if (b == B_BEDROCK) return (n > 150) ? 0x1A1A1A : ((n < 60) ? 0x6A6A6A : 0x444444);
    if (b == B_SNOW) return tint(base, 96 + n / 32);
    if (b == B_GRAVEL) return (n > 170) ? 0x5E5A57 : ((n < 50) ? 0xA8A29E : tint(base, var));
    if (b == B_ICE) return ((u + v) % 5 == 0) ? 0xE8F6FF : tint(base, var + 6);
    return tint(base, var);
}

int Game::pal(char c) {
    switch (c) {
        case 'K': return 0x202020; case 'k': return 0x101010;
        case 'W': return 0xF0F0F0; case 'w': return 0xBDBDBD;
        case 'G': return 0x8A8A8A; case 'g': return 0x555555;
        case 'B': return 0x8B6B3A; case 'b': return 0x5E4426;
        case 'T': return 0xC49A55; case 'R': return 0xC83A34;
        case 'r': return 0x8E1A1A; case 'P': return 0xE9B9A3;
        case 'p': return 0xC48A78; case 'O': return 0xE0903A;
        case 'o': return 0x8A5A3A; case 'Y': return 0xF0C020;
        case 'N': return 0x3FA03A; case 'n': return 0x2A6A2A;
        case 'Z': return 0x6B7A3A; case 'z': return 0x4A5A28;
        case 'D': return 0xD02020; case 'I': return 0xD8D8D8;
        case 'i': return 0x9A9A9A; case 'y': return 0xB8901A;
        case 'C': return 0x4AE8E0; case 'c': return 0x20A8B0;
        default: return -1;
    }
}

// dibujos 8x8 de items ('.' = transparente)
bool Game::patOf(int id, std::string* o) {
    static const char* const STICK[8] = { "......Bb", ".....Bb.", "....Bb..", "...Bb...", "..Bb....", ".Bb.....", "Bb......", "b......." };
    static const char* const PICK[8] = { "..TTTT..", ".T..BT.T", "T..B...T", "..B....T", ".B......", "B.......", "........", "........" };
    static const char* const AXE[8] = { "...TT...", "..TTTT..", "..TTBT..", "...B.T..", "..B.....", ".B......", "B.......", "........" };
    static const char* const SHOVEL[8] = { "......TT", ".....TTT", "....TTT.", "...BT...", "..B.....", ".B......", "B.......", "........" };
    static const char* const SWORD[8] = { "......TT", ".....TT.", "....TT..", "...TT...", ".b.T....", "..bB....", ".B.b....", "B......." };
    static const char* const WOOL[8] = { "........", ".WWWWWW.", ".WwWWwW.", ".WWWWWW.", ".WwWWWW.", ".WWwWWW.", ".WWWWWW.", "........" };
    static const char* const MUTTON[8] = { "........", "..RRRR..", ".RRPRRR.", ".RRRRRr.", ".RRRRrr.", "..rrrW..", "....WW..", "........" };
    static const char* const CHICKEN[8] = { "........", "..PPP...", ".PPPPP..", ".PPPpP..", "..PPP...", "...W....", "..WW....", "........" };
    static const char* const FEATHER[8] = { "......ww", ".....www", "....wWw.", "...wWw..", "..wWw...", ".wWw....", "wb......", "b......." };
    static const char* const APPLE[8] = { "...nN...", "....b...", "..DDDD..", ".DDDDDD.", ".DDPDDD.", ".DDDDDr.", "..DDrr..", "........" };
    static const char* const BEEF[8] = { "........", ".rRRRR..", ".RRPRRr.", ".RRRRRr.", ".RRRrrr.", "..RRrr..", "........", "........" };
    static const char* const STEAK[8] = { "........", ".oooooo.", ".oOoKoo.", ".ooOoKo.", ".oKooOo.", ".ooKooo.", "..oooo..", "........" };
    static const char* const CMUTTON[8] = { "........", "..oooo..", ".ooOooo.", ".oooooK.", ".ooooKK.", "..KKKW..", "....WW..", "........" };
    static const char* const CCHICKEN[8] = { "........", "..OOO...", ".OOOOO..", ".OOOoO..", "..OOO...", "...W....", "..WW....", "........" };
    static const char* const COAL[8] = { "........", "..Kgg...", ".KggKgK.", ".gKKgKg.", ".KgKggK.", "..KgKg..", "........", "........" };
    static const char* const ROTTEN[8] = { "........", "..ZZZZ..", ".ZZNZZZ.", ".ZZZZzz.", ".ZNZzzz.", "..zzzW..", "....WW..", "........" };
    static const char* const GUNP[8] = { "........", "........", "...gg...", "..gGGg..", ".gGGGGg.", ".gGgGGg.", "gGGGGGGg", "........" };
    static const char* const DOOR[8] = { "..bbbb..", ".bBBBBb.", ".bBbbBb.", ".bBBBBb.", ".bBbbBb.", ".bBBBYb.", ".bBbbBb.", ".bBBBBb." };
    static const char* const LIGHTER[8] = { ".....Y..", "....YOY.", "....OOY.", "..IIIII.", "..IiiiI.", "..IIIII.", "..iiiii.", "........" };
    static const char* const DIAMOND[8] = { "........", "..CCCC..", ".CWCCCC.", ".CCCCCc.", "..CCCc..", "...Cc...", "........", "........" };
    static const char* const BED[8] = { "........", "........", "bWWRRRRb", "bWWRRRRb", "bRRRRRRb", "bbbbbbbb", "b......b", "b......b" };
    static const char* const INGOT[8] = { "........", "...LLLL.", "..LLLLL.", ".LLWLLLl", ".LLLLLll", ".llllll.", "........", "........" };

    const char* const* p = 0;
    switch (id) {
        case I_STICK: p = STICK; break; case I_PICK: p = PICK; break; case I_AXE: p = AXE; break;
        case I_SHOVEL: p = SHOVEL; break; case I_SWORD: p = SWORD; break; case I_WOOL: p = WOOL; break;
        case I_MUTTON: p = MUTTON; break; case I_CHICKEN: p = CHICKEN; break; case I_FEATHER: p = FEATHER; break;
        case I_APPLE: p = APPLE; break; case I_BEEF: p = BEEF; break; case I_STEAK: p = STEAK; break;
        case I_COOKED_MUTTON: p = CMUTTON; break; case I_COOKED_CHICKEN: p = CCHICKEN; break;
        case I_COAL: p = COAL; break; case I_ROTTEN: p = ROTTEN; break; case I_GUNPOWDER: p = GUNP; break;
        case I_DOOR: p = DOOR; break; case I_LIGHTER: p = LIGHTER; break; case I_DIAMOND: p = DIAMOND; break;
        case B_BED: p = BED; break;
        default: break;
    }
    if (p) { for (int i = 0; i < 8; i++) o[i] = p[i]; return true; }
    if (id >= I_TOOLS && id < I_TOOLS + 16) {
        int t = (id - I_TOOLS) / 4;
        static const char* const* const bases[4] = { PICK, AXE, SHOVEL, SWORD };
        const char* const* w = bases[(id - I_TOOLS) & 3];
        char ch = (t == 0) ? 'G' : (t == 1 ? 'I' : (t == 2 ? 'Y' : 'C'));
        for (int i = 0; i < 8; i++) {
            o[i] = w[i];
            for (size_t k = 0; k < o[i].size(); k++) if (o[i][k] == 'T') o[i][k] = ch;
        }
        return true;
    }
    if (id == I_IRON_INGOT || id == I_GOLD_INGOT) {
        char hi = (id == I_IRON_INGOT) ? 'I' : 'Y', lo = (id == I_IRON_INGOT) ? 'i' : 'y';
        for (int i = 0; i < 8; i++) {
            o[i] = INGOT[i];
            for (size_t k = 0; k < o[i].size(); k++) {
                if (o[i][k] == 'L') o[i][k] = hi;
                else if (o[i][k] == 'l') o[i][k] = lo;
            }
        }
        return true;
    }
    return false;
}

const int* Game::iconOf(int id) {
    if (iconOk[id]) return iconPx[id];
    std::string pat[8];
    bool has = (id > 19) && patOf(id, pat);
    for (int r = 0; r < 8; r++) {
        for (int c = 0; c < 8; c++) {
            int col;
            if (has) col = pal(pat[r][c]);
            else if (id <= 19 || (id >= B_COAL_ORE && id <= B_DIAM_BLK) || (id >= B_NETHERRACK && id <= B_OBSIDIAN) || (id >= B_SOULSAND && id <= B_BEDROCK))
                col = (r < 2) ? texBlock(id, 0, c, r + 3) : texBlock(id, 1, c, r);
            else col = icol[id];
            iconPx[id][r * 8 + c] = col;
        }
    }
    iconOk[id] = true;
    return iconPx[id];
}

// ---------------------------------------------------------------- cajas, bloques y mobs
void Game::drawBox(float x0, float y0, float z0, float x1, float y1, float z1, int base, int lp) {
    for (int f = 0; f < 6; f++) {
        bool vis;
        if (f == 0) vis = camX > x1;
        else if (f == 1) vis = camX < x0;
        else if (f == 2) vis = camY > y1;
        else if (f == 3) vis = camY < y0;
        else if (f == 4) vis = camZ > z1;
        else vis = camZ < z0;
        if (!vis) continue;
        for (int i = 0; i < 4; i++) {
            int c = FACE[f][i];
            toCam(((c & 1) == 0) ? x0 : x1, (((c >> 1) & 1) == 0) ? y0 : y1,
                  (((c >> 2) & 1) == 0) ? z0 : z1, quadv, i * 3);
        }
        fillPoly(quadv, 4, shade(base, FSH[f] * lp / 100));
    }
}

void Game::drawSmall(int x, int y, int z, int b) {   // plantita y antorcha: cajas finas
    int lp = litPct(x + 0.5f, y + 0.5f, z + 0.5f);
    float cx = x + 0.5f, cz = z + 0.5f;
    if (b == B_TORCH) {
        drawBox(cx - 0.07f, (float)y, cz - 0.07f, cx + 0.07f, y + 0.55f, cz + 0.07f, 0x8B6B3A, lp);
        drawBox(cx - 0.09f, y + 0.55f, cz - 0.09f, cx + 0.09f, y + 0.75f, cz + 0.09f, 0xFFD040, 100);
    } else {
        drawBox(cx - 0.05f, (float)y, cz - 0.05f, cx + 0.05f, y + 0.4f, cz + 0.05f, 0x6B4A2A, lp);
        drawBox(cx - 0.3f, y + 0.3f, cz - 0.3f, cx + 0.3f, y + 0.8f, cz + 0.3f, 0x3FA03A, lp);
    }
}

bool Game::showFace(int b, int n) {
    if (b == B_WATER || b == B_LAVA) return n != b && !opaque(n);
    return !opaque(n);
}

void Game::drawBlock(int x, int y, int z, int b) {
    if (b == B_SAPLING || b == B_TORCH) { drawSmall(x, y, z, b); return; }
    if (isDoor(b)) { drawDoor(x, y, z, b); return; }
    if (b == B_BED) { drawBed(x, y, z); return; }
    if (b == B_PORTAL) { drawPortal(x, y, z); return; }
    const int* sc = shadedCol[b];
    if (camX > x + 1 && showFace(b, blockAt(x + 1, y, z))) faceBlock(x, y, z, 0, sc[0], b);
    if (camX < x && showFace(b, blockAt(x - 1, y, z))) faceBlock(x, y, z, 1, sc[1], b);
    if (camY > y + 1 && showFace(b, blockAt(x, y + 1, z))) faceBlock(x, y, z, 2, sc[2], b);
    if (camY < y && showFace(b, blockAt(x, y - 1, z))) faceBlock(x, y, z, 3, sc[3], b);
    if (camZ > z + 1 && showFace(b, blockAt(x, y, z + 1))) faceBlock(x, y, z, 4, sc[4], b);
    if (camZ < z && showFace(b, blockAt(x, y, z - 1))) faceBlock(x, y, z, 5, sc[5], b);
}

void Game::drawPortal(int x, int y, int z) {   // panel violeta que parpadea entre dos tonos
    bool alongX = isFrameBlk(blockAt(x - 1, y, z)) || isFrameBlk(blockAt(x + 1, y, z));
    int col = (((tick / 8) + x + y + z) & 1) == 0 ? 0x8A2BE2 : 0x6A1BC2;
    if (alongX) drawBox((float)x, (float)y, z + 0.4f, (float)(x + 1), (float)(y + 1), z + 0.6f, col, 100);
    else drawBox(x + 0.4f, (float)y, (float)z, x + 0.6f, (float)(y + 1), (float)(z + 1), col, 100);
}

void Game::drawBed(int x, int y, int z) {
    int lp = litPct(x + 0.5f, y + 0.5f, z + 0.5f);
    drawBox((float)x, (float)y, (float)z, (float)(x + 1), y + 0.22f, (float)(z + 1), 0x6B4A2A, lp);
    drawBox(x + 0.03f, y + 0.22f, z + 0.03f, x + 0.97f, y + 0.5f, z + 0.97f, 0xC83A34, lp);
    drawBox(x + 0.1f, y + 0.5f, z + 0.06f, x + 0.9f, y + 0.6f, z + 0.34f, 0xF0F0F0, lp);
}

// puerta: panel fino con paneles hundidos y manija; abierta gira 90 grados hacia el borde de la celda
void Game::drawDoor(int x, int y, int z, int b) {
    int lp = litPct(x + 0.5f, y + 0.5f, z + 0.5f);
    bool open = (b == B_DOOR_LO || b == B_DOOR_UO);
    bool upper = (b == B_DOOR_UC || b == B_DOOR_UO);
    int ly = upper ? y - 1 : y;
    bool spanX = doorAlongX(x, ly, z) != open;
    float t0 = open ? 0.f : 0.43f, t1 = open ? 0.13f : 0.57f;
    float pe = 0.03f;
    if (spanX) {
        drawBox((float)x, (float)y, z + t0, (float)(x + 1), (float)(y + 1), z + t1, 0x8A6A36, lp);
        drawBox(x + 0.15f, y + 0.12f, z + t0 - pe, x + 0.85f, y + 0.88f, z + t1 + pe, 0x6E5128, lp);
        if (!upper) drawBox(x + 0.76f, y + 0.45f, z + t0 - 0.06f, x + 0.86f, y + 0.55f, z + t1 + 0.06f, 0xE0C040, lp);
    } else {
        drawBox(x + t0, (float)y, (float)z, x + t1, (float)(y + 1), (float)(z + 1), 0x8A6A36, lp);
        drawBox(x + t0 - pe, y + 0.12f, z + 0.15f, x + t1 + pe, y + 0.88f, z + 0.85f, 0x6E5128, lp);
        if (!upper) drawBox(x + t0 - 0.06f, y + 0.45f, z + 0.76f, x + t1 + 0.06f, y + 0.55f, z + 0.86f, 0xE0C040, lp);
    }
}

void Game::drawMob(Mob* m) {
    const PartSet* ps;
    switch (m->type) {
        case 0: ps = &P_SHEEP; break; case 1: ps = &P_CHICKEN; break; case 2: ps = &P_COW; break;
        case 3: ps = (dim == 1) ? &P_PIGMAN : &P_ZOMBIE; break;
        case 4: ps = &P_CREEPER; break; default: ps = &P_TNT; break;
    }
    if (!sphereVisible(m->x - camX, m->y + 0.9f - camY, m->z - camZ, 1.4f)) { gCulMob++; return; }
    int mpct = litPct(m->x, m->y + 0.6f, m->z);
    float cs = cosf(m->ang), sn = sinf(m->ang);
    for (int pi = 0; pi < ps->n; pi++) {
        const Part& p = ps->p[pi];
        float cx = m->x + p.x * cs + p.z * sn;
        float cz = m->z - p.x * sn + p.z * cs;
        float cy = m->y + p.y;
        for (int c = 0; c < 8; c++) {
            float lx = ((c & 1) == 0) ? -p.hx : p.hx;
            float ly = ((c & 2) == 0) ? -p.hy : p.hy;
            float lz = ((c & 4) == 0) ? -p.hz : p.hz;
            toCam(cx + lx * cs + lz * sn, cy + ly, cz - lx * sn + lz * cs, cc, c * 3);
        }
        int base = p.col;
        if (m->hurt > 0) base = 0xFF5555;
        else if (m->fuse > 0 && (m->fuse / 3) % 2 == 0) base = 0xFFFFFF;
        for (int f = 0; f < 6; f++) {
            float lcx = NX[f] * p.hx, lcy = NY[f] * p.hy, lcz = NZ[f] * p.hz;
            float fx2 = cx + lcx * cs + lcz * sn;
            float fz2 = cz - lcx * sn + lcz * cs;
            float fy2 = cy + lcy;
            float nwx = NX[f] * cs + NZ[f] * sn;
            float nwz = -NX[f] * sn + NZ[f] * cs;
            float nwy = NY[f];
            if (nwx * (camX - fx2) + nwy * (camY - fy2) + nwz * (camZ - fz2) <= 0.f) continue;
            for (int i = 0; i < 4; i++) {
                int ci = FACE[f][i] * 3;
                quadv[i * 3] = cc[ci]; quadv[i * 3 + 1] = cc[ci + 1]; quadv[i * 3 + 2] = cc[ci + 2];
            }
            fillPoly(quadv, 4, shade(base, FSH[f] * mpct / 100));
        }
    }
}

void Game::line3(const float* v, int i, int j) {
    float ax1 = v[i], ay1 = v[i + 1], az1 = v[i + 2];
    float bx = v[j], by = v[j + 1], bz = v[j + 2];
    if (az1 < NEAR_Z && bz < NEAR_Z) return;
    if (az1 < NEAR_Z) {
        float t = (NEAR_Z - az1) / (bz - az1);
        ax1 = ax1 + (bx - ax1) * t; ay1 = ay1 + (by - ay1) * t; az1 = NEAR_Z;
    } else if (bz < NEAR_Z) {
        float t = (NEAR_Z - bz) / (az1 - bz);
        bx = bx + (ax1 - bx) * t; by = by + (ay1 - by) * t; bz = NEAR_Z;
    }
    float x1 = halfW + ax1 / az1 * focal, y1 = halfH - ay1 / az1 * focal;
    float x2 = halfW + bx / bz * focal, y2 = halfH - by / bz * focal;
    if (x1 < -3000 || x1 > 3000 || y1 < -3000 || y1 > 3000) return;
    if (x2 < -3000 || x2 > 3000 || y2 < -3000 || y2 > 3000) return;
    line(x1, y1, x2, y2, 0xFFFFFF);
}

void Game::drawWire(int x, int y, int z) {
    for (int c = 0; c < 8; c++)
        toCam((float)(x + (c & 1)), (float)(y + ((c >> 1) & 1)), (float)(z + ((c >> 2) & 1)), cc, c * 3);
    for (int e = 0; e < 12; e++) line3(cc, WIRE[e][0] * 3, WIRE[e][1] * 3);
}

void Game::buildOrder(int* o, int n, int c) {
    if (c < 0) c = 0;
    if (c > n - 1) c = n - 1;
    int k = 0;
    for (int i = 0; i < c; i++) o[k++] = i;
    for (int i = n - 1; i > c; i--) o[k++] = i;
    o[k++] = c;
}

void Game::paintWorld() {
    clear(skyColor());

    camX = px; camY = py + EYE; camZ = pz;
    cosY = cosf(yaw); sinY = sinf(yaw);
    cosP = cosf(pitch); sinP = sinf(pitch);
    fx = sinY * cosP; fy = sinP; fz = cosY * cosP;
    halfW = sw / 2.f; halfH = sh / 2.f;
    focal = halfW / tanf(FOV / 2.f);

    // base de la camara en el mundo (misma convencion que toCam) y tangentes del FOV
    gFx = fx; gFy = fy; gFz = fz;
    gRx = cosY; gRz = -sinY;
    gUx = -sinY * sinP; gUy = cosP; gUz = -cosY * sinP;
    gTH = halfW / focal; gTV = halfH / focal;
    gKH = sqrtf(1.f + gTH * gTH); gKV = sqrtf(1.f + gTV * gTV);
    gCulCol = gCulBlk = gCulMob = 0;

    int bx0 = ifloor(camX) - rd, bz0 = ifloor(camZ) - rd;
    buildOrder(oy, H, ifloor(camY));
    for (int i = 0; i < H; i++) invY[oy[i]] = i;

    // mobs dentro del area, ordenados segun el orden del pintor
    int nm = 0;
    int cnt = (int)mobs.size();
    if (cnt > 64) cnt = 64;
    for (int i = 0; i < cnt; i++) {
        Mob* m = mobs[i];
        int bi = ifloor(m->x) - bx0, bk = ifloor(m->z) - bz0;
        if (bi < 0 || bi >= nb || bk < 0 || bk >= nb) continue;
        int cy = ifloor(m->y + 0.4f);
        if (cy < 0) cy = 0;
        if (cy > H - 1) cy = H - 1;
        int key = (invX[bi] * nb + invZ[bk]) * H + invY[cy];
        int j = nm++;
        while (j > 0 && mobKey[j - 1] > key) {
            mobKey[j] = mobKey[j - 1];
            mobOrd[j] = mobOrd[j - 1];
            j--;
        }
        mobKey[j] = key;
        mobOrd[j] = i;
    }
    int mp = 0;

    // rango vertical de las columnas respecto a la camara (para el descarte por columna)
    float dyLo = -camY, dyHi = (float)H - camY;
    float zUp = (fy > 0.f) ? dyHi * fy : dyLo * fy;      // aporte maximo de la altura a la profundidad

    for (int a = 0; a < nb; a++) {
        int x = bx0 + ox[a];
        for (int b = 0; b < nb; b++) {
            int z = bz0 + oz[b];
            uint8_t* cd = chunkFor(x, z);
            if (!cd) continue;
            float cdx = x + 0.5f - camX, cdz = z + 0.5f - camZ;
            // columna entera fuera de la vista: detras (profundidad maxima de la columna) o fuera por los costados
            float zMax = cdx * fx + cdz * fz + zUp;
            if (zMax < -1.8f) { gCulCol++; continue; }
            float xr = cdx * gRx + cdz * gRz;
            if (xr < 0.f) xr = -xr;
            if (xr - gTH * zMax > 1.8f * gKH) { gCulCol++; continue; }
            int base = (((x & 15) << 4) + (z & 15)) * H;
            int colKey = (a * nb + b) * H;
            for (int c = 0; c < H; c++) {
                int y = oy[c];
                int blk = cd[base + y];
                if (blk != 0) {
                    float dx = cdx, dy = y + 0.5f - camY, dz = cdz;
                    if (dx * dx + dy * dy + dz * dz < cullSq && sphereVisible(dx, dy, dz, 0.9f)) drawBlock(x, y, z, blk);
                    else gCulBlk++;
                }
                while (mp < nm && mobKey[mp] <= colKey + c) {
                    drawMob(mobs[mobOrd[mp]]);
                    mp++;
                }
            }
        }
    }

    if (tx >= 0 && !tMob) drawWire(tx, ty, tz);

    if (blockAt(ifloor(camX), ifloor(camY), ifloor(camZ)) == B_WATER) rect(0, 0, sw, sh, 0x2050D0, 0x70);
}

// ---------------------------------------------------------------- dibujo 2D
void Game::txt(const std::string& s, int x, int y, int c, int anchor) {
    text(s.c_str(), x + 1, y + 1, 0x000000, anchor);
    text(s.c_str(), x, y, c, anchor);
}

void Game::drawIcon(int id, int dur, int x, int y, int s) {
    if (id == 0) return;
    int m = s / 6;
    int ix = x + m, iy = y + m, is = s - 2 * m;
    if (id == B_TORCH) {
        rect(ix + is / 2 - 1, iy + is / 3, 3, is - is / 3, 0x8B6B3A);
        rect(ix + is / 2 - 2, iy, 5, is / 3 + 1, 0xFFC030);
    } else if (id == B_SAPLING) {
        rect(ix + is / 2 - 1, iy + is / 2, 2, is - is / 2, 0x6B4A2A);
        rect(ix + is / 4, iy, is / 2, is / 2 + 1, 0x3FA03A);
    } else {
        const int* px_ = iconOf(id);
        int c = (s - 2) / 8;
        if (c < 1) c = 1;
        int ox2 = x + (s - c * 8) / 2, oy2 = y + (s - c * 8) / 2;
        for (int r = 0; r < 8; r++) {
            int c0 = 0;
            while (c0 < 8) {
                int col = px_[r * 8 + c0];
                int c1 = c0 + 1;
                while (c1 < 8 && px_[r * 8 + c1] == col) c1++;
                if (col >= 0) rect(ox2 + c0 * c, oy2 + r * c, (c1 - c0) * c, c, col);
                c0 = c1;
            }
        }
        if (id <= B_ICE || (id >= B_COAL_ORE && id <= B_DIAM_BLK) || (id >= B_NETHERRACK && id <= B_OBSIDIAN) || (id >= B_SOULSAND && id <= B_BEDROCK))
            frame(ox2 - 1, oy2 - 1, c * 8 + 2, c * 8 + 2, 0x000000);
        if (isTool(id) && dur > 0 && dur < toolMax(id)) {
            int bw = (is * dur) / toolMax(id);
            rect(ix, y + s - 3, bw, 2, dur * 3 < toolMax(id) ? 0xE02020 : 0x20C020);
        }
    }
}

void Game::drawCount(int cnt, int x, int y, int s) {
    if (cnt > 1) txt(fmt("%d", cnt), x + s - 2, y + s - 1, 0xFFFFFF, TA_BOTTOM | TA_RIGHT);
}

void Game::paintHud() {
    int cx = sw / 2, cy = sh / 2;
    rect(cx - 6, cy, 13, 1, 0xFFFFFF);
    rect(cx, cy - 6, 1, 13, 0xFFFFFF);

    if (mineProg > 0 && mineX >= 0) {
        int need = breakTicks(blockAt(mineX, mineY, mineZ), slId[sel]);
        int bw = 24 * mineProg / need;
        rect(cx - 13, cy + 10, 26, 5, 0x000000);
        rect(cx - 12, cy + 11, bw, 3, 0xFFFFFF);
    }

    int hs = 30;
    int hx = (sw - 9 * hs) / 2;
    int hy = sh - hs - 3;
    for (int i = 0; i < 9; i++) {
        rect(hx + i * hs, hy, hs, hs, 0x404040);
        frame(hx + i * hs, hy, hs, hs, 0x909090);
        drawIcon(slId[i], slDur[i], hx + i * hs, hy, hs);
        drawCount(slCnt[i], hx + i * hs, hy, hs);
    }
    frame(hx + sel * hs - 1, hy - 1, hs + 2, hs + 2, 0xFFFFFF);
    frame(hx + sel * hs, hy, hs, hs, 0xFFFFFF);

    int ry = hy - 12;
    if (!creative) {
        for (int i = 0; i < 10; i++) {
            int x = hx + i * 11;
            rect(x, ry, 9, 9, 0x401010);
            int hp2 = health - i * 2;
            if (hp2 >= 2) rect(x, ry, 9, 9, 0xE02020);
            else if (hp2 == 1) rect(x, ry, 5, 9, 0xE02020);
        }
        for (int i = 0; i < 10; i++) {
            int x = hx + 9 * hs - (i + 1) * 11;
            rect(x, ry, 9, 9, 0x402810);
            int hg = hunger - i * 2;
            if (hg >= 2) rect(x, ry, 9, 9, 0xC08030);
            else if (hg == 1) rect(x + 4, ry, 5, 9, 0xC08030);
        }
    }
    if (air < 300) {
        int nbub = (air * 10 + 299) / 300;
        for (int i = 0; i < nbub; i++) rect(hx + 9 * hs - 10 - i * 11, ry - 14, 9, 9, 0xA8DCFF);
    }
    if (hungerFx > 0) txt("Hambre", hx + 9 * hs, ry - 3, 0x9ACD32, TA_BOTTOM | TA_RIGHT);
    if (labelT > 0 && slId[sel] != 0) txt(names[slId[sel]], sw / 2, ry - 4, 0xFFFFFF, TA_BOTTOM | TA_HCENTER);
    if (msgT > 0) txt(msg, sw / 2, sh / 4, 0xFFFF80, TA_TOP | TA_HCENTER);
    if (creative) txt(flying ? "Creativo (vuelo)" : "Creativo", sw / 2, 2, 0xA0FFA0, TA_TOP | TA_HCENTER);

    if (portalT > 0) {
        int th = portalT / 4 + 2;
        rect(0, 0, sw, th, 0x8A2BE2);
        rect(0, sh - th, sw, th, 0x8A2BE2);
        rect(0, 0, th, sh, 0x8A2BE2);
        rect(sw - th, 0, th, sh, 0x8A2BE2);
    }
    if (dmgFlash > 0) {
        rect(0, 0, sw, 4, 0xFF0000);
        rect(0, sh - 4, sw, 4, 0xFF0000);
        rect(0, 0, 4, sh, 0xFF0000);
        rect(sw - 4, 0, 4, sh, 0xFF0000);
    }
}

void Game::paintInv() {
    clear(0x202020);
    bool pm = palMode();
    std::string ttl = craftN == FURN ? "Horno" : (craftN == 3 ? "Mesa de crafteo" : "Inventario");
    if (pm) ttl = fmt("Creativo  [L] %d/%d [R]", palPage + 1, (palN + 26) / 27);
    txt(ttl, sw / 2, 1, 0xFFFFFF, TA_TOP | TA_HCENTER);
    int s = slS;
    for (int i = 0; i < 46; i++) {
        if (!slActive[i]) continue;
        rect(slx[i], sly[i], s, s, i == OUT ? 0x6B8B6B : 0x8B8B8B);
        frame(slx[i], sly[i], s, s, 0x373737);
        if (pm && i >= 9 && i < 36) {
            int pid = palAt(i);
            drawIcon(pid, isTool(pid) ? toolMax(pid) : 0, slx[i], sly[i], s);
        } else {
            drawIcon(slId[i], slDur[i], slx[i], sly[i], s);
            drawCount(slCnt[i], slx[i], sly[i], s);
        }
    }
    if (craftN == FURN) {
        int bx = slx[36] + s + 4, bw = slx[38] - bx - 4, by = sly[38] + s / 2 - 3;
        rect(bx, by, bw, 6, 0x404040);
        rect(bx, by, bw * fProg / SMELT_T, 6, 0xF0F0F0);
        int fxx = slx[36] + s / 4, fyy = sly[36] + s + s / 4;
        rect(fxx, fyy, s / 2, s / 2, 0x404040);
        if (fBurn > 0 && fBurnMax > 0) {
            int hh = (s / 2) * fBurn / fBurnMax;
            rect(fxx, fyy + s / 2 - hh, s / 2, hh, 0xFF8000);
        }
    } else {
        int ax0 = slx[OUT] - s;
        txt("=>", ax0 + s / 2, sly[OUT] + s / 2 - 5, 0xFFFFFF, TA_TOP | TA_HCENTER);
    }
    frame(slx[cursor], sly[cursor], s, s, 0xFFFF00);
    frame(slx[cursor] + 1, slx[cursor] >= 0 ? sly[cursor] + 1 : 0, s - 2, s - 2, 0xFFFF00);
    if (heldId != 0) {
        int hx = slx[cursor] + s / 3, hy = sly[cursor] + s / 3;
        drawIcon(heldId, heldDur, hx, hy, s);
        drawCount(heldCnt, hx, hy, s);
    }
    int nid = (heldId != 0) ? heldId : ((pm && cursor >= 9 && cursor < 36) ? palAt(cursor) : slId[cursor]);
    if (nid != 0) {
        std::string nm = names[nid];
        if (cursor == OUT && heldId == 0) nm = "Resultado: " + nm;
        txt(nm, sw / 2, sh - 2, 0xFFFFFF, TA_BOTTOM | TA_HCENTER);
    }
}

// ---------------------------------------------------------------- menus estilo Pocket Edition
std::string Game::fit(const std::string& s, int maxW) {
    if (textW(s.c_str()) <= maxW) return s;
    std::string t = s;
    while (t.size() > 1 && textW((t + "..").c_str()) > maxW) t.erase(t.size() - 1);
    return t + "..";
}

void Game::metrics() {
    btnH = FONT_H + 10;
    if (btnH < 22) btnH = 22;
    btnW = sw - 24;
    if (btnW > 200) btnW = 200;
    if (btnW < 80) btnW = sw - 4;
    btnX = (sw - btnW) / 2;
}

void Game::drawDirt(int pct) {   // fondo de tierra
    int t = 16;
    for (int ty_ = 0; ty_ * t < sh; ty_++) {
        for (int tx_ = 0; tx_ * t < sw; tx_++) {
            uint32_t h0 = ((uint32_t)tx_ * 73856093u) ^ ((uint32_t)ty_ * 19349663u);
            h0 = (h0 ^ (h0 >> 13)) * 1274126177u;
            int h = (int)((h0 >> 8) & 3);
            int c = (h == 0) ? 0x6B4F30 : ((h == 1) ? 0x7B5B34 : ((h == 2) ? 0x5E4426 : 0x6F5232));
            rect(tx_ * t, ty_ * t, t, t, shade(c, pct));
            rect(tx_ * t + (int)((h0 >> 12) & 7) + 2, ty_ * t + (int)((h0 >> 16) & 7) + 2, 3, 3, shade(c, pct * 70 / 100));
        }
    }
}

void Game::dimScreen() { rect(0, 0, sw, sh, 0x000000, 0x90); }   // oscurece lo que hay detras

void Game::title(const std::string& s, int y, int col, int shadow) {
    txt(s, sw / 2 + 1, y + 2, shadow, TA_TOP | TA_HCENTER);   // pseudo-negrita: dos pasadas
    text(s.c_str(), sw / 2 + 1, y, col, TA_TOP | TA_HCENTER);
    text(s.c_str(), sw / 2, y, col, TA_TOP | TA_HCENTER);
}

// logo "MINECRAFT" con letras de 5x5 celdas dibujadas con rectangulos
void Game::drawLogo(int y) {
    static const char* const L[9][5] = {
        { "X...X", "XX.XX", "X.X.X", "X...X", "X...X" },   // M
        { "XXXXX", "..X..", "..X..", "..X..", "XXXXX" },   // I
        { "X...X", "XX..X", "X.X.X", "X..XX", "X...X" },   // N
        { "XXXXX", "X....", "XXXX.", "X....", "XXXXX" },   // E
        { ".XXXX", "X....", "X....", "X....", ".XXXX" },   // C
        { "XXXX.", "X...X", "XXXX.", "X..X.", "X...X" },   // R
        { ".XXX.", "X...X", "XXXXX", "X...X", "X...X" },   // A
        { "XXXXX", "X....", "XXXX.", "X....", "X...." },   // F
        { "XXXXX", "..X..", "..X..", "..X..", "..X.." }    // T
    };
    const int cell = 5, gap = 1, ch = 5;
    int total = 9 * (ch * cell) + 8 * gap * cell;
    int x0 = (sw - total) / 2;
    for (int pass = 0; pass < 2; pass++) {
        int off = pass == 0 ? 2 : 0;
        int col = pass == 0 ? 0x2B2B2B : 0xC6C6C6;
        for (int l = 0; l < 9; l++) {
            int lx = x0 + l * (ch * cell + gap * cell);
            for (int r = 0; r < 5; r++)
                for (int c = 0; c < 5; c++)
                    if (L[l][r][c] == 'X') rect(lx + c * cell + off, y + r * cell + off, cell, cell, col);
        }
    }
    int ty = y + 5 * cell + 5;
    textS("Micro Edition", sw / 2 + 2, ty + 2, 0x3A3000, TA_TOP | TA_HCENTER, 2);   // sombra
    textS("Micro Edition", sw / 2, ty, 0xFFE24A, TA_TOP | TA_HCENTER, 2);
    textS("N64 Edition", sw / 2 + 1, ty + 21, 0x202020, TA_TOP | TA_HCENTER, 1);
    textS("N64 Edition", sw / 2, ty + 20, 0xE0E0E0, TA_TOP | TA_HCENTER, 1);
}

void Game::drawBtn(int x, int y, int w, int h, const std::string& label, bool on) {
    int base = on ? 0x6F80C4 : 0x7C7C7C;
    int hi = on ? 0xA3B0E6 : 0xB4B4B4;
    int lo = on ? 0x3E4B8C : 0x4A4A4A;
    rect(x, y, w, h, 0x000000);
    rect(x + 1, y + 1, w - 2, h - 2, base);
    rect(x + 1, y + 1, w - 2, 2, hi);
    rect(x + 1, y + 1, 2, h - 2, hi);
    rect(x + 1, y + h - 3, w - 2, 2, lo);
    rect(x + w - 3, y + 1, 2, h - 2, lo);
    txt(fit(label, w - 8), x + w / 2, y + (h - FONT_H) / 2, on ? 0xFFFFA0 : 0xFFFFFF, TA_TOP | TA_HCENTER);
}

void Game::btn(int id, const std::string& label, int x, int y, int w, int h) {
    drawBtn(x, y, w, h, label, isSel(id));
}

void Game::paintMenu() {
    clear(0);
    metrics();
    drawDirt(55);
    int y0 = (sh / 14 > 6 ? sh / 14 : 6);
    drawLogo(y0);
    int y = y0 + 5 * 5 + 6 + FONT_H + (sh / 12 > 10 ? sh / 12 : 10) + 10;
    btn(A_PLAY, "Jugar", btnX, y, btnW, btnH);
    y += btnH + 8;
    btn(A_QUIT, "Salir", btnX, y, btnW, btnH);
    txt("A: aceptar   B: atras   Start: pausa", sw / 2, sh - 3, 0xB0B0B0, TA_BOTTOM | TA_HCENTER);
}

void Game::paintWorlds() {
    clear(0);
    metrics();
    drawDirt(50);
    title("Selecciona un mundo", 4, 0xFFFFFF, 0x202020);
    int y0 = 4 + 12 + 6;
    btn(A_NEW, "+ Crear mundo nuevo", btnX, y0, btnW, btnH);
    int backY = sh - btnH - 4;
    btn(A_WBACK, "Atras", btnX, backY, btnW, btnH);
    int top = y0 + btnH + 6, bot = backY - 4, step = btnH + 3;
    int n = (int)worlds.size();
    if (n > 70) n = 70;
    int vis = (bot - top + 3) / step;
    if (vis < 1) vis = 1;
    wVis = vis;
    int maxS = n - vis;
    if (maxS < 0) maxS = 0;
    if (wScroll > maxS) wScroll = maxS;
    if (wScroll < 0) wScroll = 0;
    if (n == 0) txt("No hay mundos todavia", sw / 2, top + 6, 0xDDDDDD, TA_TOP | TA_HCENTER);
    int xs = btnH - 6;
    for (int r = 0; r < vis; r++) {
        int i = wScroll + r;
        if (i >= n) break;
        int y = top + r * step;
        int rw2 = btnW - xs - 4;
        std::string lbl = worlds[i].name + (worlds[i].mode == 1 ? " [C]" : "");
        drawBtn(btnX, y, rw2, btnH, lbl, isSel(100 + i));
        int bx = btnX + btnW - xs, by = y + 3;
        rect(bx, by, xs, xs, 0x000000);
        rect(bx + 1, by + 1, xs - 2, xs - 2, 0x9A3030);
        txt("X", bx + xs / 2, by + (xs - FONT_H) / 2, 0xFFFFFF, TA_TOP | TA_HCENTER);
    }
    if (wScroll > 0) txt("^", sw - 4, top, 0xFFFFFF, TA_TOP | TA_RIGHT);
    if (wScroll + vis < n) txt("v", sw - 4, bot - FONT_H, 0xFFFFFF, TA_TOP | TA_RIGHT);
    txt("Z: borrar mundo", sw - 6, sh - 3, 0xB0B0B0, TA_BOTTOM | TA_RIGHT);
}

void Game::paintCreate() {
    clear(0);
    metrics();
    drawDirt(50);
    title("Crear mundo nuevo", 4, 0xFFFFFF, 0x202020);
    int y = 4 + 12 + 14;
    btn(A_CNAME, "Nombre: " + fit(cName, btnW - 80), btnX, y, btnW, btnH);
    y += btnH + 6;
    btn(A_CSEED, "Semilla: " + (cSeedRandom ? std::string("aleatoria") : fmt("%d", cSeed)), btnX, y, btnW, btnH);
    y += btnH + 6;
    btn(A_CMODE, std::string("Modo: ") + (cMode == 1 ? "Creativo" : "Supervivencia"), btnX, y, btnW, btnH);
    y += btnH + 6;
    btn(A_CGO, "Crear", btnX, y, btnW, btnH);
    btn(A_CBACK, "Atras", btnX, sh - btnH - 4, btnW, btnH);
    txt("Semilla: X alterna, Izq/Der +-1, L/R +-1000", sw / 2, sh - btnH - 4 - 2, 0xB0B0B0, TA_BOTTOM | TA_HCENTER);
}

void Game::paintConfirm() {
    clear(0);
    metrics();
    drawDirt(40);
    std::string nm = (delTarget >= 0 && delTarget < (int)worlds.size()) ? worlds[delTarget].name : "";
    int y = sh / 5;
    title("Borrar mundo?", y, 0xFFFFFF, 0x202020);
    y += 12 + 8;
    txt(fit(nm, sw - 10), sw / 2, y, 0xFFFF80, TA_TOP | TA_HCENTER);
    y += FONT_H + 2;
    txt("No se puede deshacer", sw / 2, y, 0xFF8080, TA_TOP | TA_HCENTER);
    y += FONT_H + 14;
    btn(A_YES, "Si, borrar", btnX, y, btnW, btnH);
    y += btnH + 8;
    btn(A_NO, "Cancelar", btnX, y, btnW, btnH);
}

void Game::paintPause() {
    paintWorld();
    dimScreen();
    metrics();
    int total = 12 + 14 + 3 * btnH + 2 * 8;
    int y = (sh - total) / 2;
    if (y < 2) y = 2;
    title("Juego en pausa", y, 0xFFFFFF, 0x202020);
    y += 12 + 14;
    btn(A_RESUME, "Reanudar", btnX, y, btnW, btnH);
    y += btnH + 8;
    btn(A_SETTINGS, "Ajustes", btnX, y, btnW, btnH);
    y += btnH + 8;
    btn(A_TOMENU, "Menu principal", btnX, y, btnW, btnH);
    if (storeBroken) txt("No se pudo guardar!", sw / 2, y + btnH + 4, 0xFF5555, TA_TOP | TA_HCENTER);
}

void Game::paintSettings() {
    paintWorld();
    dimScreen();
    metrics();
    int total = 12 + 14 + btnH + 8 + 2 * FONT_H + 8 + btnH;
    int y = (sh - total) / 2;
    if (y < 2) y = 2;
    title("Ajustes", y, 0xFFFFFF, 0x202020);
    y += 12 + 14;
    btn(A_RD, fmt("Render: %s (%d)", RD_NAME[rdIdx], RD_OPT[rdIdx]), btnX, y, btnW, btnH);
    y += btnH + 8;
    txt("Izq / Der o A: cambiar", sw / 2, y, 0xDDDDDD, TA_TOP | TA_HCENTER);
    y += FONT_H;
    txt("Mas lejos = mas lento", sw / 2, y, 0xDDDDDD, TA_TOP | TA_HCENTER);
    y += FONT_H + 8;
    btn(A_SETBACK, "Atras", btnX, y, btnW, btnH);
}

void Game::paintDead() {
    int w = (sw - 10 < 160) ? sw - 10 : 160, h = 50;
    int x = (sw - w) / 2, y = (sh - h) / 2;
    rect(x, y, w, h, 0x600000);
    frame(x, y, w, h, 0xFF4040);
    txt("MORISTE", sw / 2, y + 8, 0xFFFFFF, TA_TOP | TA_HCENTER);
    txt("A: reaparecer", sw / 2, y + 28, 0xFFFFFF, TA_TOP | TA_HCENTER);
}

void Game::paintDebug() {
    uint32_t now = (uint32_t)get_ticks_us();
    dbgFrames++;
    if (now - dbgT0 >= 1000000u) { dbgFps = dbgFrames; dbgFrames = 0; dbgT0 = now; }
    // RAM libre = bloques libres del heap + memoria que el heap todavia no pidio (aproximado: no descuenta el stack)
    struct mallinfo mi = mallinfo();
    uint32_t ramEnd = 0x80000000u + (uint32_t)get_memory_size();
    uint32_t heapTop = (uint32_t)(uintptr_t)sbrk(0);
    int untouched = ramEnd > heapTop ? (int)(ramEnd - heapTop) : 0;
    std::string l1 = fmt("RAM %dK usada %dK libre", (int)(mi.uordblks / 1024), (int)((mi.fordblks + untouched) / 1024));
    std::string l2 = fmt("FPS %d  Mobs %d", dbgFps, (int)mobs.size());
    std::string l3 = fmt("Dim %d  Dia %d%%", dim, bright);
    std::string l4 = fmt("X%d Y%d Z%d", ifloor(px), ifloor(py), ifloor(pz));
    std::string l5 = fmt("Cull col %d blk %d mob %d", gCulCol, gCulBlk, gCulMob);
    std::string l6 = fmt("Pad %04X  C %d %d", (unsigned)g_dbgRaw, (int)g_dbgCx, (int)g_dbgCy);
    int w = textW(l1.c_str());
    if (textW(l5.c_str()) > w) w = textW(l5.c_str());
    if (textW(l6.c_str()) > w) w = textW(l6.c_str());
    if (textW(l2.c_str()) > w) w = textW(l2.c_str());
    if (textW(l3.c_str()) > w) w = textW(l3.c_str());
    if (textW(l4.c_str()) > w) w = textW(l4.c_str());
    rect(1, 1, w + 4, FONT_H * 6 + 3, 0x000000, 0xB0);
    text(l1.c_str(), 3, 2, 0x55FF55, TA_TOP | TA_LEFT);
    text(l2.c_str(), 3, 2 + FONT_H, 0x55FF55, TA_TOP | TA_LEFT);
    text(l3.c_str(), 3, 2 + FONT_H * 2, 0x55FF55, TA_TOP | TA_LEFT);
    text(l4.c_str(), 3, 2 + FONT_H * 3, 0x55FF55, TA_TOP | TA_LEFT);
    text(l5.c_str(), 3, 2 + FONT_H * 4, 0x55FF55, TA_TOP | TA_LEFT);
    text(l6.c_str(), 3, 2 + FONT_H * 5, 0x55FF55, TA_TOP | TA_LEFT);
}

// ---------------------------------------------------------------- frame completo
void Game::render() {
    begin();
    clear(0);
    if (state == ST_MENU) paintMenu();
    else if (state == ST_WORLDS) paintWorlds();
    else if (state == ST_CREATE) paintCreate();
    else if (state == ST_CONFIRM) paintConfirm();
    else if (state == ST_PAUSE) paintPause();
    else if (state == ST_SET) paintSettings();
    else if (state == ST_INV) {
        if (!slActive[OUT]) layoutInv();
        paintInv();
    } else {
        paintWorld();
        paintHud();
        if (dbg) paintDebug();
        if (state == ST_DEAD) paintDead();
    }
    if (!busy.empty()) {
        rect(0, sh - FONT_H - 4, sw, FONT_H + 4, 0x000000);
        txt(busy, sw / 2, sh - 2, 0xFFFF55, TA_BOTTOM | TA_HCENTER);
    }
    end();
}
