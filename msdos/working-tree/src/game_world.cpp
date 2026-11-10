#include "game.h"

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
    halfW = sw / Fx(2.f); halfH = sh / Fx(2.f); focal = Fx(1.f);
    tick = 0; state = ST_MENU; rs = 0x9E3779B9u;
    kFw = kBk = kSl = kSr = kJump = kMine = false; kTurn = kLook = Fx(0.f); prevBtn = 0; sqT0 = 0; sqArm = sqDone = false;
    for (int i = 0; i < 4; i++) repCnt[i] = 0;
    touch = tFw = tBk = tL = tR = tJp = tcLook = tcInvDown = tcDropped = tpDown = tpDrag = false;
    tcMode = tcLX = tcLY = palPress = tpX = tpY = tpScroll0 = tcNext = htN = 0;
    tcSlot = tcPressSlot = -1;
    tcB = tcDX = tcDY = tcJX = tcJY = tcJS = tcSb = tcPX = tcPY = tcGX = tcIX = tcIY = tcIS = tcHx = tcHy = tcHs = 0;
    for (int i = 0; i < MAXC; i++) { chData[i] = (uint8_t*)malloc(CHB); chCx[i] = NONE; chCz[i] = NONE; chDirty[i] = false; }
    ccx = ccz = 0; seed = 0;
    rd = RD_OPT[0]; nc = 2; nch = 4; nb = 2 * RD_OPT[0] + 1; rdIdx = 0; rdApplied = 0; cullSq = Fx(528.f);   // distancia minima por defecto
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
    px = py = pz = yaw = pitch = vy = fallDist = Fx(0.f);
    onGround = moving = false;
    health = hunger = 20; hungerTimer = regenTimer = starveTimer = dmgFlash = 0;
    camX = camY = camZ = cosY = sinY = cosP = sinP = fx = fy = fz = Fx(0.f);
    polyOk = false; qa = qb = qc = qd = dSh = gx = gy = 0;
    tx = ty = tz = ax = ay = az = -1; tMob = 0;
    attackCd = mineProg = 0; mineX = mineY = mineZ = -1;
    for (int i = 0; i < NSLOT; i++) { slId[i] = slCnt[i] = slDur[i] = 0; slx[i] = sly[i] = 0; slActive[i] = false; }
    sel = labelT = msgT = 0;
    bedOn = false; bedX = bedY = bedZ = 0;
    creative = flying = false; jumpTick = -100; palPage = 0; palN = 0;
    heldId = heldCnt = heldDur = 0; cursor = 0; craftN = 2; slS = 20;
    nrec = 0;
    for (int i = 0; i < 128; i++) iconOk[i] = false;
    openChest = -1; tSel = 0; vilX = vilZ = vilY = vilN = 0; curWv = 0; curLeaf = false; lang = 0;
    langN = 0; lfSec = -1; langListed = false;
    for (int i = 0; i < MAX_LANGS; i++) langIds[i] = 0;
    for (int i = 0; i < MAX_CHESTS; i++) cUsed[i] = false;
    dbg = true; dbgT0 = 0; dbgFrames = dbgFps = 0;
    for (int i = 0; i < 64; i++) { mobKey[i] = 0; mobOrd[i] = 0; }
    ride = 0; fishState = fishX = fishY = fishZ = fishT = 0; fwTick = -100; sprint = kFwPrev = false;
    for (int i = 0; i < 128; i++) { opq[i] = false; spc[i] = false; }
    opqOk = false; topGen = 1; topW = -1; camC = 0; lastSlot = 0; cullK = Fx(1.f); cullNow = Fx(0.f);
    for (int i = 0; i < MAXC; i++) { colTop[i] = 0; colSol[i] = 0; topCx[i] = topCz[i] = NONE; topG[i] = 0; }
}

static void mkdirp(const std::string& p) {
    for (size_t i = 1; i < p.size(); i++)
        if (p[i] == '/' && p[i - 1] != ':') mkdir(p.substr(0, i).c_str(), 0777);
    mkdir(p.c_str(), 0777);
}

void Game::init(const char* argv0) {
    rs ^= (uint32_t)ticksUs();
    if (!rs) rs = 1;
    // La tarjeta SD (EverDrive / SC64 / etc.) la monta main.cpp como "sd:/".
    // Nombres cortos (8.3) a proposito, por si el FAT del cartucho no tiene nombres largos.
    baseDir = "MC64/";
    saveRoot = baseDir + "saves";
    mkdirp(saveRoot);
    initTables();
    initRecipes();
    initPalette();
    loadSettings();
    applyLang();
    setupWindow(RD_OPT[rdIdx]);
    go(ST_MENU);
}

// ---------------------------------------------------------------- utilidades
uint32_t Game::rnd32() { rs ^= rs << 13; rs ^= rs >> 17; rs ^= rs << 5; return rs; }
int Game::rnd(int n) { return (int)((rnd32() & 0x7fffffffu) % (uint32_t)n); }
float Game::rndf() { return rnd(10000) / Fx(10000.f); }

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
    int bi = (((x & 15) << 4) + (z & 15)) * H + y;
    if (chData[s][bi] == B_CHEST && b != B_CHEST) chestFree(x, y, z);
    chData[s][bi] = (uint8_t)b;
    topGen++;   // invalida el cache de alturas
    chDirty[s] = true;
}

bool Game::solidAt(int x, int y, int z) {
    if (y < 0) return true;
    if (y >= H) return false;
    uint8_t* cd = chunkFor(x, z);
    if (!cd) return true;
    int b = cd[(((x & 15) << 4) + (z & 15)) * H + y];
    return b != 0 && b != B_SAPLING && b != B_TORCH && b != B_WATER && b != B_DOOR_LO && b != B_DOOR_UO && b != B_LAVA && b != B_PORTAL && (b < B_CROP0 || b > B_CROP2); 
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
    if (id == I_HOE) return 4;
    return -1;
}
// material: 0 madera, 1 piedra, 2 hierro, 3 oro, 4 diamante
int Game::toolTier(int id) {
    if (id >= I_PICK && id <= I_SWORD) return 0;
    if (id >= I_TOOLS && id < I_TOOLS + 16) return 1 + (id - I_TOOLS) / 4;
    if (id == I_HOE) return 0;
    return -1;
}
int Game::toolLevel(int id) {
    int t = toolTier(id);
    if (t < 0) return -1;
    return t == 0 ? 0 : (t == 1 ? 1 : (t == 2 ? 2 : (t == 3 ? 0 : 3)));
}
float Game::toolSpeed(int id) {
    int t = toolTier(id);
    return t == 0 ? Fx(2.f) : (t == 1 ? Fx(4.f) : (t == 2 ? Fx(6.f) : (t == 3 ? Fx(12.f) : Fx(8.f))));
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
        || (id >= B_NETHERRACK && id <= B_OBSIDIAN) || id == B_SOULSAND || id == B_GLOWSTONE || id == B_CHEST
        || (id >= B_LOG_S && id <= B_FARM_DRY) || id == I_SEEDS || id == B_MAGMA || (id >= B_KELP && id <= B_CORAL4);
}
bool Game::isFood(int id) {
    return id == I_APPLE || id == I_BREAD || id == I_MUTTON || id == I_CHICKEN || id == I_BEEF || id == I_STEAK
        || id == I_COOKED_MUTTON || id == I_COOKED_CHICKEN || id == I_ROTTEN
        || id == I_COD || id == I_SALMON || id == I_TROPICAL
        || id == I_COOKED_COD || id == I_COOKED_SALMON || id == I_DRIED_KELP;
}
int Game::foodVal(int id) {
    if (id == I_COOKED_COD) return 5;
    if (id == I_COOKED_SALMON) return 6;
    if (id == I_DRIED_KELP) return 1;
    if (id == I_COD || id == I_SALMON || id == I_TROPICAL) return 2;
    if (id == I_STEAK) return 8;
    if (id == I_COOKED_MUTTON || id == I_COOKED_CHICKEN) return 6;
    if (id == I_BREAD) return 5;
    if (id == I_APPLE) return 4;
    if (id == I_BEEF) return 3;
    return 2;
}

