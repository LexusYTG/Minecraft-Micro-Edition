#include "game.h"
#include <pspkernel.h>
#include <pspiofilemgr.h>

std::string fmt(const char* f, ...) {
    char buf[256];
    va_list ap;
    va_start(ap, f);
    vsnprintf(buf, sizeof(buf), f, ap);
    va_end(ap);
    return std::string(buf);
}

// ---------------------------------------------------------------- inicio
Game::Game() {
    sw = gfx::SCR_W; sh = gfx::SCR_H;
    halfW = sw / 2.f; halfH = sh / 2.f; focal = 1.f;
    tick = 0; state = ST_MENU; rs = 0x9E3779B9u;
    kFw = kBk = kJump = kMine = false; kTurn = kLook = 0.f; prevBtn = 0; sqT0 = 0; sqArm = sqDone = false;
    for (int i = 0; i < 4; i++) repCnt[i] = 0;
    for (int i = 0; i < MAXC; i++) { chData[i] = (uint8_t*)malloc(CHB); chCx[i] = NONE; chCz[i] = NONE; chDirty[i] = false; }
    ccx = ccz = 0; seed = 0;
    rd = 7; nc = 2; nch = 4; nb = 15; rdIdx = 1; rdApplied = 1; cullSq = 528.f;
    needSave = storeBroken = inWorld = false;
    spawnTimer = hostTimer = 0;
    worldTime = 1000; bright = 100;
    for (int i = 0; i < 3; i++) { fId[i] = 0; fCnt[i] = 0; }
    fBurn = fBurnMax = fProg = 0; sapN = 0; torchN = 0;
    dim = portalT = lavaT = 0; portalLock = false;
    hungerFx = cactusT = airT = 0; air = 300;
    hasCur = false; delTarget = -1; jobWorldIdx = -1; hdrDummy = 0; autoT = 0; job = 0;
    navN = 1; navSel = 0; wScroll = 0; wVis = 3; navIds[0] = 0;
    btnW = btnH = btnX = 0; cSeed = 0; cMode = 0; cSeedRandom = true;
    px = py = pz = yaw = pitch = vy = fallDist = 0.f;
    onGround = moving = false;
    health = hunger = 20; hungerTimer = regenTimer = starveTimer = dmgFlash = 0;
    camX = camY = camZ = cosY = sinY = cosP = sinP = fx = fy = fz = 0.f;
    polyOk = false; qa = qb = qc = qd = dSh = gx = gy = 0;
    tx = ty = tz = ax = ay = az = -1; tMob = 0;
    attackCd = mineProg = 0; mineX = mineY = mineZ = -1;
    for (int i = 0; i < 46; i++) { slId[i] = slCnt[i] = slDur[i] = 0; slx[i] = sly[i] = 0; slActive[i] = false; }
    sel = labelT = msgT = 0;
    bedOn = false; bedX = bedY = bedZ = 0;
    creative = flying = false; jumpTick = -100; palPage = 0; palN = 0;
    heldId = heldCnt = heldDur = 0; cursor = 0; craftN = 2; slS = 20;
    nrec = 0;
    for (int i = 0; i < 80; i++) iconOk[i] = false;
    dbg = true; dbgT0 = 0; dbgFrames = dbgFps = 0;
    for (int i = 0; i < 64; i++) { mobKey[i] = 0; mobOrd[i] = 0; }
}

static void mkdirp(const std::string& p) {
    for (size_t i = 1; i < p.size(); i++)
        if (p[i] == '/' && p[i - 1] != ':') sceIoMkdir(p.substr(0, i).c_str(), 0777);
    sceIoMkdir(p.c_str(), 0777);
}

void Game::init(const char* argv0) {
    rs ^= sceKernelGetSystemTimeLow();
    if (!rs) rs = 1;
    std::string b = argv0 ? argv0 : "";
    size_t p = b.find_last_of('/');
    baseDir = (p == std::string::npos) ? "ms0:/PSP/GAME/Minecraft2D/" : b.substr(0, p + 1);
    saveRoot = baseDir + "saves";
    mkdirp(saveRoot);
    initTables();
    initRecipes();
    initPalette();
    loadSettings();
    setupWindow(RD_OPT[rdIdx]);
    go(ST_MENU);
}

// ---------------------------------------------------------------- utilidades
uint32_t Game::rnd32() { rs ^= rs << 13; rs ^= rs >> 17; rs ^= rs << 5; return rs; }
int Game::rnd(int n) { return (int)((rnd32() & 0x7fffffffu) % (uint32_t)n); }
float Game::rndf() { return rnd(10000) / 10000.f; }

int Game::shade(int c, int pct) {
    int r = ((c >> 16) & 255) * pct / 100;
    int g = ((c >> 8) & 255) * pct / 100;
    int b = (c & 255) * pct / 100;
    return (r << 16) | (g << 8) | b;
}

int Game::tint(int c, int pct) {
    int r = ((c >> 16) & 255) * pct / 100;
    int g = ((c >> 8) & 255) * pct / 100;
    int b = (c & 255) * pct / 100;
    if (r > 255) r = 255;
    if (g > 255) g = 255;
    if (b > 255) b = 255;
    if (r < 0) r = 0;
    if (g < 0) g = 0;
    if (b < 0) b = 0;
    return (r << 16) | (g << 8) | b;
}

int Game::mix(int a, int b, int p) {
    int r = (((a >> 16) & 255) * p + ((b >> 16) & 255) * (100 - p)) / 100;
    int g = (((a >> 8) & 255) * p + ((b >> 8) & 255) * (100 - p)) / 100;
    int bl = ((a & 255) * p + (b & 255) * (100 - p)) / 100;
    return (r << 16) | (g << 8) | bl;
}

int Game::tn(int u, int v, int salt) {     // ruido determinista 0..255 por pixel
    uint32_t h = ((uint32_t)u * 73856093u) ^ ((uint32_t)v * 19349663u) ^ ((uint32_t)salt * 83492791u);
    h = (h ^ (h >> 13)) * 1274126177u;
    h = h ^ (h >> 16);
    return (int)(h & 255u);
}

int Game::slotOf(int cx, int cz) {
    int sx = cx % nc, sz = cz % nc;
    if (sx < 0) sx += nc;
    if (sz < 0) sz += nc;
    return sz * nc + sx;
}

uint8_t* Game::chunkFor(int x, int z) {
    int cx = x >> 4, cz = z >> 4;
    int s = slotOf(cx, cz);
    if (chCx[s] != cx || chCz[s] != cz) return 0;
    return chData[s];
}

