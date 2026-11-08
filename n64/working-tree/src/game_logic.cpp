#include "game.h"

// ---------------------------------------------------------------- navegacion de menus
void Game::buildNav() {
    if (state == ST_MENU) { navIds[0] = A_PLAY; navIds[1] = A_QUIT; navN = 2; }
    else if (state == ST_PAUSE) { navIds[0] = A_RESUME; navIds[1] = A_SETTINGS; navIds[2] = A_TOMENU; navN = 3; }
    else if (state == ST_SET) { navIds[0] = A_RD; navIds[1] = A_LANG; navIds[2] = A_SETBACK; navN = 3; }
    else if (state == ST_CREATE) { navIds[0] = A_CNAME; navIds[1] = A_CSEED; navIds[2] = A_CMODE; navIds[3] = A_CGO; navIds[4] = A_CBACK; navN = 5; }
    else if (state == ST_CONFIRM) { navIds[0] = A_YES; navIds[1] = A_NO; navN = 2; }
    else if (state == ST_WORLDS) {
        int n = (int)worlds.size();
        if (n > 70) n = 70;
        navIds[0] = A_NEW;
        for (int i = 0; i < n; i++) navIds[i + 1] = 100 + i;
        navIds[n + 1] = A_WBACK;
        navN = n + 2;
    } else { navIds[0] = 0; navN = 1; }
    navSel = 0;
}

void Game::go(int st) { state = st; wScroll = 0; buildNav(); }

void Game::navMove(int d) {
    if (navN <= 0) return;
    navSel = (navSel + d + navN) % navN;
    if (state == ST_WORLDS) {
        int i = navSel - 1;
        if (i >= 0 && i < (int)worlds.size()) {
            if (i < wScroll) wScroll = i;
            if (i >= wScroll + wVis) wScroll = i - wVis + 1;
        }
    }
}

void Game::openMain() { go(ST_MENU); }

void Game::openWorlds() {
    loadWorldList();
    go(ST_WORLDS);
    navSel = (worlds.size() > 0) ? 1 : 0;
}

void Game::openCreate() {
    cName = fmt("Mundo %d", nextId());
    cSeedRandom = true; cSeed = 0;
    cMode = 0;
    go(ST_CREATE);
    navSel = 3;
}

void Game::openPause() { clearKeys(); go(ST_PAUSE); job = J_SAVE; }

void Game::closeSettings() {
    go(ST_PAUSE);
    navSel = 1;
    if (rdIdx != rdApplied) job = J_APPLY;
}

void Game::rdStep(int d) { rdIdx = (rdIdx + d + 5) % 5; }

void Game::askDelete(int i) {
    if (i < 0 || i >= (int)worlds.size()) return;
    delTarget = i;
    go(ST_CONFIRM);
    navSel = 1;
}

void Game::doCreate() {
    if (worlds.size() >= 60) return;
    int sd = cSeedRandom ? (int)rnd32() : cSeed;
    jobWorldIdx = addWorld(cName, sd, cMode);
    job = J_ENTER;
}

void Game::activate(int id) {
    if (id >= 200) { askDelete(id - 200); return; }
    if (id >= 100) {
        int i = id - 100;
        if (i < (int)worlds.size()) { jobWorldIdx = i; job = J_ENTER; }
        return;
    }
    switch (id) {
        case A_PLAY: openWorlds(); break;
        case A_QUIT: g_running = false; break;
        case A_RESUME: state = ST_PLAY; clearKeys(); break;
        case A_SETTINGS: go(ST_SET); break;
        case A_TOMENU: job = J_LEAVE; break;
        case A_RD: rdStep(1); break;
        case A_LANG: lang = 1 - lang; applyLang(); saveSettings(); break;
        case A_SETBACK: closeSettings(); break;
        case A_NEW: openCreate(); break;
        case A_WBACK: openMain(); break;
        case A_CNAME: job = J_NAME; break;
        case A_CSEED: cSeedRandom = !cSeedRandom; break;
        case A_CMODE: cMode = 1 - cMode; break;
        case A_CGO: doCreate(); break;
        case A_CBACK: openWorlds(); break;
        case A_YES:
            if (delTarget >= 0) deleteWorld(delTarget);
            delTarget = -1;
            openWorlds();
            break;
        case A_NO: openWorlds(); break;
        default: break;
    }
}

void Game::menuBack() {
    if (state == ST_PAUSE) { state = ST_PLAY; clearKeys(); }
    else if (state == ST_SET) closeSettings();
    else if (state == ST_WORLDS) openMain();
    else if (state == ST_CREATE || state == ST_CONFIRM) openWorlds();
}

// k: 2 arriba, 8 abajo, 4 izq, 6 der, 5 aceptar, 10 borrar, 0/12/13 atras, 1/3 L/R
void Game::menuKey(int k) {
    if (k == 2) navMove(-1);
    else if (k == 8) navMove(1);
    else if (k == 5) activate(navIds[navSel]);
    else if (k == 4 || k == 6) {
        int id = navIds[navSel];
        if (id == A_RD) rdStep(k == 6 ? 1 : -1);
        else if (id == A_LANG) activate(A_LANG);
        else if (id == A_CMODE) cMode = 1 - cMode;
        else if (id == A_CSEED) { cSeedRandom = false; cSeed += (k == 6) ? 1 : -1; }
    } else if (k == 1 || k == 3) {
        if (navIds[navSel] == A_CSEED) { cSeedRandom = false; cSeed += (k == 3) ? 1000 : -1000; }
    } else if (k == 10 && state == ST_WORLDS) {
        int id = navIds[navSel];
        if (id >= 100 && id < 200) activate(id + 100);
    } else if (k == 0 || k == 12 || k == 13) menuBack();
}

void Game::respawn() {
    if (dim != 0) switchDim(0);   // al morir en el Nether se vuelve al Overworld
    bool atBed = false;
    if (bedOn) {
        px = bedX + 0.5f; pz = bedZ + 0.5f;
        updateWindow(true);
        loadMissing(nch);
        if (blockAt(bedX, bedY, bedZ) == B_BED) {
            static const int dxs[4] = { 1, -1, 0, 0 }, dzs[4] = { 0, 0, 1, -1 };
            bool placed = false;
            for (int k = 0; k < 4 && !placed; k++) {
                int nx = bedX + dxs[k], nz = bedZ + dzs[k];
                if (solidAt(nx, bedY - 1, nz) && !boxCollides(nx + 0.5f, bedY + 0.01f, nz + 0.5f, HW, 1.8f)) {
                    px = nx + 0.5f; pz = nz + 0.5f; py = bedY + 0.01f; placed = true;
                }
            }
            if (!placed) { px = bedX + 0.5f; pz = bedZ + 0.5f; py = bedY + 1.001f; }
            vy = 0; fallDist = 0; yaw = 0; pitch = 0; onGround = false;
            clearKeys();
            atBed = true;
            say("Reapareciste en tu cama");
        } else {
            bedOn = false;
            say("Tu cama fue destruida");
        }
    }
    if (!atBed) resetPlayer();
    health = 20; hunger = 20; dmgFlash = 0; hungerFx = 0; air = 300; airT = 0;
    state = ST_PLAY;
}

// ---------------------------------------------------------------- entrada del pad
// Juego:  botones C = girar / mirar | cruceta o stick: arriba/abajo = caminar, izq/der = paso lateral
//         A = saltar (doble toque en creativo = volar) | R = romper/atacar (mantener) o interactuar/colocar (toque corto)
//         B (toque) = inventario | B + L / B + R = mover el selector de la hotbar
//         L (solo) = depuracion (F3) | Start = pausa
// Menus:  D-pad/stick = mover | A = aceptar | B/Start = atras | Z = borrar mundo / click secundario
// (la traduccion del mando de N64 a estos botones esta en main.cpp)
void Game::handlePad(const PadState& pad) {
    unsigned b = pad.Buttons;
    float lx = pad.lx, ly = pad.ly;
    unsigned bb = b;
    if (ly < -0.6f) bb |= PAD_UP;
    if (ly > 0.6f) bb |= PAD_DOWN;
    if (lx < -0.6f) bb |= PAD_LEFT;
    if (lx > 0.6f) bb |= PAD_RIGHT;
    unsigned pressed = bb & ~prevBtn;
    unsigned released = ~bb & prevBtn;
    prevBtn = bb;
    if (!busy.empty()) return;

    static bool bArmed = false, bChord = false;
    if (state != ST_PLAY) bArmed = false;

    if (state == ST_PLAY) {
        kFw = (b & PAD_UP) != 0 || ly < -0.35f;
        if (kFw && !kFwPrev) { if (tick - fwTick < 8) sprint = true; fwTick = tick; }   // doble toque adelante = correr
        kFwPrev = kFw;
        kBk = (b & PAD_DOWN) != 0 || ly > 0.35f;
        kSl = (b & PAD_LEFT) != 0 || lx < -0.35f;
        kSr = (b & PAD_RIGHT) != 0 || lx > 0.35f;
        kTurn = pad.cx;                                // C izq/der = girar
        kLook = -pad.cy;                               // C arriba = mirar arriba
        bool bHeld = (b & PAD_CIRCLE) != 0;
        kJump = (b & PAD_CROSS) != 0;
        kMine = (((b & PAD_R) != 0) && !bHeld) || tcMode == 3;
        // R: mantener = romper/atacar, toque corto = interactuar/colocar (si no rompio ni ataco)
        if ((pressed & PAD_R) && !bHeld) { sqArm = true; sqDone = false; sqT0 = tick; }
        if ((released & PAD_R) && sqArm) {
            sqArm = false;
            if (!sqDone && tick - sqT0 <= 4) doTap();
        }
        if (pressed & PAD_START) { bArmed = false; openPause(); return; }
        if ((pressed & PAD_CROSS) && creative) {        // doble toque de salto: volar / aterrizar
            if (tick - jumpTick < 8) { flying = !flying; vy = 0; fallDist = 0; jumpTick = -100; }
            else jumpTick = tick;
        }
        // B: toque = inventario (al soltar, si no se uso como modificador); B + L/R = selector de hotbar
        if (pressed & PAD_CIRCLE) { bArmed = true; bChord = false; }
        if (bHeld && (pressed & PAD_L)) { sel = (sel + 8) % 9; labelT = 40; bChord = true; }
        else if (bHeld && (pressed & PAD_R)) { sel = (sel + 1) % 9; labelT = 40; bChord = true; }
        else if (!bHeld && (pressed & PAD_L)) dbg = !dbg;
        if ((released & PAD_CIRCLE) && bArmed && !bChord) { bArmed = false; openInv(2); return; }
        if (pressed & PAD_TRIANGLE) openInv(2);
        if (pressed & PAD_SELECT) dbg = !dbg;
        return;
    }

    static const struct { unsigned m; int k; } map[] = {
        { PAD_UP, 2 }, { PAD_DOWN, 8 }, { PAD_LEFT, 4 }, { PAD_RIGHT, 6 },
        { PAD_CROSS, 5 }, { PAD_SQUARE, 10 }, { PAD_CIRCLE, 0 }, { PAD_TRIANGLE, 0 },
        { PAD_START, 12 }, { PAD_L, 1 }, { PAD_R, 3 }
    };
    for (size_t i = 0; i < sizeof(map) / sizeof(map[0]); i++)
        if ((pressed & map[i].m) && state != ST_PLAY) onKey(map[i].k);

    // auto-repeticion de las 4 direcciones (menus e inventario)
    static const unsigned dirs[4] = { PAD_UP, PAD_DOWN, PAD_LEFT, PAD_RIGHT };
    static const int dk[4] = { 2, 8, 4, 6 };
    for (int i = 0; i < 4; i++) {
        if (bb & dirs[i]) {
            repCnt[i]++;
            if (repCnt[i] > 18 && (repCnt[i] % 4) == 0) onKeyRepeat(dk[i]);
        } else repCnt[i] = 0;
    }
}

