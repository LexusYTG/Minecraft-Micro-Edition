// Port a C++ para PSP (PSPSDK) de Minecraft2D.java (J2ME).
// Misma logica que el original: mundo por chunks de 16x16x32, render 3D por software
// con algoritmo del pintor, pero las caras se rasterizan con el GPU (sceGu).
#pragma once
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <stdarg.h>
#include <string>
#include <vector>
#include <initializer_list>
#include <pspctrl.h>
#include "gfx.h"

extern volatile bool g_running;

std::string fmt(const char* f, ...);
// teclado en pantalla del sistema (osk.cpp); devuelve "" si se cancela
std::string oskEdit(const char* title, const std::string& init, int maxLen);

// ---------------------------------------------------------------- constantes
static const int CS = 16;              // lado del chunk (debe ser 16: se usa >> 4 y & 15)
static const int H = 32;               // altura del mundo
static const int CHB = CS * CS * H;    // bytes por chunk
static const int NONE = 0x7fffffff;    // slot de chunk vacio
static const int SEA = 15;
static const int MAXC = 9;             // maximo 3x3 chunks en RAM

static const int RD_OPT[5] = { 4, 7, 10, 13, 15 };
static const char* const RD_NAME[5] = { "Minima", "Normal", "Lejos", "Muy lejos", "Extrema" };

enum { ST_MENU, ST_PLAY, ST_INV, ST_DEAD, ST_PAUSE, ST_SET, ST_WORLDS, ST_CREATE, ST_CONFIRM };
enum { A_PLAY = 1, A_QUIT, A_RESUME, A_SETTINGS, A_TOMENU, A_RD, A_SETBACK, A_NEW, A_WBACK,
       A_CNAME, A_CSEED, A_CGO, A_CBACK, A_YES, A_NO, A_CMODE };
enum { J_ENTER = 1, J_APPLY, J_LEAVE, J_SAVE, J_NAME };

enum {
    B_AIR = 0, B_GRASS = 1, B_DIRT = 2, B_STONE = 3, B_LOG = 4,
    B_LEAVES = 5, B_PLANKS = 6, B_TABLE = 7, B_COBBLE = 8,
    B_FURNACE = 9, B_SAPLING = 10, B_TORCH = 11,
    B_SAND = 12, B_SANDSTONE = 13, B_CACTUS = 14, B_TNT = 15,
    B_WATER = 16, B_SNOW = 17, B_GRAVEL = 18, B_ICE = 19,
    I_STICK = 20, I_PICK = 21, I_AXE = 22, I_SHOVEL = 23, I_SWORD = 24,
    I_WOOL = 25, I_MUTTON = 26, I_CHICKEN = 27, I_FEATHER = 28, I_APPLE = 29,
    I_BEEF = 30, I_STEAK = 31, I_COOKED_MUTTON = 32, I_COOKED_CHICKEN = 33, I_COAL = 34,
    I_ROTTEN = 35, I_GUNPOWDER = 36,
    B_DOOR_LC = 37, B_DOOR_UC = 38, B_DOOR_LO = 39, B_DOOR_UO = 40, I_DOOR = 41,
    B_COAL_ORE = 42, B_IRON_ORE = 43, B_GOLD_ORE = 44, B_DIAM_ORE = 45,
    B_IRON_BLK = 46, B_GOLD_BLK = 47, B_DIAM_BLK = 48, B_BED = 49,
    I_IRON_INGOT = 50, I_GOLD_INGOT = 51, I_DIAMOND = 52, I_TOOLS = 56,
    B_NETHERRACK = 53, B_LAVA = 54, B_OBSIDIAN = 55, B_PORTAL = 72, B_SOULSAND = 73,
    B_GLOWSTONE = 74, B_BEDROCK = 75, I_LIGHTER = 76
};

static const float NEAR_Z = 0.1f, FOV = 1.1f, EYE = 1.62f, GRAV = 0.08f, JUMP = 0.52f;
static const float REACH = 5.f, MOB_REACH = 3.5f, LOOK = 0.08f, SPD = 0.16f, HW = 0.3f;
static const int OUT = 45;            // slot virtual de resultado de crafteo
static const int FURN = 4;            // craftN = 4: pantalla del horno (36 entrada, 37 combustible, 38 salida)
static const int SMELT_T = 200;       // ticks (50 ms) por item fundido
static const int DAY_LEN = 24000;     // ticks por dia completo