int Game::blockAt(int x, int y, int z) {
    if (y < 0 || y >= H) return 0;
    uint8_t* cd = chunkFor(x, z);
    if (!cd) return 0;
    return cd[(((x & 15) << 4) + (z & 15)) * H + y];
}

void Game::setBlock(int x, int y, int z, int b) {
    if (y < 0 || y >= H) return;
    int cx = x >> 4, cz = z >> 4;
    int s = slotOf(cx, cz);
    if (chCx[s] != cx || chCz[s] != cz) return;
    chData[s][(((x & 15) << 4) + (z & 15)) * H + y] = (uint8_t)b;
    chDirty[s] = true;
}

bool Game::solidAt(int x, int y, int z) {
    if (y < 0) return true;
    if (y >= H) return false;
    uint8_t* cd = chunkFor(x, z);
    if (!cd) return true;
    int b = cd[(((x & 15) << 4) + (z & 15)) * H + y];
    return b != 0 && b != B_SAPLING && b != B_TORCH && b != B_WATER && b != B_DOOR_LO && b != B_DOOR_UO && b != B_LAVA && b != B_PORTAL;
}

bool Game::boxCollides(float x, float y, float z, float hw, float h) {
    int x0 = ifloor(x - hw), x1 = ifloor(x + hw);
    int y0 = ifloor(y), y1 = ifloor(y + h);
    int z0 = ifloor(z - hw), z1 = ifloor(z + hw);
    for (int xx = x0; xx <= x1; xx++)
        for (int yy = y0; yy <= y1; yy++)
            for (int zz = z0; zz <= z1; zz++)
                if (solidAt(xx, yy, zz)) return true;
    return false;
}

int Game::topY(int x, int z) {
    for (int y = H - 1; y >= 0; y--) if (blockAt(x, y, z) != 0) return y;
    return -1;
}

// tipo: 0 pico, 1 hacha, 2 pala, 3 espada, -1 no es herramienta
int Game::toolType(int id) {
    if (id >= I_PICK && id <= I_SWORD) return id - I_PICK;
    if (id >= I_TOOLS && id < I_TOOLS + 16) return (id - I_TOOLS) & 3;
    return -1;
}
// material: 0 madera, 1 piedra, 2 hierro, 3 oro, 4 diamante
int Game::toolTier(int id) {
    if (id >= I_PICK && id <= I_SWORD) return 0;
    if (id >= I_TOOLS && id < I_TOOLS + 16) return 1 + (id - I_TOOLS) / 4;
    return -1;
}
int Game::toolLevel(int id) {
    int t = toolTier(id);
    if (t < 0) return -1;
    return t == 0 ? 0 : (t == 1 ? 1 : (t == 2 ? 2 : (t == 3 ? 0 : 3)));
}
float Game::toolSpeed(int id) {
    int t = toolTier(id);
    return t == 0 ? 2.f : (t == 1 ? 4.f : (t == 2 ? 6.f : (t == 3 ? 12.f : 8.f)));
}
int Game::toolMax(int id) {
    int t = toolTier(id);
    return t == 0 ? 59 : (t == 1 ? 131 : (t == 2 ? 250 : (t == 3 ? 32 : 1561)));
}
int Game::needLevel(int b) {
    if (b == B_OBSIDIAN) return 3;
    if (b == B_IRON_ORE || b == B_IRON_BLK) return 1;
    if (b == B_GOLD_ORE || b == B_DIAM_ORE || b == B_GOLD_BLK || b == B_DIAM_BLK) return 2;
    return 0;
}
bool Game::isBlockItem(int id) {
    return (id >= 1 && id <= 19) || id == I_DOOR || (id >= B_COAL_ORE && id <= B_BED)
        || (id >= B_NETHERRACK && id <= B_OBSIDIAN) || id == B_SOULSAND || id == B_GLOWSTONE;
}
bool Game::isFood(int id) {
    return id == I_APPLE || id == I_MUTTON || id == I_CHICKEN || id == I_BEEF || id == I_STEAK
        || id == I_COOKED_MUTTON || id == I_COOKED_CHICKEN || id == I_ROTTEN;
}
int Game::foodVal(int id) {
    if (id == I_STEAK) return 8;
    if (id == I_COOKED_MUTTON || id == I_COOKED_CHICKEN) return 6;
    if (id == I_APPLE) return 4;
    if (id == I_BEEF) return 3;
    return 2;
}

