#include <conio.h>
#include "game.h"

extern volatile unsigned char g_keys[256];

static void oskFrame(const char* title, const std::string& cur) {
    using namespace gfx;
    begin();
    clear(0x3A2A1A);
    text(title, SCR_W / 2, 30, 0xFFE24A, TA_TOP | TA_HCENTER);
    rect(30, 60, SCR_W - 60, 20, 0x000000);
    frame(30, 60, SCR_W - 60, 20, 0xA0A0A0);
    std::string shown = cur + (((ticksUs() / 400000) % 2) ? "_" : " ");
    text(shown.c_str(), 36, 66, 0xFFFFFF, TA_TOP | TA_LEFT);
    text("Enter: aceptar  Esc: cancelar", SCR_W / 2, SCR_H - 30, 0xB0B0B0, TA_TOP | TA_HCENTER);
    end();
}

static bool held() { return g_keys[0x1C] || g_keys[0x01] || g_keys[0x39]; }

std::string oskEdit(const char* title, const std::string& init, int maxLen) {
    std::string cur = init;
    if ((int)cur.size() > maxLen) cur.resize(maxLen);
    bool accepted = false, cancelled = false;

    for (int i = 0; i < 90 && held(); i++) oskFrame(title, cur);
    while (kbhit()) getch();

    while (g_running && !accepted && !cancelled) {
        while (kbhit()) {
            int c = getch();
            if (c == 0 || c == 224) { getch(); continue; }
            if (c == 13) accepted = true;
            else if (c == 27) cancelled = true;
            else if (c == 8) { if (!cur.empty()) cur.erase(cur.size() - 1); }
            else if (c >= 32 && c < 127 && (int)cur.size() < maxLen) cur += (char)c;
        }
        oskFrame(title, cur);
    }

    for (int i = 0; i < 90 && held(); i++) oskFrame(title, cur);
    while (kbhit()) getch();

    if (cancelled) return init;
    size_t a = cur.find_first_not_of(' ');
    size_t b = cur.find_last_not_of(' ');
    return (a == std::string::npos) ? std::string() : cur.substr(a, b - a + 1);
}