void Game::onKey(int k) {
    if (!busy.empty()) return;
    if (state == ST_DEAD) {
        if (k == 5) respawn();
    } else if (state == ST_INV) {
        if (k == 0 || k == 12 || k == 13) closeInv();
        else if (palMode() && (k == 1 || k == 3)) palFlip(k == 3 ? 1 : -1);
        else if (craftN == TRADE && (k == 1 || k == 3)) tradeMove(k == 3 ? 1 : -1);
        else if (k == 2 || k == 4 || k == 6 || k == 8) moveCursor(k);
        else if (k == 5) click(cursor);
        else if (k == 10) clickSecondary(cursor);
    } else if (state != ST_PLAY) {
        menuKey(k);
    }
}

void Game::onKeyRepeat(int k) {
    if (!busy.empty()) return;
    if (state == ST_INV && (k == 2 || k == 4 || k == 6 || k == 8)) moveCursor(k);
    else if (isMenu() && (k == 2 || k == 8)) menuKey(k);
}

// ---------------------------------------------------------------- inventario
int Game::addItem(int id, int cnt, int dur) {
    int left = cnt;
    if (maxStack(id) > 1) {
        for (int i = 0; i < 36; i++) {
            if (slId[i] == id && slCnt[i] < 64) {
                int mv = 64 - slCnt[i]; if (left < mv) mv = left;
                slCnt[i] += mv;
                left -= mv;
                if (left <= 0) return 0;
            }
        }
    }
    for (int i = 0; i < 36; i++) {
        if (slId[i] == 0) {
            int put = (maxStack(id) > 1) ? (left < 64 ? left : 64) : 1;
            slId[i] = id;
            slCnt[i] = put;
            slDur[i] = isTool(id) ? (dur > 0 ? dur : toolMax(id)) : 0;
            left -= put;
            if (left <= 0) return 0;
        }
    }
    return left;
}

int Game::palAt(int slot) {
    int k = palPage * 27 + (slot - 9);
    return (k >= 0 && k < palN) ? palIds[k] : 0;
}

void Game::palFlip(int d) {
    int pages = (palN + 26) / 27;
    palPage = (palPage + d + pages) % pages;
}

void Game::palTake(int slot, int cnt) {
    int pid = palAt(slot);
    if (pid == 0) return;
    if (heldId == pid && cnt > 1) { heldId = 0; heldCnt = 0; heldDur = 0; return; }
    if (heldId == pid && cnt == 1 && maxStack(pid) > 1) { if (heldCnt < 64) heldCnt++; return; }
    heldId = pid;
    heldCnt = (maxStack(pid) > 1) ? cnt : 1;
    heldDur = isTool(pid) ? toolMax(pid) : 0;
}

void Game::clearSlot(int i) { slId[i] = 0; slCnt[i] = 0; slDur[i] = 0; }

void Game::openInv(int n) {
    craftN = n;
    state = ST_INV;
    clearKeys();
    cursor = sel;
    if (n == FURN) syncToSlots();
    layoutInv();
    updateOutput();
}

void Game::closeInv() {
    if (craftN == CHEST) chestClose();
    if (craftN == FURN) {
        syncFromSlots();
        for (int i = 36; i < 45; i++) clearSlot(i);
    }
    for (int i = 36; i < 45; i++) {
        if (slId[i] != 0) { addItem(slId[i], slCnt[i], slDur[i]); clearSlot(i); }
    }
    if (heldId != 0) { if (!creative) addItem(heldId, heldCnt, heldDur); heldId = 0; heldCnt = 0; heldDur = 0; }
    clearSlot(OUT);
    state = ST_PLAY;
    clearKeys();
}

void Game::layoutInv() {
    int mw = (touch && palMode()) ? (sw / 10 > 14 ? sw / 10 : 14) : 4;
    int s = (sw - 2 * mw) / 9;
    int sv = (sh - 40 - (touch ? (sw / 11 < 30 ? sw / 11 : 30) + 6 : 0)) / 7;
    if (sv < s) s = sv;
    if (s > 30) s = 30;
    if (s < 10) s = 10;
    slS = s;
    int x0 = (sw - 9 * s) / 2;
    int y0 = 14;
    int ym = y0 + 3 * s + 8;
    int yh = ym + 3 * s + 4;
    for (int i = 0; i < NSLOT; i++) slActive[i] = false;
    for (int i = 0; i < 9; i++) { slx[i] = x0 + i * s; sly[i] = yh; slActive[i] = true; }
    for (int i = 9; i < 36; i++) {
        int r = (i - 9) / 9, c = (i - 9) % 9;
        slx[i] = x0 + c * s; sly[i] = ym + r * s; slActive[i] = true;
    }
    if (craftN == CHEST) {
        for (int i = 0; i < CHN; i++) { slx[CH0 + i] = x0 + (i % 9) * s; sly[CH0 + i] = y0 + (i / 9) * s; slActive[CH0 + i] = true; }
        return;
    }
    int n = (craftN == FURN || craftN == TRADE) ? 0 : craftN;
    int oy_ = (n == 2) ? s / 2 : 0;
    int gx0 = x0 + s + ((n == 2) ? s / 2 : 0);
    for (int r = 0; r < n; r++) {
        for (int c = 0; c < n; c++) {
            int idx = 36 + r * n + c;
            slx[idx] = gx0 + c * s; sly[idx] = y0 + oy_ + r * s; slActive[idx] = true;
        }
    }
    slx[OUT] = x0 + 6 * s; sly[OUT] = y0 + s; slActive[OUT] = true;
    if (craftN == FURN) {
        for (int i = 36; i < 45; i++) slActive[i] = false;
        slx[36] = x0 + 3 * s; sly[36] = y0;
        slx[37] = x0 + 3 * s; sly[37] = y0 + 2 * s;
        slx[38] = x0 + 6 * s; sly[38] = y0 + s;
        slActive[36] = true; slActive[37] = true; slActive[38] = true;
        slActive[OUT] = false;
    }
    if (craftN == TRADE) {
        slx[36] = x0 + 6 * s; sly[36] = y0; slActive[36] = true;
        slx[OUT] = x0 + 6 * s; sly[OUT] = y0 + 2 * s; slActive[OUT] = true;
    }
}

void Game::moveCursor(int k) {
    int c = cursor, best = -1, bs = 0x7fffffff;
    for (int j = 0; j < NSLOT; j++) {
        if (!slActive[j] || j == c) continue;
        int dx = slx[j] - slx[c], dy = sly[j] - sly[c];
        bool ok;
        if (k == 2) ok = dy < 0;
        else if (k == 8) ok = dy > 0;
        else if (k == 4) ok = dx < 0;
        else ok = dx > 0;
        if (!ok) continue;
        int score;
        if (k == 2 || k == 8) score = abs(dy) + 3 * abs(dx);
        else score = abs(dx) + 3 * abs(dy);
        if (score < bs) { bs = score; best = j; }
    }
    if (best >= 0) cursor = best;
}

int Game::findRecipe() {
    if (craftN == FURN || craftN == CHEST) return -1;
    int n = craftN;
    int minr = n, maxr = -1, minc = n, maxc = -1;
    for (int r = 0; r < n; r++) {
        for (int c = 0; c < n; c++) {
            if (slId[36 + r * n + c] != 0) {
                if (r < minr) minr = r;
                if (r > maxr) maxr = r;
                if (c < minc) minc = c;
                if (c > maxc) maxc = c;
            }
        }
    }
    if (maxr < 0) return -1;
    int bw = maxc - minc + 1, bh = maxr - minr + 1;
    for (int k = 0; k < nrec; k++) {
        if (rw[k] != bw || rh[k] != bh) continue;
        bool ok = true, okm = true;
        for (int r = 0; r < bh; r++) {
            for (int c = 0; c < bw; c++) {
                int have = pn(slId[36 + (minr + r) * n + minc + c]);
                if (have != rPat[k][r * bw + c]) ok = false;
                if (have != rPat[k][r * bw + (bw - 1 - c)]) okm = false;
            }
        }
        if (ok || okm) return k;
    }
    return -1;
}

void Game::updateOutput() {
    if (craftN == TRADE) { tradeOutput(); return; }
    int k = findRecipe();
    if (k < 0) { clearSlot(OUT); return; }
    slId[OUT] = rOut[k];
    slCnt[OUT] = rCnt[k];
    slDur[OUT] = isTool(rOut[k]) ? toolMax(rOut[k]) : 0;
}

void Game::consumeGrid() {
    for (int i = 0; i < craftN * craftN; i++) {
        int s = 36 + i;
        if (slId[s] != 0) {
            slCnt[s]--;
            if (slCnt[s] <= 0) clearSlot(s);
        }
    }
}

void Game::takeOutput() {
    if (craftN == TRADE) { tradeTake(); return; }
    if (slId[OUT] == 0) return;
    if (heldId == 0) {
        heldId = slId[OUT]; heldCnt = slCnt[OUT]; heldDur = slDur[OUT];
        consumeGrid();
    } else if (heldId == slId[OUT] && maxStack(heldId) > 1 && heldCnt + slCnt[OUT] <= 64) {
        heldCnt += slCnt[OUT];
        consumeGrid();
    }
    updateOutput();
}

void Game::click(int idx) {
    if (palMode() && idx >= 9 && idx < 36) { palTake(idx, 64); return; }
    if (craftN == FURN && idx == 38) { takeFurnaceOut(); return; }
    if (idx == OUT) { takeOutput(); return; }
    int id = slId[idx], cnt = slCnt[idx], dur = slDur[idx];
    if (heldId == 0) {
        if (id != 0) { heldId = id; heldCnt = cnt; heldDur = dur; clearSlot(idx); }
    } else if (id == 0) {
        slId[idx] = heldId; slCnt[idx] = heldCnt; slDur[idx] = heldDur;
        heldId = 0; heldCnt = 0; heldDur = 0;
    } else if (id == heldId && maxStack(id) > 1) {
        int mv = 64 - cnt; if (heldCnt < mv) mv = heldCnt;
        slCnt[idx] += mv;
        heldCnt -= mv;
        if (heldCnt <= 0) { heldId = 0; heldCnt = 0; heldDur = 0; }
    } else {
        slId[idx] = heldId; slCnt[idx] = heldCnt; slDur[idx] = heldDur;
        heldId = id; heldCnt = cnt; heldDur = dur;
    }
    if (idx >= 36) updateOutput();
}

void Game::clickSecondary(int idx) {
    if (palMode() && idx >= 9 && idx < 36) { palTake(idx, 1); return; }
    if (craftN == FURN && idx == 38) { takeFurnaceOut(); return; }
    if (idx == OUT) { takeOutput(); return; }
    if (heldId == 0) {
        if (slId[idx] != 0) {
            int take = (slCnt[idx] + 1) / 2;
            heldId = slId[idx]; heldCnt = take; heldDur = slDur[idx];
            slCnt[idx] -= take;
            if (slCnt[idx] <= 0) clearSlot(idx);
        }
    } else {
        bool empty = (slId[idx] == 0);
        bool same = (slId[idx] == heldId && slCnt[idx] < 64 && maxStack(heldId) > 1);
        if (empty || same) {
            if (empty) { slId[idx] = heldId; slDur[idx] = heldDur; slCnt[idx] = 0; }
            slCnt[idx]++;
            heldCnt--;
            if (heldCnt <= 0) { heldId = 0; heldCnt = 0; heldDur = 0; }
        }
    }
    if (idx >= 36) updateOutput();
}