// ---------------------------------------------------------------- tablas
void Game::initTables() {
    for (int i = 0; i < 80; i++) {
        names[i] = "?"; tag[i] = ""; icol[i] = 0x808080;
        cTop[i] = cSide[i] = cBot[i] = 0; hard[i] = 0.f;
    }
    names[0] = "";
    names[1] = "Pasto"; names[2] = "Tierra"; names[3] = "Piedra"; names[4] = "Tronco";
    names[5] = "Hojas"; names[6] = "Tablones"; names[7] = "Mesa de crafteo"; names[8] = "Adoquin";
    names[I_STICK] = "Palo"; names[I_PICK] = "Pico de madera"; names[I_AXE] = "Hacha de madera";
    names[I_SHOVEL] = "Pala de madera"; names[I_SWORD] = "Espada de madera";
    names[I_WOOL] = "Lana"; names[I_MUTTON] = "Cordero crudo"; names[I_CHICKEN] = "Pollo crudo";
    names[I_FEATHER] = "Pluma"; names[I_APPLE] = "Manzana";
    names[B_FURNACE] = "Horno"; names[B_SAPLING] = "Brote"; names[B_TORCH] = "Antorcha";
    names[B_SAND] = "Arena"; names[B_SANDSTONE] = "Arenisca"; names[B_CACTUS] = "Cactus"; names[B_TNT] = "Dinamita";
    names[B_WATER] = "Agua"; names[B_SNOW] = "Nieve"; names[B_GRAVEL] = "Grava"; names[B_ICE] = "Hielo";
    names[I_BEEF] = "Carne de vaca cruda"; names[I_STEAK] = "Bistec";
    names[I_COOKED_MUTTON] = "Cordero cocido"; names[I_COOKED_CHICKEN] = "Pollo cocido";
    names[I_COAL] = "Carbon vegetal"; names[I_ROTTEN] = "Carne podrida"; names[I_GUNPOWDER] = "Polvora";
    icol[I_BEEF] = 0xA83232; icol[I_STEAK] = 0x6B3A22; icol[I_COOKED_MUTTON] = 0x8A5A3A;
    icol[I_COOKED_CHICKEN] = 0xC8905A; icol[I_COAL] = 0x2A2A2A; icol[I_ROTTEN] = 0x6B7A3A;
    icol[I_GUNPOWDER] = 0x555555;

    tag[I_PICK] = "Pi"; tag[I_AXE] = "Ha"; tag[I_SHOVEL] = "Pa"; tag[I_SWORD] = "Es";
    icol[I_STICK] = 0x8B6B3A;
    icol[I_PICK] = 0xC49A55; icol[I_AXE] = 0xC49A55; icol[I_SHOVEL] = 0xC49A55; icol[I_SWORD] = 0xC49A55;
    icol[I_WOOL] = 0xEFEFEF; icol[I_MUTTON] = 0xB5453F; icol[I_CHICKEN] = 0xE9B9A3;
    icol[I_FEATHER] = 0xD8D8D8; icol[I_APPLE] = 0xD02020;

    cTop[1] = 0x55A630; cSide[1] = 0x7B5B34; cBot[1] = 0x7B5B34;
    cTop[2] = 0x7B5B34; cSide[2] = 0x7B5B34; cBot[2] = 0x7B5B34;
    cTop[3] = 0x7D7D7D; cSide[3] = 0x7D7D7D; cBot[3] = 0x7D7D7D;
    cTop[4] = 0xA98B54; cSide[4] = 0x5E4426; cBot[4] = 0xA98B54;
    cTop[5] = 0x2F7A2A; cSide[5] = 0x2F7A2A; cBot[5] = 0x2F7A2A;
    cTop[6] = 0xB8945A; cSide[6] = 0xB8945A; cBot[6] = 0xB8945A;
    cTop[7] = 0x8E6A3C; cSide[7] = 0x9C7440; cBot[7] = 0xB8945A;
    cTop[8] = 0x666666; cSide[8] = 0x666666; cBot[8] = 0x666666;
    cTop[9] = 0x8A8A8A; cSide[9] = 0x4F4F4F; cBot[9] = 0x6A6A6A;
    cTop[10] = 0x5FD04A; cSide[10] = 0x3FA03A; cBot[10] = 0x3FA03A;
    cTop[11] = 0xFFD040; cSide[11] = 0x8B6B3A; cBot[11] = 0x8B6B3A;
    cTop[12] = 0xE0D49A; cSide[12] = 0xE0D49A; cBot[12] = 0xE0D49A;
    cTop[13] = 0xD8CC8C; cSide[13] = 0xD2C583; cBot[13] = 0xD8CC8C;
    cTop[14] = 0x3D8A3A; cSide[14] = 0x2E7A2E; cBot[14] = 0x3D8A3A;
    cTop[15] = 0xC8C8C8; cSide[15] = 0xB22A2A; cBot[15] = 0x888888;
    cTop[16] = 0x2A5CE0; cSide[16] = 0x2A5CE0; cBot[16] = 0x2A5CE0;
    cTop[17] = 0xF4F8FF; cSide[17] = 0xE6EEF8; cBot[17] = 0xF4F8FF;
    cTop[18] = 0x8A8480; cSide[18] = 0x7E7874; cBot[18] = 0x8A8480;
    cTop[19] = 0x9CCBF0; cSide[19] = 0x8CBDE6; cBot[19] = 0x9CCBF0;
    for (int b = B_COAL_ORE; b <= B_DIAM_ORE; b++) { cTop[b] = 0x7D7D7D; cSide[b] = 0x7D7D7D; cBot[b] = 0x7D7D7D; }
    cTop[B_IRON_BLK] = cSide[B_IRON_BLK] = cBot[B_IRON_BLK] = 0xD8D8D8;
    cTop[B_GOLD_BLK] = cSide[B_GOLD_BLK] = cBot[B_GOLD_BLK] = 0xF2D040;
    cTop[B_DIAM_BLK] = cSide[B_DIAM_BLK] = cBot[B_DIAM_BLK] = 0x5AE8E0;
    cTop[B_BED] = 0xC83A34; cSide[B_BED] = 0x6B4A2A; cBot[B_BED] = 0x6B4A2A;
    cTop[B_NETHERRACK] = cSide[B_NETHERRACK] = cBot[B_NETHERRACK] = 0x7A2E2E;
    cTop[B_LAVA] = cSide[B_LAVA] = cBot[B_LAVA] = 0xE0601A;
    cTop[B_OBSIDIAN] = cSide[B_OBSIDIAN] = cBot[B_OBSIDIAN] = 0x1C0F2E;
    cTop[B_SOULSAND] = cSide[B_SOULSAND] = cBot[B_SOULSAND] = 0x5A4636;
    cTop[B_GLOWSTONE] = cSide[B_GLOWSTONE] = cBot[B_GLOWSTONE] = 0xF2D488;
    cTop[B_BEDROCK] = cSide[B_BEDROCK] = cBot[B_BEDROCK] = 0x444444;
    cTop[B_PORTAL] = cSide[B_PORTAL] = cBot[B_PORTAL] = 0x8A2BE2;
    for (int b = 1; b < 80; b++) {
        for (int f = 0; f < 6; f++) {
            int base = (f == 2) ? cTop[b] : (f == 3 ? cBot[b] : cSide[b]);
            shadedCol[b][f] = shade(base, FSH[f]);
        }
    }
    hard[1] = 0.6f; hard[2] = 0.5f; hard[3] = 1.5f; hard[4] = 2.f;
    hard[5] = 0.2f; hard[6] = 2.f; hard[7] = 2.5f; hard[8] = 2.f;
    hard[9] = 3.5f; hard[10] = 0.f; hard[11] = 0.f;
    hard[12] = 0.5f; hard[13] = 0.8f; hard[14] = 0.4f; hard[15] = 0.f;
    hard[16] = 0.f; hard[17] = 0.2f; hard[18] = 0.6f; hard[19] = 0.5f;
    hard[B_DOOR_LC] = hard[B_DOOR_UC] = hard[B_DOOR_LO] = hard[B_DOOR_UO] = 1.5f;
    names[B_DOOR_LC] = names[B_DOOR_UC] = names[B_DOOR_LO] = names[B_DOOR_UO] = names[I_DOOR] = "Puerta";
    icol[I_DOOR] = 0x9C7A44;
    hard[B_COAL_ORE] = hard[B_IRON_ORE] = hard[B_GOLD_ORE] = hard[B_DIAM_ORE] = 3.f;
    hard[B_IRON_BLK] = 5.f; hard[B_GOLD_BLK] = 3.f; hard[B_DIAM_BLK] = 5.f; hard[B_BED] = 0.2f;
    names[B_COAL_ORE] = "Mena de carbon"; names[B_IRON_ORE] = "Mena de hierro";
    names[B_GOLD_ORE] = "Mena de oro"; names[B_DIAM_ORE] = "Mena de diamante";
    names[B_IRON_BLK] = "Bloque de hierro"; names[B_GOLD_BLK] = "Bloque de oro";
    names[B_DIAM_BLK] = "Bloque de diamante"; names[B_BED] = "Cama";
    names[I_IRON_INGOT] = "Lingote de hierro"; names[I_GOLD_INGOT] = "Lingote de oro"; names[I_DIAMOND] = "Diamante";
    icol[I_IRON_INGOT] = 0xD8D8D8; icol[I_GOLD_INGOT] = 0xF0C020; icol[I_DIAMOND] = 0x4AE8E0;
    hard[B_NETHERRACK] = 0.4f; hard[B_LAVA] = 0.f; hard[B_OBSIDIAN] = 25.f; hard[B_SOULSAND] = 0.5f;
    hard[B_GLOWSTONE] = 0.3f; hard[B_BEDROCK] = 100000.f; hard[B_PORTAL] = 0.f;
    names[B_NETHERRACK] = "Roca del infierno"; names[B_LAVA] = "Lava"; names[B_OBSIDIAN] = "Obsidiana";
    names[B_PORTAL] = "Portal"; names[B_SOULSAND] = "Arena de almas"; names[B_GLOWSTONE] = "Piedra luminosa";
    names[B_BEDROCK] = "Lecho de roca"; names[I_LIGHTER] = "Encendedor";
    icol[I_LIGHTER] = 0xD8D8D8;
    static const char* const matN[] = { "madera", "piedra", "hierro", "oro", "diamante" };
    static const char* const typN[] = { "Pico", "Hacha", "Pala", "Espada" };
    static const int matC[] = { 0xC49A55, 0x8A8A8A, 0xD8D8D8, 0xF0D030, 0x4AE8E0 };
    for (int t = 1; t < 5; t++) {
        for (int k = 0; k < 4; k++) {
            int tid = I_TOOLS + (t - 1) * 4 + k;
            names[tid] = std::string(typN[k]) + " de " + matN[t];
            tag[tid] = tag[I_PICK + k];
            icol[tid] = matC[t];
        }
    }
}