// caras: +x, -x, +y, -y, +z, -z ; esquinas = x + 2y + 4z
static const int FACE[6][4] = { {1, 3, 7, 5}, {0, 4, 6, 2}, {2, 3, 7, 6}, {0, 4, 5, 1}, {4, 5, 7, 6}, {0, 2, 3, 1} };
static const int FSH[6] = { 80, 80, 100, 50, 65, 65 };
static const float NX[6] = { 1, -1, 0, 0, 0, 0 };
static const float NY[6] = { 0, 0, 1, -1, 0, 0 };
static const float NZ[6] = { 0, 0, 0, 0, 1, -1 };
static const int WIRE[12][2] = { {0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7} };

// ---------------------------------------------------------------- estructuras
struct Mob {
    int type;          // 0 oveja, 1 pollo, 2 vaca, 3 zombie, 4 creeper, 5 TNT encendida
    float x, y, z, vy, ang;
    int timer, hurt, flee, hp, atk, fuse, wind;
    bool moving, ground;
    Mob() { memset(this, 0, sizeof(Mob)); }
};

struct WorldInfo {
    int id, seed, mode;     // mode: 0 supervivencia, 1 creativo
    std::string name;
};

struct Part { float x, y, z, hx, hy, hz; int col; };
struct PartSet { const Part* p; int n; };

// ---------------------------------------------------------------- juego
class Game {
public:
    // ---- general
    int sw, sh;
    float halfW, halfH, focal;
    int tick;
    int state;
    uint32_t rs;                         // estado del xorshift
    std::string baseDir, saveRoot;

    // ---- entrada (ya traducida desde el pad)
    bool kFw, kBk, kJump, kMine;
    float kTurn, kLook;
    unsigned prevBtn;
    int sqT0;                            // tick en que se apreto Cuadrado
    bool sqArm, sqDone;                  // toque corto de Cuadrado pendiente / ya rompio o ataco
    int repCnt[4];

    // ---- chunks (ventana en anillo: slot = coordenada del chunk mod nc)
    uint8_t* chData[MAXC];
    int chCx[MAXC], chCz[MAXC];
    bool chDirty[MAXC];
    int ccx, ccz;
    int seed;
    int rd, nc, nch, nb, rdIdx, rdApplied;
    float cullSq;
    bool needSave, storeBroken, inWorld;
    std::string curDir;

    // ---- mobs
    std::vector<Mob*> mobs;
    int mobKey[64], mobOrd[64];
    int ox[32], oy[H], oz[32], invX[32], invZ[32], invY[H];
    int spawnTimer, hostTimer;

    // ---- dia/noche, horno, brotes, antorchas
    int worldTime, bright;
    int fId[3], fCnt[3];
    int fBurn, fBurnMax, fProg;
    int sapX[32], sapY[32], sapZ[32], sapT[32], sapN;
    float torchX[16], torchY[16], torchZ[16];
    int torchN;
    int dim, portalT, lavaT;
    bool portalLock;
    int hungerFx, cactusT, air, airT;

    // ---- mundos y menus
    std::vector<WorldInfo> worlds;
    WorldInfo curWorld;
    bool hasCur;
    int delTarget;                       // indice de la lista (o -1)
    int jobWorldIdx;
    int hdrDummy, autoT;
    volatile int job;
    std::string busy;
    int navIds[80];
    int navN, navSel, wScroll, wVis;
    int btnW, btnH, btnX;
    int cSeed, cMode;
    bool cSeedRandom;
    std::string cName;

    // ---- jugador
    float px, py, pz, yaw, pitch, vy, fallDist;
    bool onGround, moving;
    int health, hunger, hungerTimer, regenTimer, starveTimer, dmgFlash;

    // ---- camara / render
    float camX, camY, camZ, cosY, sinY, cosP, sinP, fx, fy, fz;
    float quadv[12], clipA[30], cc[24];
    int sxs[10], sys[10];
    bool polyOk;
    int qa, qb, qc, qd, dSh, gx, gy;

    // ---- objetivo y accion
    int tx, ty, tz, ax, ay, az;
    Mob* tMob;
    int attackCd, mineProg, mineX, mineY, mineZ;

    // ---- inventario: 0-35 inventario (0-8 hotbar), 36-44 grilla, 45 resultado
    int slId[46], slCnt[46], slDur[46];
    int sel, labelT, msgT;
    std::string msg;
    bool bedOn;
    int bedX, bedY, bedZ;
    bool creative, flying;
    int jumpTick, palPage;
    int palIds[80], palN;
    int heldId, heldCnt, heldDur;
    int cursor, craftN, slS;
    int slx[46], sly[46];
    bool slActive[46];