// ---------------------------------------------------------------- logica por tick (50 ms)
void Game::update() {
    tick++;
    if (labelT > 0) labelT--;
    if (msgT > 0) msgT--;
    if (dmgFlash > 0) dmgFlash--;
    if (!tpDown && (tcMode != 0 || tFw || tBk || tL || tR || tJp)) tcCancel();
    if (state == ST_INV) tcInvTick();
    if (state == ST_INV && craftN == FURN) { syncFromSlots(); updateFurnace(); syncToSlots(); }
    if (state != ST_PLAY) return;
    worldTime++;
    if (dim == 0 && tick % 200 == 0) growCrops();
    bright = (dim == 1) ? 45 : dayBright();
    if (bright < 100 && tick % 5 == 0) scanTorches();
    updateFurnace();
    updateSaplings();
    if (++autoT >= 2400) { autoT = 0; needSave = true; }
    updatePlayer();
    updatePortal();
    updateWindow(false);
    loadMissing(1);
    updateMobs();
    castRay();
    updateAction();
    updateSurvival();
}

// devuelve true si ejecuto un trabajo pesado (el bucle principal reinicia el reloj)
void Game::runJobs() {
    int j = job;
    if (j == 0 && needSave) j = J_SAVE;
    if (j == 0) return;
    job = 0;
    if (j == J_NAME) {                          // editor de texto del sistema (bloquea hasta aceptar o cancelar)
        std::string s = oskEdit("Nombre del mundo", cName, 20);
        if (s.empty()) s = fmt("Mundo %d", nextId());
        cName = s;
        return;
    }
    needSave = false;
    busy = (j == J_ENTER) ? "Cargando mundo..." : (j == J_APPLY ? "Cargando..." : "Guardando...");
    render();                                   // muestra el cartel antes de bloquear
    if (j == J_ENTER) {
        if (jobWorldIdx >= 0 && jobWorldIdx < (int)worlds.size()) { WorldInfo w = worlds[jobWorldIdx]; enterWorld(w); }
    } else if (j == J_APPLY) applyRd();
    else if (j == J_LEAVE) { leaveWorld(); openMain(); }
    else saveAll();
    busy.clear();
}

void Game::hurt(int n) {
    if (n <= 0 || creative) return;
    health -= n;
    dmgFlash = 6;
    if (health <= 0) {
        health = 0;
        state = ST_DEAD;
        clearKeys();
    }
}

void Game::updatePlayer() {
    bool fw = kFw || tFw, bk = kBk || tBk, jp = kJump || tJp;
    yaw += kTurn * LOOK;
    if (tL) yaw -= LOOK;
    if (tR) yaw += LOOK;
    pitch += kLook * LOOK;
    if (pitch > 1.4f) pitch = 1.4f;
    if (pitch < -1.4f) pitch = -1.4f;
    if (!fw) sprint = false;
    if (ride) {
        if (mobAlive(ride)) { rideBoat(fw, bk); return; }
        ride = 0;
    }

    float sn = sinf(yaw), cs = cosf(yaw);
    if (creative && flying) {
        float sp2 = SPD * (sprint ? 2.4f : 1.6f);
        float cpc = cosf(pitch), spc = sinf(pitch);
        float fmx = 0, fmy = 0, fmz = 0;
        if (fw) { fmx += sn * cpc * sp2; fmz += cs * cpc * sp2; fmy += spc * sp2; }
        if (bk) { fmx -= sn * cpc * sp2; fmz -= cs * cpc * sp2; fmy -= spc * sp2; }
        if (kSl) { fmx -= cs * sp2; fmz += sn * sp2; }
        if (kSr) { fmx += cs * sp2; fmz -= sn * sp2; }
        if (jp) fmy += sp2;
        if (fmx != 0 && !boxCollides(px + fmx, py, pz, HW, 1.8f)) px += fmx;
        if (fmz != 0 && !boxCollides(px, py, pz + fmz, HW, 1.8f)) pz += fmz;
        if (fmy != 0 && !boxCollides(px, py + fmy, pz, HW, 1.8f)) py += fmy;
        if (py < 1.f) py = 1.f;
        vy = 0; fallDist = 0; onGround = false;
        moving = (fw || bk || kSl || kSr);
        return;
    }
    float mx = 0, mz = 0;
    if (fw) { mx += sn * SPD; mz += cs * SPD; }
    if (bk) { mx -= sn * SPD; mz -= cs * SPD; }
    if (kSl) { mx -= cs * SPD; mz += sn * SPD; }
    if (kSr) { mx += cs * SPD; mz -= sn * SPD; }
    moving = (fw || bk || kSl || kSr);
    bool inW = waterAtF(px, py + 0.4f, pz) || waterAtF(px, py + 1.2f, pz)
            || lavaAtF(px, py + 0.4f, pz) || lavaAtF(px, py + 1.2f, pz);
    bool swim = inW && !lavaAtF(px, py + 0.4f, pz) && !lavaAtF(px, py + 1.2f, pz)
            && waterAtF(px, py + 0.4f, pz) && waterAtF(px, py + 1.2f, pz);
    int bc = inW ? bubbleAt(ifloor(px), ifloor(py + 0.3f), ifloor(pz)) : 0;   // corriente de burbujas bajo el jugador
    if (swim && (fw || bk)) {
        // nadar: igual que volar, avanza hacia donde miras; doble toque adelante = mas rapido
        float s2 = SPD * ((sprint && fw) ? 1.2f : 0.6f);
        float cpc = cosf(pitch), spc2 = sinf(pitch), dr = fw ? 1.f : -1.f;
        float smx = dr * sn * cpc * s2, smz = dr * cs * cpc * s2, smy = dr * spc2 * s2;
        if (smy > 0 && !waterAtF(px, py + 1.9f, pz)) smy = 0;
        bool sbx = boxCollides(px + smx, py, pz, HW, 1.8f), sbz = boxCollides(px, py, pz + smz, HW, 1.8f);
        if (!sbx) px += smx;
        if (!sbz) pz += smz;
        if (smy != 0 && !boxCollides(px, py + smy, pz, HW, 1.8f)) py += smy;
        if ((sbx || sbz) && dr > 0 && !boxCollides(px + smx, py + 1.0f, pz + smz, HW, 1.8f) && !boxCollides(px, py + 0.15f, pz, HW, 1.8f)) py += 0.15f;
        if (bc != 0) { float bpy = bc * 0.3f; if (!boxCollides(px, py + bpy, pz, HW, 1.8f)) py += bpy; }
        vy = 0; fallDist = 0; onGround = false;
        return;
    }
    if (inW) { mx *= 0.5f; mz *= 0.5f; }
    else if (sprint && fw) { mx *= 1.4f; mz *= 1.4f; }

    bool blockedX = mx != 0 && boxCollides(px + mx, py, pz, HW, 1.8f);
    bool blockedZ = mz != 0 && boxCollides(px, py, pz + mz, HW, 1.8f);
    if (mx != 0 && !blockedX) px += mx;
    if (mz != 0 && !blockedZ) pz += mz;
    bool autoJump = false;
    if (onGround && (blockedX || blockedZ)) {
        float ax2 = blockedX ? px + mx : px, az2 = blockedZ ? pz + mz : pz;
        if (!boxCollides(px, py + 1.0f, pz, HW, 1.8f) && !boxCollides(ax2, py + 1.0f, az2, HW, 1.8f)) autoJump = true;
    }

    if (cactusT > 0) cactusT--;
    if (cactusT <= 0 && touchingCactus()) { hurt(1); cactusT = 10; }
    float oldY = py;
    if (inW) {
        if (jp) vy += 0.04f;
        if (blockedX || blockedZ) {
            float wx = blockedX ? px + mx : px, wz = blockedZ ? pz + mz : pz;
            if (!boxCollides(wx, py + 1.0f, wz, HW, 1.8f)) vy = 0.3f;
        }
        if (bc > 0) vy += 0.12f; else if (bc < 0) vy -= 0.1f;
        vy = vy * 0.8f - 0.02f;
        fallDist = 0.f;
    } else {
        if ((jp || autoJump) && onGround) vy = JUMP;
        vy = (vy - GRAV) * 0.98f;
    }
    float ny = py + vy;
    onGround = false;
    if (boxCollides(px, ny, pz, HW, 1.8f)) {
        if (vy < 0) {
            float sp = ifloor(ny) + 1.001f;
            if (!boxCollides(px, sp, pz, HW, 1.8f)) py = sp;
            onGround = true;
            if (fallDist > 3.f) hurt((int)ceilf(fallDist - 3.f));
            fallDist = 0;
        } else {
            float sp = ifloor(ny + 1.8f) - 1.8f - 0.001f;
            if (!boxCollides(px, sp, pz, HW, 1.8f)) py = sp;
        }
        vy = 0;
    } else {
        py = ny;
        if (vy < 0) fallDist += oldY - py;
        else fallDist = 0;
    }
}

void Game::updateMobs() {
    updateFishing();
    for (int i = (int)mobs.size() - 1; i >= 0; i--) {
        if (i >= (int)mobs.size()) continue;
        Mob* m = mobs[i];
        updateMob(m);
        if (m->type == 5 && ++m->fuse >= 80) explode(m);
        if ((m->type == 3 || m->type == 4) && bright >= 90 && m->hp > 0 && (tick % 20) == 0) { m->hp--; m->hurt = 6; }
        if (m->hp <= 0) {
            if (tMob == m) tMob = 0;
            if (ride == m) ride = 0;
            delete m;
            mobs.erase(mobs.begin() + i);
        }
    }
    if (++spawnTimer >= 400) {
        spawnTimer = 0;
        if (dim == 0 && countMobs(false) < 10) spawnMob(rnd(3), true);
    }
    if (bright < 70 && ++hostTimer >= 100) {
        hostTimer = 0;
        if (countMobs(true) < 6) spawnMob(dim == 1 ? 3 : hostType(), true);
    }
}

int Game::countMobs(bool hostile) {
    int n = 0;
    for (size_t i = 0; i < mobs.size(); i++) {
        Mob* m = mobs[i];
        if (m->type == 5 || m->type == 6 || m->type == 11 || (m->type >= 7 && m->type <= 9)) continue;
        if ((m->type >= 3) == hostile) n++;
    }
    return n;
}