void Game::rec(int w, int h, std::initializer_list<int> pat, int out, int cnt) {
    rw[nrec] = w; rh[nrec] = h; rOut[nrec] = out; rCnt[nrec] = cnt;
    int i = 0;
    for (int v : pat) rPat[nrec][i++] = v;
    nrec++;
}

void Game::initRecipes() {
    int P = B_PLANKS, S = I_STICK;
    nrec = 0;
    rec(1, 1, { B_LOG }, B_PLANKS, 4);
    rec(1, 2, { P, P }, I_STICK, 4);
    rec(2, 2, { P, P, P, P }, B_TABLE, 1);
    rec(3, 3, { P, P, P, 0, S, 0, 0, S, 0 }, I_PICK, 1);
    rec(2, 3, { P, P, P, S, 0, S }, I_AXE, 1);
    rec(1, 3, { P, S, S }, I_SHOVEL, 1);
    rec(1, 3, { P, P, S }, I_SWORD, 1);
    rec(1, 2, { I_COAL, S }, B_TORCH, 4);
    int C = B_COBBLE;
    rec(3, 3, { C, C, C, C, 0, C, C, C, C }, B_FURNACE, 1);
    rec(1, 2, { I_COAL, B_SAND }, I_GUNPOWDER, 2);
    int G = I_GUNPOWDER, SA = B_SAND;
    rec(3, 3, { SA, G, SA, G, SA, G, SA, G, SA }, B_TNT, 1);
    rec(2, 3, { P, P, P, P, P, P }, I_DOOR, 1);
    int mats[4] = { B_COBBLE, I_IRON_INGOT, I_GOLD_INGOT, I_DIAMOND };
    for (int t = 0; t < 4; t++) {
        int M = mats[t], tb = I_TOOLS + t * 4;
        rec(3, 3, { M, M, M, 0, S, 0, 0, S, 0 }, tb, 1);
        rec(2, 3, { M, M, M, S, 0, S }, tb + 1, 1);
        rec(1, 3, { M, S, S }, tb + 2, 1);
        rec(1, 3, { M, M, S }, tb + 3, 1);
    }
    int ii = I_IRON_INGOT, gi = I_GOLD_INGOT, di = I_DIAMOND;
    rec(3, 3, { ii, ii, ii, ii, ii, ii, ii, ii, ii }, B_IRON_BLK, 1);
    rec(3, 3, { gi, gi, gi, gi, gi, gi, gi, gi, gi }, B_GOLD_BLK, 1);
    rec(3, 3, { di, di, di, di, di, di, di, di, di }, B_DIAM_BLK, 1);
    rec(1, 1, { B_IRON_BLK }, I_IRON_INGOT, 9);
    rec(1, 1, { B_GOLD_BLK }, I_GOLD_INGOT, 9);
    rec(1, 1, { B_DIAM_BLK }, I_DIAMOND, 9);
    int WL = I_WOOL;
    rec(3, 2, { WL, WL, WL, P, P, P }, B_BED, 1);
    rec(1, 2, { I_IRON_INGOT, I_COAL }, I_LIGHTER, 1);
}

void Game::initPalette() {
    palN = 0;
    for (int id = 1; id <= 19; id++) palIds[palN++] = id;
    for (int id = B_COAL_ORE; id <= B_BED; id++) palIds[palN++] = id;
    palIds[palN++] = I_DOOR;
    palIds[palN++] = B_NETHERRACK; palIds[palN++] = B_LAVA; palIds[palN++] = B_OBSIDIAN;
    palIds[palN++] = B_SOULSAND; palIds[palN++] = B_GLOWSTONE; palIds[palN++] = I_LIGHTER;
    for (int id = I_STICK; id <= I_GUNPOWDER; id++) palIds[palN++] = id;
    for (int id = I_IRON_INGOT; id <= I_DIAMOND; id++) palIds[palN++] = id;
    for (int id = I_TOOLS; id < I_TOOLS + 16; id++) palIds[palN++] = id;
}

// ---------------------------------------------------------------- generacion
int Game::hash(int x, int z, int salt) {
    uint32_t h = (uint32_t)x * 374761393u + (uint32_t)z * 668265263u + (uint32_t)salt * 1274126177u + (uint32_t)seed;
    h = (h ^ (h >> 13)) * 1274126177u;
    h = h ^ (h >> 16);
    return (int)(h & 0x7fffffffu);
}

float Game::lat(int ix, int iz, int salt) { return (hash(ix, iz, salt) % 1000) / 1000.f; }

