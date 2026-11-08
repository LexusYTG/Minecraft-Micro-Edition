#pragma once
#include <stdint.h>

struct Fx {
    int v;
    constexpr Fx() : v(0) {}
    constexpr Fx(int i) : v(i << 16) {}
    constexpr Fx(unsigned i) : v((int)(i << 16)) {}
    constexpr Fx(long i) : v((int)(i << 16)) {}
    constexpr Fx(unsigned long i) : v((int)(i << 16)) {}
    constexpr Fx(float f) : v((int)(f * 65536.0f)) {}
    constexpr Fx(double d) : v((int)(d * 65536.0)) {}
    static constexpr Fx raw(int r) { Fx x; x.v = r; return x; }
    explicit operator int() const { return v >= 0 ? (v >> 16) : -((-v) >> 16); }
    explicit operator bool() const { return v != 0; }
    Fx operator-() const { return raw(-v); }
    Fx operator+() const { return *this; }
    Fx& operator++() { v += 65536; return *this; }
    Fx& operator--() { v -= 65536; return *this; }
    Fx operator++(int) { Fx o = *this; v += 65536; return o; }
    Fx operator--(int) { Fx o = *this; v -= 65536; return o; }
};

static inline int fxsat(long long r) { return r > 0x7fffffffLL ? 0x7fffffff : (r < -0x7fffffffLL ? -0x7fffffff : (int)r); }
static inline Fx operator+(Fx a, Fx b) { return Fx::raw(a.v + b.v); }
static inline Fx operator-(Fx a, Fx b) { return Fx::raw(a.v - b.v); }
static inline Fx operator*(Fx a, Fx b) {
    int lo, hi;
    __asm__("imull %3" : "=a"(lo), "=d"(hi) : "a"(a.v), "rm"(b.v) : "cc");
    if ((unsigned)(hi + 32768) > 65535u) return Fx::raw(hi < 0 ? -0x7fffffff : 0x7fffffff);
    return Fx::raw((int)(((unsigned)lo >> 16) | ((unsigned)hi << 16)));
}
static inline Fx operator/(Fx a, Fx b) {
    int av = a.v, bv = b.v;
    if (bv == 0) return Fx::raw(av < 0 ? -0x7fffffff : 0x7fffffff);
    int aa = av < 0 ? -av : av, bb = bv < 0 ? -bv : bv;
    if (aa < 0 || (aa >> 15) >= bb) return Fx::raw(((av < 0) != (bv < 0)) ? -0x7fffffff : 0x7fffffff);
    int q, r;
    __asm__("idivl %4" : "=a"(q), "=d"(r) : "a"(av << 16), "d"(av >> 16), "rm"(bv) : "cc");
    return Fx::raw(q);
}
static inline Fx& operator+=(Fx& a, Fx b) { a.v += b.v; return a; }
static inline Fx& operator-=(Fx& a, Fx b) { a.v -= b.v; return a; }
static inline Fx& operator*=(Fx& a, Fx b) { a = a * b; return a; }
static inline Fx& operator/=(Fx& a, Fx b) { a = a / b; return a; }
static inline bool operator<(Fx a, Fx b) { return a.v < b.v; }
static inline bool operator>(Fx a, Fx b) { return a.v > b.v; }
static inline bool operator<=(Fx a, Fx b) { return a.v <= b.v; }
static inline bool operator>=(Fx a, Fx b) { return a.v >= b.v; }
static inline bool operator==(Fx a, Fx b) { return a.v == b.v; }
static inline bool operator!=(Fx a, Fx b) { return a.v != b.v; }

void fxInit();
Fx fxsin(Fx a);
Fx fxcos(Fx a);
Fx fxtan(Fx a);
Fx fxsqrt(Fx a);
Fx fxatan2(Fx y, Fx x);
Fx fxceil(Fx a);
Fx fxabs(Fx a);