void Game::updateMob(Mob* m) {
    if (m->type == 11) { updateBoat(m); return; }
    if (m->type >= 7 && m->type <= 9) { updateFish(m); return; }
    float hw = mobHW(m->type);
    float mh = mobH(m->type);
    if (m->hurt > 0) m->hurt--;
    if (m->flee > 0) m->flee--;
    bool chase = (m->type == 3 || m->type == 4 || m->type == 10) && m->flee <= 0 && hostileAI(m);
    if (!chase && m->type != 5 && --m->timer <= 0) {
        if (rnd(3) == 0) { m->moving = false; m->timer = 20 + rnd(40); }
        else { m->moving = true; m->ang = rndf() * 6.2832f; m->timer = 20 + rnd(60); }
    }
    if (m->moving || m->flee > 0) {
        float sp = (m->flee > 0) ? 0.10f : mobSpeed(m->type);
        float mx = sinf(m->ang) * sp, mz = cosf(m->ang) * sp;
        bool blocked = false;
        if (!boxCollides(m->x + mx, m->y, m->z, hw, mh)) m->x += mx; else blocked = true;
        if (!boxCollides(m->x, m->y, m->z + mz, hw, mh)) m->z += mz; else blocked = true;
        if (blocked) {
            if (m->ground) m->vy = JUMP;
            if (m->flee <= 0 && rnd(4) == 0) m->ang = rndf() * 6.2832f;
        }
    }
    m->vy = (m->vy - GRAV) * 0.98f;
    float ny = m->y + m->vy;
    m->ground = false;
    if (boxCollides(m->x, ny, m->z, hw, mh)) {
        if (m->vy < 0) {
            float sp = ifloor(ny) + 1.001f;
            if (!boxCollides(m->x, sp, m->z, hw, mh)) m->y = sp;
            m->ground = true;
        }
        m->vy = 0;
    } else {
        m->y = ny;
    }
}

void Game::castRay() {
    float sy = sinf(yaw), cy = cosf(yaw);
    float sp = sinf(pitch), cp = cosf(pitch);
    float dx = sy * cp, dy = sp, dz = cy * cp;
    float ex = px, ey = py + EYE, ez = pz;
    tx = -1; ty = -1; tz = -1; ax = -1; ay = -1; az = -1;
    float tDist = 99.f;
    int lx = ifloor(ex), ly = ifloor(ey), lz = ifloor(ez);
    float d = 0.05f;
    int doorB = 0;
    bool bk = slId[sel] == I_BUCKET || slId[sel] == I_BOAT;
    int qx = lx, qy = ly, qz = lz;
    while (d < REACH) {
        int ix = ifloor(ex + dx * d), iy = ifloor(ey + dy * d), iz = ifloor(ez + dz * d);
        if (ix != lx || iy != ly || iz != lz) {
            int hb = blockAt(ix, iy, iz);
            if (hb != 0 && (bk || (hb != B_WATER && hb != B_LAVA)) && hb != B_PORTAL && hb != B_DOOR_LO && hb != B_DOOR_UO) {
                tx = ix; ty = iy; tz = iz;
                ax = lx; ay = ly; az = lz;
                tDist = d;
                break;
            }
            qx = lx; qy = ly; qz = lz;
            lx = ix; ly = iy; lz = iz;
            doorB = (hb == B_DOOR_LO || hb == B_DOOR_UO) ? hb : 0;
        }
        if (doorB != 0 && rayInDoor(doorB, ix, iy, iz, ex + dx * d, ez + dz * d)) {
            tx = ix; ty = iy; tz = iz;
            ax = qx; ay = qy; az = qz;
            tDist = d;
            break;
        }
        d += 0.04f;
    }
    tMob = 0;
    float best = MOB_REACH;
    if (tDist < best) best = tDist;
    for (size_t i = 0; i < mobs.size(); i++) {
        Mob* m = mobs[i];
        if (m->hp <= 0 || m->type == 5 || m == ride) continue;
        float r = mobR(m->type);
        float h = mobH(m->type);
        if (!((m->type >= 7 && m->type <= 9) || m->type == 11) && blockAt(ifloor(m->x), ifloor(m->y + h * 0.5f), ifloor(m->z)) != 0) continue;
        r = r * 0.8f;
        float cx = m->x - ex, cyy = m->y + h * 0.5f - ey, czz = m->z - ez;
        float t = cx * dx + cyy * dy + czz * dz;
        if (t < 0.2f || t > best) continue;
        float d2 = cx * cx + cyy * cyy + czz * czz - t * t;
        if (d2 < r * r) { best = t; tMob = m; }
    }
}

int Game::breakTicks(int b, int tool) {
    float secs = hard[b] * 1.5f;
    int tt = toolType(tool);
    bool right = false;
    if (b == B_STONE || b == B_COBBLE || b == B_FURNACE || b == B_SANDSTONE || b == B_ICE
            || (b >= B_COAL_ORE && b <= B_DIAM_BLK) || b == B_NETHERRACK || b == B_OBSIDIAN || (b >= B_CORAL0 && b <= B_CORAL4)) {
        right = (tt == 0);
        if (!right || toolLevel(tool) < needLevel(b)) { right = false; secs = hard[b] * 5.f; }
    } else if (isLog(b) || isPlanks(b) || b == B_TABLE || b == B_CHEST || isDoor(b)) {
        right = (tt == 1);
    } else if (b == B_GRASS || b == B_DIRT || b == B_SAND || b == B_SNOW || b == B_GRAVEL || b == B_SOULSAND || b == B_FARM_DRY || b == B_FARM_WET) {
        right = (tt == 2);
    }
    if (right) secs = secs / toolSpeed(tool);
    return (int)(secs * 20.f) + 1;
}

void Game::damageTool(int amount) {
    if (creative) return;
    if (isTool(slId[sel])) {
        slDur[sel] -= amount;
        if (slDur[sel] <= 0) clearSlot(sel);
    }
}

void Game::breakBlock(int x, int y, int z) {
    int b = blockAt(x, y, z);
    if (b == B_BEDROCK || b == B_LAVA || b == B_PORTAL) return;
    if ((b == B_FARM_DRY || b == B_FARM_WET) && blockAt(x, y + 1, z) >= B_CROP0 && blockAt(x, y + 1, z) <= B_CROP2) setBlock(x, y + 1, z, 0);
    if (b == B_CHEST && !creative) {
        int cid = chestFind(x, y, z);
        if (cid >= 0 && cFill[cid] > 0) { say("El cofre no esta vacio"); return; }
    }
    if (creative) {   // sin botin ni desgaste de herramienta
        if (isDoor(b)) {
            int cly = (b == B_DOOR_UC || b == B_DOOR_UO) ? y - 1 : y;
            if (isDoor(blockAt(x, cly, z))) setBlock(x, cly, z, 0);
            if (isDoor(blockAt(x, cly + 1, z))) setBlock(x, cly + 1, z, 0);
        } else {
            if (b == B_BED && dim == 0 && bedOn && bedX == x && bedY == y && bedZ == z) { bedOn = false; say("Cama destruida: sin punto de reaparicion"); }
            setBlock(x, y, z, fillOf(b));
        }
        return;
    }
    if (isDoor(b)) {
        int ly = (b == B_DOOR_UC || b == B_DOOR_UO) ? y - 1 : y;
        if (isDoor(blockAt(x, ly, z))) setBlock(x, ly, z, 0);
        if (isDoor(blockAt(x, ly + 1, z))) setBlock(x, ly + 1, z, 0);
        addItem(I_DOOR, 1, 0);
        damageTool(1);
        return;
    }
    int tl = slId[sel];
    bool pick = (toolType(tl) == 0);
    int lvl = pick ? toolLevel(tl) : -1;
    int drop = b;
    if (b == B_GRASS) drop = B_DIRT;
    else if (b == B_STONE || b == B_COBBLE) drop = pick ? B_COBBLE : 0;
    else if (b == B_SANDSTONE) drop = pick ? B_SANDSTONE : 0;
    else if (isLeaves(b)) drop = 0;
    else if (b == B_FARM_DRY || b == B_FARM_WET) drop = B_DIRT;
    else if (b >= B_CROP0 && b <= B_CROP2) drop = I_SEEDS;
    else if (b == B_ICE) drop = 0;
    else if (b == B_SEAGRASS) drop = 0;
    else if (b == B_SNOW) drop = (toolType(tl) == 2) ? B_SNOW : 0;
    else if (b == B_COAL_ORE) drop = (lvl >= 0) ? I_COAL : 0;
    else if (b == B_IRON_ORE) drop = (lvl >= 1) ? B_IRON_ORE : 0;
    else if (b == B_GOLD_ORE) drop = (lvl >= 2) ? B_GOLD_ORE : 0;
    else if (b == B_DIAM_ORE) drop = (lvl >= 2) ? I_DIAMOND : 0;
    else if (b >= B_IRON_BLK && b <= B_DIAM_BLK) drop = (lvl >= needLevel(b)) ? b : 0;
    else if (b == B_OBSIDIAN) drop = (lvl >= 3) ? b : 0;
    else if (b == B_NETHERRACK) drop = pick ? b : 0;
    if (b == B_BED && dim == 0 && bedOn && bedX == x && bedY == y && bedZ == z) { bedOn = false; say("Cama destruida: sin punto de reaparicion"); }
    setBlock(x, y, z, fillOf(b));
    if (drop != 0) addItem(drop, 1, 0);
    if (isLeaves(b) && rnd(8) == 0) addItem(I_APPLE, 1, 0);
    if (isLeaves(b) && rnd(6) == 0) addItem(B_SAPLING, 1, 0);
    if (b == B_CROP2) {
        addItem(I_WHEAT, 1, 0);
        if (rnd(2) == 0) addItem(I_SEEDS, 1, 0);
    }
    damageTool(1);
}

void Game::attackMob(Mob* m) {
    if (m->type == 11) {   // el barco no se daña: se recoge
        if (m == ride) return;
        for (size_t i = 0; i < mobs.size(); i++) {
            if (mobs[i] == m) { mobs.erase(mobs.begin() + i); break; }
        }
        delete m;
        tMob = 0;
        addItem(I_BOAT, 1, 0);
        return;
    }
    int t = slId[sel];
    int dmg = 1;
    int ttp = toolType(t);
    if (ttp == 3) dmg = 4;
    else if (ttp == 1) dmg = 3;
    else if (ttp == 0 || ttp == 2) dmg = 2;
    if (ttp >= 0) {
        int tr = toolTier(t);
        dmg += (tr == 1) ? 1 : ((tr == 2) ? 2 : ((tr == 4) ? 3 : 0));
    }
    m->hp -= dmg;
    m->hurt = 8;
    if (m->type < 3) {
        m->flee = 60;
        m->moving = true;
        m->ang = yaw + (rndf() - 0.5f) * 1.2f;
    }
    m->vy = 0.25f;
    float kx = sinf(yaw) * 0.4f, kz = cosf(yaw) * 0.4f;
    float hw = mobHW(m->type);
    float mh = mobH(m->type);
    if (!boxCollides(m->x + kx, m->y, m->z + kz, hw, mh)) { m->x += kx; m->z += kz; }
    damageTool(isTool(t) ? (toolType(t) == 3 ? 1 : 2) : 0);
    if (m->hp <= 0) {
        if (m->type == 0) {
            addItem(I_WOOL, 1, 0);
            addItem(I_MUTTON, 1 + rnd(2), 0);
        } else if (m->type == 1) {
            addItem(I_CHICKEN, 1, 0);
            int f = rnd(3);
            if (f > 0) addItem(I_FEATHER, f, 0);
        } else if (m->type == 2) {
            addItem(I_BEEF, 1 + rnd(3), 0);
        } else if (m->type == 3) {
            int f = rnd(3);
            if (f > 0) addItem(I_ROTTEN, f, 0);
            if (dim == 1 && rnd(4) == 0) addItem(I_GOLD_INGOT, 1, 0);
        } else if (m->type == 4) {
            int f = rnd(3);
            if (f > 0) addItem(I_GUNPOWDER, f, 0);
        } else if (m->type == 7) {
            addItem(I_COD, 1, 0);
        } else if (m->type == 8) {
            addItem(I_SALMON, 1, 0);
        } else if (m->type == 9) {
            addItem(I_TROPICAL, 1, 0);
        } else if (m->type == 10) {
            int f = rnd(3);
            if (f > 0) addItem(I_STRING, f, 0);
            if (rnd(3) == 0) addItem(I_SPIDER_EYE, 1, 0);
        }
        for (size_t i = 0; i < mobs.size(); i++) {
            if (mobs[i] == m) { mobs.erase(mobs.begin() + i); break; }
        }
        delete m;
        tMob = 0;
    }
}