float Game::noise(int x, int z, int shift, int salt) {
    int ix = x >> shift, iz = z >> shift;
    int mask = (1 << shift) - 1;
    float size = (float)(1 << shift);
    float u = (x & mask) / size, w = (z & mask) / size;
    u = u * u * (3.f - 2.f * u);
    w = w * w * (3.f - 2.f * w);
    float n00 = lat(ix, iz, salt), n10 = lat(ix + 1, iz, salt);
    float n01 = lat(ix, iz + 1, salt), n11 = lat(ix + 1, iz + 1, salt);
    float p = n00 + (n10 - n00) * u;
    float q = n01 + (n11 - n01) * u;
    return p + (q - p) * w;
}

int Game::biomeAt(int x, int z) {
    if (noise(x, z, 6, 13) > 0.6f) return 1;
    if (noise(x, z, 6, 29) > 0.62f) return 2;
    if (noise(x, z, 6, 41) > 0.6f) return 3;
    if (noise(x, z, 6, 53) > 0.66f) return 4;
    return 0;
}

bool Game::cactusAt(int x, int z) {
    if (hash(x, z, 10) % 36 != 0) return false;
    return abs(x) + abs(z) >= 5;
}

float Game::riverV(int x, int z) {
    float r = noise(x, z, 6, 21) - 0.5f;
    return r < 0.f ? -r : r;
}

int Game::heightAt(int x, int z) {
    float v1 = noise(x, z, 3, 1), v2 = noise(x, z, 5, 2);
    int h = 12 + (int)(v1 * 4.f + v2 * 6.f);
    float cont = 0.38f - noise(x, z, 7, 61);
    if (cont > 0.f) h -= (int)(cont * 40.f);
    if (biomeAt(x, z) == 4) {
        h += (int)((noise(x, z, 6, 53) - 0.66f) * 70.f);
        if (h > H - 4) h = H - 4;
    } else {
        float rv = riverV(x, z);
        if (rv < 0.025f) {
            if (h > SEA - 2) h = SEA - 2;
        } else if (rv < 0.06f) {
            int cap = SEA + 1 + (int)((rv - 0.025f) * 80.f);
            if (h > cap) h = cap;
        }
    }
    if (h < 3) h = 3;
    return h;
}

bool Game::treeAt(int x, int z) {
    int hh = hash(x, z, 7);
    if (hh % 12 != 0) return false;
    int bio = biomeAt(x, z);
    if (bio == 1 || bio == 4) return false;
    int mod = bio == 3 ? 12 : (bio == 2 ? 72 : 48);
    if (hh % mod != 0) return false;
    if (abs(x) + abs(z) < 5) return false;
    if (noise(x, z, 3, 1) > 0.82f) return false;
    return heightAt(x, z) > SEA + 1;
}

void Game::putTree(uint8_t* d, int x0, int z0, int x, int y, int z, int b, bool force) {
    if (y < 0 || y >= H) return;
    int lx = x - x0, lz = z - z0;
    if (lx < 0 || lx >= CS || lz < 0 || lz >= CS) return;
    int i = (lx * CS + lz) * H + y;
    if (force || d[i] == 0) d[i] = (uint8_t)b;
}

int Game::oreAt(int x, int y, int z, int dep) {
    if (dep < 2) return B_STONE;
    int c = hash(x >> 1, z >> 1, (y >> 1) + 200) % 1000;
    int t;
    if (c < 22) t = B_COAL_ORE;
    else if (c >= 22 && c < 37 && dep >= 4) t = B_IRON_ORE;
    else if (c >= 37 && c < 43 && y <= 12 && dep >= 4) t = B_GOLD_ORE;
    else if (c >= 43 && c < 47 && y <= 7 && dep >= 5) t = B_DIAM_ORE;
    else if (c >= 47 && c < 58 && y <= 6 && dep >= 6) t = B_OBSIDIAN;
    else return B_STONE;
    return (hash(x, z, y + 300) % 10 < 7) ? t : B_STONE;
}

void Game::genNether(int s, int cx, int cz) {
    uint8_t* d = chData[s];
    memset(d, 0, CHB);
    int x0 = cx << 4, z0 = cz << 4;
    for (int lx = 0; lx < CS; lx++) {
        for (int lz = 0; lz < CS; lz++) {
            int x = x0 + lx, z = z0 + lz;
            int fl = 6 + (int)(noise(x, z, 4, 11) * 7.f);
            int ce = 25 - (int)(noise(x, z, 4, 12) * 4.f);
            if (hash(x, z, 52) % 22 == 0) ce -= 3;
            if (hash(x, z, 53) % 24 == 0) fl += 2;
            int base = (lx * CS + lz) * H;
            for (int y = 0; y < H; y++) {
                int blk = 0;
                if (y == 0 || y == H - 1) blk = B_BEDROCK;
                else if (y < fl) {
                    blk = B_NETHERRACK;
                    if (y == fl - 1 && noise(x, z, 3, 13) > 0.62f) blk = B_SOULSAND;
                    else if (hash(x >> 1, z >> 1, (y >> 1) + 400) % 1000 < 15 && hash(x, z, y + 500) % 10 < 7) blk = B_GOLD_ORE;
                } else if (y >= ce) {
                    blk = B_NETHERRACK;
                    if (y == ce && hash(x >> 1, z >> 1, 51) % 28 == 0) blk = B_GLOWSTONE;
                } else if (y < 9) {
                    blk = B_LAVA;
                }
                d[base + y] = (uint8_t)blk;
            }
        }
    }
}