// ---------------------------------------------------------------- tablas
void Game::initTables() {
    for (int i = 0; i < 128; i++) {
        names[i] = "?"; tag[i] = ""; icol[i] = 0x808080;
        cTop[i] = cSide[i] = cBot[i] = 0; hard[i] = Fx(0.f);
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
    names[I_STRING] = "Hilo"; names[I_SPIDER_EYE] = "Ojo de arana"; names[I_ROD] = "Cana de pescar";
    names[I_COD] = "Bacalao crudo"; names[I_SALMON] = "Salmon crudo"; names[I_TROPICAL] = "Pez tropical";
    icol[I_STRING] = 0xDDDDDD; icol[I_SPIDER_EYE] = 0xB02030; icol[I_ROD] = 0x8B6B3A;
    icol[I_COD] = 0xB8996A; icol[I_SALMON] = 0xC96A5A; icol[I_TROPICAL] = 0xE0903A;
    names[I_COOKED_COD] = "Bacalao cocido"; names[I_COOKED_SALMON] = "Salmon cocido"; names[I_DRIED_KELP] = "Alga seca";
    icol[I_COOKED_COD] = 0x9A7040; icol[I_COOKED_SALMON] = 0xD07A4A; icol[I_DRIED_KELP] = 0x1F4A22;

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
    cTop[B_CHEST] = 0xA0722F; cSide[B_CHEST] = 0x8A5E26; cBot[B_CHEST] = 0x6E4A1E;
    cTop[B_LOG_S] = 0x705C38; cSide[B_LOG_S] = 0x3E2D18; cBot[B_LOG_S] = 0x705C38;
    cTop[B_LOG_S + 1] = 0xA96132; cSide[B_LOG_S + 1] = 0x5E3017; cBot[B_LOG_S + 1] = 0xA96132;
    cTop[B_LEAVES_S] = cSide[B_LEAVES_S] = cBot[B_LEAVES_S] = 0x1D4C38;
    cTop[B_LEAVES_S + 1] = cSide[B_LEAVES_S + 1] = cBot[B_LEAVES_S + 1] = 0x5E7A22;
    cTop[B_PLANKS_S] = cSide[B_PLANKS_S] = cBot[B_PLANKS_S] = 0x7A623C;
    cTop[B_PLANKS_S + 1] = cSide[B_PLANKS_S + 1] = cBot[B_PLANKS_S + 1] = 0xB86836;
    cTop[B_FARM_DRY] = 0x6B4A28; cSide[B_FARM_DRY] = 0x7B5B34; cBot[B_FARM_DRY] = 0x7B5B34;
    cTop[B_FARM_WET] = 0x3F2A16; cSide[B_FARM_WET] = 0x6A4C2A; cBot[B_FARM_WET] = 0x7B5B34;
    for (int b = B_CROP0; b <= B_CROP2; b++) cTop[b] = cSide[b] = cBot[b] = 0x4FB03A;
    cTop[B_KELP] = cSide[B_KELP] = cBot[B_KELP] = 0x2F8A3A;
    cTop[B_SEAGRASS] = cSide[B_SEAGRASS] = cBot[B_SEAGRASS] = 0x3FA03A;
    {
        static const int coralC[5] = { 0xD85A9A, 0x3A5AD8, 0xA040C8, 0xD83A2A, 0xE0C030 };
        static const char* const coralN[5] = { "Coral cerebro", "Coral tubo", "Coral burbuja", "Coral de fuego", "Coral cuerno" };
        for (int k = 0; k < 5; k++) {
            int cb = B_CORAL0 + k;
            cTop[cb] = cSide[cb] = cBot[cb] = coralC[k];
            hard[cb] = Fx(1.5f); names[cb] = coralN[k]; icol[cb] = coralC[k];
        }
    }
    names[B_KELP] = "Alga marina"; names[B_SEAGRASS] = "Pasto marino";
    hard[B_KELP] = Fx(0.f); hard[B_SEAGRASS] = Fx(0.f);
    icol[B_KELP] = 0x2F8A3A; icol[B_SEAGRASS] = 0x3FA03A;
    cTop[B_MAGMA] = cSide[B_MAGMA] = cBot[B_MAGMA] = 0x7A2A10; icol[I_BOAT] = 0x8B6B3A;
    hard[B_CHEST] = Fx(2.5f); hard[B_LOG_S] = hard[B_LOG_S + 1] = Fx(2.f); hard[B_LEAVES_S] = hard[B_LEAVES_S + 1] = Fx(0.2f);
    hard[B_PLANKS_S] = hard[B_PLANKS_S + 1] = Fx(2.f); hard[B_FARM_DRY] = hard[B_FARM_WET] = Fx(0.6f);
    names[B_CHEST] = "Cofre";
    names[B_LOG_S] = "Tronco de abeto"; names[B_LOG_S + 1] = "Tronco de acacia";
    names[B_LEAVES_S] = "Hojas de abeto"; names[B_LEAVES_S + 1] = "Hojas de acacia";
    names[B_PLANKS_S] = "Tablones de abeto"; names[B_PLANKS_S + 1] = "Tablones de acacia";
    names[B_FARM_DRY] = "Tierra labrada"; names[B_FARM_WET] = "Tierra labrada humeda";
    names[B_CROP0] = names[B_CROP1] = names[B_CROP2] = "Trigo";
    names[I_HOE] = "Azada de madera"; names[I_SEEDS] = "Semillas"; names[I_WHEAT] = "Trigo"; names[I_BREAD] = "Pan";
    names[I_EMERALD] = "Esmeralda"; names[I_BUCKET] = "Cubeta"; names[I_BUCKET_W] = "Cubeta con agua"; names[I_BUCKET_L] = "Cubeta con lava";
    icol[I_HOE] = 0xC49A55; icol[I_SEEDS] = 0x6B8B2A; icol[I_WHEAT] = 0xD8C040; icol[I_BREAD] = 0xC8883A;
    icol[I_EMERALD] = 0x2AD36A; icol[I_BUCKET] = 0xB0B0B0; icol[I_BUCKET_W] = 0x2A5CE0; icol[I_BUCKET_L] = 0xE0601A;
    tag[I_HOE] = "Az";
    for (int b = 1; b < 128; b++) {
        for (int f = 0; f < 6; f++) {
            int base = (f == 2) ? cTop[b] : (f == 3 ? cBot[b] : cSide[b]);
            shadedCol[b][f] = shade(base, FSH[f]);
        }
    }
    hard[1] = Fx(0.6f); hard[2] = Fx(0.5f); hard[3] = Fx(1.5f); hard[4] = Fx(2.f);
    hard[5] = Fx(0.2f); hard[6] = Fx(2.f); hard[7] = Fx(2.5f); hard[8] = Fx(2.f);
    hard[9] = Fx(3.5f); hard[10] = Fx(0.f); hard[11] = Fx(0.f);
    hard[12] = Fx(0.5f); hard[13] = Fx(0.8f); hard[14] = Fx(0.4f); hard[15] = Fx(0.f);
    hard[16] = Fx(0.f); hard[17] = Fx(0.2f); hard[18] = Fx(0.6f); hard[19] = Fx(0.5f);
    hard[B_DOOR_LC] = hard[B_DOOR_UC] = hard[B_DOOR_LO] = hard[B_DOOR_UO] = Fx(1.5f);
    names[B_DOOR_LC] = names[B_DOOR_UC] = names[B_DOOR_LO] = names[B_DOOR_UO] = names[I_DOOR] = "Puerta";
    icol[I_DOOR] = 0x9C7A44;
    hard[B_COAL_ORE] = hard[B_IRON_ORE] = hard[B_GOLD_ORE] = hard[B_DIAM_ORE] = Fx(3.f);
    hard[B_IRON_BLK] = Fx(5.f); hard[B_GOLD_BLK] = Fx(3.f); hard[B_DIAM_BLK] = Fx(5.f); hard[B_BED] = Fx(0.2f);
    names[B_COAL_ORE] = "Mena de carbon"; names[B_IRON_ORE] = "Mena de hierro";
    names[B_GOLD_ORE] = "Mena de oro"; names[B_DIAM_ORE] = "Mena de diamante";
    names[B_IRON_BLK] = "Bloque de hierro"; names[B_GOLD_BLK] = "Bloque de oro";
    names[B_DIAM_BLK] = "Bloque de diamante"; names[B_BED] = "Cama";
    names[I_IRON_INGOT] = "Lingote de hierro"; names[I_GOLD_INGOT] = "Lingote de oro"; names[I_DIAMOND] = "Diamante";
    icol[I_IRON_INGOT] = 0xD8D8D8; icol[I_GOLD_INGOT] = 0xF0C020; icol[I_DIAMOND] = 0x4AE8E0;
    hard[B_NETHERRACK] = Fx(0.4f); hard[B_LAVA] = Fx(0.f); hard[B_OBSIDIAN] = Fx(25.f); hard[B_SOULSAND] = Fx(0.5f);
    hard[B_GLOWSTONE] = Fx(0.3f); hard[B_BEDROCK] = Fx(30000.f); hard[B_PORTAL] = Fx(0.f);
    names[B_NETHERRACK] = "Roca del infierno"; names[B_LAVA] = "Lava"; names[B_OBSIDIAN] = "Obsidiana";
    names[B_MAGMA] = "Bloque de magma"; names[I_BOAT] = "Barco"; hard[B_MAGMA] = 0.5f;
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
    rec(3, 3, { P, P, P, P, 0, P, P, P, P }, B_CHEST, 1);
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
    rec(1, 1, { B_LOG_S }, B_PLANKS_S, 4);
    rec(1, 1, { B_LOG_S + 1 }, B_PLANKS_S + 1, 4);
    rec(2, 3, { P, P, 0, S, 0, S }, I_HOE, 1);
    rec(3, 2, { I_IRON_INGOT, 0, I_IRON_INGOT, 0, I_IRON_INGOT, 0 }, I_BUCKET, 1);
    rec(3, 1, { I_WHEAT, I_WHEAT, I_WHEAT }, I_BREAD, 1);
    rec(3, 3, { 0, 0, S, 0, S, I_STRING, S, 0, I_STRING }, I_ROD, 1);
    rec(3, 2, { P, 0, P, P, P, P }, I_BOAT, 1);
}

void Game::initPalette() {
    palN = 0;
    for (int id = 1; id <= 19; id++) palIds[palN++] = id;
    for (int id = B_COAL_ORE; id <= B_BED; id++) palIds[palN++] = id;
    palIds[palN++] = I_DOOR;
    palIds[palN++] = B_NETHERRACK; palIds[palN++] = B_LAVA; palIds[palN++] = B_OBSIDIAN;
    palIds[palN++] = B_SOULSAND; palIds[palN++] = B_GLOWSTONE; palIds[palN++] = I_LIGHTER; palIds[palN++] = B_CHEST;
    for (int id = I_STICK; id <= I_GUNPOWDER; id++) palIds[palN++] = id;
    for (int id = I_IRON_INGOT; id <= I_DIAMOND; id++) palIds[palN++] = id;
    for (int id = I_TOOLS; id < I_TOOLS + 16; id++) palIds[palN++] = id;
    for (int id = B_LOG_S; id <= B_FARM_DRY; id++) palIds[palN++] = id;
    for (int id = I_HOE; id <= I_BUCKET_L; id++) palIds[palN++] = id;
    for (int id = B_KELP; id <= B_CORAL4; id++) palIds[palN++] = id;
    for (int id = I_STRING; id <= I_DRIED_KELP; id++) palIds[palN++] = id;
    palIds[palN++] = B_MAGMA; palIds[palN++] = I_BOAT;
}

// ---------------------------------------------------------------- generacion
int Game::hash(int x, int z, int salt) {
    uint32_t h = (uint32_t)x * 374761393u + (uint32_t)z * 668265263u + (uint32_t)salt * 1274126177u + (uint32_t)seed;
    h = (h ^ (h >> 13)) * 1274126177u;
    h = h ^ (h >> 16);
    return (int)(h & 0x7fffffffu);
}

float Game::lat(int ix, int iz, int salt) { return (hash(ix, iz, salt) % 1000) / Fx(1000.f); }

float Game::noise(int x, int z, int shift, int salt) {
    int ix = x >> shift, iz = z >> shift;
    int mask = (1 << shift) - 1;
    float size = (float)(1 << shift);
    float u = (x & mask) / size, w = (z & mask) / size;
    u = u * u * (Fx(3.f) - Fx(2.f) * u);
    w = w * w * (Fx(3.f) - Fx(2.f) * w);
    float n00 = lat(ix, iz, salt), n10 = lat(ix + 1, iz, salt);
    float n01 = lat(ix, iz + 1, salt), n11 = lat(ix + 1, iz + 1, salt);
    float p = n00 + (n10 - n00) * u;
    float q = n01 + (n11 - n01) * u;
    return p + (q - p) * w;
}

int Game::biomeAt(int x, int z) {
    // biomas grandes y coherentes: temperatura (frio -> nieve, calor+seco -> desierto) y humedad (bosque)
    if (noise(x, z, 7, 53) > Fx(0.68f)) return 4;                 // montanas
    float t = noise(x, z, 8, 13);
    if (t < Fx(0.36f)) return 2;                                  // nieve
    if (t > Fx(0.64f) && noise(x, z, 8, 29) < Fx(0.5f)) return 1;     // desierto
    return noise(x, z, 8, 41) > Fx(0.58f) ? 3 : 0;                // bosque / llanura
}

bool Game::desertAt(int x, int z) { return biomeAt(x, z) == 1; }

bool Game::cactusAt(int x, int z) {
    if (hash(x, z, 10) % 36 != 0) return false;
    return abs(x) + abs(z) >= 5;
}

float Game::riverV(int x, int z) {
    float r = noise(x, z, 7, 21) - Fx(0.5f);
    return r < Fx(0.f) ? -r : r;
}

int Game::heightAt(int x, int z) {
    float v1 = noise(x, z, 4, 1), v2 = noise(x, z, 6, 2);
    int h = 12 + (int)(v1 * Fx(3.f) + v2 * Fx(8.f));
    float cont = Fx(0.38f) - noise(x, z, 8, 61);
    if (cont > Fx(0.f)) h -= (int)(cont * Fx(56.f));   // oceanos mas hondos
    float mn = noise(x, z, 7, 53);
    if (mn > Fx(0.68f)) {
        h += (int)((mn - Fx(0.68f)) * Fx(70.f));
        if (h > H - 4) h = H - 4;
    } else {
        float rv = riverV(x, z);
        if (rv < Fx(0.018f)) {
            if (h > SEA - 2) h = SEA - 2;
        } else if (rv < Fx(0.045f)) {
            int cap = SEA + 1 + (int)((rv - Fx(0.018f)) * Fx(100.f));
            if (h > cap) h = cap;
        }
    }
    if (h < 3) h = 3;
    return h;
}

int Game::groundH(int x, int z) { return heightAt(x, z); }

bool Game::treeAt(int x, int z) {
    int hh = hash(x, z, 7);
    if (hh % 12 != 0) return false;
    int bio = biomeAt(x, z);
    if (bio == 1 || bio == 4) return false;
    int mod = bio == 3 ? 12 : (bio == 2 ? 72 : 48);
    if (hh % mod != 0) return false;
    if (abs(x) + abs(z) < 5) return false;
    if (noise(x, z, 3, 1) > Fx(0.82f)) return false;
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
            int fl = 6 + (int)(noise(x, z, 4, 11) * Fx(7.f));
            int ce = 25 - (int)(noise(x, z, 4, 12) * Fx(4.f));
            if (hash(x, z, 52) % 22 == 0) ce -= 3;
            if (hash(x, z, 53) % 24 == 0) fl += 2;
            int base = (lx * CS + lz) * H;
            for (int y = 0; y < H; y++) {
                int blk = 0;
                if (y == 0 || y == H - 1) blk = B_BEDROCK;
                else if (y < fl) {
                    blk = B_NETHERRACK;
                    if (y == fl - 1 && noise(x, z, 3, 13) > Fx(0.62f)) blk = B_SOULSAND;
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

// vida marina: parches de magma/arena de almas, pasto, coral y bosques de algas segun la profundidad
void Game::seaLife(uint8_t* d, int base, int x, int z, int h) {
    int dp = SEA - h, top = SEA - 2;   // top = ultimo nivel por debajo de la superficie
    if (h > top) return;
    int r = hash(x, z, 14);
    if (dp >= 3) {   // parches de magma (corriente descendente) y arena de almas (ascendente) en el fondo
        int q = hash(x >> 1, z >> 1, 77) % 70;
        if (q == 0) { d[base + h - 1] = B_MAGMA; return; }
        if (q == 1) { d[base + h - 1] = B_SOULSAND; return; }
    }
    if (dp <= 2 || (dp <= 7 && noise(x, z, 5, 71) <= Fx(0.5f))) {
        if (r % 5 == 0) d[base + h] = B_SEAGRASS;
    } else if (dp <= 7) {
        if (hash(x, z, 16) % 5 < 2) {   // formacion de coral: color por zona de 4x4, con variacion
            int ch = 1 + hash(x, z, 17) % 3;
            int ty = (hash(x >> 2, z >> 2, 18) + (hash(x, z, 19) % 4 == 0 ? 1 : 0)) % 5;
            for (int k = 0; k < ch && h + k <= top; k++) d[base + h + k] = (uint8_t)(B_CORAL0 + ty);
        } else if (r % 6 == 0) {
            d[base + h] = B_SEAGRASS;
        }
    } else if (noise(x, z, 4, 72) > Fx(0.45f)) {   // bosque de algas
        if (r % 4 == 0) {
            int kt = top - ((hash(x, z, 15) % 3 == 0) ? 1 + hash(x, z, 20) % 2 : 0);
            for (int y = h; y <= kt; y++) d[base + y] = B_KELP;
        }
    } else if (r % 9 == 0) {
        d[base + h] = B_SEAGRASS;
    }
}

bool Game::shipFor(int x0, int z0) {
    int kx = x0 >> 6, kz = z0 >> 6;
    if (hash(kx, kz, 41) % 4 != 0) return false;
    shX = (kx << 6) + 12 + hash(kx, kz, 42) % 40;
    shZ = (kz << 6) + 12 + hash(kx, kz, 43) % 40;
    if (x0 + CS <= shX - 6 || x0 > shX + 6 || z0 + CS <= shZ - 3 || z0 > shZ + 3) return false;
    shY = heightAt(shX, shZ);
    return shY <= SEA - 7 && biomeAt(shX, shZ) != 4;
}

void Game::buildShip(uint8_t* d, int x0, int z0) {
    int y0 = shY;
    for (int dx = -5; dx <= 5; dx++) {
        int hw = (dx == -5 || dx == 5) ? 0 : ((dx == -4 || dx == 4) ? 1 : 2);   // proa y popa afinadas
        for (int dz = -hw; dz <= hw; dz++) {
            int x = shX + dx, z = shZ + dz;
            if (x < x0 || x >= x0 + CS || z < z0 || z >= z0 + CS) continue;
            for (int y = heightAt(x, z); y <= y0; y++) putTree(d, x0, z0, x, y, z, B_PLANKS, true);
            putTree(d, x0, z0, x, y0 + 1, z, B_PLANKS, true);
            bool edge = dz == hw || dz == -hw || dx == 5 || dx == -5;
            for (int y = y0 + 2; y <= y0 + 3; y++)
                putTree(d, x0, z0, x, y, z, (edge && hash(x, z, y + 44) % 4 != 0) ? B_PLANKS : B_WATER, true);
        }
    }
    int mh = 2 + hash(shX, shZ, 45) % 3;   // palo mayor roto
    for (int y = y0 + 2; y <= y0 + 2 + mh; y++) putTree(d, x0, z0, shX, y, shZ, B_LOG, true);
    putTree(d, x0, z0, shX - 2, y0 + 1, shZ, B_GRAVEL, true);   // el cofre se apoya en grava: marca de botin
    putTree(d, x0, z0, shX - 2, y0 + 2, shZ, B_CHEST, true);
}

// botin del naufragio (solo la primera vez que se abre el cofre)
void Game::seedWreck() {
    int n = 3 + rnd(3);
    for (int i = 0; i < n; i++) {
        int r = rnd(10), it = r < 3 ? I_STRING : (r < 5 ? I_STICK : (r < 7 ? I_COD : (r < 8 ? I_APPLE : (r < 9 ? I_COAL : I_EMERALD))));
        int sl = CH0 + rnd(CHN);
        if (slId[sl] == 0) { slId[sl] = it; slCnt[sl] = 1 + rnd(3); slDur[sl] = 0; }
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
            bool chan = bio != 4 && riverV(x, z) < Fx(0.018f);
            int base = (lx * CS + lz) * H;
            for (int y = 0; y < h; y++) {
                int dep = h - 1 - y;
                int blk;
                if (bio == 1) blk = dep <= 1 ? B_SAND : (dep == 2 ? B_SANDSTONE : B_STONE);
                else if (bio == 4) blk = (dep == 0 && h >= 24) ? B_SNOW : B_STONE;
                else if (h <= SEA + 1) blk = dep <= 2 ? ((chan || (h < SEA - 7 && noise(x, z, 3, 81) > Fx(0.5f))) ? B_GRAVEL : B_SAND) : B_STONE;
                else if (dep == 0) blk = bio == 2 ? B_SNOW : B_GRASS;
                else if (dep <= 2) blk = B_DIRT;
                else blk = B_STONE;
                if (blk == B_STONE) blk = oreAt(x, y, z, dep);
                d[base + y] = (uint8_t)blk;
            }
            for (int y = h; y < SEA; y++) d[base + y] = (uint8_t)((bio == 2 && y == SEA - 1) ? B_ICE : B_WATER);
            if (h > SEA + 1 && bio != 4 && noise(x, z, 3, 1) > Fx(0.82f)) {
                d[base + h - 1] = B_STONE;
                d[base + h - 2] = B_STONE;
            } else if (bio == 1 && h > SEA && cactusAt(x, z)) {
                int ch = 1 + (hash(x, z, 12) % 3);
                for (int k = 0; k < ch && h + k < H; k++) d[base + h + k] = B_CACTUS;
            } else if (h < SEA && bio != 4 && riverV(x, z) >= Fx(0.045f)) {
                seaLife(d, base, x, z, h);
            }
        }
    }
    for (int qx = x0 - 2; qx < x0 + CS + 2; qx++) {
        for (int qz = z0 - 2; qz < z0 + CS + 2; qz++) {
            if (!treeAt(qx, qz)) continue;
            int tv = treeVar(qx, qz);
            int top = groundH(qx, qz) - 1;
            int th = (tv == 1 ? 6 : 4) + (hash(qx, qz, 8) & 1);
            for (int dy = th - 2; dy <= th + 1; dy++) {
                int r = (dy <= th - 1) ? 2 : 1;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (r == 2 && abs(dx) == 2 && abs(dz) == 2 && (hash(qx + dx, qz + dz, 9) & 1) == 0) continue;
                        if (dy == th + 1 && abs(dx) + abs(dz) > 1) continue;
                        putTree(d, x0, z0, qx + dx, top + dy, qz + dz, leavesOf(tv), false);
                    }
                }
            }
            for (int dy = 1; dy <= th; dy++) putTree(d, x0, z0, qx, top + dy, qz, logOf(tv), true);
        }
    }
    if (villageFor(x0, z0)) buildVillage(d, x0, z0);
    if (shipFor(x0, z0)) buildShip(d, x0, z0);
}

// ---------------------------------------------------------------- almacenamiento en archivos
// sd:/MC64/saves/worlds.txt, settings.cfg y sd:/MC64/saves/w<ID>/{level.dat, <O|N><cx><cz>.bin}
std::string Game::worldPath(int id) { return saveRoot + "/w" + fmt("%d", id); }

std::string Game::chunkPath(int cx, int cz) {
    // nombre 8.3: dimension + cx/cz en hex con desplazamiento (soporta +-2048 chunks)
    return curDir + fmt("/%c%03X%03X.bin", dim ? 'N' : 'O', (cx + 2048) & 0xFFF, (cz + 2048) & 0xFFF);
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
        if (slId[i] < 0 || slId[i] > 127 || slCnt[i] < 0 || slCnt[i] > 64) { slId[i] = 0; slCnt[i] = 0; slDur[i] = 0; }
        if (slId[i] == 0) { slCnt[i] = 0; slDur[i] = 0; }
    }
    ok = ok && r32(f, worldTime);
    for (int i = 0; ok && i < 3; i++) {
        ok = r16(f, fId[i]) && r16(f, fCnt[i]);
        if (fId[i] < 0 || fId[i] > 127 || fCnt[i] < 0 || fCnt[i] > 64) { fId[i] = 0; fCnt[i] = 0; }
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
    std::vector<std::string> names;
    struct ffblk e;
    std::string pat = dir + "/*.*";
    for (int r = findfirst(pat.c_str(), &e, FA_ARCH | FA_RDONLY | FA_HIDDEN | FA_SYSTEM); r == 0; r = findnext(&e)) names.push_back(e.ff_name);
    for (size_t i = 0; i < names.size(); i++) unlink((dir + "/" + names[i]).c_str());
    if (removeDir) rmdir(dir.c_str());
}

// guarda todo el mundo: chunks modificados en RAM + cabecera
void Game::saveAll() {
    if (!inWorld) return;
    storeBroken = false;
    chestCommit();
    for (int s = 0; s < nch; s++) {
        if (chCx[s] != NONE && chDirty[s]) {
            writeChunk(chCx[s], chCz[s], chData[s]);
            chDirty[s] = false;
        }
    }
    writeHeader();
    writeChests();
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
    topGen++;
    if (fresh) spawnInChunk(cx, cz);
}

// ventana de chunks: nc x nc en RAM (2x2 hasta distancia 7, 3x3 para distancias mayores)
void Game::setupWindow(int newRd) {
    rd = newRd;
    nb = 2 * rd + 1;
    cullSq = Fx(2.f) * (rd + 1) * (rd + 1) + Fx(400.f);
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
    return ifloor(b / Fx(16.f) - nc / Fx(2.f) + Fx(0.5f));
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
    tMob = 0; ride = 0;
}

void Game::dropOrphanMobs() {
    for (int i = (int)mobs.size() - 1; i >= 0; i--) {
        Mob* m = mobs[i];
        if (!chunkFor(ifloor(m->x), ifloor(m->z))) {
            if (tMob == m) tMob = 0;
            if (ride == m) ride = 0;
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
    if (bright < 70) for (int i = 0; i < 3; i++) spawnMob(hostType(), false);
}

bool Game::spawnAt(int type, int x, int z) {
    if (mobs.size() >= 40) return false;
    if (!chunkFor(x, z)) return false;
    if (type >= 7 && type <= 9) {   // peces: solo en agua de 4+ bloques de profundidad
        if (dim != 0) return false;
        int nf = 0;
        for (size_t i = 0; i < mobs.size(); i++) { int mt = mobs[i]->type; if (mt >= 7 && mt <= 9) nf++; }
        int h = heightAt(x, z), dp = SEA - h;
        if (nf >= 12 || dp < 4) return false;
        int y = h + 2 + rnd(dp - 3);
        if (blockAt(x, y, z) != B_WATER || blockAt(x, y + 1, z) != B_WATER) return false;
        Mob* f = new Mob();
        f->type = type; f->x = x + Fx(0.5f); f->z = z + Fx(0.5f); f->y = y + Fx(0.2f);
        f->ang = rndf() * Fx(6.2832f); f->hp = mobHp(type); f->timer = 10 + rnd(40);
        if (type == 9) f->col = (rnd(256) << 16) | (rnd(256) << 8) | rnd(256);
        mobs.push_back(f);
        return true;
    }
    if (dim == 1) {   // nether: buscar suelo firme dentro de la caverna
        if (type < 3) return false;
        for (int y = 24; y > 3; y--) {
            if (blockAt(x, y, z) == 0 && blockAt(x, y + 1, z) == 0 && solidAt(x, y - 1, z)) {
                Mob* nm = new Mob();
                nm->type = type;
                nm->x = x + Fx(0.5f); nm->z = z + Fx(0.5f); nm->y = y + Fx(0.001f);
                nm->ang = rndf() * Fx(6.2832f);
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
    else if (tb == 0 || isLeaves(tb) || tb == B_SAPLING || tb == B_TORCH || tb == B_WATER) return false;
    if (blockAt(x, top + 1, z) != 0 || blockAt(x, top + 2, z) != 0) return false;
    Mob* m = new Mob();
    m->type = type;
    m->x = x + Fx(0.5f); m->z = z + Fx(0.5f); m->y = top + Fx(1.001f);
    m->ang = rndf() * Fx(6.2832f);
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
            float dx = x + Fx(0.5f) - px, dz = z + Fx(0.5f) - pz;
            if (dx * dx + dz * dz < Fx(36.f)) continue;
        }
        if (spawnAt(type, x, z)) return;
    }
}

void Game::spawnInChunk(int cx, int cz) {   // animales para un chunk recien generado (0 a 2)
    if (dim == 1) return;
    if (rnd(3) == 0) {
        for (int t = 0; t < 6; t++) if (spawnAt(7 + rnd(3), (cx << 4) + rnd(CS), (cz << 4) + rnd(CS))) break;
    }
    if (villageFor(cx << 4, cz << 4)) {
        for (int k = 0; k < vilN; k++) {
            int hx = vilX + HX[k], hz = vilZ + HZ[k] + 3;
            if ((hx >> 4) == cx && (hz >> 4) == cz && houseOk(k)) spawnAt(6, hx, hz);
        }
    }
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
    px = sx + Fx(0.5f); pz = sz + Fx(0.5f);
    updateWindow(true);
    loadMissing(nch);
    int top = topY(sx, sz);
    py = top + Fx(1.001f);
    vy = 0; fallDist = 0; yaw = 0; pitch = 0; onGround = false;
    clearKeys();
}

void Game::clearKeys() {
    kFw = kBk = kSl = kSr = kJump = kMine = false;
    kTurn = kLook = Fx(0.f);
    mineProg = 0; mineX = -1;
    tFw = tBk = tL = tR = tJp = false;
    tcMode = 0; tcInvDown = false; tpDown = false;
}

void Game::resetPlayerState() {
    for (int i = 0; i < NSLOT; i++) { slId[i] = 0; slCnt[i] = 0; slDur[i] = 0; }
    clearChests();
    heldId = 0; heldCnt = 0; heldDur = 0;
    sel = 0; health = 20; hunger = 20;
    hungerTimer = 0; regenTimer = 0; starveTimer = 0; dmgFlash = 0;
    worldTime = 1000; bright = 100;
    for (int i = 0; i < 3; i++) { fId[i] = 0; fCnt[i] = 0; }
    fBurn = 0; fBurnMax = 0; fProg = 0;
    sapN = 0; torchN = 0; hostTimer = 0;
    bedOn = false; msgT = 0; flying = false;
    ride = 0; fishState = 0; sprint = false; fwTick = -100;
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
    if (ok) loadChests();
    if (!ok) {
        wipeDir(curDir, false);
        resetPlayerState();
        seed = w.seed;
    }
    inWorld = true;
    bool sane = ok && py >= Fx(0.f) && py <= H + Fx(10.f) && fabsf(px) < Fx(30000.f) && fabsf(pz) < Fx(30000.f);
    if (sane) {
        updateWindow(true);
        loadMissing(nch);
        vy = 0; fallDist = 0; onGround = false;
        clearKeys();
        int g = 0;
        while (g++ < H && boxCollides(px, py, pz, HW, Fx(1.8f))) py += Fx(1.f);
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
        int l = fgetc(f);
        lang = (l >= 0 && l < 128) ? l : 0;   // ID de idioma; applyLang() lo valida
        fclose(f);
    }
    rdApplied = rdIdx;
}

void Game::saveSettings() {
    FILE* f = fopen((saveRoot + "/settings.cfg").c_str(), "wb");
    if (f) { fputc(rdIdx, f); fputc(lang, f); fclose(f); }
}

int Game::vt(int c, int wv, bool leaf) {
    int r = (c >> 16) & 255, g = (c >> 8) & 255, bl = c & 255;
    if (leaf) {
        if (wv == 1) { r = r * 5 / 8; g = g * 5 / 8; bl = bl * 4 / 3; if (bl > 255) bl = 255; }
        else { r = r * 2; if (r > 255) r = 255; bl = bl * 4 / 5; }
    } else if (wv == 1) {
        r = r * 2 / 3; g = g * 2 / 3; bl = bl * 2 / 3;
    } else {
        g = g * 7 / 10; bl = bl * 6 / 10;
    }
    return (r << 16) | (g << 8) | bl;
}

int Game::treeVar(int x, int z) {
    int bio = biomeAt(x, z);
    if (bio == 2) return 1;
    if (bio == 0 && noise(x, z, 6, 77) > Fx(0.58f)) return 2;
    return 0;
}

bool Game::wetNear(int x, int y, int z) {
    for (int dx = -8; dx <= 8; dx++)
        for (int dz = -8; dz <= 8; dz++)
            for (int dy = -1; dy <= 1; dy++)
                if (blockAt(x + dx, y + dy, z + dz) == B_WATER) return true;
    return false;
}

void Game::growCrops() {
    for (int s = 0; s < MAXC; s++) {
        if (chCx[s] == NONE) continue;
        uint8_t* d = chData[s];
        for (int i = 1; i < CHB; i++) {
            int b = d[i];
            if ((b == B_CROP0 || b == B_CROP1) && d[i - 1] == B_FARM_WET) { d[i] = (uint8_t)(b + 1); chDirty[s] = true; }
        }
    }
}

void Game::tradeOutput() {
    int t = tSel, ic = T_INC[t];
    if (slId[36] != T_IN[t] || slCnt[36] < ic) { clearSlot(OUT); return; }
    int o = T_OUT[t], oc = T_OUTC[t];
    int n = slCnt[36] / ic;
    int cap = maxStack(o) > 1 ? 64 / oc : 1;
    if (n > cap) n = cap;
    slId[OUT] = o;
    slCnt[OUT] = n * oc;
    slDur[OUT] = 0;
}

void Game::tradeTake() {
    if (slId[OUT] == 0) return;
    int o = slId[OUT], c = slCnt[OUT];
    if (heldId == 0) { heldId = o; heldCnt = c; heldDur = 0; }
    else if (heldId == o && maxStack(o) > 1 && heldCnt + c <= 64) heldCnt += c;
    else return;
    int n = c / T_OUTC[tSel];
    slCnt[36] -= n * T_INC[tSel];
    if (slCnt[36] <= 0) clearSlot(36);
    updateOutput();
}

void Game::tradeMove(int d) {
    tSel = (tSel + d + 8) % 8;
    updateOutput();
}

bool Game::villageFor(int x0, int z0) {
    int kx = x0 >> 6, kz = z0 >> 6;
    if (hash(kx, kz, 31) % 4 != 0) return false;
    vilX = (kx << 6) + 14 + hash(kx, kz, 32) % 36;
    vilZ = (kz << 6) + 14 + hash(kx, kz, 33) % 36;
    if (x0 + CS <= vilX - 13 || x0 > vilX + 13 || z0 + CS <= vilZ - 13 || z0 > vilZ + 13) return false;
    if (biomeAt(vilX, vilZ) == 4) return false;
    vilY = heightAt(vilX, vilZ);
    if (vilY <= SEA + 1 || vilY >= H - 8) return false;
    vilN = 3 + hash(kx, kz, 34) % 4;
    return true;
}

bool Game::houseOk(int k) {
    int hy = heightAt(vilX + HX[k], vilZ + HZ[k]);
    return hy > SEA + 1 && hy < H - 8 && abs(hy - vilY) <= 3;
}

void Game::buildVillage(uint8_t* d, int x0, int z0) {
    int bio = biomeAt(vilX, vilZ);
    int wv = bio == 1 ? 2 : (bio == 2 ? 1 : 0);
    int lg = logOf(wv), pl = planksOf(wv);
    int fy = vilY - 1;
    for (int dx = -2; dx <= 2; dx++) {
        for (int dz = -2; dz <= 2; dz++) {
            int x = vilX + dx, z = vilZ + dz;
            bool mid = dx == 0 && dz == 0;
            for (int y = fy - 4; y < fy; y++) putTree(d, x0, z0, x, y, z, B_DIRT, true);
            putTree(d, x0, z0, x, fy, z, mid ? B_WATER : B_FARM_WET, true);
            putTree(d, x0, z0, x, fy + 1, z, mid ? 0 : B_CROP2, true);
            putTree(d, x0, z0, x, fy + 2, z, 0, true);
            putTree(d, x0, z0, x, fy + 3, z, 0, true);
        }
    }
    for (int k = 0; k < vilN; k++) {
        if (!houseOk(k)) continue;
        int hx = vilX + HX[k], hz = vilZ + HZ[k];
        if (hx + 2 < x0 || hx - 2 >= x0 + CS || hz + 3 < z0 || hz - 2 >= z0 + CS) continue;
        int hy = heightAt(hx, hz) - 1;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = hx + dx, z = hz + dz;
                bool ex = dx == -2 || dx == 2, ez = dz == -2 || dz == 2;
                for (int y = hy - 4; y < hy; y++) putTree(d, x0, z0, x, y, z, B_COBBLE, true);
                putTree(d, x0, z0, x, hy, z, pl, true);
                for (int y = hy + 1; y <= hy + 3; y++) putTree(d, x0, z0, x, y, z, (ex && ez) ? lg : ((ex || ez) ? pl : 0), true);
                putTree(d, x0, z0, x, hy + 4, z, pl, true);
            }
        }
        putTree(d, x0, z0, hx, hy + 1, hz + 2, B_DOOR_LC, true);
        putTree(d, x0, z0, hx, hy + 2, hz + 2, B_DOOR_UC, true);
        putTree(d, x0, z0, hx - 2, hy + 2, hz, 0, true);
        putTree(d, x0, z0, hx + 2, hy + 2, hz, 0, true);
        putTree(d, x0, z0, hx - 1, hy + 1, hz - 1, B_TABLE, true);
        putTree(d, x0, z0, hx + 1, hy + 1, hz - 1, B_TORCH, true);
        for (int sd = -1; sd <= 1; sd += 2) {
            putTree(d, x0, z0, hx + sd, hy, hz + 3, B_COBBLE, true);
            putTree(d, x0, z0, hx + sd, hy + 1, hz + 3, B_TORCH, true);
            putTree(d, x0, z0, hx + sd, hy + 2, hz + 3, 0, true);
        }
    }
}

void Game::clearChests() {
    for (int i = 0; i < MAX_CHESTS; i++) {
        cUsed[i] = false; cFill[i] = 0;
        for (int k = 0; k < CHN; k++) cSlId[i][k] = cSlCnt[i][k] = cSlDur[i][k] = 0;
    }
    openChest = -1;
}

int Game::chestFind(int x, int y, int z) {
    for (int i = 0; i < MAX_CHESTS; i++)
        if (cUsed[i] && cX[i] == x && cY[i] == y && cZ[i] == z && cDim[i] == dim) return i;
    return -1;
}

int Game::chestAlloc(int x, int y, int z) {
    for (int i = 0; i < MAX_CHESTS; i++) {
        if (cUsed[i]) continue;
        cUsed[i] = true; cX[i] = x; cY[i] = y; cZ[i] = z; cDim[i] = dim; cFill[i] = 0;
        for (int k = 0; k < CHN; k++) cSlId[i][k] = cSlCnt[i][k] = cSlDur[i][k] = 0;
        return i;
    }
    return -1;
}

void Game::chestFree(int x, int y, int z) {
    int i = chestFind(x, y, z);
    if (i < 0) return;
    cUsed[i] = false; cFill[i] = 0;
    if (openChest == i) openChest = -1;
}

void Game::chestCommit() {
    if (state != ST_INV || craftN != CHEST || openChest < 0) return;
    int f = 0;
    for (int k = 0; k < CHN; k++) {
        cSlId[openChest][k] = slId[CH0 + k]; cSlCnt[openChest][k] = slCnt[CH0 + k]; cSlDur[openChest][k] = slDur[CH0 + k];
        if (slId[CH0 + k] != 0) f++;
    }
    cFill[openChest] = f;
}

void Game::chestOpenAt(int x, int y, int z) {
    int id = chestFind(x, y, z);
    bool fresh = id < 0;
    if (id < 0) id = chestAlloc(x, y, z);
    if (id < 0) { say("Limite de cofres alcanzado (64)"); return; }
    for (int k = 0; k < CHN; k++) { slId[CH0 + k] = cSlId[id][k]; slCnt[CH0 + k] = cSlCnt[id][k]; slDur[CH0 + k] = cSlDur[id][k]; }
    if (fresh && blockAt(x, y - 1, z) == B_GRAVEL) seedWreck();
    openChest = id;
    openInv(CHEST);
}

void Game::chestClose() {
    chestCommit();
    openChest = -1;
    for (int k = 0; k < CHN; k++) clearSlot(CH0 + k);
}

void Game::writeChests() {
    if (storeBroken || !hasCur) return;
    FILE* f = fopen((curDir + "/chests.dat").c_str(), "wb");
    if (!f) { storeBroken = true; return; }
    w8(f, 1);
    for (int i = 0; i < MAX_CHESTS; i++) {
        w8(f, cUsed[i] ? 1 : 0);
        if (!cUsed[i]) continue;
        w32(f, cX[i]); w32(f, cY[i]); w32(f, cZ[i]); w32(f, cDim[i]);
        for (int k = 0; k < CHN; k++) { w16(f, cSlId[i][k]); w16(f, cSlCnt[i][k]); w16(f, cSlDur[i][k]); }
    }
    fclose(f);
}

void Game::loadChests() {
    clearChests();
    FILE* f = fopen((curDir + "/chests.dat").c_str(), "rb");
    if (!f) return;
    int v = 0;
    if (!r8(f, v) || v != 1) { fclose(f); return; }
    for (int i = 0; i < MAX_CHESTS; i++) {
        int u = 0;
        if (!r8(f, u)) break;
        if (!u) continue;
        int x, y, z, dm;
        if (!(r32(f, x) && r32(f, y) && r32(f, z) && r32(f, dm))) break;
        int fill = 0;
        bool ok = true;
        for (int k = 0; k < CHN && ok; k++) {
            int a, b, c;
            ok = r16(f, a) && r16(f, b) && r16(f, c);
            if (!ok) break;
            if (a < 0 || a > 127 || b < 0 || b > 64) a = b = c = 0;
            if (a == 0) b = c = 0;
            cSlId[i][k] = a; cSlCnt[i][k] = b; cSlDur[i][k] = c;
            if (a) fill++;
        }
        if (!ok) { for (int k = 0; k < CHN; k++) cSlId[i][k] = cSlCnt[i][k] = cSlDur[i][k] = 0; break; }
        cUsed[i] = true; cX[i] = x; cY[i] = y; cZ[i] = z; cDim[i] = dm; cFill[i] = fill;
    }
    fclose(f);
}

// ---------------------------------------------------------------- idiomas
// Mismo sistema que la version Java (Minecraft2D.java): TODOS los idiomas viven en UN archivo,
// leng/idiomas.txt (el formato esta explicado en el propio archivo y es el mismo en todas las
// plataformas). Para agregar un idioma solo se edita ese archivo, no este codigo.
//
// L_ES = textos originales en espanol. Sirven de clave en el codigo (L("Jugar")) y de respaldo si a un
// idioma le falta una linea. El ID de cada texto en idiomas.txt es su posicion + 1:
//   NO reordenar ni borrar entradas; un texto nuevo se agrega AL FINAL y tambien a cada idioma del archivo.
// IDs 1..999 = interfaz; NAME_BASE + id de bloque/item (1000..1127) = nombres.
// Los IDs 1..95 son los de la version Java (algunos solo los usa esa version: teclado numerico, pantalla de
// controles); del 96 en adelante son textos de los gamepads (PSP / N64 / MS-DOS) que Java no usa.
static const char* const L_ES[] = {
    "Jugar",   // 1
    "Salir",   // 2
    "En juego: L o R = pausa",   // 3
    "Selecciona un mundo",   // 4
    "+ Crear mundo nuevo",   // 5
    "Atras",   // 6
    "No hay mundos todavia",   // 7
    "Crear mundo nuevo",   // 8
    "Nombre: ",   // 9
    "Semilla: ",   // 10
    "aleatoria",   // 11
    "Modo: ",   // 12
    "Creativo",   // 13
    "Supervivencia",   // 14
    "Crear",   // 15
    "Borrar mundo?",   // 16
    "No se puede deshacer",   // 17
    "Si, borrar",   // 18
    "Cancelar",   // 19
    "Juego en pausa",   // 20
    "Reanudar",   // 21
    "Ajustes",   // 22
    "Menu principal",   // 23
    "No se pudo guardar!",   // 24
    "Minima",   // 25
    "Lejos",   // 26
    "Muy lejos",   // 27
    "Extrema",   // 28
    "4 / 6 o 5: cambiar",   // 29
    "Mas lejos = mas lento",   // 30
    "MORISTE",   // 31
    "5: reaparecer",   // 32
    "Hambre",   // 33
    "Creativo (vuelo)",   // 34
    "Cofre",   // 35
    "Horno",   // 36
    "Mesa de crafteo",   // 37
    "Inventario",   // 38
    "Aldeano  [1][3] lista",   // 39
    "Resultado: ",   // 40
    "Cargando mundo...",   // 41
    "Cargando...",   // 42
    "Guardando...",   // 43
    "Mundo ",   // 44
    "Nombre del mundo",   // 45
    "Semilla (vacio = aleatoria)",   // 46
    "Reapareciste en tu cama",   // 47
    "Tu cama fue destruida",   // 48
    "El cofre no esta vacio",   // 49
    "Cama destruida: sin punto de reaparicion",   // 50
    "Portal encendido",   // 51
    "Marco de portal incompleto",   // 52
    "Limite de cofres alcanzado (64)",   // 53
    "No se pudo leer el cofre",   // 54
    "Error al guardar el cofre",   // 55
    "No se puede dormir aqui",   // 56
    "Dormiste hasta la manana",   // 57
    "Punto de reaparicion guardado",   // 58
    "Toca: reaparecer",   // 59
    "Controles",   // 60
    "Restaurar",   // 61
    "Adelante",   // 62
    "Retroceder",   // 63
    "Girar izq.",   // 64
    "Girar der.",   // 65
    "Saltar",   // 66
    "Mirar arriba",   // 67
    "Mirar abajo",   // 68
    "Romper / colocar",   // 69
    "Hotbar anterior",   // 70
    "Hotbar siguiente",   // 71
    "Pausa",   // 72
    "Pulsa una tecla",   // 73
    "Tecla repetida!",   // 74
    "5: elegir y pulsa una tecla",   // 75
    "El barco se hundio!",   // 76
    "Montaste el barco",   // 77
    "Sedal lanzado",   // 78
    "Apunta al agua",   // 79
    "Pescaste algo!",   // 80
    "Se escapo",   // 81
    "Nada todavia",   // 82
    "Pica! Clic ya",   // 83
    "Entraste al Nether",   // 84
    "Volviste al Overworld",   // 85
    "Idioma: ",   // 86
    "Render: ",   // 87
    "Normal",   // 88
    "2/Arr",   // 89
    "4/Izq",   // 90
    "5/OK",   // 91
    "6/Der",   // 92
    "8/Abj",   // 93
    "L / Izq",   // 94
    "R / Der",   // 95
    "X: aceptar   O: atras   Start: pausa en juego",   // 96   // gamepads
    "A: aceptar   B: atras   Start: pausa",   // 97   // gamepads
    "Cuadrado: borrar mundo",   // 98   // gamepads
    "Z: borrar mundo",   // 99   // gamepads
    "Semilla: X alterna, Izq/Der +-1, L/R +-1000",   // 100   // gamepads
    "Izq / Der o X: cambiar",   // 101   // gamepads
    "Izq / Der o A: cambiar",   // 102   // gamepads
    "X: reaparecer",   // 103   // gamepads
    "A: reaparecer",   // 104   // gamepads
    "Aldeano  [L][R] lista"   // 105   // gamepads
};
static const int L_ES_N = (int)(sizeof(L_ES) / sizeof(L_ES[0]));

static std::string trimWs(const std::string& s) {
    size_t a = s.find_first_not_of(" \t\r\n");
    if (a == std::string::npos) return std::string();
    size_t b = s.find_last_not_of(" \t\r\n");
    return s.substr(a, b - a + 1);
}

// quita espacios y el comentario del final (un '#' precedido de espacio o tab)
static std::string langTrim(const std::string& in) {
    std::string s = trimWs(in);
    for (size_t i = 1; i < s.size(); i++) {
        if (s[i] == '#' && (s[i - 1] == ' ' || s[i - 1] == '\t')) return trimWs(s.substr(0, i));
    }
    return s;
}

static int langNum(const std::string& in) {
    std::string s = trimWs(in);
    size_t n = s.size();
    if (n == 0 || n > 5) return -2;
    int v = 0;
    for (size_t i = 0; i < n; i++) {
        char c = s[i];
        if (c < '0' || c > '9') return -2;
        v = v * 10 + (c - '0');
    }
    return v;
}

// UTF-8 -> ASCII. Las fuentes de las consolas solo tienen los glifos ASCII 32..127 (1 byte = 1 glifo), asi que
// las letras con acento se dibujan sin acento (a, e, n, u...) y cualquier otro caracter se muestra como '?'.
static std::string utf8Fold(const std::string& s) {
    static const char* const LAT1 =   // U+00C0..U+00FF
        "AAAAAAACEEEEIIII" "DNOOOOOxOUUUUYTs" "aaaaaaaceeeeiiii" "dnooooo/ouuuuyty";
    std::string r;
    size_t n = s.size(), i = 0;
    r.reserve(n);
    while (i < n) {
        int c = (unsigned char)s[i++];
        int cp = 0;
        if (c < 0x80) { r += (char)c; continue; }
        if (c >= 0xC0 && c < 0xE0 && i < n) {
            cp = ((c & 0x1F) << 6) | ((unsigned char)s[i] & 0x3F);
            i += 1;
        } else if (c >= 0xE0 && c < 0xF0 && i + 1 < n) {
            cp = ((c & 0x0F) << 12) | (((unsigned char)s[i] & 0x3F) << 6) | ((unsigned char)s[i + 1] & 0x3F);
            i += 2;
        } else if (c >= 0xF0 && c < 0xF8) {
            i += 3;
            if (i > n) i = n;
            r += '?';
            continue;
        } else {
            r += '?';
            continue;
        }
        if (cp >= 0xC0 && cp <= 0xFF) r += LAT1[cp - 0xC0];
        else r += '?';
    }
    return r;
}

static bool readWholeFile(const std::string& path, std::string& out) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return false;
    char buf[1024];
    size_t n;
    out.clear();
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0) out.append(buf, n);
    fclose(f);
    return !out.empty();
}