void Game::updateAction() {
    if (attackCd > 0) attackCd--;
    if (!kMine) { mineProg = 0; mineX = -1; return; }
    if (tMob) {
        mineProg = 0;
        if (attackCd <= 0 && (tMob->type == 11 ? tick - sqT0 > 9 : (tMob->type != 6 || tick - sqT0 > 6))) {
            attackMob(tMob);
            sqDone = true;
            attackCd = 8;
        }
    } else if (tx >= 0 && !(slId[sel] == I_BOAT && blockAt(tx, ty, tz) == B_WATER)) {
        if (tx != mineX || ty != mineY || tz != mineZ) {
            mineX = tx; mineY = ty; mineZ = tz; mineProg = 0;
        }
        mineProg++;
        hungerTimer++;
        int need = creative ? 4 : breakTicks(blockAt(tx, ty, tz), slId[sel]);
        if (mineProg >= need) {
            breakBlock(tx, ty, tz);
            sqDone = true;
            mineProg = 0;
            mineX = -1;
        }
    } else {
        mineProg = 0;
        mineX = -1;
    }
}

void Game::consumeSel() {
    if (creative) return;
    slCnt[sel]--;
    if (slCnt[sel] <= 0) clearSlot(sel);
}

void Game::doTap() {
    int id = slId[sel];
    if (ride) { dismount(); return; }
    if (tMob && tMob->type == 11) { mount(tMob); return; }
    if (id == I_BOAT && tx >= 0) { placeBoat(); return; }
    if (id == I_ROD) { useRod(); return; }
    if (tMob && tMob->type == 6) { openInv(TRADE); return; }
    if (tx >= 0 && blockAt(tx, ty, tz) == B_TABLE) { openInv(3); return; }
    if (tx >= 0 && blockAt(tx, ty, tz) == B_FURNACE) { openInv(FURN); return; }
    if (tx >= 0 && blockAt(tx, ty, tz) == B_CHEST) { chestOpenAt(tx, ty, tz); return; }
    if (tx >= 0 && blockAt(tx, ty, tz) == B_TNT) { igniteTnt(tx, ty, tz, 1); return; }
    if (tx >= 0 && isDoor(blockAt(tx, ty, tz))) { toggleDoor(tx, ty, tz); return; }
    if (tx >= 0 && blockAt(tx, ty, tz) == B_BED) { useBed(tx, ty, tz); return; }
    if (tx >= 0 && slId[sel] == I_LIGHTER) {
        if (lightPortal(ax, ay, az)) say("Portal encendido");
        else if (blockAt(tx, ty, tz) == B_OBSIDIAN) say("Marco de portal incompleto");
        return;
    }
    if (tx >= 0 && id == I_HOE) {
        int hb = blockAt(tx, ty, tz);
        if ((hb == B_GRASS || hb == B_DIRT) && blockAt(tx, ty + 1, tz) == 0) {
            setBlock(tx, ty, tz, wetNear(tx, ty, tz) ? B_FARM_WET : B_FARM_DRY);
            damageTool(1);
        }
        return;
    }
    if (tx >= 0 && id == I_BUCKET) {
        int hb = blockAt(tx, ty, tz);
        if (hb == B_WATER || hb == B_LAVA) {
            setBlock(tx, ty, tz, 0);
            slId[sel] = hb == B_WATER ? I_BUCKET_W : I_BUCKET_L;
        }
        return;
    }
    if (tx >= 0 && (id == I_BUCKET_W || id == I_BUCKET_L)) {
        int cur = blockAt(ax, ay, az);
        if (cur == 0 || cur == B_WATER || cur == B_LAVA) {
            setBlock(ax, ay, az, id == I_BUCKET_W ? B_WATER : B_LAVA);
            slId[sel] = I_BUCKET;
        }
        return;
    }
    if (isFood(id)) {
        if (hunger < 20) {
            hunger = hunger + foodVal(id); if (hunger > 20) hunger = 20;
            if (id == I_ROTTEN) hungerFx = 300;
            consumeSel();
        }
        return;
    }
    if (isBlockItem(id) && tx >= 0) placeBlock(id);
}

void Game::placeBlock(int id) {
    if (ay < 0 || ay >= H) return;
    int cur = blockAt(ax, ay, az);
    if (cur != 0 && cur != B_WATER && cur != B_LAVA) return;
    if (id == I_DOOR) {
        if (ay + 1 >= H) return;
        int up = blockAt(ax, ay + 1, az);
        if (up != 0 && up != B_WATER) return;
        if (!solidAt(ax, ay - 1, az)) return;   // necesita piso firme
        if (px + HW > ax && px - HW < ax + 1 && pz + HW > az && pz - HW < az + 1
                && py + 1.8f > ay && py < ay + 2) return;
        setBlock(ax, ay, az, B_DOOR_LC);
        setBlock(ax, ay + 1, az, B_DOOR_UC);
        consumeSel();
        return;
    }
    if (px + HW > ax && px - HW < ax + 1 && pz + HW > az && pz - HW < az + 1
            && py + 1.8f > ay && py < ay + 1) return;
    if (id == B_SAPLING) {
        int below = blockAt(ax, ay - 1, az);
        if (below != B_GRASS && below != B_DIRT) return;
    }
    if ((id == B_KELP || id == B_SEAGRASS) && cur != B_WATER) return;   // solo bajo el agua
    if (id == I_SEEDS) {
        int below = blockAt(ax, ay - 1, az);
        if (below != B_FARM_DRY && below != B_FARM_WET) return;
    }
    if (id == B_CHEST && chestAlloc(ax, ay, az) < 0) { say("Limite de cofres alcanzado (64)"); return; }
    setBlock(ax, ay, az, id == I_SEEDS ? B_CROP0 : (id == B_FARM_DRY ? (wetNear(ax, ay, az) ? B_FARM_WET : B_FARM_DRY) : id));
    if (id == B_CHEST && blockAt(ax, ay, az) != B_CHEST) chestFree(ax, ay, az);
    if (id == B_SAPLING) addSapling(ax, ay, az);
    consumeSel();
}

void Game::updateSurvival() {
    if (creative) { air = 300; airT = 0; hunger = 20; hungerFx = 0; health = 20; return; }
    if (lavaAtF(px, py + 0.3f, pz) || lavaAtF(px, py + 1.2f, pz)) {
        if (++lavaT >= 8) { lavaT = 0; hurt(3); }
    } else {
        lavaT = 0;
    }
    if (waterAtF(px, py + EYE, pz)) {
        if (air > 0) air--;
        else if (++airT >= 20) { airT = 0; hurt(2); }
    } else {
        airT = 0;
        if (air < 300) { air += 6; if (air > 300) air = 300; }
    }
    if (hungerFx > 0) {
        hungerFx--;
        if (hunger > 0 && rnd(50) == 0) hunger--;
    }
    hungerTimer++;
    if (moving && onGround) hungerTimer += (sprint ? 2 : 1);
    if (hungerTimer >= 900) {
        hungerTimer = 0;
        if (hunger > 0) hunger--;
    }
    if (hunger >= 18 && health < 20) {
        if (++regenTimer >= 80) { regenTimer = 0; health++; hungerTimer += 120; }
    } else {
        regenTimer = 0;
    }
    if (hunger == 0) {
        if (++starveTimer >= 80) { starveTimer = 0; if (health > 1) hurt(1); }
    } else {
        starveTimer = 0;
    }
}

// ---------------------------------------------------------------- horno
int Game::smeltResult(int id) {
    if (isLog(id)) return I_COAL;
    if (id == B_COBBLE) return B_STONE;
    if (id == I_MUTTON) return I_COOKED_MUTTON;
    if (id == I_CHICKEN) return I_COOKED_CHICKEN;
    if (id == I_BEEF) return I_STEAK;
    if (id == B_IRON_ORE) return I_IRON_INGOT;
    if (id == B_GOLD_ORE) return I_GOLD_INGOT;
    if (id == I_COD) return I_COOKED_COD;         // pescado -> cocido
    if (id == I_SALMON) return I_COOKED_SALMON;
    if (id == B_KELP) return I_DRIED_KELP;        // alga -> alga seca
    return 0;
}

int Game::fuelTime(int id) {
    if (id == I_COAL) return 1600;
    if (isLog(id) || isPlanks(id) || id == B_TABLE) return 300;
    if (id == I_STICK || id == B_SAPLING) return 100;
    return 0;
}

void Game::updateFurnace() {
    int res = smeltResult(fId[0]);
    bool can = res != 0 && fCnt[0] > 0 && (fId[2] == 0 || (fId[2] == res && fCnt[2] < 64));
    if (fBurn <= 0 && can && fId[1] != 0 && fuelTime(fId[1]) > 0) {
        fBurn = fuelTime(fId[1]);
        fBurnMax = fBurn;
        fCnt[1]--;
        if (fCnt[1] <= 0) { fId[1] = 0; fCnt[1] = 0; }
    }
    if (fBurn > 0) {
        fBurn--;
        if (can) {
            if (++fProg >= SMELT_T) {
                fProg = 0;
                if (fId[2] == 0) { fId[2] = res; fCnt[2] = 1; } else fCnt[2]++;
                fCnt[0]--;
                if (fCnt[0] <= 0) { fId[0] = 0; fCnt[0] = 0; }
            }
        } else {
            fProg = 0;
        }
    } else {
        fProg = 0;
    }
}

void Game::syncToSlots() {
    for (int i = 0; i < 3; i++) { slId[36 + i] = fId[i]; slCnt[36 + i] = fCnt[i]; slDur[36 + i] = 0; }
}

void Game::syncFromSlots() {
    for (int i = 0; i < 3; i++) { fId[i] = slId[36 + i]; fCnt[i] = slCnt[36 + i]; }
}

void Game::takeFurnaceOut() {
    if (slId[38] == 0) return;
    if (heldId == 0) {
        heldId = slId[38]; heldCnt = slCnt[38]; heldDur = 0;
        clearSlot(38);
    } else if (heldId == slId[38] && heldCnt + slCnt[38] <= 64) {
        heldCnt += slCnt[38];
        clearSlot(38);
    }
}

// ---------------------------------------------------------------- brotes
void Game::addSapling(int x, int y, int z) {
    if (sapN >= 32) return;
    sapX[sapN] = x; sapY[sapN] = y; sapZ[sapN] = z; sapT[sapN] = 5;
    sapN++;
}

void Game::updateSaplings() {
    for (int i = sapN - 1; i >= 0; i--) {
        if (--sapT[i] > 0) continue;
        int x = sapX[i], y = sapY[i], z = sapZ[i];
        sapN--;
        sapX[i] = sapX[sapN]; sapY[i] = sapY[sapN]; sapZ[i] = sapZ[sapN]; sapT[i] = sapT[sapN];
        if (blockAt(x, y, z) == B_SAPLING) growTree(x, y, z);
    }
}