void Game::genChunk(int s, int cx, int cz) {
    if (dim == 1) { genNether(s, cx, cz); return; }
    uint8_t* d = chData[s];
    memset(d, 0, CHB);
    int x0 = cx << 4, z0 = cz << 4;
    for (int lx = 0; lx < CS; lx++) {
        for (int lz = 0; lz < CS; lz++) {
            int x = x0 + lx, z = z0 + lz;
            int h = heightAt(x, z);
            int bio = biomeAt(x, z);
            bool chan = bio != 4 && riverV(x, z) < 0.025f;
            int base = (lx * CS + lz) * H;
            for (int y = 0; y < h; y++) {
                int dep = h - 1 - y;
                int blk;
                if (bio == 1) blk = dep <= 1 ? B_SAND : (dep == 2 ? B_SANDSTONE : B_STONE);
                else if (bio == 4) blk = (dep == 0 && h >= 24) ? B_SNOW : B_STONE;
                else if (h <= SEA + 1) blk = dep <= 2 ? (chan ? B_GRAVEL : B_SAND) : B_STONE;
                else if (dep == 0) blk = bio == 2 ? B_SNOW : B_GRASS;
                else if (dep <= 2) blk = B_DIRT;
                else blk = B_STONE;
                if (blk == B_STONE) blk = oreAt(x, y, z, dep);
                d[base + y] = (uint8_t)blk;
            }
            for (int y = h; y < SEA; y++) d[base + y] = (uint8_t)((bio == 2 && y == SEA - 1) ? B_ICE : B_WATER);
            if (h > SEA + 1 && bio != 4 && noise(x, z, 3, 1) > 0.82f) {
                d[base + h - 1] = B_STONE;
                d[base + h - 2] = B_STONE;
            } else if (bio == 1 && h > SEA && cactusAt(x, z)) {
                int ch = 1 + (hash(x, z, 12) % 3);
                for (int k = 0; k < ch && h + k < H; k++) d[base + h + k] = B_CACTUS;
            }
        }
    }
    for (int qx = x0 - 2; qx < x0 + CS + 2; qx++) {
        for (int qz = z0 - 2; qz < z0 + CS + 2; qz++) {
            if (!treeAt(qx, qz)) continue;
            int top = heightAt(qx, qz) - 1;
            int th = 4 + (hash(qx, qz, 8) & 1);
            for (int dy = th - 2; dy <= th + 1; dy++) {
                int r = (dy <= th - 1) ? 2 : 1;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (r == 2 && abs(dx) == 2 && abs(dz) == 2 && (hash(qx + dx, qz + dz, 9) & 1) == 0) continue;
                        if (dy == th + 1 && abs(dx) + abs(dz) > 1) continue;
                        putTree(d, x0, z0, qx + dx, top + dy, qz + dz, B_LEAVES, false);
                    }
                }
            }
            for (int dy = 1; dy <= th; dy++) putTree(d, x0, z0, qx, top + dy, qz, B_LOG, true);
        }
    }
}

// ---------------------------------------------------------------- almacenamiento en archivos
// <juego>/saves/worlds.txt, settings.cfg y <juego>/saves/w<ID>/{level.dat, c<dim>_<cx>_<cz>.bin}
std::string Game::worldPath(int id) { return saveRoot + "/w" + fmt("%d", id); }

std::string Game::chunkPath(int cx, int cz) {
    return curDir + fmt("/c%d_%d_%d.bin", dim, cx, cz);
}

bool Game::readChunk(int cx, int cz, uint8_t* d) {
    if (storeBroken) return false;
    FILE* f = fopen(chunkPath(cx, cz).c_str(), "rb");
    if (!f) return false;
    size_t n = fread(d, 1, CHB, f);
    fclose(f);
    return n == (size_t)CHB;
}

void Game::writeChunk(int cx, int cz, const uint8_t* d) {
    if (storeBroken) return;
    FILE* f = fopen(chunkPath(cx, cz).c_str(), "wb");
    if (!f) { storeBroken = true; return; }
    size_t n = fwrite(d, 1, CHB, f);
    fclose(f);
    if (n != (size_t)CHB) storeBroken = true;
}

static void w32(FILE* f, int v) { fwrite(&v, 4, 1, f); }
static void wf(FILE* f, float v) { fwrite(&v, 4, 1, f); }
static void w16(FILE* f, int v) { short s = (short)v; fwrite(&s, 2, 1, f); }
static void w8(FILE* f, int v) { unsigned char c = (unsigned char)v; fwrite(&c, 1, 1, f); }
static bool r32(FILE* f, int& v) { return fread(&v, 4, 1, f) == 1; }
static bool rf(FILE* f, float& v) { return fread(&v, 4, 1, f) == 1; }
static bool r16(FILE* f, int& v) { short s; if (fread(&s, 2, 1, f) != 1) return false; v = s; return true; }
static bool r8(FILE* f, int& v) { unsigned char c; if (fread(&c, 1, 1, f) != 1) return false; v = c; return true; }

void Game::writeHeader() {
    if (storeBroken || !hasCur) return;
    FILE* f = fopen((curDir + "/level.dat").c_str(), "wb");
    if (!f) { storeBroken = true; return; }
    w8(f, 5);                                   // version
    w32(f, seed);
    wf(f, px); wf(f, py); wf(f, pz); wf(f, yaw); wf(f, pitch);
    w32(f, health); w32(f, hunger); w32(f, sel);
    for (int i = 0; i < 36; i++) { w16(f, slId[i]); w16(f, slCnt[i]); w16(f, slDur[i]); }
    w32(f, worldTime);
    for (int i = 0; i < 3; i++) { w16(f, fId[i]); w16(f, fCnt[i]); }
    w16(f, fBurn); w16(f, fBurnMax); w16(f, fProg);
    w8(f, bedOn ? 1 : 0); w32(f, bedX); w32(f, bedY); w32(f, bedZ);
    w8(f, dim);
    fclose(f);
}

bool Game::readHeader() {
    FILE* f = fopen((curDir + "/level.dat").c_str(), "rb");
    if (!f) return false;
    bool ok = true;
    int ver = 0, t = 0;
    ok = ok && r8(f, ver) && ver == 5;
    ok = ok && r32(f, seed);
    ok = ok && rf(f, px) && rf(f, py) && rf(f, pz) && rf(f, yaw) && rf(f, pitch);
    ok = ok && r32(f, health) && r32(f, hunger) && r32(f, sel);
    for (int i = 0; ok && i < 36; i++) {
        ok = r16(f, slId[i]) && r16(f, slCnt[i]) && r16(f, slDur[i]);
        if (slId[i] < 0 || slId[i] > 79 || slCnt[i] < 0 || slCnt[i] > 64) { slId[i] = 0; slCnt[i] = 0; slDur[i] = 0; }
        if (slId[i] == 0) { slCnt[i] = 0; slDur[i] = 0; }
    }
    ok = ok && r32(f, worldTime);
    for (int i = 0; ok && i < 3; i++) {
        ok = r16(f, fId[i]) && r16(f, fCnt[i]);
        if (fId[i] < 0 || fId[i] > 79 || fCnt[i] < 0 || fCnt[i] > 64) { fId[i] = 0; fCnt[i] = 0; }
        if (fId[i] == 0) fCnt[i] = 0;
    }
    ok = ok && r16(f, fBurn) && r16(f, fBurnMax) && r16(f, fProg);
    ok = ok && r8(f, t); bedOn = t != 0;
    ok = ok && r32(f, bedX) && r32(f, bedY) && r32(f, bedZ);
    ok = ok && r8(f, dim);
    fclose(f);
    if (!ok) return false;
    if (dim < 0 || dim > 1) dim = 0;
    if (worldTime < 0) worldTime = 0;
    if (fBurn < 0) fBurn = 0;
    if (fProg < 0 || fProg >= SMELT_T) fProg = 0;
    if (bedY < 0 || bedY >= H) bedOn = false;
    if (health < 1) health = 1;
    if (health > 20) health = 20;
    if (hunger < 0) hunger = 0;
    if (hunger > 20) hunger = 20;
    if (sel < 0 || sel > 8) sel = 0;
    return true;
}