// Donde esta el archivo de idiomas: carpeta leng\ junto al ejecutable (directorio actual) -> leng/idiomas.txt
bool Game::loadLangData(std::string& out) {
    return readWholeFile("leng/idiomas.txt", out) || readWholeFile(baseDir + "leng/idiomas.txt", out);
}

std::string Game::L(const std::string& t) {
    if (t.empty() || txKey.empty()) return t;
    std::map<std::string, int>::const_iterator it = txKey.find(t);
    if (it == txKey.end()) return t;
    const std::string& s = ltxt[it->second];
    if (s.empty()) return t;
    if (t[t.size() - 1] == ' ' && s[s.size() - 1] != ' ') return s + " ";
    return s;
}

int Game::langPos() {
    for (int i = 0; i < langN; i++) if (langIds[i] == lang) return i;
    return -1;
}

void Game::nextLang() {
    lang = langIds[(langPos() + 1) % langN];
}

void Game::applyLang() {
    static bool saved = false;
    if (txKey.empty()) {
        for (int i = 0; i < L_ES_N; i++) if (txKey.find(L_ES[i]) == txKey.end()) txKey[L_ES[i]] = i + 1;
    }
    if (!langListed) {
        langListed = true;
        readLangFile(true, -1);
        if (langN == 0) { langN = 1; langIds[0] = 0; langNames[0] = "Espanol"; }
    }
    if (langPos() < 0) lang = langIds[0];
    if (!saved) { for (int i = 0; i < 128; i++) namesEs[i] = names[i]; saved = true; }
    for (int i = 0; i < TX_MAX; i++) ltxt[i].clear();
    readLangFile(false, lang);
    for (int i = 0; i < 128; i++) {
        const std::string& s = ltxt[NAME_BASE + i];
        names[i] = s.empty() ? namesEs[i] : s;
    }
}