void Game::growTree(int x, int y, int z) {
    int top = y - 1;
    int tv = treeVar(x, z);
    int th = (tv == 1 ? 6 : 4) + rnd(2);
    if (y + th >= H) return;
    for (int dy = 0; dy <= th; dy++) {
        int b = blockAt(x, y + dy, z);
        if (b != 0 && b != B_SAPLING && !isLeaves(b)) return;
    }
    for (int dy = 1; dy <= th; dy++) setBlock(x, top + dy, z, logOf(tv));
    for (int dy = th - 2; dy <= th + 1; dy++) {
        int r = (dy <= th - 1) ? 2 : 1;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (r == 2 && abs(dx) == 2 && abs(dz) == 2 && rnd(2) == 0) continue;
                if (dy == th + 1 && abs(dx) + abs(dz) > 1) continue;
                if (dx == 0 && dz == 0 && dy <= th) continue;
                if (blockAt(x + dx, top + dy, z + dz) == 0) setBlock(x + dx, top + dy, z + dz, leavesOf(tv));
            }
        }
    }
    int g = 0;
    while (g++ < H && boxCollides(px, py, pz, HW, 1.8f)) py += 1.f;
}

// ---------------------------------------------------------------- dia / noche
int Game::dayBright() {
    int tt = worldTime % DAY_LEN;
    if (tt < 12000) return 100;
    if (tt < 13800) return 100 - (tt - 12000) * 65 / 1800;
    if (tt < 22200) return 35;
    return 35 + (tt - 22200) * 65 / 1800;
}

int Game::skyColor() {
    if (dim == 1) return 0x2A0808;
    int p = (bright - 35) * 100 / 65;
    if (p < 0) p = 0;
    if (p > 100) p = 100;
    return mix(0x87CEEB, 0x05081C, p);
}

void Game::scanTorches() {
    torchN = 0;
    int bx = ifloor(px), bz = ifloor(pz);
    for (int x = bx - 14; x <= bx + 14 && torchN < 16; x++) {
        for (int z = bz - 14; z <= bz + 14 && torchN < 16; z++) {
            uint8_t* cd = chunkFor(x, z);
            if (!cd) continue;
            int base = (((x & 15) << 4) + (z & 15)) * H;
            for (int y = 0; y < H && torchN < 16; y++) {
                if (cd[base + y] == B_TORCH || cd[base + y] == B_GLOWSTONE) {
                    torchX[torchN] = x + 0.5f; torchY[torchN] = y + 0.5f; torchZ[torchN] = z + 0.5f;
                    torchN++;
                }
            }
        }
    }
}

int Game::litPct(float x, float y, float z) {
    int p = bright;
    if (p >= 100) return 100;
    for (int i = 0; i < torchN; i++) {
        float dx = x - torchX[i], dy = y - torchY[i], dz = z - torchZ[i];
        float d2 = dx * dx + dy * dy + dz * dz;
        if (d2 < 64.f) {
            int tp = 100 - (int)(d2 * 1.5f);
            if (tp > p) p = tp;
        }
    }
    return p;
}

// ---------------------------------------------------------------- mobs: datos
float Game::mobHW(int t) { if (t == 11) return 0.6f; if (t >= 7 && t <= 9) return 0.15f; if (t == 10) return 0.5f; if (t == 5) return 0.49f; return t == 0 ? 0.35f : (t == 1 ? 0.2f : (t == 2 ? 0.4f : 0.3f)); }
float Game::mobH(int t) { if (t == 11) return 0.45f; if (t >= 7 && t <= 9) return 0.3f; if (t == 10) return 0.7f; if (t == 5) return 1.0f; return t == 0 ? 1.0f : (t == 1 ? 0.7f : (t == 2 ? 1.25f : ((t == 3 || t == 6) ? 1.8f : 1.6f))); }
float Game::mobR(int t) { if (t == 11) return 0.8f; if (t == 5) return 0.f; return t == 0 ? 0.5f : (t == 1 ? 0.3f : (t == 2 ? 0.55f : (t == 3 ? 0.45f : 0.4f))); }
float Game::mobSpeed(int t) { if (t >= 7 && t <= 9) return 0.04f; if (t == 10) return 0.08f; return t == 3 ? 0.07f : (t == 4 ? 0.06f : 0.035f); }
int Game::mobHp(int t) { if (t == 11) return 1000; if (t >= 7 && t <= 9) return 3; if (t == 10) return 8; return t == 0 ? 8 : (t == 1 ? 4 : (t == 2 ? 10 : (t == 3 ? 12 : 10))); }

// ---------------------------------------------------------------- nether y portales
// vacia la ventana de chunks (lo modificado se escribe con la dimension vieja) y cambia de dimension
void Game::switchDim(int nd) {
    for (int sl = 0; sl < nch; sl++) unloadSlot(sl);
    clearMobs();
    sapN = 0; torchN = 0;
    dim = nd;
    portalT = 0;
}

bool Game::lavaAtF(float x, float y, float z) { return blockAt(ifloor(x), ifloor(y), ifloor(z)) == B_LAVA; }
bool Game::waterAtF(float x, float y, float z) { return wtr(blockAt(ifloor(x), ifloor(y), ifloor(z))); }   // las algas estan bajo el agua

int Game::pb(int axis, int u, int y, int f) { return axis == 0 ? blockAt(u, y, f) : blockAt(f, y, u); }

// intenta encender un marco de obsidiana (interior 2-4 de ancho x 3-5 de alto)
bool Game::lightPortal(int ax0, int ay0, int az0) {
    for (int axis = 0; axis < 2; axis++) {
        int u = (axis == 0) ? ax0 : az0, f = (axis == 0) ? az0 : ax0, y = ay0;
        if (pb(axis, u, y, f) != 0) continue;
        while (y > 1 && pb(axis, u, y - 1, f) == 0) y--;
        if (pb(axis, u, y - 1, f) != B_OBSIDIAN) continue;
        for (int i = 0; i < 4 && pb(axis, u - 1, y, f) == 0; i++) u--;
        int w = 0;
        while (w < 5 && pb(axis, u + w, y, f) == 0) w++;
        int h = 0;
        while (h < 6 && pb(axis, u, y + h, f) == 0) h++;
        if (w < 2 || w > 4 || h < 3 || h > 5) continue;
        bool ok = true;
        for (int i = 0; i < w && ok; i++) {
            if (pb(axis, u + i, y - 1, f) != B_OBSIDIAN || pb(axis, u + i, y + h, f) != B_OBSIDIAN) ok = false;
            for (int j = 0; j < h && ok; j++) if (pb(axis, u + i, y + j, f) != 0) ok = false;
        }
        for (int j = 0; j < h && ok; j++) {
            if (pb(axis, u - 1, y + j, f) != B_OBSIDIAN || pb(axis, u + w, y + j, f) != B_OBSIDIAN) ok = false;
        }
        if (!ok) continue;
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                if (axis == 0) setBlock(u + i, y + j, f, B_PORTAL);
                else setBlock(f, y + j, u + i, B_PORTAL);
            }
        }
        return true;
    }
    return false;
}

void Game::buildPortal(int x, int y, int z) {
    for (int ix = x - 1; ix <= x + 2; ix++) {
        for (int iz = z - 1; iz <= z + 1; iz++) {
            setBlock(ix, y - 1, iz, B_OBSIDIAN);
            for (int iy = y; iy <= y + 3; iy++) setBlock(ix, iy, iz, 0);
        }
    }
    for (int iy = y; iy <= y + 2; iy++) {
        setBlock(x - 1, iy, z, B_OBSIDIAN);
        setBlock(x + 2, iy, z, B_OBSIDIAN);
        setBlock(x, iy, z, B_PORTAL);
        setBlock(x + 1, iy, z, B_PORTAL);
    }
    setBlock(x, y + 3, z, B_OBSIDIAN);
    setBlock(x + 1, y + 3, z, B_OBSIDIAN);
}

// cruza el portal: coordenadas 1:8, busca un portal cercano o construye uno nuevo
void Game::usePortal() {
    float nx = (dim == 0) ? px / 8.f : px * 8.f;
    float nz = (dim == 0) ? pz / 8.f : pz * 8.f;
    switchDim(1 - dim);
    px = nx; pz = nz;
    updateWindow(true);
    loadMissing(nch);
    int cx0 = ifloor(nx), cz0 = ifloor(nz);
    int fx_ = 0, fy_ = -1, fz_ = 0;
    for (int x = cx0 - 10; x <= cx0 + 10 && fy_ < 0; x++) {
        for (int z = cz0 - 10; z <= cz0 + 10 && fy_ < 0; z++) {
            if (!chunkFor(x, z)) continue;
            for (int y = 1; y < H - 1; y++) {
                if (blockAt(x, y, z) == B_PORTAL && blockAt(x, y - 1, z) == B_OBSIDIAN) { fx_ = x; fy_ = y; fz_ = z; break; }
            }
        }
    }
    if (fy_ >= 0) {
        px = fx_ + 0.5f; pz = fz_ + 0.5f; py = fy_ + 0.01f;
    } else {
        int y;
        if (dim == 1) {
            y = 12;
            for (int yy = 24; yy > 3; yy--) {
                if (blockAt(cx0, yy, cz0) == 0 && blockAt(cx0, yy + 1, cz0) == 0 && solidAt(cx0, yy - 1, cz0)) { y = yy; break; }
            }
        } else {
            y = topY(cx0, cz0) + 1;
            if (y < 2) y = 2;
            if (y > H - 7) y = H - 7;
        }
        buildPortal(cx0, y, cz0);
        px = cx0 + 1.0f; pz = cz0 + 0.5f; py = y + 0.01f;
    }
    vy = 0; fallDist = 0; onGround = false;
    portalLock = true; portalT = 0;
    needSave = true;
    say(dim == 1 ? "Entraste al Nether" : "Volviste al Overworld");
}

void Game::updatePortal() {
    bool inP = blockAt(ifloor(px), ifloor(py + 0.5f), ifloor(pz)) == B_PORTAL
            || blockAt(ifloor(px), ifloor(py + 1.4f), ifloor(pz)) == B_PORTAL;
    if (!inP) { portalT = 0; portalLock = false; return; }
    if (portalLock) return;
    if (++portalT >= (creative ? 2 : 60)) { portalT = 0; usePortal(); }
}

// ---------------------------------------------------------------- puertas y cama
bool Game::doorAlongX(int x, int ly, int z) {
    bool wx = opaque(blockAt(x - 1, ly, z)) || opaque(blockAt(x + 1, ly, z));
    bool wz = opaque(blockAt(x, ly, z - 1)) || opaque(blockAt(x, ly, z + 1));
    if (wz && !wx) return false;
    return true;
}

void Game::useBed(int x, int y, int z) {
    if (dim != 0) { say("No se puede dormir aqui"); return; }
    bedOn = true; bedX = x; bedY = y; bedZ = z;
    needSave = true;
    if ((worldTime % DAY_LEN) >= 13000) {
        worldTime = (worldTime / DAY_LEN + 1) * DAY_LEN + 1000;
        bright = dayBright();
        say("Dormiste hasta la manana");
    } else {
        say("Punto de reaparicion guardado");
    }
}

