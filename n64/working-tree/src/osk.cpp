// Teclado en pantalla propio (la N64 no tiene teclado del sistema): equivalente al TextBox de J2ME
// para escribir el nombre del mundo. A = escribir letra | B = borrar | Start = aceptar | Z = cancelar.
#include "game.h"
#include <joypad.h>
#include <timer.h>

static const char* const ROWS[] = {
    "ABCDEFGHIJKLM", "NOPQRSTUVWXYZ", "abcdefghijklm", "nopqrstuvwxyz", "0123456789.- "
};
static const int NROWS = 5, NCOLS = 13;

static void oskFrame(const char* title, const std::string& cur, int selR, int selC) {
    using namespace gfx;
    begin();
    clear(0x3A2A1A);
    text(title, SCR_W / 2, 20, 0xFFE24A, TA_TOP | TA_HCENTER);
    rect(30, 46, SCR_W - 60, 20, 0x000000);
    frame(30, 46, SCR_W - 60, 20, 0xA0A0A0);
    std::string shown = cur + ((get_ticks_ms() / 400) % 2 ? "_" : " ");
    text(shown.c_str(), 36, 52, 0xFFFFFF, TA_TOP | TA_LEFT);

    const int cw = 20, ch = 18;
    int x0 = (SCR_W - cw * NCOLS) / 2, y0 = 84;
    for (int r = 0; r < NROWS; r++) {
        for (int c = 0; c < NCOLS; c++) {
            bool on = (r == selR && c == selC);
            int x = x0 + c * cw, y = y0 + r * ch;
            rect(x + 1, y + 1, cw - 2, ch - 2, on ? 0xFFE24A : 0x5A4630);
            char s[2] = { ROWS[r][c], 0 };
            if (s[0] == ' ') s[0] = '_';
            text(s, x + cw / 2, y + 4, on ? 0x000000 : 0xFFFFFF, TA_TOP | TA_HCENTER);
        }
    }
    text("A: letra  B: borrar", SCR_W / 2, SCR_H - 34, 0xB0B0B0, TA_TOP | TA_HCENTER);
    text("Start: aceptar  Z: cancelar", SCR_W / 2, SCR_H - 22, 0xB0B0B0, TA_TOP | TA_HCENTER);
    end();
}

static int stickDir(int v) { return v > 40 ? 1 : (v < -40 ? -1 : 0); }

std::string oskEdit(const char* title, const std::string& init, int maxLen) {
    std::string cur = init;
    if ((int)cur.size() > maxLen) cur.resize(maxLen);
    int selR = 0, selC = 0;
    int prevSx = 0, prevSy = 0;
    bool accepted = false, cancelled = false;

    // esperar a que se suelten los botones (el menu no debe recibir el A / Start que abrio el editor)
    for (int i = 0; i < 90; i++) {
        joypad_poll();
        joypad_buttons_t h = joypad_get_buttons_held(JOYPAD_PORT_1);
        if (!(h.a || h.b || h.z || h.start)) break;
        oskFrame(title, cur, selR, selC);
    }

    while (g_running && !accepted && !cancelled) {
        joypad_poll();
        joypad_buttons_t p = joypad_get_buttons_pressed(JOYPAD_PORT_1);
        joypad_inputs_t in = joypad_get_inputs(JOYPAD_PORT_1);

        int sx = stickDir(in.stick_x), sy = stickDir(in.stick_y);
        int dx = 0, dy = 0;
        if (p.d_left || p.c_left || (sx < 0 && prevSx >= 0)) dx = -1;
        if (p.d_right || p.c_right || (sx > 0 && prevSx <= 0)) dx = 1;
        if (p.d_up || p.c_up || (sy > 0 && prevSy <= 0)) dy = -1;     // stick N64: arriba = positivo
        if (p.d_down || p.c_down || (sy < 0 && prevSy >= 0)) dy = 1;
        prevSx = sx; prevSy = sy;
        selC = (selC + dx + NCOLS) % NCOLS;
        selR = (selR + dy + NROWS) % NROWS;

        if (p.a && (int)cur.size() < maxLen) cur += ROWS[selR][selC];
        if (p.b && !cur.empty()) cur.erase(cur.size() - 1);
        if (p.start) accepted = true;
        if (p.z) cancelled = true;

        oskFrame(title, cur, selR, selC);
    }

    // soltar botones antes de volver al menu
    for (int i = 0; i < 90; i++) {
        joypad_poll();
        joypad_buttons_t h = joypad_get_buttons_held(JOYPAD_PORT_1);
        if (!(h.a || h.b || h.z || h.start)) break;
        oskFrame(title, cur, selR, selC);
    }

    if (cancelled) return init;          // cancelado: se conserva el nombre actual
    size_t a = cur.find_first_not_of(' ');
    size_t b = cur.find_last_not_of(' ');
    return (a == std::string::npos) ? std::string() : cur.substr(a, b - a + 1);
}