void Game::wipeDir(const std::string& dir, bool removeDir) {
    SceUID d = sceIoDopen(dir.c_str());
    if (d >= 0) {
        SceIoDirent e;
        memset(&e, 0, sizeof(e));
        while (sceIoDread(d, &e) > 0) {
            if (e.d_name[0] != '.' && !FIO_S_ISDIR(e.d_stat.st_mode)) sceIoRemove((dir + "/" + e.d_name).c_str());
            memset(&e, 0, sizeof(e));
        }
        sceIoDclose(d);
    }
    if (removeDir) sceIoRmdir(dir.c_str());
}

// guarda todo el mundo: chunks modificados en RAM + cabecera
void Game::saveAll() {
    if (!inWorld) return;
    storeBroken = false;
    for (int s = 0; s < nch; s++) {
        if (chCx[s] != NONE && chDirty[s]) {
            writeChunk(chCx[s], chCz[s], chData[s]);
            chDirty[s] = false;
        }
    }
    writeHeader();
}

void Game::unloadSlot(int s) {
    if (chCx[s] == NONE) return;
    if (chDirty[s]) { writeChunk(chCx[s], chCz[s], chData[s]); chDirty[s] = false; }
    chCx[s] = NONE;
    chCz[s] = NONE;
}

void Game::loadChunk(int s, int cx, int cz) {
    unloadSlot(s);
    bool fresh = false;
    if (readChunk(cx, cz, chData[s])) {
        chDirty[s] = false;
    } else {
        genChunk(s, cx, cz);
        chDirty[s] = false;
        fresh = true;
    }
    chCx[s] = cx;
    chCz[s] = cz;
    if (fresh) spawnInChunk(cx, cz);
}

// ventana de chunks: nc x nc en RAM (2x2 hasta distancia 7, 3x3 para distancias mayores)
void Game::setupWindow(int newRd) {
    rd = newRd;
    nb = 2 * rd + 1;
    cullSq = 2.f * (rd + 1) * (rd + 1) + 400.f;
    int nn = (rd <= 7) ? 2 : 3;
    if (nn != nc || nch == 0) {
        nc = nn;
        nch = nc * nc;
        for (int i = 0; i < MAXC; i++) { chCx[i] = NONE; chCz[i] = NONE; chDirty[i] = false; }
    }
    buildOrder(ox, nb, rd);
    buildOrder(oz, nb, rd);
    for (int i = 0; i < nb; i++) { invX[ox[i]] = i; invZ[oz[i]] = i; }
}

int Game::pickOrigin(int cur, int b, bool force) {
    if (!force && b - rd >= (cur << 4) && b + rd <= ((cur + nc) << 4) - 1) return cur;
    return ifloor(b / 16.f - nc / 2.f + 0.5f);
}

void Game::updateWindow(bool force) {
    ccx = pickOrigin(ccx, ifloor(px), force);
    ccz = pickOrigin(ccz, ifloor(pz), force);
}

int Game::loadMissing(int max) {
    int n = 0;
    for (int dz = 0; dz < nc && n < max; dz++) {
        for (int dx = 0; dx < nc && n < max; dx++) {
            int cx = ccx + dx, cz = ccz + dz;
            int s = slotOf(cx, cz);
            if (chCx[s] == cx && chCz[s] == cz) continue;
            loadChunk(s, cx, cz);
            n++;
        }
    }
    if (n > 0) dropOrphanMobs();
    return n;
}

void Game::clearMobs() {
    for (size_t i = 0; i < mobs.size(); i++) delete mobs[i];
    mobs.clear();
    tMob = 0;
}

void Game::dropOrphanMobs() {
    for (int i = (int)mobs.size() - 1; i >= 0; i--) {
        Mob* m = mobs[i];
        if (!chunkFor(ifloor(m->x), ifloor(m->z))) {
            if (tMob == m) tMob = 0;
            delete m;
            mobs.erase(mobs.begin() + i);
        }
    }
}

void Game::spawnMobs() {
    clearMobs();
    bright = dayBright();
    for (int i = 0; i < 7; i++) spawnMob(0, false);
    for (int i = 0; i < 6; i++) spawnMob(1, false);
    for (int i = 0; i < 5; i++) spawnMob(2, false);
    if (bright < 70) for (int i = 0; i < 3; i++) spawnMob(3 + rnd(2), false);
}

bool Game::spawnAt(int type, int x, int z) {
    if (mobs.size() >= 40) return false;
    if (!chunkFor(x, z)) return false;
    if (dim == 1) {   // nether: buscar suelo firme dentro de la caverna
        if (type < 3) return false;
        for (int y = 24; y > 3; y--) {
            if (blockAt(x, y, z) == 0 && blockAt(x, y + 1, z) == 0 && solidAt(x, y - 1, z)) {
                Mob* nm = new Mob();
                nm->type = type;
                nm->x = x + 0.5f; nm->z = z + 0.5f; nm->y = y + 0.001f;
                nm->ang = rndf() * 6.2832f;
                nm->hp = mobHp(type);
                nm->timer = 10 + rnd(40);
                mobs.push_back(nm);
                return true;
            }
        }
        return false;
    }
    int top = topY(x, z);
    if (top < 0 || top + 2 >= H) return false;
    int tb = blockAt(x, top, z);
    if (type < 3) { if (tb != B_GRASS) return false; }
    else if (tb == 0 || tb == B_LEAVES || tb == B_SAPLING || tb == B_TORCH || tb == B_WATER) return false;
    if (blockAt(x, top + 1, z) != 0 || blockAt(x, top + 2, z) != 0) return false;
    Mob* m = new Mob();
    m->type = type;
    m->x = x + 0.5f; m->z = z + 0.5f; m->y = top + 1.001f;
    m->ang = rndf() * 6.2832f;
    m->hp = mobHp(type);
    m->timer = 10 + rnd(40);
    mobs.push_back(m);
    return true;
}

void Game::spawnMob(int type, bool far_) {
    int bx = ifloor(px), bz = ifloor(pz);
    for (int tries = 0; tries < 30; tries++) {
        int x = bx - 8 + rnd(17), z = bz - 8 + rnd(17);
        if (far_) {
            float dx = x + 0.5f - px, dz = z + 0.5f - pz;
            if (dx * dx + dz * dz < 36.f) continue;
        }
        if (spawnAt(type, x, z)) return;
    }
}