void Game::toggleDoor(int x, int y, int z) {
    int b = blockAt(x, y, z);
    int ly = (b == B_DOOR_UC || b == B_DOOR_UO) ? y - 1 : y;
    int lb = blockAt(x, ly, z);
    if (!isDoor(lb)) { setBlock(x, y, z, 0); return; }   // mitad huerfana
    bool open = (lb == B_DOOR_LO);
    if (open && px + HW > x && px - HW < x + 1 && pz + HW > z && pz - HW < z + 1
            && py + 1.8f > ly && py < ly + 2) return;   // no cerrar con el jugador adentro
    setBlock(x, ly, z, open ? B_DOOR_LC : B_DOOR_LO);
    if (isDoor(blockAt(x, ly + 1, z))) setBlock(x, ly + 1, z, open ? B_DOOR_UC : B_DOOR_UO);
}

bool Game::rayInDoor(int hb, int x, int y, int z, float rx, float rz) {
    int ly = (hb == B_DOOR_UO) ? y - 1 : y;
    bool spanX = !doorAlongX(x, ly, z);
    if (spanX) return (rz - z) < 0.14f;
    return (rx - x) < 0.14f;
}

// true si no hay bloques solidos entre dos puntos
bool Game::losClear(float x0, float y0, float z0, float x1, float y1, float z1) {
    float ddx = x1 - x0, ddy = y1 - y0, ddz = z1 - z0;
    float len = sqrtf(ddx * ddx + ddy * ddy + ddz * ddz);
    int n = (int)(len * 3.f) + 2;
    for (int i = 1; i < n; i++) {
        float t = (float)i / (float)n;
        if (solidAt(ifloor(x0 + ddx * t), ifloor(y0 + ddy * t), ifloor(z0 + ddz * t))) return false;
    }
    return true;
}

// true si el mob hostil persigue al jugador (fija ang y moving)
bool Game::hostileAI(Mob* m) {
    if (m->atk > 0) m->atk--;
    float dx = px - m->x, dz = pz - m->z;
    float d2 = dx * dx + dz * dz;
    float dy = py - m->y;
    float lim = (m->type == 3 || m->type == 10) ? 256.f : 144.f;
    if (d2 > lim) { m->fuse = 0; m->wind = 0; return false; }
    m->ang = atan2f(dx, dz);
    m->moving = true;
    if (m->type == 3 || m->type == 10) {
        bool near_ = d2 < 1.7f && dy < 1.2f && dy > -1.2f
                && losClear(m->x, m->y + 1.0f, m->z, px, py + 1.0f, pz);
        if (near_) {
            m->moving = false;
            if (++m->wind >= 12 && m->atk <= 0) { hurt(m->type == 10 ? 2 : 3); m->atk = 40; m->wind = 0; }
        } else if (m->wind > 0) {
            m->wind--;
        }
    } else {
        bool near_ = d2 < 4.f && dy < 1.6f && dy > -1.6f
                && losClear(m->x, m->y + 0.8f, m->z, px, py + 0.9f, pz);
        if (near_) {
            m->moving = false;
            if (++m->fuse >= 30) explode(m);
        } else if (m->fuse > 0) {
            m->fuse--;
        }
    }
    return true;
}

void Game::explode(Mob* m) {
    bool big = (m->type == 5);
    int rr = big ? 4 : 3;
    int lim = big ? 17 : 10;
    int cx = ifloor(m->x), cy = ifloor(m->y + (big ? 0.5f : 0.8f)), cz = ifloor(m->z);
    for (int dx = -rr; dx <= rr; dx++) {
        for (int dy = -rr; dy <= rr; dy++) {
            for (int dz = -rr; dz <= rr; dz++) {
                if (dx * dx + dy * dy + dz * dz > lim) continue;
                int yy = cy + dy;
                if (yy <= 0) continue;
                int b = blockAt(cx + dx, yy, cz + dz);
                if (b == 0 || b == B_WATER || b == B_LAVA || b == B_PORTAL || b == B_BEDROCK || b == B_OBSIDIAN) continue;
                if (b == B_TNT) igniteTnt(cx + dx, yy, cz + dz, 50 + rnd(20));
                else setBlock(cx + dx, yy, cz + dz, 0);
            }
        }
    }
    float ddx = px - m->x, ddy = (py + 0.9f) - (m->y + 0.8f), ddz = pz - m->z;
    float d = sqrtf(ddx * ddx + ddy * ddy + ddz * ddz);
    float reach = big ? 8.f : 6.f;
    if (d < reach && losClear(m->x, m->y + 0.8f, m->z, px, py + 0.9f, pz)) hurt((int)((reach - d) * (big ? 2.2f : 1.8f)));
    if (big) {
        for (size_t i = 0; i < mobs.size(); i++) {
            Mob* o = mobs[i];
            if (o == m || o->type == 5) continue;
            float ex = o->x - m->x, ey = o->y - m->y, ez = o->z - m->z;
            float dd = sqrtf(ex * ex + ey * ey + ez * ez);
            if (dd < 6.f) { o->hp -= (int)((6.f - dd) * 2.f) + 1; o->hurt = 8; }
        }
    }
    m->hp = -999;
}

void Game::igniteTnt(int x, int y, int z, int startFuse) {
    setBlock(x, y, z, 0);
    Mob* m = new Mob();
    m->type = 5;
    m->x = x + 0.5f; m->y = y + 0.001f; m->z = z + 0.5f;
    m->hp = 1;
    m->fuse = startFuse;
    mobs.push_back(m);
}

bool Game::touchingCactus() {
    int x0 = ifloor(px - HW - 0.12f), x1 = ifloor(px + HW + 0.12f);
    int z0 = ifloor(pz - HW - 0.12f), z1 = ifloor(pz + HW + 0.12f);
    int y0 = ifloor(py), y1 = ifloor(py + 1.6f);
    for (int xx = x0; xx <= x1; xx++)
        for (int yy = y0; yy <= y1; yy++)
            for (int zz = z0; zz <= z1; zz++)
                if (blockAt(xx, yy, zz) == B_CACTUS) return true;
    return false;
}

// ---------------------------------------------------------------- barco, peces y pesca (update acuatico)
// -1 = corriente descendente (magma al fondo de la columna de agua), +1 = ascendente (arena de almas), 0 = nada
int Game::bubbleAt(int x, int y, int z) {
    if (!wtr(blockAt(x, y, z))) return 0;
    for (int i = 0; i < 24 && y > 0; i++) {
        y--;
        int b = blockAt(x, y, z);
        if (b == B_MAGMA) return -1;
        if (b == B_SOULSAND) return 1;
        if (!wtr(b)) return 0;
    }
    return 0;
}

int Game::hostType() { int r = rnd(3); return r == 0 ? 3 : (r == 1 ? 4 : 10); }

bool Game::mobAlive(Mob* m) {
    for (size_t i = 0; i < mobs.size(); i++) if (mobs[i] == m) return true;
    return false;
}

bool Game::boatOk(float x, float z, int by) {
    int ix = ifloor(x), iz = ifloor(z);
    return wtr(blockAt(ix, by, iz)) && !opaque(blockAt(ix, by + 1, iz));
}

void Game::floatBoat(Mob* m, int bx, int by, int bz) {
    int cy = by, n = 0;
    while (n < 20 && wtr(blockAt(bx, cy + 1, bz))) { cy++; n++; }
    float t = cy + 0.9f;
    if (m->y < t) { m->y += 0.08f; if (m->y > t) m->y = t; }
    else if (m->y > t) { m->y -= 0.08f; if (m->y < t) m->y = t; }
}

void Game::sinkBoat(Mob* m, int bx, int bz) {
    float ny = m->y - 0.12f;
    if (wtr(blockAt(bx, ifloor(ny), bz))) m->y = ny;
    else m->y = ifloor(ny) + 1.f;   // apoyado sobre el bloque del fondo
}

// barco sin jinete: flota en la superficie, se hunde en una corriente descendente, cae si no hay agua
void Game::updateBoat(Mob* m) {
    if (m->hurt > 0) m->hurt--;
    if (m == ride) return;
    int bx = ifloor(m->x), bz = ifloor(m->z), by = ifloor(m->y + 0.05f);
    m->sp = 0;
    if (wtr(blockAt(bx, by, bz))) {
        m->vy = 0;
        if (bubbleAt(bx, by, bz) < 0) { m->sunk = true; sinkBoat(m, bx, bz); }
        else { m->sunk = false; floatBoat(m, bx, by, bz); }
    } else {
        m->sunk = false;
        m->vy = (m->vy - GRAV) * 0.98f;
        float ny = m->y + m->vy;
        if (boxCollides(m->x, ny, m->z, 0.6f, 0.45f)) { if (m->vy < 0) m->y = ifloor(ny) + 1.001f; m->vy = 0; }
        else m->y = ny;
    }
}

// el jugador maneja el barco como su propio cuerpo: no sale del agua, mas rapido, flota siempre
void Game::rideBoat(bool fw, bool bk) {
    Mob* m = ride;
    float sn = sinf(yaw), cs = cosf(yaw);
    float tg = fw ? 0.26f : (bk ? -0.1f : 0.f);
    m->sp += (tg - m->sp) * 0.2f;
    m->ang = yaw;
    int by = ifloor(m->y + 0.05f);
    float mx = sn * m->sp, mz = cs * m->sp;
    float k = m->sp >= 0.f ? 0.7f : -0.7f, qx = sn * k, qz = cs * k;
    if (boatOk(m->x + mx + qx, m->z + mz + qz, by)) { m->x += mx; m->z += mz; }
    else if (boatOk(m->x + mx + qx, m->z + qz, by)) m->x += mx;
    else if (boatOk(m->x + qx, m->z + mz + qz, by)) m->z += mz;
    else m->sp = 0.f;
    int bx = ifloor(m->x), bz = ifloor(m->z);
    by = ifloor(m->y + 0.05f);
    if (bubbleAt(bx, by, bz) < 0) {   // corriente descendente: el barco se hunde y el jugador se baja
        ride = 0;
        m->sunk = true;
        m->sp = 0.f;
        px = m->x; pz = m->z; py = m->y + 0.3f; vy = 0.f;
        say("El barco se hundio!");
        return;
    }
    floatBoat(m, bx, by, bz);
    px = m->x; pz = m->z; py = m->y + 0.14f;
    vy = 0.f; fallDist = 0.f; onGround = false;
    moving = fw || bk;
}

void Game::mount(Mob* m) {
    ride = m;
    m->sunk = false; m->sp = 0.f; m->ang = yaw;
    px = m->x; pz = m->z; py = m->y + 0.14f;
    vy = 0.f; fallDist = 0.f; flying = false; sprint = false;
    say("Montaste el barco");
}

void Game::dismount() {
    Mob* m = ride;
    ride = 0;
    if (!m || !mobAlive(m)) return;
    for (int i = 0; i < 8; i++) {
        float a = i * 0.7854f;
        float nx = m->x + sinf(a) * 1.4f, nz = m->z + cosf(a) * 1.4f;
        for (int k = 0; k <= 1; k++) {
            int fy = ifloor(m->y) + k;
            if (opaque(blockAt(ifloor(nx), fy, ifloor(nz))) && !boxCollides(nx, fy + 1.001f, nz, HW, 1.8f)) {
                px = nx; pz = nz; py = fy + 1.001f; vy = 0.f; fallDist = 0.f;
                return;
            }
        }
    }
    py = m->y + 0.3f;   // sin orilla cerca: queda nadando
}

