// Capa grafica minima sobre libdragon (rdpq): triangulos planos en coordenadas de pantalla (320x240)
// + texto con la fuente builtin de libdragon (se dibuja encima de todo al final del frame).
#pragma once
#include <stdint.h>

namespace gfx {

static const int SCR_W = 320, SCR_H = 240;
static const int FONT_H = 10;            // alto de linea (el glifo es 8x8)

enum { TA_LEFT = 0, TA_HCENTER = 1, TA_RIGHT = 2, TA_TOP = 0, TA_BOTTOM = 4 };

void init();
void shutdown();

void begin();                             // adquiere el framebuffer y reinicia la lista de textos
void clear(uint32_t rgb);                 // limpia el color buffer
void end();                               // dibuja los textos, cierra el frame y hace display_show

void tri(float x0, float y0, float x1, float y1, float x2, float y2, uint32_t rgb, int a = 255);
void rect(int x, int y, int w, int h, uint32_t rgb, int a = 255);
void frame(int x, int y, int w, int h, uint32_t rgb);       // contorno de 1 px que ocupa exactamente w x h
void line(float x0, float y0, float x1, float y1, uint32_t rgb);

void text(const char* s, int x, int y, uint32_t rgb, int anchor);   // sin sombra
int  textW(const char* s);
void textS(const char* s, int x, int y, uint32_t rgb, int anchor, int scale);   // scale > 1 = pseudo-negrita

} // namespace gfx
