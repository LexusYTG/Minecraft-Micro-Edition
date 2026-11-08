#include <string.h>
#include <stdlib.h>
#include <dpmi.h>
#include <go32.h>
#include <pc.h>
#include <conio.h>
#include "game.h"

volatile bool g_running = true;
volatile unsigned g_dbgRaw = 0;
volatile int g_dbgCx = 0, g_dbgCy = 0;
volatile unsigned char g_keys[256];

static _go32_dpmi_seginfo oldKb, newKb;
static volatile int kbExt = 0;

static void kbIsr() {
    int sc = inportb(0x60);
    if (sc == 0xE0) { kbExt = 1; return; }
    int k = (sc & 0x7F) | (kbExt ? 0x80 : 0);
    g_keys[k] = (sc & 0x80) ? 0 : 1;
    kbExt = 0;
}
static void kbIsrEnd() {}

static void kbDone() {
    _go32_dpmi_set_protected_mode_interrupt_vector(9, &oldKb);
    while (kbhit()) getch();
}

static void kbInit() {
    _go32_dpmi_lock_data((void*)g_keys, sizeof(g_keys));
    _go32_dpmi_lock_data((void*)&kbExt, sizeof(kbExt));
    _go32_dpmi_lock_code((void*)kbIsr, (unsigned long)kbIsrEnd - (unsigned long)kbIsr);
    _go32_dpmi_get_protected_mode_interrupt_vector(9, &oldKb);
    newKb.pm_offset = (unsigned long)kbIsr;
    newKb.pm_selector = _go32_my_cs();
    _go32_dpmi_chain_protected_mode_interrupt_vector(9, &newKb);
    atexit(kbDone);
}

static inline bool K(int sc) { return g_keys[sc] || g_keys[sc | 0x80]; }

static void readPad(PadState& p) {
    unsigned b = 0;
    if (K(0x18) || K(0x39)) b |= PAD_CROSS;
    if (K(0x12)) b |= PAD_CIRCLE;
    if (K(0x01)) b |= PAD_SELECT;
    if (K(0x2C)) b |= PAD_SQUARE;
    if (K(0x1A)) b |= PAD_L;
    if (K(0x16) || K(0x1D) || K(0x1B)) b |= PAD_R;
    if (K(0x10) || K(0x2F) || K(0x1C)) b |= PAD_START;
    if (K(0x48)) b |= PAD_UP;
    if (K(0x50)) b |= PAD_DOWN;
    if (K(0x4B)) b |= PAD_LEFT;
    if (K(0x4D)) b |= PAD_RIGHT;
    p.Buttons = b;
    p.lx = (K(0x20) ? 1 : 0) - (K(0x1E) ? 1 : 0);
    p.ly = (K(0x1F) ? 1 : 0) - (K(0x11) ? 1 : 0);
    p.cx = (K(0x26) ? 1 : 0) - (K(0x24) ? 1 : 0);
    p.cy = (K(0x25) ? 1 : 0) - (K(0x17) ? 1 : 0);
}

int main(void) {
    kbInit();
    gfx::init();
    atexit(gfx::shutdown);

    Game* g = new Game();
    g->init(0);

    const uint64_t TICK_US = 50000;
    uint64_t last = ticksUs();
    uint64_t acc = 0;

    while (g_running) {
        if (K(0x44)) g_running = false;
        PadState pad;
        readPad(pad);
        g_dbgRaw = pad.Buttons;
        g_dbgCx = (int)pad.cx;
        g_dbgCy = (int)pad.cy;
        g->handlePad(pad);

        uint64_t now = ticksUs();
        acc += now - last;
        last = now;
        if (acc > 4 * TICK_US) acc = 4 * TICK_US;
        while (acc >= TICK_US) {
            g->update();
            acc -= TICK_US;
        }

        bool heavy = (g->job != 0) || g->needSave;
        g->runJobs();
        if (heavy) {
            last = ticksUs();
            acc = 0;
        }

        g->render();
        while (kbhit()) getch();
    }

    if (g->inWorld) g->saveAll();
    return 0;
}