void Game::spawnInChunk(int cx, int cz) {   // animales para un chunk recien generado (0 a 2)
    if (dim == 1) return;
    int n = rnd(3);
    for (int i = 0; i < n; i++) {
        for (int tries = 0; tries < 8; tries++) {
            int x = (cx << 4) + rnd(CS), z = (cz << 4) + rnd(CS);
            if (spawnAt(rnd(3), x, z)) break;
        }
    }
}

void Game::resetPlayer() {
    int sx = 0, sz = 0;
    bool found = heightAt(0, 0) > SEA + 1;
    for (int r = 2; r <= 64 && !found; r += 2) {
        for (int a = 0; a < 8 && !found; a++) {
            int qx = (a == 0 || a == 1 || a == 7) ? r : ((a >= 3 && a <= 5) ? -r : 0);
            int qz = (a >= 1 && a <= 3) ? r : ((a >= 5 && a <= 7) ? -r : 0);
            if (heightAt(qx, qz) > SEA + 1 && !treeAt(qx, qz) && !(biomeAt(qx, qz) == 1 && cactusAt(qx, qz))) {
                sx = qx; sz = qz; found = true;
            }
        }
    }
    px = sx + 0.5f; pz = sz + 0.5f;
    updateWindow(true);
    loadMissing(nch);
    int top = topY(sx, sz);
    py = top + 1.001f;
    vy = 0; fallDist = 0; yaw = 0; pitch = 0; onGround = false;
    clearKeys();
}

void Game::clearKeys() {
    kFw = kBk = kJump = kMine = false;
    kTurn = kLook = 0.f;
    mineProg = 0; mineX = -1;
}

void Game::resetPlayerState() {
    for (int i = 0; i < 46; i++) { slId[i] = 0; slCnt[i] = 0; slDur[i] = 0; }
    heldId = 0; heldCnt = 0; heldDur = 0;
    sel = 0; health = 20; hunger = 20;
    hungerTimer = 0; regenTimer = 0; starveTimer = 0; dmgFlash = 0;
    worldTime = 1000; bright = 100;
    for (int i = 0; i < 3; i++) { fId[i] = 0; fCnt[i] = 0; }
    fBurn = 0; fBurnMax = 0; fProg = 0;
    sapN = 0; torchN = 0; hostTimer = 0;
    bedOn = false; msgT = 0; flying = false;
    dim = 0; portalT = 0; portalLock = false; lavaT = 0;
    hungerFx = 0; cactusT = 0; air = 300; airT = 0;
}

// entra a un mundo (nuevo o guardado)
void Game::enterWorld(const WorldInfo& w) {
    inWorld = false;
    curWorld = w; hasCur = true;
    curDir = worldPath(w.id);
    mkdirp(curDir);
    needSave = false;
    storeBroken = false;
    creative = (w.mode == 1);
    flying = false;
    seed = w.seed;
    for (int i = 0; i < nch; i++) { chCx[i] = NONE; chCz[i] = NONE; chDirty[i] = false; }
    clearMobs();
    resetPlayerState();
    bool ok = readHeader();
    if (!ok) {
        wipeDir(curDir, false);
        resetPlayerState();
        seed = w.seed;
    }
    inWorld = true;
    bool sane = ok && py >= 0.f && py <= H + 10.f && fabsf(px) < 1000000.f && fabsf(pz) < 1000000.f;
    if (sane) {
        updateWindow(true);
        loadMissing(nch);
        vy = 0; fallDist = 0; onGround = false;
        clearKeys();
        int g = 0;
        while (g++ < H && boxCollides(px, py, pz, HW, 1.8f)) py += 1.f;
    } else {
        resetPlayer();
    }
    spawnMobs();
    if (!ok) writeHeader();
    autoT = 0;
    state = ST_PLAY;
}

void Game::leaveWorld() {
    if (inWorld) saveAll();
    inWorld = false;
    needSave = false;
    for (int i = 0; i < nch; i++) { chCx[i] = NONE; chCz[i] = NONE; chDirty[i] = false; }
    clearMobs();
    hasCur = false;
}

void Game::applyRd() {
    if (rdApplied == rdIdx) return;
    rdApplied = rdIdx;
    saveSettings();
    for (int s = 0; s < nch; s++) unloadSlot(s);
    setupWindow(RD_OPT[rdIdx]);
    if (inWorld) {
        updateWindow(true);
        loadMissing(nch);
    }
}

// ---------------------------------------------------------------- lista de mundos y ajustes
void Game::loadWorldList() {
    worlds.clear();
    FILE* f = fopen((saveRoot + "/worlds.txt").c_str(), "r");
    if (!f) return;
    char line[160];
    while (fgets(line, sizeof(line), f)) {
        int id, sd, mode;
        char name[96];
        if (sscanf(line, "%d %d %d %95[^\r\n]", &id, &sd, &mode, name) == 4) {
            WorldInfo w; w.id = id; w.seed = sd; w.mode = mode; w.name = name;
            size_t p = 0;
            while (p < worlds.size() && worlds[p].id < w.id) p++;
            worlds.insert(worlds.begin() + p, w);
        }
    }
    fclose(f);
}

void Game::saveWorldList() {
    FILE* f = fopen((saveRoot + "/worlds.txt").c_str(), "w");
    if (!f) return;
    for (size_t i = 0; i < worlds.size(); i++)
        fprintf(f, "%d %d %d %s\n", worlds[i].id, worlds[i].seed, worlds[i].mode, worlds[i].name.c_str());
    fclose(f);
}

int Game::nextId() {
    int id = 1;
    for (size_t i = 0; i < worlds.size(); i++) if (worlds[i].id >= id) id = worlds[i].id + 1;
    return id;
}

int Game::addWorld(const std::string& name, int sd, int mode) {
    WorldInfo w; w.id = nextId(); w.name = name; w.seed = sd; w.mode = mode;
    worlds.push_back(w);
    saveWorldList();
    return (int)worlds.size() - 1;
}

void Game::deleteWorld(int idx) {
    if (idx < 0 || idx >= (int)worlds.size()) return;
    wipeDir(worldPath(worlds[idx].id), true);
    worlds.erase(worlds.begin() + idx);
    saveWorldList();
}

void Game::loadSettings() {
    FILE* f = fopen((saveRoot + "/settings.cfg").c_str(), "rb");
    if (f) {
        int c = fgetc(f);
        if (c >= 0 && c < 5) rdIdx = c;
        fclose(f);
    }
    rdApplied = rdIdx;
}

void Game::saveSettings() {
    FILE* f = fopen((saveRoot + "/settings.cfg").c_str(), "wb");
    if (f) { fputc(rdIdx, f); fclose(f); }
}
