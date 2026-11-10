// Teclado en pantalla del sistema (equivalente al TextBox de J2ME) para escribir el nombre del mundo.
#include "game.h"
#include <pspkernel.h>
#include <pspctrl.h>
#include <pspdisplay.h>
#include <psputility.h>

static bool g_oskDone = false;

// se ejecuta una vez por frame, despues de cerrar la lista de display y antes del swap
static void oskHook() {
    switch (sceUtilityOskGetStatus()) {
        case PSP_UTILITY_DIALOG_VISIBLE: sceUtilityOskUpdate(1); break;
        case PSP_UTILITY_DIALOG_QUIT: sceUtilityOskShutdownStart(); break;
        case PSP_UTILITY_DIALOG_NONE: g_oskDone = true; break;
        default: break;
    }
}

static void toUtf16(const std::string& s, unsigned short* d, int cap) {
    int n = 0;
    for (size_t i = 0; i < s.size() && n < cap - 1; i++) d[n++] = (unsigned char)s[i];
    d[n] = 0;
}

std::string oskEdit(const char* title, const std::string& init, int maxLen) {
    static unsigned short intext[128], outtext[128], desc[128];
    memset(intext, 0, sizeof(intext));
    memset(outtext, 0, sizeof(outtext));
    memset(desc, 0, sizeof(desc));
    toUtf16(init, intext, 128);
    toUtf16(title, desc, 128);

    SceUtilityOskData data;
    memset(&data, 0, sizeof(data));
    data.language = PSP_UTILITY_OSK_LANGUAGE_DEFAULT;
    data.lines = 1;
    data.unk_24 = 1;
    data.inputtype = PSP_UTILITY_OSK_INPUTTYPE_ALL;
    data.desc = desc;
    data.intext = intext;
    data.outtextlength = 128;
    data.outtextlimit = maxLen;
    data.outtext = outtext;

    SceUtilityOskParams params;
    memset(&params, 0, sizeof(params));
    params.base.size = sizeof(params);
    sceUtilityGetSystemParamInt(PSP_SYSTEMPARAM_ID_INT_LANGUAGE, &params.base.language);
    sceUtilityGetSystemParamInt(PSP_SYSTEMPARAM_ID_INT_UNKNOWN, &params.base.buttonSwap);
    params.base.graphicsThread = 17;
    params.base.accessThread = 19;
    params.base.fontThread = 18;
    params.base.soundThread = 16;
    params.datacount = 1;
    params.data = &data;

    if (sceUtilityOskInitStart(&params) < 0) return "";

    g_oskDone = false;
    gfx::setPostHook(oskHook);
    while (g_running && !g_oskDone) {
        gfx::begin();
        gfx::clear(0x3A2A1A);
        gfx::end();
    }
    gfx::setPostHook(0);

    // esperar a que se suelten los botones para que el menu no reciba el "Circulo" o la "X" del teclado
    for (int i = 0; i < 90; i++) {
        SceCtrlData pad;
        sceCtrlPeekBufferPositive(&pad, 1);
        if (!(pad.Buttons & 0xFFFF)) break;
        sceDisplayWaitVblankStart();
    }

    std::string out;
    if (g_oskDone && data.result == PSP_UTILITY_OSK_RESULT_CHANGED) {
        for (int i = 0; i < 127 && outtext[i]; i++) {
            unsigned c = outtext[i];
            out += (c >= 32 && c < 127) ? (char)c : '?';
        }
        size_t a = out.find_first_not_of(' ');
        size_t b = out.find_last_not_of(' ');
        out = (a == std::string::npos) ? std::string() : out.substr(a, b - a + 1);
    } else if (g_oskDone) {
        return init;                 // cancelado: se conserva el nombre actual
    }
    return out;
}