    // ---- tablas
    std::string names[80];
    std::string tag[80];
    int icol[80], cTop[80], cSide[80], cBot[80];
    int shadedCol[80][6];
    float hard[80];
    int nrec;
    int rw[48], rh[48], rOut[48], rCnt[48], rPat[48][9];
    int iconPx[80][64];
    bool iconOk[80];

    // ---- depuracion
    bool dbg;
    uint32_t dbgT0;
    int dbgFrames, dbgFps;

    // =============================================================== metodos
    Game();
    void init(const char* argv0);

    // utilidades
    uint32_t rnd32();
    int rnd(int n);
    float rndf();
    static int ifloor(float f) { int i = (int)f; return (f < (float)i) ? i - 1 : i; }
    static int shade(int c, int pct);
    static int tint(int c, int pct);
    static int mix(int a, int b, int p);
    static int tn(int u, int v, int salt);
    int slotOf(int cx, int cz);
    uint8_t* chunkFor(int x, int z);
    int blockAt(int x, int y, int z);
    void setBlock(int x, int y, int z, int b);
    bool solidAt(int x, int y, int z);
    bool boxCollides(float x, float y, float z, float hw, float h);
    int topY(int x, int z);
    int toolType(int id); int toolTier(int id); int toolLevel(int id);
    float toolSpeed(int id); int toolMax(int id); int needLevel(int b);
    bool isTool(int id) { return toolType(id) >= 0; }
    void say(const std::string& t) { msg = t; msgT = 70; }
    bool isBlockItem(int id);
    bool isDoor(int b) { return b >= B_DOOR_LC && b <= B_DOOR_UO; }
    bool isFood(int id); int foodVal(int id);
    int maxStack(int id) { return isTool(id) ? 1 : 64; }

    // tablas
    void initTables(); void initRecipes(); void initPalette();
    void rec(int w, int h, std::initializer_list<int> pat, int out, int cnt);

    // generacion
    int hash(int x, int z, int salt);
    float lat(int ix, int iz, int salt);
    float noise(int x, int z, int shift, int salt);
    int biomeAt(int x, int z);
    bool cactusAt(int x, int z);
    float riverV(int x, int z);
    int heightAt(int x, int z);
    bool treeAt(int x, int z);
    void putTree(uint8_t* d, int x0, int z0, int x, int y, int z, int b, bool force);
    int oreAt(int x, int y, int z, int dep);
    void genNether(int s, int cx, int cz);
    void genChunk(int s, int cx, int cz);

    // almacenamiento (archivos en el Memory Stick)
    std::string worldPath(int id);
    std::string chunkPath(int cx, int cz);
    bool readChunk(int cx, int cz, uint8_t* d);
    void writeChunk(int cx, int cz, const uint8_t* d);
    void writeHeader();
    bool readHeader();
    void saveAll();
    void wipeDir(const std::string& dir, bool removeDir);
    void unloadSlot(int s);
    void loadChunk(int s, int cx, int cz);
    void setupWindow(int newRd);
    int pickOrigin(int cur, int b, bool force);
    void updateWindow(bool force);
    int loadMissing(int max);
    void dropOrphanMobs();
    void clearMobs();
    void spawnMobs(); bool spawnAt(int type, int x, int z); void spawnMob(int type, bool far_);
    void spawnInChunk(int cx, int cz);
    void resetPlayer(); void clearKeys(); void resetPlayerState();
    void enterWorld(const WorldInfo& w);
    void leaveWorld();
    void applyRd();
    void loadWorldList(); void saveWorldList();
    int nextId();
    int addWorld(const std::string& name, int sd, int mode);
    void deleteWorld(int idx);
    void loadSettings(); void saveSettings();

    // menus
    bool isMenu() { return state == ST_MENU || state >= ST_PAUSE; }
    void buildNav(); void go(int st); void navMove(int d);
    void openMain(); void openWorlds(); void openCreate(); void openPause(); void closeSettings();
    void rdStep(int d); void askDelete(int i); void doCreate();
    void activate(int id); void menuBack(); void menuKey(int k);
    void respawn();

    // entrada
    void handlePad(const SceCtrlData& pad);
    void onKey(int k);
    void onKeyRepeat(int k);