void Game::placeBoat() {
    float x, y, z;
    if (blockAt(tx, ty, tz) == B_WATER) {
        int cy = ty, n = 0;
        while (n < 20 && wtr(blockAt(tx, cy + 1, tz))) { cy++; n++; }
        x = tx + 0.5f; z = tz + 0.5f; y = cy + 0.9f;
    } else {
        if (ax < 0 || blockAt(ax, ay, az) != 0) return;
        x = ax + 0.5f; z = az + 0.5f; y = ay + 0.001f;
    }
    Mob* nm = new Mob();
    nm->type = 11; nm->x = x; nm->y = y; nm->z = z; nm->ang = yaw; nm->hp = 1000;
    mobs.push_back(nm);
    consumeSel();
}

// peces: nadan sin gravedad dentro del agua y rebotan contra la orilla/fondo/superficie
void Game::updateFish(Mob* m) {
    if (m->hurt > 0) m->hurt--;
    if (--m->timer <= 0) { m->ang = rndf() * 6.2832f; m->vy = (rndf() - 0.5f) * 0.03f; m->timer = 30 + rnd(60); }
    float nx = m->x + sinf(m->ang) * 0.04f, nz = m->z + cosf(m->ang) * 0.04f, ny = m->y + m->vy;
    if (wtr(blockAt(ifloor(nx), ifloor(ny + 0.1f), ifloor(nz))) && wtr(blockAt(ifloor(nx), ifloor(ny + 0.45f), ifloor(nz)))) {
        m->x = nx; m->y = ny; m->z = nz;
    } else { m->ang += 2.5f; m->vy = -m->vy; m->timer = 10; }
}

// cana de pescar: clic lanza al agua; tras X ticks pica (30 ticks de ventana); clic al picar = 70% de pescar
void Game::useRod() {
    if (fishState == 0) {
        float sy = sinf(yaw), cy = cosf(yaw), sp = sinf(pitch), cp = cosf(pitch);
        for (int i = 2; i <= 16; i++) {
            float d = i * 0.5f;
            int x = ifloor(px + sy * cp * d), y = ifloor(py + EYE + sp * d), z = ifloor(pz + cy * cp * d);
            int b = blockAt(x, y, z);
            if (b == B_WATER) { fishX = x; fishY = y; fishZ = z; fishState = 1; fishT = 60 + rnd(140); say("Sedal lanzado"); return; }
            if (b != 0 && b != B_KELP && b != B_SEAGRASS) break;
        }
        say("Apunta al agua");
        return;
    }
    if (fishState == 2 && rnd(100) < 70) {
        int r = rnd(100);
        addItem(r < 50 ? I_COD : (r < 80 ? I_SALMON : (r < 95 ? I_TROPICAL : I_STRING)), 1, 0);
        say("Pescaste algo!");
    } else say(fishState == 2 ? "Se escapo" : "Nada todavia");
    fishState = 0;
}

void Game::updateFishing() {
    if (fishState == 0) return;
    if (slId[sel] != I_ROD || blockAt(fishX, fishY, fishZ) != B_WATER
            || fabsf(fishX + 0.5f - px) > 20.f || fabsf(fishZ + 0.5f - pz) > 20.f) { fishState = 0; return; }
    if (--fishT <= 0) {
        if (fishState == 1) { fishState = 2; fishT = 30; say("Pica! Clic ya"); }
        else { fishState = 0; say("Se escapo"); }
    }
}

void Game::touchPress(int x, int y) {
    if (!busy.empty()) return;
    tpDown = true;
    tpDrag = false;
    tpX = x;
    tpY = y;
    tpScroll0 = wScroll;
    touch = true;
    if (state == ST_PLAY) tcPress(x, y);
    else if (state == ST_INV) tcInvPress(x, y);
    else if (state == ST_DEAD) respawn();
}

void Game::touchDrag(int x, int y) {
    if (!busy.empty()) return;
    if (state == ST_PLAY) { tcDrag(x, y); return; }
    if (state == ST_INV) { tcInvDrag(x, y); return; }
    if (!tpDown) return;
    if (abs(y - tpY) > 8) tpDrag = true;
    if (tpDrag && state == ST_WORLDS) {
        int step = btnH + 3;
        if (step < 4) step = 4;
        wScroll = tpScroll0 + (tpY - y) / step;
    }
}

void Game::touchRelease(int x, int y) {
    (void)x; (void)y;
    if (!busy.empty()) {
        tFw = tBk = tL = tR = tJp = false;
        kMine = false; sqArm = false;
        tcMode = 0; tcInvDown = false; tpDown = false;
        return;
    }
    if (state == ST_PLAY) { tcRelease(); tpDown = false; return; }
    if (state == ST_INV) { tcInvRelease(); tpDown = false; return; }
    if (!tpDown) return;
    tpDown = false;
    if (tpDrag || !isMenu()) return;
    for (int i = 0; i < htN; i++) {
        if (hit(tpX, tpY, htX[i], htY[i], htW[i], htH[i])) {
            for (int j = 0; j < navN; j++) if (navIds[j] == htId[i]) navSel = j;
            activate(htId[i]);
            return;
        }
    }
}

void Game::addHit(int x, int y, int w, int h, int id) {
    if (htN >= 64) return;
    htX[htN] = x; htY[htN] = y; htW[htN] = w; htH[htN] = h; htId[htN] = id;
    htN++;
}

void Game::layoutTouch() {
    int m = (sw < sh ? sw : sh) / 8;
    if (m < 22) m = 22;
    if (m > 40) m = 40;
    tcB = m;
    tcHs = sw / 11 < 30 ? sw / 11 : 30;
    tcHx = (sw - 10 * tcHs) / 2;
    tcHy = sh - tcHs - 3;
    tcIX = tcHx + 9 * tcHs; tcIY = tcHy; tcIS = tcHs;
    int bot = tcHy - 28;
    tcDX = 6 + tcB * 3 / 2; tcDY = bot - tcB * 3 / 2;
    tcJS = tcB * 3 / 2; tcJX = sw - 6 - tcJS; tcJY = bot - tcJS;
    tcSb = tcB * 3 / 4; tcPX = sw / 2 - tcSb - 3; tcGX = sw / 2 + 3; tcPY = 3;
}

bool Game::tcDpad(int x, int y) {
    int dx = x - tcDX, dy = y - tcDY, lim = tcB * 3 / 2 + tcB / 3, t = tcB / 2;
    bool in = dx >= -lim && dx <= lim && dy >= -lim && dy <= lim;
    bool wasFw = tFw;
    tFw = in && dy < -t;
    if (tFw && !wasFw) { if (tick - fwTick < 8) sprint = true; fwTick = tick; }
    tBk = in && dy > t; tL = in && dx < -t; tR = in && dx > t;
    return in;
}

void Game::tcPress(int x, int y) {
    layoutTouch();
    tcMode = 0;
    if (hit(x, y, tcPX - 3, 0, tcSb + 6, tcSb + 8)) { tpDown = false; openPause(); return; }
    if (hit(x, y, tcGX - 3, 0, tcSb + 6, tcSb + 8)) { dbg = !dbg; tcMode = 4; return; }
    if (x >= tcIX && y >= tcIY - tcIS / 2) { tpDown = false; openInv(2); return; }
    if (y >= tcHy - 2 && x >= tcHx && x < tcHx + 9 * tcHs) { sel = (x - tcHx) / tcHs; labelT = 40; tcMode = 4; return; }
    if (tcDpad(x, y)) { tcMode = 1; return; }
    if (hit(x, y, tcJX - 6, tcJY - 6, tcJS + 12, tcJS + 12)) {
        tcMode = 2; tJp = true;
        if (creative) {
            if (tick - jumpTick < 8) { flying = !flying; vy = 0; fallDist = 0; jumpTick = -100; }
            else jumpTick = tick;
        }
        return;
    }
    tcMode = 3; tcLX = x; tcLY = y; tcLook = false;
    kMine = true; sqArm = true; sqDone = false; sqT0 = tick; mineProg = 0;
}

void Game::tcDrag(int x, int y) {
    if (tcMode == 1) { tcDpad(x, y); return; }
    if (tcMode != 3) return;
    if (!tcLook) {
        if (tick - sqT0 > 6) return;
        int ddx = x - tcLX, ddy = y - tcLY;
        if (ddx * ddx + ddy * ddy < 100) return;
        tcLook = true;
        kMine = false; sqArm = false; mineProg = 0; mineX = -1;
    }
    yaw += (x - tcLX) * 0.012f;
    pitch -= (y - tcLY) * 0.012f;
    if (pitch > 1.4f) pitch = 1.4f;
    if (pitch < -1.4f) pitch = -1.4f;
    tcLX = x; tcLY = y;
}

void Game::tcCancel() {
    tFw = tBk = tL = tR = tJp = false;
    if (tcMode == 3) { kMine = false; sqArm = false; mineProg = 0; mineX = -1; }
    tcMode = 0;
}

void Game::tcRelease() {
    if (tcMode == 1) { tFw = tBk = tL = tR = false; }
    else if (tcMode == 2) tJp = false;
    else if (tcMode == 3) {
        if (!tcLook && sqArm && !sqDone && tick - sqT0 <= 8) doTap();
        kMine = false; sqArm = false; mineProg = 0; mineX = -1;
    }
    tcMode = 0;
}

int Game::slotAt(int x, int y) {
    for (int j = 0; j < NSLOT; j++) {
        if (slActive[j] && x >= slx[j] && x < slx[j] + slS && y >= sly[j] && y < sly[j] + slS) return j;
    }
    return -1;
}

bool Game::dropOk(int j) {
    if (j < 0 || j == OUT || (craftN == FURN && j == 38)) return false;
    if (palMode() && j >= 9 && j < 36) return false;
    return true;
}

void Game::tcInvPress(int x, int y) {
    layoutTouch();
    tcInvDown = false; tcDropped = false; tcSlot = -1; tcPressSlot = -1;
    if (x >= tcIX && y >= tcIY - tcIS / 2) { tpDown = false; closeInv(); return; }
    if (palMode() && y < 16) { palFlip(x < sw / 2 ? -1 : 1); return; }
    if (palMode() && y >= 12 && y < sly[0] && (x < slx[9] || x >= slx[9] + 9 * slS)) {
        palFlip(x < sw / 2 ? -1 : 1);
        palPress = x < sw / 2 ? 1 : 2;
        return;
    }
    if (craftN == TRADE) {
        int r = slS * 3 / 4, w = 5 * slS, vis = (sly[9] - 22) / r;
        if (vis < 1) vis = 1;
        int first = tSel - vis + 1;
        if (first < 0) first = 0;
        if (hit(x, y, slx[9], 14, w, vis * r)) {
            int t = first + (y - 14) / r;
            if (t < 8) { tSel = t; updateOutput(); }
            return;
        }
    }
    int j = slotAt(x, y);
    if (j < 0) return;
    tcSlot = j; tcPressSlot = j; cursor = j; tcInvDown = true;
    tcNext = tick + 20;
}

void Game::tcInvDrag(int x, int y) {
    if (!tcInvDown) return;
    int j = slotAt(x, y);
    if (j != tcSlot) { tcSlot = j; if (j >= 0) cursor = j; }
}

void Game::tcInvTick() {
    if (!tcInvDown || heldId == 0 || !dropOk(tcSlot)) { if (tcInvDown) tcNext = tick + 20; return; }
    if (tick >= tcNext) {
        tcNext = tick + 20;
        clickSecondary(tcSlot);
        tcDropped = true;
    }
}

void Game::tcInvRelease() {
    palPress = 0;
    if (tcInvDown && !tcDropped && tcPressSlot >= 0 && tcSlot == tcPressSlot) click(tcPressSlot);
    tcInvDown = false;
}
