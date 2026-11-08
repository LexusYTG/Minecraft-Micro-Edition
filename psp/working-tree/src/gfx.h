// Capa grafica minima sobre sceGu: triangulos de color en coordenadas de pantalla (480x272)
// + texto 8x8 con pspDebugScreen (se dibuja encima de todo al final del frame).
#pragma once
#include <stdint.h>

namespace gfx {

static const int SCR_W = 480, SCR_H = 272;
static const int FONT_H = 10;            // alto de linea (el glifo es 8x8)

enum { TA_LEFT = 0, TA_HCENTER = 1, TA_RIGHT = 2, TA_TOP = 0, TA_BOTTOM = 4 };

void init();
void shutdown();

void begin();                             // abre la lista de display y reinicia el lote
void clear(uint32_t rgb);                 // limpia el color buffer
void end();                               // dibuja el lote, texto, vsync y swap
void setPostHook(void (*fn)());           // se llama tras sceGuSync de cada end() (teclado del sistema)

void tri(float x0, float y0, float x1, float y1, float x2, float y2, uint32_t rgb, int a = 255);
void rect(int x, int y, int w, int h, uint32_t rgb, int a = 255);
void frame(int x, int y, int w, int h, uint32_t rgb);       // contorno de 1 px que ocupa exactamente w x h
void line(float x0, float y0, float x1, float y1, uint32_t rgb);

void text(const char* s, int x, int y, uint32_t rgb, int anchor);   // sin sombra
int  textW(const char* s);
void textS(const char* s, int x, int y, uint32_t rgb, int anchor, int scale);   // texto con glifos de 8*scale px

} // namespace gfx
