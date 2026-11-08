// Minecraft Micro Edition para Nintendo 64 (libdragon) - punto de entrada y bucle principal
// (tick fijo de 50 ms, como el original J2ME).
#include "game.h"
#include <joypad.h>
#include <timer.h>
#include <debug.h>
#include <n64sys.h>
#include <math.h>

volatile bool g_running = true;

// lectura cruda del mando, solo para la pantalla de depuracion (F3)
volatile unsigned g_dbgRaw = 0;
volatile int g_dbgCx = 0, g_dbgCy = 0;

// ---------------------------------------------------------------- mando N64 -> botones virtuales
enum {
    R_A = 1 << 0, R_B = 1 << 1, R_Z = 1 << 2, R_START = 1 << 3,
    R_DU = 1 << 4, R_DD = 1 << 5, R_DL = 1 << 6, R_DR = 1 << 7,
    R_L = 1 << 8, R_R = 1 << 9,
    R_CU = 1 << 10, R_CD = 1 << 11, R_CL = 1 << 12, R_CR = 1 << 13
};

static unsigned packRaw(const joypad_buttons_t& h) {
    unsigned r = 0;
    if (h.a) r |= R_A;
    if (h.b) r |= R_B;
    if (h.z) r |= R_Z;
    if (h.start) r |= R_START;
    if (h.d_up) r |= R_DU;
    if (h.d_down) r |= R_DD;
    if (h.d_left) r |= R_DL;
    if (h.d_right) r |= R_DR;
    if (h.l) r |= R_L;
    if (h.r) r |= R_R;
    if (h.c_up) r |= R_CU;
    if (h.c_down) r |= R_CD;
    if (h.c_left) r |= R_CL;
    if (h.c_right) r |= R_CR;
    return r;
}

// Controles (botones virtuales, ver game.h; la logica esta en Game::handlePad):
//   A = X (saltar en juego / aceptar en menus)   B = Circulo (inventario / atras; B + L/R = hotbar)
//   R = romper / colocar (juego)   L = depuracion (F3)   Z = Cuadrado (borrar mundo / click secundario en menus)
//   Botones C = camara (pad.cx / pad.cy)   Start = Start   Cruceta y stick = caminar (stick tambien navega menus)
static unsigned toVirtual(unsigned r) {
    unsigned v = 0;
    if (r & R_A) v |= PAD_CROSS;
    if (r & R_B) v |= PAD_CIRCLE;
    if (r & R_Z) v |= PAD_SQUARE;
    if (r & R_L) v |= PAD_L;
    if (r & R_R) v |= PAD_R;
    if (r & R_START) v |= PAD_START;
    if (r & R_DU) v |= PAD_UP;
    if (r & R_DD) v |= PAD_DOWN;
    if (r & R_DL) v |= PAD_LEFT;
    if (r & R_DR) v |= PAD_RIGHT;
    return v;
}

static float axis(int v) {
    float f = v / 80.f;                  // el stick de N64 llega a ~+-85
    return f < -1.f ? -1.f : (f > 1.f ? 1.f : f);
}

int main(void) {
    debug_init_sdfs("sd:/", -1);         // tarjeta SD del flashcart (si no hay, el juego sigue sin guardar)
    timer_init();
    joypad_init();
    gfx::init();

    Game* g = new Game();
    g->init(0);

    const uint64_t TICK_US = 50000;
    uint64_t last = get_ticks_us();
    uint64_t acc = 0;

    while (g_running) {
        joypad_poll();
        PadState pad;
        pad.Buttons = 0; pad.lx = pad.ly = 0.f; pad.cx = pad.cy = 0.f;
        if (joypad_is_connected(JOYPAD_PORT_1)) {
            joypad_inputs_t in = joypad_get_inputs(JOYPAD_PORT_1);
            pad.Buttons = toVirtual(packRaw(in.btn));
            pad.cx = (in.btn.c_right ? 1.f : 0.f) - (in.btn.c_left ? 1.f : 0.f);
            pad.cy = (in.btn.c_down ? 1.f : 0.f) - (in.btn.c_up ? 1.f : 0.f);   // arriba = negativo
            // respaldo: si el driver / emulador reporta los C como stick C en vez de botones
            if (pad.cx == 0.f && pad.cy == 0.f) {
                float sx = axis(in.cstick_x), sy = -axis(in.cstick_y);
                if (fabsf(sx) > 0.3f) pad.cx = sx > 0.f ? 1.f : -1.f;
                if (fabsf(sy) > 0.3f) pad.cy = sy > 0.f ? 1.f : -1.f;
            }
            g_dbgRaw = packRaw(in.btn);
            g_dbgCx = (int)pad.cx; g_dbgCy = (int)pad.cy;
            pad.lx = axis(in.stick_x);
            pad.ly = -axis(in.stick_y);  // N64: arriba = positivo; el juego espera arriba = negativo
        }
        g->handlePad(pad);

        uint64_t now = get_ticks_us();
        acc += now - last;
        last = now;
        if (acc > 4 * TICK_US) acc = 4 * TICK_US;       // no acumular atraso (evita rafagas tras cargar)
        while (acc >= TICK_US) {
            g->update();
            acc -= TICK_US;
        }

        bool heavy = (g->job != 0) || g->needSave;
        g->runJobs();
        if (heavy) {                                     // los trabajos pesados congelan el juego: reiniciar el reloj
            last = get_ticks_us();
            acc = 0;
        }

        g->render();                                     // display_get() espera al vsync
    }

    if (g->inWorld) g->saveAll();
    gfx::shutdown();
    return 0;
}