    // inventario
    int addItem(int id, int cnt, int dur);
    bool palMode() { return creative && state == ST_INV && craftN == 2; }
    int palAt(int slot); void palFlip(int d); void palTake(int slot, int cnt);
    void clearSlot(int i);
    void openInv(int n); void closeInv(); void layoutInv(); void moveCursor(int k);
    int findRecipe(); void updateOutput(); void consumeGrid(); void takeOutput();
    void click(int idx); void clickSecondary(int idx);

    // logica
    void update();
    void runJobs();
    void hurt(int n);
    void updatePlayer();
    void updateMobs(); int countMobs(bool hostile); void updateMob(Mob* m);
    void castRay();
    int breakTicks(int b, int tool);
    void damageTool(int amount);
    void breakBlock(int x, int y, int z);
    void attackMob(Mob* m);
    void updateAction();
    void consumeSel();
    void doTap();
    void placeBlock(int id);
    void updateSurvival();
    int smeltResult(int id); int fuelTime(int id);
    void updateFurnace(); void syncToSlots(); void syncFromSlots(); void takeFurnaceOut();
    void addSapling(int x, int y, int z); void updateSaplings(); void growTree(int x, int y, int z);
    int dayBright(); int skyColor(); void scanTorches(); int litPct(float x, float y, float z);
    float mobHW(int t); float mobH(int t); float mobR(int t); float mobSpeed(int t); int mobHp(int t);
    void switchDim(int nd);
    bool lavaAtF(float x, float y, float z); bool waterAtF(float x, float y, float z);
    int pb(int axis, int u, int y, int f);
    bool lightPortal(int ax0, int ay0, int az0);
    void buildPortal(int x, int y, int z);
    void usePortal(); void updatePortal();
    bool doorAlongX(int x, int ly, int z);
    void useBed(int x, int y, int z);
    void toggleDoor(int x, int y, int z);
    bool rayInDoor(int hb, int x, int y, int z, float rx, float rz);
    bool losClear(float x0, float y0, float z0, float x1, float y1, float z1);
    bool hostileAI(Mob* m);
    void explode(Mob* m);
    void igniteTnt(int x, int y, int z, int startFuse);
    bool touchingCactus();

    // render
    void render();
    void toCam(float x, float y, float z, float* o, int i);
    int clip(const float* in, int n, float* out);
    void fillPoly(const float* v, int n, int color);
    bool opaque(int b);
    void faceBlock(int x, int y, int z, int f, int color, int b);
    void detailInit(int f, int lp);
    int mk(int c, int pct) { return tint(c, dSh * pct / 100); }
    void pt(float u, float v);
    void dLine(float u0, float v0, float u1, float v1, int col);
    void dRect(float u0, float v0, float u1, float v1, int col);
    void dFrame(float u0, float v0, float u1, float v1, int col);
    void detail(int b, int f, int lp);
    int plankPx(int u, int v, int var);
    int texBlock(int b, int face, int u, int v);
    static int pal(char c);
    bool patOf(int id, std::string* out);
    const int* iconOf(int id);
    void drawBox(float x0, float y0, float z0, float x1, float y1, float z1, int base, int lp);
    void drawSmall(int x, int y, int z, int b);
    bool showFace(int b, int n);
    void drawBlock(int x, int y, int z, int b);
    bool isFrameBlk(int b) { return b == B_OBSIDIAN || b == B_PORTAL; }
    void drawPortal(int x, int y, int z); void drawBed(int x, int y, int z); void drawDoor(int x, int y, int z, int b);
    void drawMob(Mob* m);
    void line3(const float* v, int i, int j);
    void drawWire(int x, int y, int z);
    void buildOrder(int* o, int n, int c);
    void paintWorld();
    void txt(const std::string& s, int x, int y, int c, int anchor);
    void drawIcon(int id, int dur, int x, int y, int s);
    void drawCount(int cnt, int x, int y, int s);
    void paintHud(); void paintInv();
    std::string fit(const std::string& s, int maxW);
    bool isSel(int id) { return navN > 0 && navIds[navSel] == id; }
    void metrics(); void drawDirt(int pct); void dimScreen();
    void title(const std::string& s, int y, int col, int shadow);
    void drawLogo(int y);
    void drawBtn(int x, int y, int w, int h, const std::string& label, bool on);
    void btn(int id, const std::string& label, int x, int y, int w, int h);
    void paintMenu(); void paintWorlds(); void paintCreate(); void paintConfirm();
    void paintPause(); void paintSettings(); void paintDead(); void paintDebug();
};
