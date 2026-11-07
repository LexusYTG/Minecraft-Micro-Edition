#include "game.h"

// ---------------------------------------------------------------- navegacion de menus
void Game::buildNav() {
    if (state == ST_MENU) { navIds[0] = A_PLAY; navIds[1] = A_QUIT; navN = 2; }
    else if (state == ST_PAUSE) { navIds[0] = A_RESUME; navIds[1] = A_SETTINGS; navIds[2] = A_TOMENU; navN = 3; }
    else if (state == ST_SET) { navIds[0] = A_RD; navIds[1] = A_SETBACK; navN = 2; }
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
        kBk = (b & PAD_DOWN) != 0 || ly > 0.35f;
        kSl = (b & PAD_LEFT) != 0 || lx < -0.35f;
        kSr = (b & PAD_RIGHT) != 0 || lx > 0.35f;
        kTurn = pad.cx;                                // C izq/der = girar
        kLook = -pad.cy;                               // C arriba = mirar arriba
        bool bHeld = (b & PAD_CIRCLE) != 0;
        kJump = (b & PAD_CROSS) != 0;
        kMine = ((b & PAD_R) != 0) && !bHeld;
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
    int s = (sw - 8) / 9;
    if ((sh - 40) / 7 < s) s = (sh - 40) / 7;
    if (s > 30) s = 30;
    if (s < 10) s = 10;
    slS = s;
    int x0 = (sw - 9 * s) / 2;
    int y0 = 14;
    int ym = y0 + 3 * s + 8;
    int yh = ym + 3 * s + 4;
    for (int i = 0; i < 46; i++) slActive[i] = false;
    for (int i = 0; i < 9; i++) { slx[i] = x0 + i * s; sly[i] = yh; slActive[i] = true; }
    for (int i = 9; i < 36; i++) {
        int r = (i - 9) / 9, c = (i - 9) % 9;
        slx[i] = x0 + c * s; sly[i] = ym + r * s; slActive[i] = true;
    }
    int n = (craftN == FURN) ? 0 : craftN;
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
}

void Game::moveCursor(int k) {
    int c = cursor, best = -1, bs = 0x7fffffff;
    for (int j = 0; j < 46; j++) {
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
    if (craftN == FURN) return -1;
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
                int have = slId[36 + (minr + r) * n + minc + c];
                if (have != rPat[k][r * bw + c]) ok = false;
                if (have != rPat[k][r * bw + (bw - 1 - c)]) okm = false;
            }
        }
        if (ok || okm) return k;
    }
    return -1;
}

