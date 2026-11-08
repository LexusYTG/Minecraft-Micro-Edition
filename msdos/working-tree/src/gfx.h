#pragma once
#include <stdint.h>
#include "fixed.h"

uint64_t ticksUs();
int ramFreeK();
int ramUsedK();

namespace gfx {

static const int SCR_W = 320, SCR_H = 200;
static const int FONT_H = 10;

enum { TA_LEFT = 0, TA_HCENTER = 1, TA_RIGHT = 2, TA_TOP = 0, TA_BOTTOM = 4 };

void init();
void shutdown();

void begin();
void clear(uint32_t rgb);
void end();

void tri(Fx x0, Fx y0, Fx x1, Fx y1, Fx x2, Fx y2, uint32_t rgb, int a = 255);
void rect(int x, int y, int w, int h, uint32_t rgb, int a = 255);
void frame(int x, int y, int w, int h, uint32_t rgb);
void line(Fx x0, Fx y0, Fx x1, Fx y1, uint32_t rgb);

void text(const char* s, int x, int y, uint32_t rgb, int anchor);
int  textW(const char* s);
void textS(const char* s, int x, int y, uint32_t rgb, int anchor, int scale);

}
