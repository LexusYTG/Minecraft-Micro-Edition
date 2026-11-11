// Minicraft2D para PSP - punto de entrada y bucle principal (tick fijo de 50 ms, como el original J2ME).
#include <pspkernel.h>
#include <pspctrl.h>
#include <pspdisplay.h>
#include <psppower.h>
#include "game.h"

PSP_MODULE_INFO("Minicraft2D", PSP_MODULE_USER, 1, 0);
PSP_MAIN_THREAD_ATTR(PSP_THREAD_ATTR_USER);
PSP_HEAP_SIZE_KB(-1024);

volatile bool g_running = true;

static int exitCallback(int, int, void*) {
    g_running = false;
    return 0;
}

static int callbackThread(SceSize, void*) {
    int cb = sceKernelCreateCallback("exit_cb", exitCallback, NULL);
    sceKernelRegisterExitCallback(cb);
    sceKernelSleepThreadCB();
    return 0;
}

static void setupCallbacks() {
    int t = sceKernelCreateThread("cb_thread", callbackThread, 0x11, 0xFA0, 0, 0);
    if (t >= 0) sceKernelStartThread(t, 0, 0);
}

int main(int argc, char** argv) {
    setupCallbacks();
    scePowerSetClockFrequency(333, 333, 166);
    sceCtrlSetSamplingCycle(0);
    sceCtrlSetSamplingMode(PSP_CTRL_MODE_ANALOG);

    gfx::init();
    Game* g = new Game();
    g->init(argc > 0 ? argv[0] : 0);

    const u64 TICK_US = 50000;
    u64 last = sceKernelGetSystemTimeWide();
    u64 acc = 0;

    while (g_running) {
        SceCtrlData pad;
        sceCtrlPeekBufferPositive(&pad, 1);
        g->handlePad(pad);

        u64 now = sceKernelGetSystemTimeWide();
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
            last = sceKernelGetSystemTimeWide();
            acc = 0;
        }

        g->render();                                     // incluye vsync (60 Hz)
    }

    if (g->inWorld) g->saveAll();
    gfx::shutdown();
    sceKernelExitGame();
    return 0;
}