void Game::updateOutput() {
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
    if (state == ST_INV && craftN == FURN) { syncFromSlots(); updateFurnace(); syncToSlots(); }
    if (state != ST_PLAY) return;
    worldTime++;
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
    bool fw = kFw, bk = kBk, jp = kJump;
    yaw += kTurn * LOOK;
    pitch += kLook * LOOK;
    if (pitch > 1.4f) pitch = 1.4f;
    if (pitch < -1.4f) pitch = -1.4f;

    float sn = sinf(yaw), cs = cosf(yaw);
    if (creative && flying) {
        float sp2 = SPD * 1.6f;
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
    if (inW) { mx *= 0.5f; mz *= 0.5f; }

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
    for (int i = (int)mobs.size() - 1; i >= 0; i--) {
        if (i >= (int)mobs.size()) continue;
        Mob* m = mobs[i];
        updateMob(m);
        if (m->type == 5 && ++m->fuse >= 80) explode(m);
        if ((m->type == 3 || m->type == 4) && bright >= 90 && m->hp > 0 && (tick % 20) == 0) { m->hp--; m->hurt = 6; }
        if (m->hp <= 0) {
            if (tMob == m) tMob = 0;
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
        if (countMobs(true) < 6) spawnMob(dim == 1 ? 3 : 3 + rnd(2), true);
    }
}

int Game::countMobs(bool hostile) {
    int n = 0;
    for (size_t i = 0; i < mobs.size(); i++) {
        Mob* m = mobs[i];
        if (m->type == 5) continue;
        if ((m->type >= 3) == hostile) n++;
    }
    return n;
}

void Game::updateMob(Mob* m) {
    float hw = mobHW(m->type);
    float mh = mobH(m->type);
    if (m->hurt > 0) m->hurt--;
    if (m->flee > 0) m->flee--;
    bool chase = (m->type == 3 || m->type == 4) && m->flee <= 0 && hostileAI(m);
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
    int qx = lx, qy = ly, qz = lz;
    while (d < REACH) {
        int ix = ifloor(ex + dx * d), iy = ifloor(ey + dy * d), iz = ifloor(ez + dz * d);
        if (ix != lx || iy != ly || iz != lz) {
            int hb = blockAt(ix, iy, iz);
            if (hb != 0 && hb != B_WATER && hb != B_LAVA && hb != B_PORTAL && hb != B_DOOR_LO && hb != B_DOOR_UO) {
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
        if (m->hp <= 0 || m->type == 5) continue;
        float r = mobR(m->type);
        float h = mobH(m->type);
        if (blockAt(ifloor(m->x), ifloor(m->y + h * 0.5f), ifloor(m->z)) != 0) continue;
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
            || (b >= B_COAL_ORE && b <= B_DIAM_BLK) || b == B_NETHERRACK || b == B_OBSIDIAN) {
        right = (tt == 0);
        if (!right || toolLevel(tool) < needLevel(b)) { right = false; secs = hard[b] * 5.f; }
    } else if (b == B_LOG || b == B_PLANKS || b == B_TABLE || isDoor(b)) {
        right = (tt == 1);
    } else if (b == B_GRASS || b == B_DIRT || b == B_SAND || b == B_SNOW || b == B_GRAVEL || b == B_SOULSAND) {
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
    if (creative) {   // sin botin ni desgaste de herramienta
        if (isDoor(b)) {
            int cly = (b == B_DOOR_UC || b == B_DOOR_UO) ? y - 1 : y;
            if (isDoor(blockAt(x, cly, z))) setBlock(x, cly, z, 0);
            if (isDoor(blockAt(x, cly + 1, z))) setBlock(x, cly + 1, z, 0);
        } else {
            if (b == B_BED && dim == 0 && bedOn && bedX == x && bedY == y && bedZ == z) { bedOn = false; say("Cama destruida: sin punto de reaparicion"); }
            setBlock(x, y, z, 0);
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
    else if (b == B_LEAVES) drop = 0;
    else if (b == B_ICE) drop = 0;
    else if (b == B_SNOW) drop = (toolType(tl) == 2) ? B_SNOW : 0;
    else if (b == B_COAL_ORE) drop = (lvl >= 0) ? I_COAL : 0;
    else if (b == B_IRON_ORE) drop = (lvl >= 1) ? B_IRON_ORE : 0;
    else if (b == B_GOLD_ORE) drop = (lvl >= 2) ? B_GOLD_ORE : 0;
    else if (b == B_DIAM_ORE) drop = (lvl >= 2) ? I_DIAMOND : 0;
    else if (b >= B_IRON_BLK && b <= B_DIAM_BLK) drop = (lvl >= needLevel(b)) ? b : 0;
    else if (b == B_OBSIDIAN) drop = (lvl >= 3) ? b : 0;
    else if (b == B_NETHERRACK) drop = pick ? b : 0;
    if (b == B_BED && dim == 0 && bedOn && bedX == x && bedY == y && bedZ == z) { bedOn = false; say("Cama destruida: sin punto de reaparicion"); }
    setBlock(x, y, z, b == B_ICE ? B_WATER : 0);
    if (drop != 0) addItem(drop, 1, 0);
    if (b == B_LEAVES && rnd(8) == 0) addItem(I_APPLE, 1, 0);
    if (b == B_LEAVES && rnd(6) == 0) addItem(B_SAPLING, 1, 0);
    damageTool(1);
}

void Game::attackMob(Mob* m) {
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
        } else {
            int f = rnd(3);
            if (f > 0) addItem(I_GUNPOWDER, f, 0);
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
        if (attackCd <= 0) {
            attackMob(tMob);
            sqDone = true;
            attackCd = 8;
        }
    } else if (tx >= 0) {
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
    if (tx >= 0 && blockAt(tx, ty, tz) == B_TABLE) { openInv(3); return; }
    if (tx >= 0 && blockAt(tx, ty, tz) == B_FURNACE) { openInv(FURN); return; }
    if (tx >= 0 && blockAt(tx, ty, tz) == B_TNT) { igniteTnt(tx, ty, tz, 1); return; }
    if (tx >= 0 && isDoor(blockAt(tx, ty, tz))) { toggleDoor(tx, ty, tz); return; }
    if (tx >= 0 && blockAt(tx, ty, tz) == B_BED) { useBed(tx, ty, tz); return; }
    if (tx >= 0 && slId[sel] == I_LIGHTER) {
        if (lightPortal(ax, ay, az)) say("Portal encendido");
        else if (blockAt(tx, ty, tz) == B_OBSIDIAN) say("Marco de portal incompleto");
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
    setBlock(ax, ay, az, id);
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
    if (moving && onGround) hungerTimer++;
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
    if (id == B_LOG) return I_COAL;
    if (id == B_COBBLE) return B_STONE;
    if (id == I_MUTTON) return I_COOKED_MUTTON;
    if (id == I_CHICKEN) return I_COOKED_CHICKEN;
    if (id == I_BEEF) return I_STEAK;
    if (id == B_IRON_ORE) return I_IRON_INGOT;
    if (id == B_GOLD_ORE) return I_GOLD_INGOT;
    return 0;
}

int Game::fuelTime(int id) {
    if (id == I_COAL) return 1600;
    if (id == B_LOG || id == B_PLANKS || id == B_TABLE) return 300;
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
    int th = 4 + rnd(2);
    if (y + th >= H) return;
    for (int dy = 0; dy <= th; dy++) {
        int b = blockAt(x, y + dy, z);
        if (b != 0 && b != B_SAPLING && b != B_LEAVES) return;
    }
    for (int dy = 1; dy <= th; dy++) setBlock(x, top + dy, z, B_LOG);
    for (int dy = th - 2; dy <= th + 1; dy++) {
        int r = (dy <= th - 1) ? 2 : 1;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (r == 2 && abs(dx) == 2 && abs(dz) == 2 && rnd(2) == 0) continue;
                if (dy == th + 1 && abs(dx) + abs(dz) > 1) continue;
                if (dx == 0 && dz == 0 && dy <= th) continue;
                if (blockAt(x + dx, top + dy, z + dz) == 0) setBlock(x + dx, top + dy, z + dz, B_LEAVES);
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
float Game::mobHW(int t) { if (t == 5) return 0.49f; return t == 0 ? 0.35f : (t == 1 ? 0.2f : (t == 2 ? 0.4f : 0.3f)); }
float Game::mobH(int t) { if (t == 5) return 1.0f; return t == 0 ? 1.0f : (t == 1 ? 0.7f : (t == 2 ? 1.25f : (t == 3 ? 1.8f : 1.6f))); }
float Game::mobR(int t) { if (t == 5) return 0.f; return t == 0 ? 0.5f : (t == 1 ? 0.3f : (t == 2 ? 0.55f : (t == 3 ? 0.45f : 0.4f))); }
float Game::mobSpeed(int t) { return t == 3 ? 0.07f : (t == 4 ? 0.06f : 0.035f); }
int Game::mobHp(int t) { return t == 0 ? 8 : (t == 1 ? 4 : (t == 2 ? 10 : (t == 3 ? 12 : 10))); }

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
bool Game::waterAtF(float x, float y, float z) { return blockAt(ifloor(x), ifloor(y), ifloor(z)) == B_WATER; }

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
    float lim = (m->type == 3) ? 256.f : 144.f;
    if (d2 > lim) { m->fuse = 0; m->wind = 0; return false; }
    m->ang = atan2f(dx, dz);
    m->moving = true;
    if (m->type == 3) {
        bool near_ = d2 < 1.7f && dy < 1.2f && dy > -1.2f
                && losClear(m->x, m->y + 1.0f, m->z, px, py + 1.0f, pz);
        if (near_) {
            m->moving = false;
            if (++m->wind >= 12 && m->atk <= 0) { hurt(3); m->atk = 40; m->wind = 0; }
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