// lee idiomas.txt: list = arma la lista de idiomas (cabecera); si no, carga en ltxt[] el bloque [id]
void Game::readLangFile(bool list, int id) {
    lfSec = -1;
    std::string data;
    if (!loadLangData(data)) return;
    size_t i = 0, n = data.size();
    if (n >= 3 && (unsigned char)data[0] == 0xEF && (unsigned char)data[1] == 0xBB && (unsigned char)data[2] == 0xBF) i = 3;   // BOM
    bool done = false;
    while (!done && i < n) {
        size_t e = data.find('\n', i);
        if (e == std::string::npos) e = n;
        done = langLine(utf8Fold(data.substr(i, e - i)), list, id);
        i = e + 1;
    }
}

// procesa una linea; devuelve true cuando ya no hace falta seguir leyendo
bool Game::langLine(const std::string& line, bool list, int id) {
    std::string s = langTrim(line);
    if (s.empty()) return false;
    char c0 = s[0];
    if (c0 == '#') return false;
    if (c0 == '[') {
        if (list) return true;                          // la cabecera termina en el primer bloque
        size_t cb = s.find(']');
        if (cb == std::string::npos) return false;
        if (lfSec == id) return true;                   // termino el bloque pedido
        lfSec = langNum(s.substr(1, cb - 1));
        return false;
    }
    size_t eq = s.find('=');
    if (eq == std::string::npos || eq == 0) return false;
    if (list) {
        size_t e = s.find(']');
        if (s.compare(0, 5, "lang[") == 0 && e != std::string::npos && e > 5 && e < eq && langN < MAX_LANGS) {
            int lid = langNum(s.substr(5, e - 5));
            std::string nm = trimWs(s.substr(eq + 1));
            if (lid >= 0 && lid < 128 && !nm.empty()) {
                bool dup = false;
                for (int i = 0; i < langN; i++) if (langIds[i] == lid) dup = true;
                if (!dup) { langIds[langN] = lid; langNames[langN] = nm; langN++; }
            }
        }
    } else if (lfSec == id) {
        int tid = langNum(s.substr(0, eq));
        std::string t = trimWs(s.substr(eq + 1));
        if (tid > 0 && tid < TX_MAX && !t.empty()) ltxt[tid] = t;
    }
    return false;
}
