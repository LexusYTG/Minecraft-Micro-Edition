import javax.microedition.lcdui.*;
import javax.microedition.midlet.*;
import javax.microedition.rms.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Random;
import java.util.Vector;

public class Minecraft2D extends MIDlet {
    // true = ventanita de depuracion (RAM, FPS, chunks, posicion) mientras se juega
static final boolean DEBUG = true;
    static long memBase;   // memoria viva antes de crear el juego (solo DEBUG)

    private Display display;
    private Minecraft3DCanvas canvas;

    public void startApp() {
        display = Display.getDisplay(this);
        if (DEBUG && canvas == null) {
            Runtime rt = Runtime.getRuntime();
            rt.gc();
            memBase = rt.totalMemory() - rt.freeMemory();
        }
        if (canvas == null) canvas = new Minecraft3DCanvas(this, display);
        display.setCurrent(canvas);
        canvas.start();
    }

    public void pauseApp() {
        if (canvas != null) canvas.stop();
    }

    public void destroyApp(boolean unconditional) {
        if (canvas != null) canvas.stop();
    }

    void quit() {
        destroyApp(true);
        notifyDestroyed();
    }
}

class Mob {
    int type;          // 0 = oveja, 1 = pollo, 2 = vaca, 3 = zombie, 4 = creeper
    float x, y, z, vy, ang;
    int timer, hurt, flee, hp, atk, fuse, wind;   // atk/wind: zombie (enfriamiento/carga), fuse: creeper
    boolean moving, ground;
}

class WorldInfo {
    int id, seed, rid;
    int mode;          // 0 = supervivencia, 1 = creativo
    String name;
}

class Minecraft3DCanvas extends Canvas implements Runnable, CommandListener {
    // ---------------------------------------------------------------- constantes
    // mundo infinito por chunks (CS debe ser 16: se usa >> 4 y & 15)
    static final int CS = 16;            // lado del chunk en bloques
    static final int H = 32;             // altura del mundo
    static final int NC_MAX = 3;         // ventana maxima 3x3 (distancias > 7)

    static final int CHB = CS * CS * H;  // bytes por chunk en RAM
    static final int PB = CHB;
    static final int SAVE_LIMIT = 16384; // al juntar 16 KB de chunks modificados se escriben al RecordStore
    static final String LIST_STORE = "mcw_list", CFG_STORE = "mcw_cfg", OLD_STORE = "mc2dworld";
    static final int[] RD_OPT = { 4, 7, 10, 13, 15 };   // distancia de renderizado en bloques
    static final String[] RD_NAME = { "Minima", "Normal", "Lejos", "Muy lejos", "Extrema" };


    static final int NONE = 0x7fffffff;  // slot de chunk vacio

    static final int ST_MENU = 0, ST_PLAY = 1, ST_INV = 2, ST_DEAD = 3, ST_PAUSE = 4, ST_SET = 5,
            ST_WORLDS = 6, ST_CREATE = 7, ST_CONFIRM = 8;
    static final int A_PLAY = 1, A_QUIT = 2, A_RESUME = 3, A_SETTINGS = 4, A_TOMENU = 5, A_RD = 6, A_SETBACK = 7,
            A_NEW = 8, A_WBACK = 9, A_CNAME = 10, A_CSEED = 11, A_CGO = 12, A_CBACK = 13, A_YES = 14, A_NO = 15, A_CMODE = 16, A_LANG = 17;
    static final int J_ENTER = 1, J_APPLY = 2, J_LEAVE = 3, J_SAVE = 4, J_FLUSH = 5;

    static final int B_AIR = 0, B_GRASS = 1, B_DIRT = 2, B_STONE = 3, B_LOG = 4,
            B_LEAVES = 5, B_PLANKS = 6, B_TABLE = 7, B_COBBLE = 8,
            B_FURNACE = 9, B_SAPLING = 10, B_TORCH = 11,
            B_SAND = 12, B_SANDSTONE = 13, B_CACTUS = 14, B_TNT = 15,
            B_WATER = 16, B_SNOW = 17, B_GRAVEL = 18, B_ICE = 19;

    static final int SEA = 15;

    static final int I_STICK = 20, I_PICK = 21, I_AXE = 22, I_SHOVEL = 23, I_SWORD = 24,
            I_WOOL = 25, I_MUTTON = 26, I_CHICKEN = 27, I_FEATHER = 28, I_APPLE = 29,
            I_BEEF = 30, I_STEAK = 31, I_COOKED_MUTTON = 32, I_COOKED_CHICKEN = 33, I_COAL = 34,
            I_ROTTEN = 35, I_GUNPOWDER = 36;
    // puerta: 2 bloques de alto (L = inferior, U = superior; C = cerrada, O = abierta); I_DOOR = item
    static final int B_DOOR_LC = 37, B_DOOR_UC = 38, B_DOOR_LO = 39, B_DOOR_UO = 40, I_DOOR = 41;
    // menas, bloques de mineral, cama; lingotes/diamante; herramientas por material (56..71)
    static final int B_COAL_ORE = 42, B_IRON_ORE = 43, B_GOLD_ORE = 44, B_DIAM_ORE = 45,
            B_IRON_BLK = 46, B_GOLD_BLK = 47, B_DIAM_BLK = 48, B_BED = 49;
    static final int I_IRON_INGOT = 50, I_GOLD_INGOT = 51, I_DIAMOND = 52, I_TOOLS = 56;
    // Nether: roca, lava, obsidiana, portal, arena de almas, piedra luminosa, lecho de roca; encendedor
    static final int B_NETHERRACK = 53, B_LAVA = 54, B_OBSIDIAN = 55, B_PORTAL = 72, B_SOULSAND = 73,
            B_GLOWSTONE = 74, B_BEDROCK = 75, I_LIGHTER = 76;
    // cofre: bloque + pantalla (craftN = CHEST). Cada cofre tiene un id fijo 0..63; su contenido vive en disco
    static final int B_CHEST = 77, CHEST = 5, MAX_CHESTS = 64;
    static final int B_LOG_S = 78, B_LEAVES_S = 80, B_PLANKS_S = 82, B_FARM_DRY = 84, B_FARM_WET = 85,
            B_CROP0 = 86, B_CROP1 = 87, B_CROP2 = 88, I_HOE = 89, I_SEEDS = 90, I_WHEAT = 91, I_BREAD = 92,
            I_EMERALD = 93, I_BUCKET = 94, I_BUCKET_W = 95, I_BUCKET_L = 96, TRADE = 6;
    static final int[] HX = { -9, 9, 0, 0, -9, 9 }, HZ = { 0, 0, -9, 9, -9, 9 };
    static final int[] T_IN = { I_WHEAT, I_COAL, I_EMERALD, I_EMERALD, I_EMERALD, I_EMERALD, I_EMERALD, I_EMERALD };
    static final int[] T_INC = { 20, 15, 1, 1, 1, 1, 3, 2 };
    static final int[] T_OUT = { I_EMERALD, I_EMERALD, I_BREAD, I_STICK, I_SEEDS, I_BUCKET, I_DIAMOND, I_IRON_INGOT };
    static final int[] T_OUTC = { 1, 1, 4, 32, 8, 1, 1, 3 };
    static final int CH0 = 46, CHN = 27, NSLOT = CH0 + CHN;   // slots 46..72 = contenido del cofre abierto

    static final float NEAR = 0.1f, FOV = 1.1f, EYE = 1.62f, GRAV = 0.08f, JUMP = 0.52f;
    static final float REACH = 5f, MOB_REACH = 3.5f, LOOK = 0.08f, SPD = 0.16f, HW = 0.3f;
    static final int TOOL_DUR = 59;
    static final int OUT = 45;   // slot virtual de resultado de crafteo
    static final int FURN = 4;   // craftN = 4: pantalla del horno (slots 36 entrada, 37 combustible, 38 salida)
    static final int SMELT_T = 200;    // ticks (50 ms) por item fundido = 10 s
    static final int DAY_LEN = 24000;  // ticks por dia completo = 20 min

    // caras: +x, -x, +y, -y, +z, -z ; esquinas = x + 2y + 4z
    static final int[][] FACE = { {1, 3, 7, 5}, {0, 4, 6, 2}, {2, 3, 7, 6}, {0, 4, 5, 1}, {4, 5, 7, 6}, {0, 2, 3, 1} };
    static final int[] FSH = { 80, 80, 100, 50, 65, 65 };
    static final float[] NX = { 1, -1, 0, 0, 0, 0 };
    static final float[] NY = { 0, 0, 1, -1, 0, 0 };
    static final float[] NZ = { 0, 0, 0, 0, 1, -1 };
    static final int[][] WIRE = { {0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7} };

    // ---------------------------------------------------------------- estado
    final Object lock = new Object();
    boolean running;
    Thread thread;
    Random rand = new Random();
    Font font;
    Graphics gg;
    int sw, sh;
    float halfW, halfH, focal;
    int tick;

    int state = ST_MENU;
    boolean[] kd = new boolean[14];   // 0-9, 10 = *, 11 = #, 12 = L, 13 = R
    int layout = 0;                   // 0 = A (caminar), 1 = B (camara)

    // chunks en anillo: slot = coordenada del chunk mod NC
    byte[][] chData;
    int[] chCx, chCz;
    boolean[] chDirty;
    Hashtable pending = new Hashtable(); // chunks editados (empaquetados) esperando escribirse
    Hashtable idx = new Hashtable();     // clave de chunk -> id de registro en el RecordStore
    int pendBytes;
    boolean needFlush, needSave, storeBroken, inWorld;
    RecordStore rs;
    byte[] rbuf = new byte[PB];
    int ccx, ccz;                        // chunk central objetivo
    int seed;
    Vector mobs = new Vector();
    int[] mobKey = new int[64];
    int[] mobOrd = new int[64];
    int[] invX, invZ, invY = new int[H];
    int spawnTimer, hostTimer;
    // dia / noche
    int worldTime = 1000, bright = 100;
    // horno (uno por mundo, compartido por todos los hornos colocados)
    int[] fId = new int[3], fCnt = new int[3];
    int fBurn, fBurnMax, fProg;
    // plantitas (saplings) esperando crecer
    int[] sapX = new int[32], sapY = new int[32], sapZ = new int[32], sapT = new int[32];
    int sapN;
    // antorchas cercanas (para iluminar de noche)
    float[] torchX = new float[16], torchY = new float[16], torchZ = new float[16];
    int torchN;
    float[][] cowParts, zombieParts, creeperParts, tntParts, pigmanParts, villagerParts;
    int tSel, vilX, vilZ, vilY, vilN;
    int dim, portalT, lavaT;      // dim: 0 = overworld, 1 = nether
    boolean portalLock;
    int hungerFx, cactusT, air = 300, airT;
    int[] tintBuf;

    // depuracion
    // ventana de chunks (depende de la distancia de renderizado)
    int rd = 7, nc = 2, nch = 4, nb = 15, rdIdx = 1, rdApplied = 1;
    float cullSq = 528f;

    // mundos y menus
    Minecraft2D midlet;
    Display disp;
    Vector worlds = new Vector();
    WorldInfo curWorld, jobWorld, delTarget;
    String curStore = "";
    int hdrRid, autoT;
    volatile int job;
    String busy;
    Font fontBig, fontMid;
    int[] navIds = new int[] { 0 };
    int navN = 1, navSel, wScroll, wVis = 3;
    int[] htX = new int[64], htY = new int[64], htW = new int[64], htH = new int[64], htId = new int[64];
    int htN, btnW, btnH, btnX;
    int tpX, tpY, tpScroll0;
    boolean tpDown, tpDrag;
    String cName = "", cSeedTxt = "";
    int editing;
    Command cmdOk = new Command("OK", Command.OK, 1);
    Command cmdCancel = new Command("Cancelar", Command.CANCEL, 2);

    long dbgT0, dbgMemT;
    int dbgFrames, dbgFps;
    String dbgMem = "", dbgInfo = "";

    // jugador (px,py,pz = pies)
    float px, py, pz, yaw, pitch, vy, fallDist;
    boolean onGround, moving;
    int health = 20, hunger = 20, hungerTimer, regenTimer, starveTimer, dmgFlash;

    // camara / render
    float camX, camY, camZ, cosY, sinY, cosP, sinP, fx, fy, fz;
    int[] ox, oy = new int[H], oz;
    float[] quad = new float[12];
    float[] clipA = new float[30];
    float[] cc = new float[24];
    int[] sxs = new int[10], sys = new int[10];

    // objetivo
    int tx = -1, ty = -1, tz = -1, ax = -1, ay = -1, az = -1;
    Mob tMob;

    // accion
    int starTicks, attackCd, mineProg, mineX = -1, mineY = -1, mineZ = -1;
    boolean starConsumed;

    // inventario: 0-35 inventario (0-8 hotbar), 36-44 grilla, 45 resultado
    int[] slId = new int[NSLOT], slCnt = new int[NSLOT], slDur = new int[NSLOT];
    int sel, labelT, msgT;
    String msg = "";
    boolean bedOn;
    int bedX, bedY, bedZ;
    // modo creativo
    boolean creative, flying;
    int jumpTick = -100, palPage, cMode;
    int[] palIds = new int[128];
    int palN;
    int heldId, heldCnt, heldDur;
    int cursor, craftN = 2, slS;
    int[] slx = new int[NSLOT], sly = new int[NSLOT];
    boolean[] slActive = new boolean[NSLOT];
    // cofres: tabla id -> posicion (RAM, ~1 KB). El contenido NO se guarda en RAM, solo en disco
    boolean[] cUsed = new boolean[MAX_CHESTS];
    int[] cX = new int[MAX_CHESTS], cY = new int[MAX_CHESTS], cZ = new int[MAX_CHESTS], cDim = new int[MAX_CHESTS];
    int[] cRid = new int[MAX_CHESTS], cFill = new int[MAX_CHESTS];   // registro en disco / slots ocupados
    boolean chestTabDirty, quietLoad;
    int openChest = -1;
    byte[] chestSnap = new byte[CHN * 4];   // contenido tal como se leyo: se guarda solo si cambio

    // datos de items
    String[] names = new String[128];
    String[] tag = new String[128];
    int[] icol = new int[128];
    int[] cTop = new int[128], cSide = new int[128], cBot = new int[128];
    int[][] shadedCol = new int[128][6];
    float[] hard = new float[128];
    float[][] sheepParts, chickenParts;

    // recetas
    int nrec;
    int[] rw = new int[48], rh = new int[48], rOut = new int[48], rCnt = new int[48];
    int[][] rPat = new int[48][];

    // ---------------------------------------------------------------- inicio
    public Minecraft3DCanvas(Minecraft2D m, Display d) {
        midlet = m;
        disp = d;
        setFullScreenMode(true);
        sw = getWidth();
        sh = getHeight();
        font = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        fontMid = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
        fontBig = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_LARGE);
        initTables();
        initRecipes();
        initPalette();
        loadSettings();
        applyLang();
        setupWindow(RD_OPT[rdIdx]);
        go(ST_MENU);
    }

    protected void sizeChanged(int w, int h) {
        sw = w;
        sh = h;
    }

    protected void showNotify() {
        setFullScreenMode(true);
    }

    // al irse la app a segundo plano: pausa y guarda el mundo
    protected void hideNotify() {
        synchronized (lock) {
            clearKeys();
            if (inWorld) {
                if (state == ST_PLAY) go(ST_PAUSE);
                chestCommit();
                saveAll();
            }
        }
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this);
        thread.start();
    }

    public void stop() {
        synchronized (lock) {
            if (inWorld) {
                if (state == ST_PLAY) go(ST_PAUSE);
                chestCommit();
                saveAll();
            }
            closeStore();
        }
        running = false;
    }

    public void run() {
        while (running) {
            long t0 = System.currentTimeMillis();
            update();
            int j = job;
            if (j == 0 && needSave) j = J_SAVE;
            if (j == 0 && needFlush) j = J_FLUSH;
            if (j != 0) {
                job = 0;
                needSave = false;
                runJob(j);
            }
            repaint();
            long dt = System.currentTimeMillis() - t0;
            long sl = 50 - dt;
            if (sl < 5) sl = 5;
            try { Thread.sleep(sl); } catch (Exception e) {}
        }
        closeStore();
    }

    // trabajos pesados (cargar, guardar): se muestra el cartel y se congela el juego
    void runJob(int j) {
        if (j == J_ENTER) busy = "Cargando mundo...";
        else if (j == J_APPLY) busy = "Cargando...";
        else busy = "Guardando...";
        repaint();
        serviceRepaints();
        try {
            synchronized (lock) {
                if (j == J_ENTER) enterWorld(jobWorld);
                else if (j == J_APPLY) applyRd();
                else if (j == J_LEAVE) { leaveWorld(); openMain(); }
                else if (j == J_SAVE) saveAll();
                else flushPending();
            }
        } catch (Throwable th) {
            synchronized (lock) {
                if (j == J_ENTER) {
                    inWorld = false;
                    closeStore();
                    openWorlds();
                }
            }
        }
        busy = null;
    }

    // ---------------------------------------------------------------- utilidades
    int rnd(int n) {
        return (rand.nextInt() & 0x7fffffff) % n;
    }

    float rndf() {
        return rnd(10000) / 10000f;
    }

    static int ifloor(float f) {
        int i = (int) f;
        return (f < i) ? i - 1 : i;
    }

    static int shade(int c, int pct) {
        int r = ((c >> 16) & 255) * pct / 100;
        int g = ((c >> 8) & 255) * pct / 100;
        int b = (c & 255) * pct / 100;
        return (r << 16) | (g << 8) | b;
    }

    int slotOf(int cx, int cz) {
        int sx = cx % nc, sz = cz % nc;
        if (sx < 0) sx += nc;
        if (sz < 0) sz += nc;
        return sz * nc + sx;
    }

    // datos del chunk que contiene (x,z), o null si no esta cargado
    byte[] chunkFor(int x, int z) {
        int cx = x >> 4, cz = z >> 4;
        int s = slotOf(cx, cz);
        if (chCx[s] != cx || chCz[s] != cz) return null;
        return chData[s];
    }

    // indice dentro del chunk: columna contigua en y
    int blockAt(int x, int y, int z) {
        if (y < 0 || y >= H) return 0;
        byte[] cd = chunkFor(x, z);
        if (cd == null) return 0;
        return cd[(((x & 15) << 4) + (z & 15)) * H + y];
    }

    void setBlock(int x, int y, int z, int b) {
        if (y < 0 || y >= H) return;
        int cx = x >> 4, cz = z >> 4;
        int s = slotOf(cx, cz);
        if (chCx[s] != cx || chCz[s] != cz) return;
        int bi = (((x & 15) << 4) + (z & 15)) * H + y;
        if (chData[s][bi] == B_CHEST && b != B_CHEST) chestFree(x, y, z);
        chData[s][bi] = (byte) b;
        chDirty[s] = true;
    }

    boolean solidAt(int x, int y, int z) {
        if (y < 0) return true;
        if (y >= H) return false;
        byte[] cd = chunkFor(x, z);
        if (cd == null) return true;
        int b = cd[(((x & 15) << 4) + (z & 15)) * H + y];
        return b != 0 && b != B_SAPLING && b != B_TORCH && b != B_WATER && b != B_DOOR_LO && b != B_DOOR_UO && b != B_LAVA && b != B_PORTAL && (b < B_CROP0 || b > B_CROP2);
    }

    boolean boxCollides(float x, float y, float z, float hw, float h) {
        int x0 = ifloor(x - hw), x1 = ifloor(x + hw);
        int y0 = ifloor(y), y1 = ifloor(y + h);
        int z0 = ifloor(z - hw), z1 = ifloor(z + hw);
        for (int xx = x0; xx <= x1; xx++)
            for (int yy = y0; yy <= y1; yy++)
                for (int zz = z0; zz <= z1; zz++)
                    if (solidAt(xx, yy, zz)) return true;
        return false;
    }

    int topY(int x, int z) {
        for (int y = H - 1; y >= 0; y--) if (blockAt(x, y, z) != 0) return y;
        return -1;
    }

    // tipo: 0 pico, 1 hacha, 2 pala, 3 espada, -1 no es herramienta
    int toolType(int id) {
        if (id >= I_PICK && id <= I_SWORD) return id - I_PICK;
        if (id >= I_TOOLS && id < I_TOOLS + 16) return (id - I_TOOLS) & 3;
        if (id == I_HOE) return 4;
        return -1;
    }
    // material: 0 madera, 1 piedra, 2 hierro, 3 oro, 4 diamante, -1 no es herramienta
    int toolTier(int id) {
        if (id >= I_PICK && id <= I_SWORD) return 0;
        if (id >= I_TOOLS && id < I_TOOLS + 16) return 1 + (id - I_TOOLS) / 4;
        if (id == I_HOE) return 0;
        return -1;
    }
    // nivel de mineria (el oro es rapido pero de nivel bajo)
    int toolLevel(int id) {
        int t = toolTier(id);
        if (t < 0) return -1;
        return t == 0 ? 0 : (t == 1 ? 1 : (t == 2 ? 2 : (t == 3 ? 0 : 3)));
    }
    float toolSpeed(int id) {
        int t = toolTier(id);
        return t == 0 ? 2f : (t == 1 ? 4f : (t == 2 ? 6f : (t == 3 ? 12f : 8f)));
    }
    int toolMax(int id) {
        int t = toolTier(id);
        return t == 0 ? 59 : (t == 1 ? 131 : (t == 2 ? 250 : (t == 3 ? 32 : 1561)));
    }
    int needLevel(int b) {
        if (b == B_OBSIDIAN) return 3;
        if (b == B_IRON_ORE || b == B_IRON_BLK) return 1;
        if (b == B_GOLD_ORE || b == B_DIAM_ORE || b == B_GOLD_BLK || b == B_DIAM_BLK) return 2;
        return 0;
    }
    boolean isTool(int id) { return toolType(id) >= 0; }
    int lang;
    String[] namesEs;

    static final String[] L_ES = {
        "Jugar",
        "Salir",
        "En juego: L o R = pausa",
        "Selecciona un mundo",
        "+ Crear mundo nuevo",
        "Atras",
        "No hay mundos todavia",
        "Crear mundo nuevo",
        "Nombre: ",
        "Semilla: ",
        "aleatoria",
        "Modo: ",
        "Creativo",
        "Supervivencia",
        "Crear",
        "Borrar mundo?",
        "No se puede deshacer",
        "Si, borrar",
        "Cancelar",
        "Juego en pausa",
        "Reanudar",
        "Ajustes",
        "Menu principal",
        "No se pudo guardar!",
        "Minima",
        "Lejos",
        "Muy lejos",
        "Extrema",
        "4 / 6 o 5: cambiar",
        "Mas lejos = mas lento",
        "MORISTE",
        "5: reaparecer",
        "Hambre",
        "Creativo (vuelo)",
        "Cofre",
        "Horno",
        "Mesa de crafteo",
        "Inventario",
        "Aldeano  [1][3] lista",
        "Resultado: ",
        "Cargando mundo...",
        "Cargando...",
        "Guardando...",
        "Mundo ",
        "Nombre del mundo",
        "Semilla (vacio = aleatoria)",
        "Reapareciste en tu cama",
        "Tu cama fue destruida",
        "El cofre no esta vacio",
        "Cama destruida: sin punto de reaparicion",
        "Portal encendido",
        "Marco de portal incompleto",
        "Limite de cofres alcanzado (64)",
        "No se pudo leer el cofre",
        "Error al guardar el cofre",
        "No se puede dormir aqui",
        "Dormiste hasta la manana",
        "Punto de reaparicion guardado" };
    static final String[] L_EN = {
        "Play",
        "Quit",
        "In game: L or R = pause",
        "Select a world",
        "+ Create new world",
        "Back",
        "No worlds yet",
        "Create new world",
        "Name: ",
        "Seed: ",
        "random",
        "Mode: ",
        "Creative",
        "Survival",
        "Create",
        "Delete world?",
        "This cannot be undone",
        "Yes, delete",
        "Cancel",
        "Game paused",
        "Resume",
        "Settings",
        "Main menu",
        "Could not save!",
        "Minimum",
        "Far",
        "Very far",
        "Extreme",
        "4 / 6 or 5: change",
        "Farther = slower",
        "YOU DIED",
        "5: respawn",
        "Hunger",
        "Creative (flying)",
        "Chest",
        "Furnace",
        "Crafting Table",
        "Inventory",
        "Villager  [1][3] list",
        "Result: ",
        "Loading world...",
        "Loading...",
        "Saving...",
        "World ",
        "World name",
        "Seed (empty = random)",
        "You respawned in your bed",
        "Your bed was destroyed",
        "The chest is not empty",
        "Bed destroyed: no respawn point",
        "Portal lit",
        "Portal frame incomplete",
        "Chest limit reached (64)",
        "Could not read the chest",
        "Error saving the chest",
        "You cannot sleep here",
        "You slept until morning",
        "Respawn point saved" };

    String L(String t) {
        if (lang == 0 || t == null) return t;
        for (int i = 0; i < L_ES.length; i++) if (L_ES[i].equals(t)) return L_EN[i];
        return t;
    }

    void applyLang() {
        if (namesEs == null) {
            namesEs = new String[128];
            for (int i = 0; i < 128; i++) namesEs[i] = names[i];
        }
        for (int i = 0; i < 128; i++) names[i] = namesEs[i];
        if (lang == 0) return;
        String[] e = { "", "Grass", "Dirt", "Stone", "Log", "Leaves", "Planks", "Crafting Table", "Cobblestone", "Furnace", "Sapling", "Torch", "Sand", "Sandstone", "Cactus", "TNT", "Water", "Snow", "Gravel", "Ice" };
        for (int i = 0; i < e.length; i++) names[i] = e[i];
        String[] it = { "Stick", "Wooden Pickaxe", "Wooden Axe", "Wooden Shovel", "Wooden Sword", "Wool", "Raw Mutton", "Raw Chicken", "Feather", "Apple", "Raw Beef", "Steak", "Cooked Mutton", "Cooked Chicken", "Charcoal", "Rotten Flesh", "Gunpowder" };
        for (int i = 0; i < it.length; i++) names[I_STICK + i] = it[i];
        for (int i = B_DOOR_LC; i <= I_DOOR; i++) names[i] = "Door";
        String[] o = { "Coal Ore", "Iron Ore", "Gold Ore", "Diamond Ore", "Iron Block", "Gold Block", "Diamond Block", "Bed", "Iron Ingot", "Gold Ingot", "Diamond", "Netherrack", "Lava", "Obsidian" };
        for (int i = 0; i < o.length; i++) names[B_COAL_ORE + i] = o[i];
        String[] n2 = { "Portal", "Soul Sand", "Glowstone", "Bedrock", "Flint and Steel", "Chest" };
        for (int i = 0; i < n2.length; i++) names[B_PORTAL + i] = n2[i];
        String[] n3 = { "Spruce Log", "Acacia Log", "Spruce Leaves", "Acacia Leaves", "Spruce Planks", "Acacia Planks", "Farmland", "Wet Farmland", "Wheat", "Wheat", "Wheat", "Wooden Hoe", "Seeds", "Wheat", "Bread", "Emerald", "Bucket", "Water Bucket", "Lava Bucket" };
        for (int i = 0; i < n3.length; i++) names[B_LOG_S + i] = n3[i];
        String[] matE = { "Wooden", "Stone", "Iron", "Golden", "Diamond" };
        String[] typE = { "Pickaxe", "Axe", "Shovel", "Sword" };
        for (int t = 1; t < 5; t++) {
            for (int k = 0; k < 4; k++) names[I_TOOLS + (t - 1) * 4 + k] = cat(cat(matE[t], " "), typE[k]);
        }
    }

    void say(String t) { msg = L(t); msgT = 70; }
    boolean isBlockItem(int id) { return (id >= 1 && id <= 19) || id == I_DOOR || (id >= B_COAL_ORE && id <= B_BED)
                || (id >= B_NETHERRACK && id <= B_OBSIDIAN) || id == B_SOULSAND || id == B_GLOWSTONE || id == B_CHEST
                || (id >= B_LOG_S && id <= B_FARM_DRY) || id == I_SEEDS; }
    boolean isDoor(int b) { return b >= B_DOOR_LC && b <= B_DOOR_UO; }
    boolean isFood(int id) {
        return id == I_APPLE || id == I_BREAD || id == I_MUTTON || id == I_CHICKEN || id == I_BEEF || id == I_STEAK
                || id == I_COOKED_MUTTON || id == I_COOKED_CHICKEN || id == I_ROTTEN;
    }
    int foodVal(int id) {
        if (id == I_STEAK) return 8;
        if (id == I_COOKED_MUTTON || id == I_COOKED_CHICKEN) return 6;
        if (id == I_BREAD) return 5;
        if (id == I_APPLE) return 4;
        if (id == I_BEEF) return 3;
        return 2;
    }
    int maxStack(int id) { return (isTool(id) || (id >= I_BUCKET && id <= I_BUCKET_L)) ? 1 : 64; }

    // ---------------------------------------------------------------- tablas
    int logOf(int v) { return v == 0 ? B_LOG : B_LOG_S + v - 1; }
    int leavesOf(int v) { return v == 0 ? B_LEAVES : B_LEAVES_S + v - 1; }
    int planksOf(int v) { return v == 0 ? B_PLANKS : B_PLANKS_S + v - 1; }
    boolean isLog(int b) { return b == B_LOG || b == B_LOG_S || b == B_LOG_S + 1; }
    boolean isLeaves(int b) { return b == B_LEAVES || b == B_LEAVES_S || b == B_LEAVES_S + 1; }
    boolean isPlanks(int b) { return b == B_PLANKS || b == B_PLANKS_S || b == B_PLANKS_S + 1; }
    int pn(int id) { return (id == B_PLANKS_S || id == B_PLANKS_S + 1) ? B_PLANKS : id; }

    int vt(int c, int wv, boolean leaf) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, bl = c & 255;
        if (leaf) {
            if (wv == 1) { r = r * 5 / 8; g = g * 5 / 8; bl = Math.min(255, bl * 4 / 3); }
            else { r = Math.min(255, r * 2); bl = bl * 4 / 5; }
        } else if (wv == 1) {
            r = r * 2 / 3; g = g * 2 / 3; bl = bl * 2 / 3;
        } else {
            g = g * 7 / 10; bl = bl * 6 / 10;
        }
        return (r << 16) | (g << 8) | bl;
    }

    int treeVar(int x, int z) {
        int bio = biomeAt(x, z);
        if (bio == 2) return 1;
        if (bio == 0 && noise(x, z, 6, 77) > 0.58f) return 2;
        return 0;
    }

    boolean wetNear(int x, int y, int z) {
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (blockAt(x + dx, y + dy, z + dz) == B_WATER) return true;
                }
            }
        }
        return false;
    }

    void growCrops() {
        for (int s = 0; s < chData.length; s++) {
            if (chData[s] == null || chCx[s] == NONE) continue;
            byte[] d = chData[s];
            for (int i = 1; i < CHB; i++) {
                int b = d[i];
                if ((b == B_CROP0 || b == B_CROP1) && d[i - 1] == B_FARM_WET) {
                    d[i] = (byte) (b + 1);
                    chDirty[s] = true;
                }
            }
        }
    }

    void tradeOutput() {
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

    void tradeTake() {
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

    void tradeMove(int d) {
        tSel = (tSel + d + T_IN.length) % T_IN.length;
        updateOutput();
    }

    void paintTrade() {
        int s = slS, x = slx[9], rh = s * 3 / 4, w = 5 * s;
        int vis = (sly[9] - 22) / rh;
        if (vis < 1) vis = 1;
        int first = tSel - vis + 1;
        if (first < 0) first = 0;
        for (int i = 0; i < vis && first + i < T_IN.length; i++) {
            int t = first + i, y = 14 + i * rh;
            gg.setColor(t == tSel ? 0x5A5A3A : 0x3A3A3A);
            gg.fillRect(x, y, w, rh - 1);
            if (t == tSel) {
                gg.setColor(0xFFFF00);
                gg.drawRect(x, y, w - 1, rh - 2);
            }
            drawIcon(T_IN[t], 0, x + 2, y, rh);
            drawCount(T_INC[t], x + 2, y, rh);
            txt("=>", x + w / 2, y + rh / 2 - 5, 0xFFFFFF, Graphics.TOP | Graphics.HCENTER);
            drawIcon(T_OUT[t], 0, x + w - rh - 2, y, rh);
            drawCount(T_OUTC[t], x + w - rh - 2, y, rh);
        }
        txt("v", slx[36] + s / 2, sly[36] + s + s / 2 - 5, 0xFFFFFF, Graphics.TOP | Graphics.HCENTER);
    }

    boolean villageFor(int x0, int z0) {
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

    boolean houseOk(int k) {
        int hy = heightAt(vilX + HX[k], vilZ + HZ[k]);
        return hy > SEA + 1 && hy < H - 8 && Math.abs(hy - vilY) <= 3;
    }

    void buildVillage(byte[] d, int x0, int z0) {
        int bio = biomeAt(vilX, vilZ);
        int wv = bio == 1 ? 2 : (bio == 2 ? 1 : 0);
        int lg = logOf(wv), pl = planksOf(wv);
        int fy = vilY - 1;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = vilX + dx, z = vilZ + dz;
                boolean mid = dx == 0 && dz == 0;
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
                    boolean ex = dx == -2 || dx == 2, ez = dz == -2 || dz == 2;
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

    void initTables() {
        for (int i = 0; i < 128; i++) { names[i] = "?"; tag[i] = ""; icol[i] = 0x808080; }
        names[0] = "";
        names[1] = "Pasto"; names[2] = "Tierra"; names[3] = "Piedra"; names[4] = "Tronco";
        names[5] = "Hojas"; names[6] = "Tablones"; names[7] = "Mesa de crafteo"; names[8] = "Adoquin";
        names[I_STICK] = "Palo"; names[I_PICK] = "Pico de madera"; names[I_AXE] = "Hacha de madera";
        names[I_SHOVEL] = "Pala de madera"; names[I_SWORD] = "Espada de madera";
        names[I_WOOL] = "Lana"; names[I_MUTTON] = "Cordero crudo"; names[I_CHICKEN] = "Pollo crudo";
        names[I_FEATHER] = "Pluma"; names[I_APPLE] = "Manzana";
        names[B_FURNACE] = "Horno"; names[B_CHEST] = "Cofre"; names[B_SAPLING] = "Brote"; names[B_TORCH] = "Antorcha";
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
        cTop[B_IRON_BLK] = 0xD8D8D8; cSide[B_IRON_BLK] = 0xD8D8D8; cBot[B_IRON_BLK] = 0xD8D8D8;
        cTop[B_GOLD_BLK] = 0xF2D040; cSide[B_GOLD_BLK] = 0xF2D040; cBot[B_GOLD_BLK] = 0xF2D040;
        cTop[B_DIAM_BLK] = 0x5AE8E0; cSide[B_DIAM_BLK] = 0x5AE8E0; cBot[B_DIAM_BLK] = 0x5AE8E0;
        cTop[B_BED] = 0xC83A34; cSide[B_BED] = 0x6B4A2A; cBot[B_BED] = 0x6B4A2A;
        cTop[B_NETHERRACK] = 0x7A2E2E; cSide[B_NETHERRACK] = 0x7A2E2E; cBot[B_NETHERRACK] = 0x7A2E2E;
        cTop[B_LAVA] = 0xE0601A; cSide[B_LAVA] = 0xE0601A; cBot[B_LAVA] = 0xE0601A;
        cTop[B_OBSIDIAN] = 0x1C0F2E; cSide[B_OBSIDIAN] = 0x1C0F2E; cBot[B_OBSIDIAN] = 0x1C0F2E;
        cTop[B_SOULSAND] = 0x5A4636; cSide[B_SOULSAND] = 0x5A4636; cBot[B_SOULSAND] = 0x5A4636;
        cTop[B_GLOWSTONE] = 0xF2D488; cSide[B_GLOWSTONE] = 0xF2D488; cBot[B_GLOWSTONE] = 0xF2D488;
        cTop[B_BEDROCK] = 0x444444; cSide[B_BEDROCK] = 0x444444; cBot[B_BEDROCK] = 0x444444;
        cTop[B_PORTAL] = 0x8A2BE2; cSide[B_PORTAL] = 0x8A2BE2; cBot[B_PORTAL] = 0x8A2BE2;
        cTop[B_CHEST] = 0xA0722F; cSide[B_CHEST] = 0x8A5E26; cBot[B_CHEST] = 0x6E4A1E;
        names[B_LOG_S] = "Tronco de abeto"; names[B_LOG_S + 1] = "Tronco de acacia";
        names[B_LEAVES_S] = "Hojas de abeto"; names[B_LEAVES_S + 1] = "Hojas de acacia";
        names[B_PLANKS_S] = "Tablones de abeto"; names[B_PLANKS_S + 1] = "Tablones de acacia";
        names[B_FARM_DRY] = "Tierra labrada"; names[B_FARM_WET] = "Tierra labrada humeda";
        names[B_CROP0] = "Trigo"; names[B_CROP1] = "Trigo"; names[B_CROP2] = "Trigo";
        names[I_HOE] = "Azada de madera"; names[I_SEEDS] = "Semillas"; names[I_WHEAT] = "Trigo"; names[I_BREAD] = "Pan";
        names[I_EMERALD] = "Esmeralda"; names[I_BUCKET] = "Cubeta"; names[I_BUCKET_W] = "Cubeta con agua"; names[I_BUCKET_L] = "Cubeta con lava";
        icol[I_HOE] = 0xC49A55; icol[I_SEEDS] = 0x6B8B2A; icol[I_WHEAT] = 0xD8C040; icol[I_BREAD] = 0xC8883A;
        icol[I_EMERALD] = 0x2AD36A; icol[I_BUCKET] = 0xB0B0B0; icol[I_BUCKET_W] = 0x2A5CE0; icol[I_BUCKET_L] = 0xE0601A;
        tag[I_HOE] = "Az";
        cTop[B_LOG_S] = 0x705C38; cSide[B_LOG_S] = 0x3E2D18; cBot[B_LOG_S] = 0x705C38;
        cTop[B_LOG_S + 1] = 0xA96132; cSide[B_LOG_S + 1] = 0x5E3017; cBot[B_LOG_S + 1] = 0xA96132;
        cTop[B_LEAVES_S] = 0x1D4C38; cSide[B_LEAVES_S] = 0x1D4C38; cBot[B_LEAVES_S] = 0x1D4C38;
        cTop[B_LEAVES_S + 1] = 0x5E7A22; cSide[B_LEAVES_S + 1] = 0x5E7A22; cBot[B_LEAVES_S + 1] = 0x5E7A22;
        cTop[B_PLANKS_S] = 0x7A623C; cSide[B_PLANKS_S] = 0x7A623C; cBot[B_PLANKS_S] = 0x7A623C;
        cTop[B_PLANKS_S + 1] = 0xB86836; cSide[B_PLANKS_S + 1] = 0xB86836; cBot[B_PLANKS_S + 1] = 0xB86836;
        cTop[B_FARM_DRY] = 0x6B4A28; cSide[B_FARM_DRY] = 0x7B5B34; cBot[B_FARM_DRY] = 0x7B5B34;
        cTop[B_FARM_WET] = 0x3F2A16; cSide[B_FARM_WET] = 0x6A4C2A; cBot[B_FARM_WET] = 0x7B5B34;
        for (int b = B_CROP0; b <= B_CROP2; b++) { cTop[b] = 0x4FB03A; cSide[b] = 0x4FB03A; cBot[b] = 0x4FB03A; }
        hard[B_LOG_S] = 2f; hard[B_LOG_S + 1] = 2f; hard[B_LEAVES_S] = 0.2f; hard[B_LEAVES_S + 1] = 0.2f;
        hard[B_PLANKS_S] = 2f; hard[B_PLANKS_S + 1] = 2f; hard[B_FARM_DRY] = 0.6f; hard[B_FARM_WET] = 0.6f;
        for (int b = 1; b < 128; b++) {
            for (int f = 0; f < 6; f++) {
                int base = (f == 2) ? cTop[b] : (f == 3 ? cBot[b] : cSide[b]);
                shadedCol[b][f] = shade(base, FSH[f]);
            }
        }
        hard[1] = 0.6f; hard[2] = 0.5f; hard[3] = 1.5f; hard[4] = 2f;
        hard[5] = 0.2f; hard[6] = 2f; hard[7] = 2.5f; hard[8] = 2f;
        hard[9] = 3.5f; hard[10] = 0f; hard[11] = 0f;
        hard[12] = 0.5f; hard[13] = 0.8f; hard[14] = 0.4f; hard[15] = 0f;
        hard[16] = 0f; hard[17] = 0.2f; hard[18] = 0.6f; hard[19] = 0.5f;
        hard[B_DOOR_LC] = 1.5f; hard[B_DOOR_UC] = 1.5f; hard[B_DOOR_LO] = 1.5f; hard[B_DOOR_UO] = 1.5f;
        names[B_DOOR_LC] = "Puerta"; names[B_DOOR_UC] = "Puerta"; names[B_DOOR_LO] = "Puerta"; names[B_DOOR_UO] = "Puerta"; names[I_DOOR] = "Puerta";
        icol[I_DOOR] = 0x9C7A44;
        hard[B_COAL_ORE] = 3f; hard[B_IRON_ORE] = 3f; hard[B_GOLD_ORE] = 3f; hard[B_DIAM_ORE] = 3f;
        hard[B_IRON_BLK] = 5f; hard[B_GOLD_BLK] = 3f; hard[B_DIAM_BLK] = 5f; hard[B_BED] = 0.2f;
        names[B_COAL_ORE] = "Mena de carbon"; names[B_IRON_ORE] = "Mena de hierro";
        names[B_GOLD_ORE] = "Mena de oro"; names[B_DIAM_ORE] = "Mena de diamante";
        names[B_IRON_BLK] = "Bloque de hierro"; names[B_GOLD_BLK] = "Bloque de oro";
        names[B_DIAM_BLK] = "Bloque de diamante"; names[B_BED] = "Cama";
        names[I_IRON_INGOT] = "Lingote de hierro"; names[I_GOLD_INGOT] = "Lingote de oro"; names[I_DIAMOND] = "Diamante";
        icol[I_IRON_INGOT] = 0xD8D8D8; icol[I_GOLD_INGOT] = 0xF0C020; icol[I_DIAMOND] = 0x4AE8E0;
        hard[B_NETHERRACK] = 0.4f; hard[B_LAVA] = 0f; hard[B_OBSIDIAN] = 25f; hard[B_SOULSAND] = 0.5f;
        hard[B_GLOWSTONE] = 0.3f; hard[B_BEDROCK] = 100000f; hard[B_PORTAL] = 0f; hard[B_CHEST] = 2.5f;
        names[B_NETHERRACK] = "Roca del infierno"; names[B_LAVA] = "Lava"; names[B_OBSIDIAN] = "Obsidiana";
        names[B_PORTAL] = "Portal"; names[B_SOULSAND] = "Arena de almas"; names[B_GLOWSTONE] = "Piedra luminosa";
        names[B_BEDROCK] = "Lecho de roca"; names[I_LIGHTER] = "Encendedor";
        icol[I_LIGHTER] = 0xD8D8D8;
        String[] matN = { "madera", "piedra", "hierro", "oro", "diamante" };
        String[] typN = { "Pico", "Hacha", "Pala", "Espada" };
        int[] matC = { 0xC49A55, 0x8A8A8A, 0xD8D8D8, 0xF0D030, 0x4AE8E0 };
        for (int t = 1; t < 5; t++) {
            for (int k = 0; k < 4; k++) {
                int tid = I_TOOLS + (t - 1) * 4 + k;
                names[tid] = cat(cat(typN[k], " de "), matN[t]);
                tag[tid] = tag[I_PICK + k];
                icol[tid] = matC[t];
            }
        }

        sheepParts = new float[][] {
            { 0f, 0.65f, 0f, 0.28f, 0.27f, 0.42f, 0xEEEEEE },
            { 0f, 0.85f, 0.52f, 0.16f, 0.16f, 0.16f, 0xD2B48C },
            { 0.17f, 0.2f, 0.28f, 0.06f, 0.2f, 0.06f, 0xBFA982 },
            { -0.17f, 0.2f, 0.28f, 0.06f, 0.2f, 0.06f, 0xBFA982 },
            { 0.17f, 0.2f, -0.28f, 0.06f, 0.2f, 0.06f, 0xBFA982 },
            { -0.17f, 0.2f, -0.28f, 0.06f, 0.2f, 0.06f, 0xBFA982 }
        };
        chickenParts = new float[][] {
            { 0f, 0.33f, 0f, 0.14f, 0.14f, 0.2f, 0xF4F4F4 },
            { 0f, 0.55f, 0.18f, 0.07f, 0.09f, 0.07f, 0xF4F4F4 },
            { 0f, 0.52f, 0.27f, 0.03f, 0.02f, 0.04f, 0xF0C020 },
            { 0f, 0.66f, 0.18f, 0.02f, 0.03f, 0.04f, 0xD02020 },
            { 0.05f, 0.1f, 0f, 0.02f, 0.1f, 0.02f, 0xF0C020 },
            { -0.05f, 0.1f, 0f, 0.02f, 0.1f, 0.02f, 0xF0C020 }
        };
        cowParts = new float[][] {
            { 0f, 0.75f, 0f, 0.30f, 0.30f, 0.50f, 0x4A3A2A },
            { 0f, 0.80f, 0.05f, 0.31f, 0.10f, 0.22f, 0xEFEFEF },
            { 0f, 0.95f, 0.60f, 0.18f, 0.18f, 0.18f, 0x5A4A3A },
            { 0f, 0.86f, 0.80f, 0.12f, 0.07f, 0.04f, 0xD8B8A0 },
            { 0.18f, 0.25f, 0.35f, 0.07f, 0.25f, 0.07f, 0x3A2E22 },
            { -0.18f, 0.25f, 0.35f, 0.07f, 0.25f, 0.07f, 0x3A2E22 },
            { 0.18f, 0.25f, -0.35f, 0.07f, 0.25f, 0.07f, 0x3A2E22 },
            { -0.18f, 0.25f, -0.35f, 0.07f, 0.25f, 0.07f, 0x3A2E22 }
        };
        zombieParts = new float[][] {
            { 0.12f, 0.4f, 0f, 0.10f, 0.40f, 0.10f, 0x2F3F8F },
            { -0.12f, 0.4f, 0f, 0.10f, 0.40f, 0.10f, 0x2F3F8F },
            { 0f, 1.1f, 0f, 0.22f, 0.35f, 0.12f, 0x3FA0A0 },
            { 0f, 1.65f, 0f, 0.20f, 0.20f, 0.20f, 0x4A8A4A },
            { 0.30f, 1.25f, 0.25f, 0.08f, 0.08f, 0.30f, 0x4A8A4A },
            { -0.30f, 1.25f, 0.25f, 0.08f, 0.08f, 0.30f, 0x4A8A4A }
        };
        pigmanParts = new float[][] {
            { 0.12f, 0.4f, 0f, 0.10f, 0.40f, 0.10f, 0x7A4A32 },
            { -0.12f, 0.4f, 0f, 0.10f, 0.40f, 0.10f, 0x7A4A32 },
            { 0f, 1.1f, 0f, 0.22f, 0.35f, 0.12f, 0xB86868 },
            { 0f, 1.65f, 0f, 0.20f, 0.20f, 0.20f, 0xD89090 },
            { 0f, 1.6f, 0.22f, 0.08f, 0.06f, 0.04f, 0xB06060 },
            { 0.30f, 1.25f, 0.25f, 0.08f, 0.08f, 0.30f, 0xD89090 },
            { -0.30f, 1.25f, 0.25f, 0.08f, 0.08f, 0.30f, 0xD89090 }
        };
        tntParts = new float[][] { { 0f, 0.5f, 0f, 0.49f, 0.49f, 0.49f, 0xC03030 } };
        villagerParts = new float[][] {
            { 0.12f, 0.4f, 0f, 0.10f, 0.40f, 0.10f, 0x5A4630 },
            { -0.12f, 0.4f, 0f, 0.10f, 0.40f, 0.10f, 0x5A4630 },
            { 0f, 1.1f, 0f, 0.22f, 0.35f, 0.14f, 0x6B4A2A },
            { 0f, 1.65f, 0f, 0.20f, 0.20f, 0.18f, 0xD9AE8A },
            { 0f, 1.58f, 0.2f, 0.05f, 0.10f, 0.06f, 0xC99A78 },
            { 0f, 1.25f, 0.2f, 0.26f, 0.08f, 0.10f, 0x8A6A3A }
        };
        creeperParts = new float[][] {
            { 0.12f, 0.2f, 0.12f, 0.10f, 0.20f, 0.10f, 0x3A8A3A },
            { -0.12f, 0.2f, 0.12f, 0.10f, 0.20f, 0.10f, 0x3A8A3A },
            { 0.12f, 0.2f, -0.12f, 0.10f, 0.20f, 0.10f, 0x3A8A3A },
            { -0.12f, 0.2f, -0.12f, 0.10f, 0.20f, 0.10f, 0x3A8A3A },
            { 0f, 0.8f, 0f, 0.20f, 0.40f, 0.12f, 0x4AAA4A },
            { 0f, 1.4f, 0f, 0.20f, 0.20f, 0.20f, 0x5ABA5A },
            { 0.08f, 1.45f, 0.20f, 0.06f, 0.06f, 0.01f, 0x101010 },
            { -0.08f, 1.45f, 0.20f, 0.06f, 0.06f, 0.01f, 0x101010 },
            { 0f, 1.30f, 0.20f, 0.05f, 0.07f, 0.01f, 0x101010 }
        };
    }

    void rec(int w, int h, int[] pat, int out, int cnt) {
        rw[nrec] = w; rh[nrec] = h; rPat[nrec] = pat; rOut[nrec] = out; rCnt[nrec] = cnt;
        nrec++;
    }

    void initRecipes() {
        int P = B_PLANKS, S = I_STICK;
        nrec = 0;
        rec(1, 1, new int[] { B_LOG }, B_PLANKS, 4);
        rec(1, 2, new int[] { P, P }, I_STICK, 4);
        rec(2, 2, new int[] { P, P, P, P }, B_TABLE, 1);
        rec(3, 3, new int[] { P, P, P, 0, S, 0, 0, S, 0 }, I_PICK, 1);
        rec(2, 3, new int[] { P, P, P, S, 0, S }, I_AXE, 1);
        rec(1, 3, new int[] { P, S, S }, I_SHOVEL, 1);
        rec(1, 3, new int[] { P, P, S }, I_SWORD, 1);
        rec(1, 2, new int[] { I_COAL, S }, B_TORCH, 4);
        int C = B_COBBLE;
        rec(3, 3, new int[] { C, C, C, C, 0, C, C, C, C }, B_FURNACE, 1);
        rec(3, 3, new int[] { P, P, P, P, 0, P, P, P, P }, B_CHEST, 1);
        rec(1, 2, new int[] { I_COAL, B_SAND }, I_GUNPOWDER, 2);
        int G = I_GUNPOWDER, SA = B_SAND;
        rec(3, 3, new int[] { SA, G, SA, G, SA, G, SA, G, SA }, B_TNT, 1);
        rec(2, 3, new int[] { P, P, P, P, P, P }, I_DOOR, 1);
        int[] mats = { B_COBBLE, I_IRON_INGOT, I_GOLD_INGOT, I_DIAMOND };
        for (int t = 0; t < 4; t++) {
            int M = mats[t], tb = I_TOOLS + t * 4;
            rec(3, 3, new int[] { M, M, M, 0, S, 0, 0, S, 0 }, tb, 1);
            rec(2, 3, new int[] { M, M, M, S, 0, S }, tb + 1, 1);
            rec(1, 3, new int[] { M, S, S }, tb + 2, 1);
            rec(1, 3, new int[] { M, M, S }, tb + 3, 1);
        }
        int ii = I_IRON_INGOT, gi = I_GOLD_INGOT, di = I_DIAMOND;
        rec(3, 3, new int[] { ii, ii, ii, ii, ii, ii, ii, ii, ii }, B_IRON_BLK, 1);
        rec(3, 3, new int[] { gi, gi, gi, gi, gi, gi, gi, gi, gi }, B_GOLD_BLK, 1);
        rec(3, 3, new int[] { di, di, di, di, di, di, di, di, di }, B_DIAM_BLK, 1);
        rec(1, 1, new int[] { B_IRON_BLK }, I_IRON_INGOT, 9);
        rec(1, 1, new int[] { B_GOLD_BLK }, I_GOLD_INGOT, 9);
        rec(1, 1, new int[] { B_DIAM_BLK }, I_DIAMOND, 9);
        int WL = I_WOOL;
        rec(3, 2, new int[] { WL, WL, WL, P, P, P }, B_BED, 1);
        rec(1, 2, new int[] { I_IRON_INGOT, I_COAL }, I_LIGHTER, 1);
        rec(1, 1, new int[] { B_LOG_S }, B_PLANKS_S, 4);
        rec(1, 1, new int[] { B_LOG_S + 1 }, B_PLANKS_S + 1, 4);
        rec(2, 3, new int[] { P, P, 0, S, 0, S }, I_HOE, 1);
        rec(3, 2, new int[] { I_IRON_INGOT, 0, I_IRON_INGOT, 0, I_IRON_INGOT, 0 }, I_BUCKET, 1);
        rec(3, 1, new int[] { I_WHEAT, I_WHEAT, I_WHEAT }, I_BREAD, 1);
    }

    // ---------------------------------------------------------------- mundo
    int hash(int x, int z, int salt) {
        int h = x * 374761393 + z * 668265263 + salt * 1274126177 + seed;
        h = (h ^ (h >>> 13)) * 1274126177;
        h = h ^ (h >>> 16);
        return h & 0x7fffffff;
    }

    float lat(int ix, int iz, int salt) {
        return (hash(ix, iz, salt) % 1000) / 1000f;
    }

    // ruido suave por interpolacion; shift = log2 del tamano de celda
    float noise(int x, int z, int shift, int salt) {
        int ix = x >> shift, iz = z >> shift;
        int mask = (1 << shift) - 1;
        float size = (float) (1 << shift);
        float u = (x & mask) / size, w = (z & mask) / size;
        u = u * u * (3f - 2f * u);
        w = w * w * (3f - 2f * w);
        float n00 = lat(ix, iz, salt), n10 = lat(ix + 1, iz, salt);
        float n01 = lat(ix, iz + 1, salt), n11 = lat(ix + 1, iz + 1, salt);
        float p = n00 + (n10 - n00) * u;
        float q = n01 + (n11 - n01) * u;
        return p + (q - p) * w;
    }

    int biomeAt(int x, int z) {
        if (noise(x, z, 6, 13) > 0.6f) return 1;
        if (noise(x, z, 6, 29) > 0.62f) return 2;
        if (noise(x, z, 6, 41) > 0.6f) return 3;
        if (noise(x, z, 6, 53) > 0.66f) return 4;
        return 0;
    }

    boolean desertAt(int x, int z) {
        return biomeAt(x, z) == 1;
    }

    boolean cactusAt(int x, int z) {
        if (hash(x, z, 10) % 36 != 0) return false;
        return Math.abs(x) + Math.abs(z) >= 5;
    }

    float riverV(int x, int z) {
        float r = noise(x, z, 6, 21) - 0.5f;
        return r < 0f ? -r : r;
    }

    int heightAt(int x, int z) {
        float v1 = noise(x, z, 3, 1), v2 = noise(x, z, 5, 2);
        int h = 12 + (int) (v1 * 4f + v2 * 6f);
        float cont = 0.38f - noise(x, z, 7, 61);
        if (cont > 0f) h -= (int) (cont * 40f);
        if (biomeAt(x, z) == 4) {
            h += (int) ((noise(x, z, 6, 53) - 0.66f) * 70f);
            if (h > H - 4) h = H - 4;
        } else {
            float rv = riverV(x, z);
            if (rv < 0.025f) {
                if (h > SEA - 2) h = SEA - 2;
            } else if (rv < 0.06f) {
                int cap = SEA + 1 + (int) ((rv - 0.025f) * 80f);
                if (h > cap) h = cap;
            }
        }
        if (h < 3) h = 3;
        return h;
    }

    int groundH(int x, int z) {
        return heightAt(x, z);
    }

    boolean treeAt(int x, int z) {
        int hh = hash(x, z, 7);
        if (hh % 12 != 0) return false;
        int bio = biomeAt(x, z);
        if (bio == 1 || bio == 4) return false;
        int mod = bio == 3 ? 12 : (bio == 2 ? 72 : 48);
        if (hh % mod != 0) return false;
        if (Math.abs(x) + Math.abs(z) < 5) return false;
        if (noise(x, z, 3, 1) > 0.82f) return false;
        return heightAt(x, z) > SEA + 1;
    }

    void putTree(byte[] d, int x0, int z0, int x, int y, int z, int b, boolean force) {
        if (y < 0 || y >= H) return;
        int lx = x - x0, lz = z - z0;
        if (lx < 0 || lx >= CS || lz < 0 || lz >= CS) return;
        int i = (lx * CS + lz) * H + y;
        if (force || d[i] == 0) d[i] = (byte) b;
    }

    // menas en vetas de 2x2x2: el tipo depende de la profundidad
    int oreAt(int x, int y, int z, int dep) {
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

    // Nether: suelo de roca con mar de lava, techo con piedra luminosa, lecho de roca arriba y abajo
    void genNether(int s, int cx, int cz) {
        byte[] d = chData[s];
        for (int i = 0; i < CHB; i++) d[i] = 0;
        int x0 = cx << 4, z0 = cz << 4;
        for (int lx = 0; lx < CS; lx++) {
            for (int lz = 0; lz < CS; lz++) {
                int x = x0 + lx, z = z0 + lz;
                int fl = 6 + (int) (noise(x, z, 4, 11) * 7f);
                int ce = 25 - (int) (noise(x, z, 4, 12) * 4f);
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
                    d[base + y] = (byte) blk;
                }
            }
        }
    }

    void genChunk(int s, int cx, int cz) {
        if (dim == 1) { genNether(s, cx, cz); return; }
        byte[] d = chData[s];
        for (int i = 0; i < CHB; i++) d[i] = 0;
        int x0 = cx << 4, z0 = cz << 4;
        for (int lx = 0; lx < CS; lx++) {
            for (int lz = 0; lz < CS; lz++) {
                int x = x0 + lx, z = z0 + lz;
                int h = heightAt(x, z);
                int bio = biomeAt(x, z);
                boolean chan = bio != 4 && riverV(x, z) < 0.025f;
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
                    d[base + y] = (byte) blk;
                }
                for (int y = h; y < SEA; y++) d[base + y] = (byte) ((bio == 2 && y == SEA - 1) ? B_ICE : B_WATER);
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
                int tv = treeVar(qx, qz);
                int top = groundH(qx, qz) - 1;
                int th = (tv == 1 ? 6 : 4) + (hash(qx, qz, 8) & 1);
                for (int dy = th - 2; dy <= th + 1; dy++) {
                    int r = (dy <= th - 1) ? 2 : 1;
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (r == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2
                                    && (hash(qx + dx, qz + dz, 9) & 1) == 0) continue;
                            if (dy == th + 1 && Math.abs(dx) + Math.abs(dz) > 1) continue;
                            putTree(d, x0, z0, qx + dx, top + dy, qz + dz, leavesOf(tv), false);
                        }
                    }
                }
                for (int dy = 1; dy <= th; dy++) putTree(d, x0, z0, qx, top + dy, qz, logOf(tv), true);
            }
        }
        if (villageFor(x0, z0)) buildVillage(d, x0, z0);
    }

    int chunkKey(int cx, int cz) {
        int kz = (dim == 1) ? (cz ^ 0x8000) : cz;   // el Nether usa claves distintas en el mismo almacen
        return ((cx & 0xFFFF) << 16) | (kz & 0xFFFF);
    }

    byte[] packChunk(byte[] d) {
        byte[] p = new byte[PB];
        System.arraycopy(d, 0, p, 0, PB);
        return p;
    }

    void unpackChunk(byte[] p, byte[] d) {
        System.arraycopy(p, 0, d, 0, PB);
    }

    void openStore() throws RecordStoreException {
        if (rs == null) rs = RecordStore.openRecordStore(curStore, true);
    }

    void closeStore() {
        if (rs != null) {
            try { rs.closeRecordStore(); } catch (Exception e) {}
            rs = null;
        }
    }

    // borra por completo el RecordStore del mundo actual
    void wipeWorldStore() {
        closeStore();
        idx.clear();
        pending.clear();
        pendBytes = 0;
        needFlush = false;
        storeBroken = false;
        hdrRid = 0;
        try { RecordStore.deleteRecordStore(curStore); } catch (Exception e) {}
        if (curWorld != null) { try { RecordStore.deleteRecordStore(chestStoreName()); } catch (Exception e) {} }
        clearChestTable();
    }

    boolean readStore(Integer k, byte[] d) {
        Integer rid = (Integer) idx.get(k);
        if (rid == null || storeBroken) return false;
        try {
            openStore();
            int n = rs.getRecord(rid.intValue(), rbuf, 0);
            if (n != PB) return false;
            unpackChunk(rbuf, d);
            return true;
        } catch (Exception e) {
            storeBroken = true;
            return false;
        }
    }

    // registro cabecera del mundo: semilla, posicion, vida, inventario e indice de chunks
    void writeHeader() {
        if (storeBroken) return;
        try {
            openStore();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bo);
            o.writeByte(4);
            o.writeInt(seed);
            o.writeFloat(px); o.writeFloat(py); o.writeFloat(pz);
            o.writeFloat(yaw); o.writeFloat(pitch);
            o.writeInt(health); o.writeInt(hunger); o.writeInt(sel); o.writeInt(layout);
            for (int i = 0; i < 36; i++) {
                o.writeByte(slId[i]);
                o.writeByte(slCnt[i]);
                o.writeShort(slDur[i]);
            }
            o.writeInt(idx.size());
            Enumeration en = idx.keys();
            while (en.hasMoreElements()) {
                Integer k = (Integer) en.nextElement();
                o.writeInt(k.intValue());
                o.writeInt(((Integer) idx.get(k)).intValue());
            }
            o.writeInt(worldTime);
            for (int i = 0; i < 3; i++) { o.writeByte(fId[i]); o.writeByte(fCnt[i]); }
            o.writeShort(fBurn); o.writeShort(fBurnMax); o.writeShort(fProg);
            o.writeByte(bedOn ? 1 : 0); o.writeInt(bedX); o.writeInt(bedY); o.writeInt(bedZ);
            o.writeByte(dim);
            o.flush();
            byte[] b = bo.toByteArray();
            if (hdrRid == 0) hdrRid = rs.addRecord(b, 0, b.length);
            else rs.setRecord(hdrRid, b, 0, b.length);
        } catch (Exception e) {
            storeBroken = true;
        }
    }

    boolean readHeader() {
        try {
            openStore();
            byte[] b = rs.getRecord(1);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
            int ver = in.readByte();
            if (ver < 1 || ver > 4) return false;
            seed = in.readInt();
            px = in.readFloat(); py = in.readFloat(); pz = in.readFloat();
            yaw = in.readFloat(); pitch = in.readFloat();
            health = in.readInt(); hunger = in.readInt(); sel = in.readInt(); layout = in.readInt();
            for (int i = 0; i < 36; i++) {
                slId[i] = in.readByte();
                slCnt[i] = in.readByte();
                slDur[i] = in.readShort();
                if (slId[i] < 0 || slId[i] > 79 || slCnt[i] < 0 || slCnt[i] > 64) { slId[i] = 0; slCnt[i] = 0; slDur[i] = 0; }
                if (slId[i] == 0) { slCnt[i] = 0; slDur[i] = 0; }
            }
            int n = in.readInt();
            for (int j = 0; j < n; j++) {
                int k = in.readInt();
                int rid = in.readInt();
                idx.put(new Integer(k), new Integer(rid));
            }
            if (ver >= 2) {
                worldTime = in.readInt();
                for (int i = 0; i < 3; i++) {
                    fId[i] = in.readByte();
                    fCnt[i] = in.readByte();
                    if (fId[i] < 0 || fId[i] > 79 || fCnt[i] < 0 || fCnt[i] > 64) { fId[i] = 0; fCnt[i] = 0; }
                    if (fId[i] == 0) fCnt[i] = 0;
                }
                fBurn = in.readShort(); fBurnMax = in.readShort(); fProg = in.readShort();
                if (worldTime < 0) worldTime = 0;
                if (fBurn < 0) fBurn = 0;
                if (fProg < 0 || fProg >= SMELT_T) fProg = 0;
            }
            if (ver >= 3) {
                bedOn = in.readByte() != 0;
                bedX = in.readInt(); bedY = in.readInt(); bedZ = in.readInt();
                if (bedY < 0 || bedY >= H) bedOn = false;
            }
            if (ver >= 4) {
                dim = in.readByte();
                if (dim < 0 || dim > 1) dim = 0;
            }
            if (health < 1) health = 1;
            if (health > 20) health = 20;
            if (hunger < 0) hunger = 0;
            if (hunger > 20) hunger = 20;
            if (sel < 0 || sel > 8) sel = 0;
            if (layout < 0 || layout > 1) layout = 0;
            hdrRid = 1;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // escribe todos los chunks pendientes y la cabecera; si falla, quedan en RAM
    void flushPending() {
        needFlush = false;
        if (storeBroken) return;
        if (hdrRid == 0) writeHeader();
        if (storeBroken) return;
        try {
            openStore();
            Enumeration en = pending.keys();
            while (en.hasMoreElements()) {
                Integer k = (Integer) en.nextElement();
                byte[] p = (byte[]) pending.get(k);
                Integer rid = (Integer) idx.get(k);
                if (rid != null) {
                    rs.setRecord(rid.intValue(), p, 0, PB);
                } else {
                    int id = rs.addRecord(p, 0, PB);
                    idx.put(k, new Integer(id));
                }
            }
            pending.clear();
            pendBytes = 0;
        } catch (Exception e) {
            storeBroken = true;
            return;
        }
        writeHeader();
    }

    // guarda todo el mundo: chunks modificados que estan en RAM + cabecera
    void saveAll() {
        if (!inWorld) return;
        storeBroken = false;
        for (int s = 0; s < nch; s++) {
            if (chCx[s] != NONE && chDirty[s]) {
                Object old = pending.put(new Integer(chunkKey(chCx[s], chCz[s])), packChunk(chData[s]));
                if (old == null) pendBytes += PB;
                chDirty[s] = false;
            }
        }
        flushPending();
        if (chestTabDirty) chestSaveTable();
    }

    void unloadSlot(int s) {
        if (chCx[s] == NONE) return;
        if (chDirty[s]) {
            Object old = pending.put(new Integer(chunkKey(chCx[s], chCz[s])), packChunk(chData[s]));
            if (old == null) pendBytes += PB;
            chDirty[s] = false;
            if (pendBytes >= SAVE_LIMIT && !storeBroken) needFlush = true;
        }
        chCx[s] = NONE;
        chCz[s] = NONE;
    }

    void loadChunk(int s, int cx, int cz) {
        unloadSlot(s);
        Integer k = new Integer(chunkKey(cx, cz));
        boolean fresh = false;
        byte[] p = (byte[]) pending.get(k);
        if (p != null) {
            pending.remove(k);
            pendBytes -= PB;
            unpackChunk(p, chData[s]);
            chDirty[s] = true;
        } else if (readStore(k, chData[s])) {
            chDirty[s] = false;
        } else {
            genChunk(s, cx, cz);
            chDirty[s] = false;
            fresh = true;
        }
        chCx[s] = cx;
        chCz[s] = cz;
        if (fresh && !quietLoad) spawnInChunk(cx, cz);
    }

    // ventana de chunks: nc x nc en RAM (2x2 hasta distancia 7, 3x3 para distancias mayores)
    void setupWindow(int newRd) {
        rd = newRd;
        nb = 2 * rd + 1;
        cullSq = 2f * (rd + 1) * (rd + 1) + 400f;
        int nn = (rd <= 7) ? 2 : 3;
        if (chData == null || nn != nc) {
            chData = null;
            nc = nn;
            nch = nc * nc;
            chData = new byte[nch][];
            chCx = new int[nch];
            chCz = new int[nch];
            chDirty = new boolean[nch];
            for (int i = 0; i < nch; i++) {
                chData[i] = new byte[CHB];
                chCx[i] = NONE;
                chCz[i] = NONE;
            }
        }
        ox = new int[nb];
        oz = new int[nb];
        invX = new int[nb];
        invZ = new int[nb];
        buildOrder(ox, nb, rd);
        buildOrder(oz, nb, rd);
        for (int i = 0; i < nb; i++) { invX[ox[i]] = i; invZ[oz[i]] = i; }
    }

    // origen de la ventana en un eje: se mantiene mientras cubra el radio de dibujo, si no se recentra
    int pickOrigin(int cur, int b, boolean force) {
        if (!force && b - rd >= (cur << 4) && b + rd <= ((cur + nc) << 4) - 1) return cur;
        return ifloor(b / 16f - nc / 2f + 0.5f);
    }

    void updateWindow(boolean force) {
        ccx = pickOrigin(ccx, ifloor(px), force);
        ccz = pickOrigin(ccz, ifloor(pz), force);
    }

    // carga hasta max chunks faltantes alrededor de (ccx,ccz); devuelve cuantos cargo
    int loadMissing(int max) {
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

    void dropOrphanMobs() {
        for (int i = mobs.size() - 1; i >= 0; i--) {
            Mob m = (Mob) mobs.elementAt(i);
            if (chunkFor(ifloor(m.x), ifloor(m.z)) == null) mobs.removeElementAt(i);
        }
    }

    void spawnMobs() {
        mobs.removeAllElements();
        bright = dayBright();
        for (int i = 0; i < 7; i++) spawnMob(0, false);
        for (int i = 0; i < 6; i++) spawnMob(1, false);
        for (int i = 0; i < 5; i++) spawnMob(2, false);
        if (bright < 70) for (int i = 0; i < 3; i++) spawnMob(3 + rnd(2), false);
    }

    boolean spawnAt(int type, int x, int z) {
        if (mobs.size() >= 40) return false;
        if (chunkFor(x, z) == null) return false;
        if (dim == 1) {   // nether: buscar suelo firme dentro de la caverna
            if (type < 3) return false;
            for (int y = 24; y > 3; y--) {
                if (blockAt(x, y, z) == 0 && blockAt(x, y + 1, z) == 0 && solidAt(x, y - 1, z)) {
                    Mob nm = new Mob();
                    nm.type = type;
                    nm.x = x + 0.5f; nm.z = z + 0.5f; nm.y = y + 0.001f;
                    nm.ang = rndf() * 6.2832f;
                    nm.hp = mobHp(type);
                    nm.timer = 10 + rnd(40);
                    mobs.addElement(nm);
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
        Mob m = new Mob();
        m.type = type;
        m.x = x + 0.5f; m.z = z + 0.5f; m.y = top + 1.001f;
        m.ang = rndf() * 6.2832f;
        m.hp = mobHp(type);
        m.timer = 10 + rnd(40);
        mobs.addElement(m);
        return true;
    }

    void spawnMob(int type, boolean far) {
        int bx = ifloor(px), bz = ifloor(pz);
        for (int tries = 0; tries < 30; tries++) {
            int x = bx - 8 + rnd(17), z = bz - 8 + rnd(17);
            if (far) {
                float dx = x + 0.5f - px, dz = z + 0.5f - pz;
                if (dx * dx + dz * dz < 36f) continue;
            }
            if (spawnAt(type, x, z)) return;
        }
    }

    // animales para un chunk recien generado (0 a 2)
    void spawnInChunk(int cx, int cz) {
        if (dim == 1) return;
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

    void resetPlayer() {
        int sx = 0, sz = 0;
        boolean found = heightAt(0, 0) > SEA + 1;
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

    void clearKeys() {
        for (int i = 0; i < kd.length; i++) kd[i] = false;
        starTicks = 0; starConsumed = false; mineProg = 0; mineX = -1;
    }

    void resetPlayerState() {
        for (int i = 0; i < NSLOT; i++) { slId[i] = 0; slCnt[i] = 0; slDur[i] = 0; }
        heldId = 0; heldCnt = 0; heldDur = 0;
        sel = 0; health = 20; hunger = 20;
        hungerTimer = 0; regenTimer = 0; starveTimer = 0; dmgFlash = 0;
        layout = 0;
        worldTime = 1000; bright = 100;
        for (int i = 0; i < 3; i++) { fId[i] = 0; fCnt[i] = 0; }
        fBurn = 0; fBurnMax = 0; fProg = 0;
        sapN = 0; torchN = 0; hostTimer = 0;
        bedOn = false; msgT = 0; flying = false;
        dim = 0; portalT = 0; portalLock = false; lavaT = 0;
        hungerFx = 0; cactusT = 0; air = 300; airT = 0;
    }

    // entra a un mundo (nuevo o guardado); se llama desde el hilo del juego con el lock tomado
    void enterWorld(WorldInfo w) {
        closeStore();
        idx.clear();
        pending.clear();
        pendBytes = 0;
        needFlush = false;
        needSave = false;
        storeBroken = false;
        hdrRid = 0;
        inWorld = false;
        curWorld = w;
        curStore = cat("mcw", w.id);
        creative = (w.mode == 1);
        flying = false;
        seed = w.seed;
        for (int i = 0; i < nch; i++) { chCx[i] = NONE; chCz[i] = NONE; chDirty[i] = false; }
        mobs.removeAllElements();
        resetPlayerState();
        boolean ok = readHeader();
        if (!ok) {
            wipeWorldStore();
            resetPlayerState();
            seed = w.seed;
        }
        loadChestTable();
        inWorld = true;
        boolean sane = ok && py >= 0f && py <= H + 10f && Math.abs(px) < 1000000f && Math.abs(pz) < 1000000f;
        if (sane) {
            updateWindow(true);
            loadMissing(nch);
            vy = 0; fallDist = 0; onGround = false;
            clearKeys();
            int g = 0;
            while (g++ < H && boxCollides(px, py, pz, HW, 1.8f)) py += 1f;
        } else {
            resetPlayer();
        }
        spawnMobs();
        if (!ok) writeHeader();
        autoT = 0;
        state = ST_PLAY;
    }

    // guarda y sale del mundo actual
    void leaveWorld() {
        if (inWorld) saveAll();
        inWorld = false;
        closeStore();
        idx.clear();
        pending.clear();
        pendBytes = 0;
        needFlush = false;
        needSave = false;
        for (int i = 0; i < nch; i++) { chCx[i] = NONE; chCz[i] = NONE; chDirty[i] = false; }
        mobs.removeAllElements();
        curWorld = null;
    }

    // aplica la distancia de renderizado elegida (reorganiza la ventana de chunks)
    void applyRd() {
        if (rdApplied == rdIdx) return;
        rdApplied = rdIdx;
        saveSettings();
        for (int s = 0; s < nch; s++) unloadSlot(s);
        setupWindow(RD_OPT[rdIdx]);
        if (inWorld) {
            updateWindow(true);
            loadMissing(nch);
            if (needFlush) flushPending();
        }
    }

    // ---------------------------------------------------------------- lista de mundos y ajustes (RecordStore)
    byte[] enc(WorldInfo w) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bo);
            o.writeInt(w.id);
            o.writeInt(w.seed);
            o.writeUTF(w.name);
            o.writeByte(w.mode);
            o.flush();
            return bo.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    void loadWorldList() {
        worlds.removeAllElements();
        try { RecordStore.deleteRecordStore(OLD_STORE); } catch (Exception e) {}   // formato viejo, no se puede continuar
        RecordStore r = null;
        try {
            r = RecordStore.openRecordStore(LIST_STORE, true);
            RecordEnumeration en = r.enumerateRecords(null, null, false);
            while (en.hasNextElement()) {
                int rid = en.nextRecordId();
                try {
                    DataInputStream in = new DataInputStream(new ByteArrayInputStream(r.getRecord(rid)));
                    WorldInfo w = new WorldInfo();
                    w.rid = rid;
                    w.id = in.readInt();
                    w.seed = in.readInt();
                    w.name = in.readUTF();
                    if (in.available() > 0) w.mode = in.readByte();
                    int p = 0;
                    while (p < worlds.size() && ((WorldInfo) worlds.elementAt(p)).id < w.id) p++;
                    worlds.insertElementAt(w, p);
                } catch (Exception e) {}
            }
            en.destroy();
        } catch (Exception e) {}
        if (r != null) { try { r.closeRecordStore(); } catch (Exception e) {} }
    }

    int nextId() {
        int id = 1;
        for (int i = 0; i < worlds.size(); i++) {
            int v = ((WorldInfo) worlds.elementAt(i)).id;
            if (v >= id) id = v + 1;
        }
        return id;
    }

    WorldInfo addWorld(String name, int sd, int mode) {
        WorldInfo w = new WorldInfo();
        w.id = nextId();
        w.name = name;
        w.seed = sd;
        w.mode = mode;
        RecordStore r = null;
        try {
            r = RecordStore.openRecordStore(LIST_STORE, true);
            byte[] b = enc(w);
            w.rid = r.addRecord(b, 0, b.length);
        } catch (Exception e) {}
        if (r != null) { try { r.closeRecordStore(); } catch (Exception e) {} }
        worlds.addElement(w);
        return w;
    }

    void deleteWorld(WorldInfo w) {
        try { RecordStore.deleteRecordStore(cat("mcw", w.id)); } catch (Exception e) {}
        try { RecordStore.deleteRecordStore(cat("mcc", w.id)); } catch (Exception e) {}
        RecordStore r = null;
        try {
            r = RecordStore.openRecordStore(LIST_STORE, true);
            r.deleteRecord(w.rid);
        } catch (Exception e) {}
        if (r != null) { try { r.closeRecordStore(); } catch (Exception e) {} }
        worlds.removeElement(w);
    }

    void loadSettings() {
        RecordStore r = null;
        try {
            r = RecordStore.openRecordStore(CFG_STORE, true);
            if (r.getNumRecords() > 0) {
                byte[] b = r.getRecord(1);
                if (b.length > 0 && b[0] >= 0 && b[0] < RD_OPT.length) rdIdx = b[0];
                if (b.length > 1 && (b[1] == 0 || b[1] == 1)) lang = b[1];
            }
        } catch (Exception e) {}
        if (r != null) { try { r.closeRecordStore(); } catch (Exception e) {} }
        rdApplied = rdIdx;
    }

    void saveSettings() {
        RecordStore r = null;
        try {
            r = RecordStore.openRecordStore(CFG_STORE, true);
            byte[] b = new byte[] { (byte) rdIdx, (byte) lang };
            if (r.getNumRecords() > 0) r.setRecord(1, b, 0, 2);
            else r.addRecord(b, 0, 2);
        } catch (Exception e) {}
        if (r != null) { try { r.closeRecordStore(); } catch (Exception e) {} }
    }

    // ---------------------------------------------------------------- navegacion de menus
    boolean isMenu() {
        return state == ST_MENU || state >= ST_PAUSE;
    }

    void buildNav() {
        if (state == ST_MENU) navIds = new int[] { A_PLAY, A_LANG, A_QUIT };
        else if (state == ST_PAUSE) navIds = new int[] { A_RESUME, A_SETTINGS, A_TOMENU };
        else if (state == ST_SET) navIds = new int[] { A_RD, A_SETBACK };
        else if (state == ST_CREATE) navIds = new int[] { A_CNAME, A_CSEED, A_CMODE, A_CGO, A_CBACK };
        else if (state == ST_CONFIRM) navIds = new int[] { A_YES, A_NO };
        else if (state == ST_WORLDS) {
            int n = worlds.size();
            navIds = new int[n + 2];
            navIds[0] = A_NEW;
            for (int i = 0; i < n; i++) navIds[i + 1] = 100 + i;
            navIds[n + 1] = A_WBACK;
        } else navIds = new int[] { 0 };
        navN = navIds.length;
        navSel = 0;
    }

    void go(int st) {
        state = st;
        wScroll = 0;
        buildNav();
    }

    void navMove(int d) {
        if (navN <= 0) return;
        navSel = (navSel + d + navN) % navN;
        if (state == ST_WORLDS) {
            int i = navSel - 1;
            if (i >= 0 && i < worlds.size()) {
                if (i < wScroll) wScroll = i;
                if (i >= wScroll + wVis) wScroll = i - wVis + 1;
            }
        }
    }

    void openMain() {
        go(ST_MENU);
    }

    void openWorlds() {
        loadWorldList();
        go(ST_WORLDS);
        navSel = (worlds.size() > 0) ? 1 : 0;
    }

    void openCreate() {
        cName = cat(L("Mundo "), nextId());
        cSeedTxt = "";
        cMode = 0;
        go(ST_CREATE);
        navSel = 3;
    }

    void openPause() {
        clearKeys();
        go(ST_PAUSE);
        job = J_SAVE;
    }

    void closeSettings() {
        go(ST_PAUSE);
        navSel = 1;
        if (rdIdx != rdApplied) job = J_APPLY;
    }

    void rdStep(int d) {
        rdIdx = (rdIdx + d + RD_OPT.length) % RD_OPT.length;
    }

    void askDelete(int i) {
        if (i < 0 || i >= worlds.size()) return;
        delTarget = (WorldInfo) worlds.elementAt(i);
        go(ST_CONFIRM);
        navSel = 1;
    }

    void doCreate() {
        int sd;
        if (cSeedTxt.length() == 0) {
            sd = rand.nextInt();
        } else {
            try { sd = Integer.parseInt(cSeedTxt); } catch (Exception e) { sd = cSeedTxt.hashCode(); }
        }
        jobWorld = addWorld(cName, sd, cMode);
        job = J_ENTER;
    }

    // abre el editor de texto del sistema (nombre o semilla)
    void editText(int which) {
        editing = which;
        TextBox tb = new TextBox(L(which == 1 ? "Nombre del mundo" : "Semilla (vacio = aleatoria)"),
                which == 1 ? cName : cSeedTxt, which == 1 ? 20 : 12, TextField.ANY);
        tb.addCommand(cmdOk);
        tb.addCommand(cmdCancel);
        tb.setCommandListener(this);
        disp.setCurrent(tb);
    }

    public void commandAction(Command c, Displayable d) {
        synchronized (lock) {
            if (c == cmdOk && d instanceof TextBox) {
                String s = ((TextBox) d).getString().trim();
                if (editing == 1) cName = (s.length() == 0) ? cat(L("Mundo "), nextId()) : s;
                else cSeedTxt = s;
            }
        }
        setFullScreenMode(true);
        disp.setCurrent(this);
    }

    void activate(int id) {
        if (id >= 200) { askDelete(id - 200); return; }
        if (id >= 100) {
            int i = id - 100;
            if (i < worlds.size()) {
                jobWorld = (WorldInfo) worlds.elementAt(i);
                job = J_ENTER;
            }
            return;
        }
        switch (id) {
            case A_PLAY: openWorlds(); break;
            case A_QUIT: midlet.quit(); break;
            case A_LANG: lang = 1 - lang; applyLang(); saveSettings(); break;
            case A_RESUME: state = ST_PLAY; clearKeys(); break;
            case A_SETTINGS: go(ST_SET); break;
            case A_TOMENU: job = J_LEAVE; break;
            case A_RD: rdStep(1); break;
            case A_SETBACK: closeSettings(); break;
            case A_NEW: openCreate(); break;
            case A_WBACK: openMain(); break;
            case A_CNAME: editText(1); break;
            case A_CSEED: editText(2); break;
            case A_CMODE: cMode = 1 - cMode; break;
            case A_CGO: doCreate(); break;
            case A_CBACK: openWorlds(); break;
            case A_YES:
                if (delTarget != null) deleteWorld(delTarget);
                delTarget = null;
                openWorlds();
                break;
            case A_NO: openWorlds(); break;
            default: break;
        }
    }

    void menuBack() {
        if (state == ST_PAUSE) { state = ST_PLAY; clearKeys(); }
        else if (state == ST_SET) closeSettings();
        else if (state == ST_WORLDS) openMain();
        else if (state == ST_CREATE || state == ST_CONFIRM) openWorlds();
    }

    void menuKey(int k) {
        if (k == 2) navMove(-1);
        else if (k == 8) navMove(1);
        else if (k == 5) activate(navIds[navSel]);
        else if (k == 4 || k == 6) {
            if (navIds[navSel] == A_RD) rdStep(k == 6 ? 1 : -1);
        } else if (k == 10 && state == ST_WORLDS) {
            int id = navIds[navSel];
            if (id >= 100 && id < 200) activate(id + 100);
        } else if (k == 0 || k == 12 || k == 13) menuBack();
    }

    void respawn() {
        if (dim != 0) switchDim(0);   // al morir en el Nether se vuelve al Overworld
        boolean atBed = false;
        if (bedOn) {
            px = bedX + 0.5f; pz = bedZ + 0.5f;
            updateWindow(true);
            loadMissing(nch);
            if (blockAt(bedX, bedY, bedZ) == B_BED) {
                int[] dxs = { 1, -1, 0, 0 }, dzs = { 0, 0, 1, -1 };
                boolean placed = false;
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

    // ---------------------------------------------------------------- teclas
    int mapKey(int keyCode) {
        if (keyCode >= KEY_NUM0 && keyCode <= KEY_NUM9) return keyCode - KEY_NUM0;
        // L / R (teclas laterales o soft keys): 12 = L, 13 = R. Cualquiera de las dos abre el menu de pausa
        if (keyCode == 'l' || keyCode == 'L' || keyCode == -6 || keyCode == -21) return 12;
        if (keyCode == 'r' || keyCode == 'R' || keyCode == -7 || keyCode == -22) return 13;
        if (keyCode == KEY_STAR) return 10;
        if (keyCode == KEY_POUND) return 11;
        try {
            String kn = getKeyName(keyCode);
            if (kn != null) {
                kn = kn.toUpperCase();
                if (kn.indexOf("SOFT") >= 0) return (kn.indexOf("RIGHT") >= 0 || kn.indexOf('2') >= 0) ? 13 : 12;
            }
        } catch (Exception e) {}
        int a;
        try { a = getGameAction(keyCode); } catch (Exception e) { return -1; }
        if (a == UP) return 2;
        if (a == DOWN) return 8;
        if (a == LEFT) return 4;
        if (a == RIGHT) return 6;
        if (a == FIRE) return 5;
        return -1;
    }

    protected void keyPressed(int keyCode) {
        if (busy != null) return;
        int k = mapKey(keyCode);
        if (k < 0) return;
        synchronized (lock) {
            if (state == ST_PLAY) {
                if (k == 12 || k == 13) { openPause(); return; }
                kd[k] = true;
                if (creative && k == (layout == 0 ? 5 : 9)) {   // doble toque de salto: volar / aterrizar
                    if (tick - jumpTick < 8) { flying = !flying; vy = 0; fallDist = 0; jumpTick = -100; }
                    else jumpTick = tick;
                }
                if (k == 0) openInv(2);
                else if (k == 11) layout = 1 - layout;
                else if (k == 1) { sel = (sel + 8) % 9; labelT = 40; }
                else if (k == 3) { sel = (sel + 1) % 9; labelT = 40; }
                else if (k == 10) { starTicks = 1; starConsumed = false; mineProg = 0; }
            } else if (state == ST_DEAD) {
                if (k == 5) respawn();
            } else if (state == ST_INV) {
                if (k == 0 || k == 12 || k == 13) closeInv();
                else if (craftN == TRADE && (k == 1 || k == 3)) tradeMove(k == 3 ? 1 : -1);
                else if (palMode() && (k == 1 || k == 3)) palFlip(k == 3 ? 1 : -1);
                else if (k == 2 || k == 4 || k == 6 || k == 8) moveCursor(k);
                else if (k == 5) click(cursor);
                else if (k == 10) clickSecondary(cursor);
            } else {
                menuKey(k);
            }
        }
    }

    protected void keyRepeated(int keyCode) {
        if (busy != null) return;
        int k = mapKey(keyCode);
        if (k < 0) return;
        synchronized (lock) {
            if (state == ST_INV && (k == 2 || k == 4 || k == 6 || k == 8)) moveCursor(k);
            else if (isMenu() && (k == 2 || k == 8)) menuKey(k);
        }
    }

    protected void keyReleased(int keyCode) {
        int k = mapKey(keyCode);
        if (k < 0) return;
        synchronized (lock) {
            kd[k] = false;
            if (k == 10) {
                if (state == ST_PLAY && !starConsumed && starTicks > 0 && starTicks <= 6) doTap();
                starTicks = 0; starConsumed = false; mineProg = 0; mineX = -1;
            }
        }
    }

    // tactil: tocar un boton lo activa, arrastrar desplaza la lista de mundos
    protected void pointerPressed(int x, int y) {
        if (busy != null) return;
        synchronized (lock) {
            tpDown = true;
            tpDrag = false;
            tpX = x;
            tpY = y;
            tpScroll0 = wScroll;
        }
    }

    protected void pointerDragged(int x, int y) {
        synchronized (lock) {
            if (!tpDown) return;
            if (Math.abs(y - tpY) > 8) tpDrag = true;
            if (tpDrag && state == ST_WORLDS) {
                int step = btnH + 3;
                if (step < 4) step = 4;
                wScroll = tpScroll0 + (tpY - y) / step;
            }
        }
    }

    protected void pointerReleased(int x, int y) {
        if (busy != null) return;
        synchronized (lock) {
            if (!tpDown) return;
            tpDown = false;
            if (tpDrag || !isMenu()) return;
            for (int i = 0; i < htN; i++) {
                if (tpX >= htX[i] && tpX < htX[i] + htW[i] && tpY >= htY[i] && tpY < htY[i] + htH[i]) {
                    for (int j = 0; j < navN; j++) if (navIds[j] == htId[i]) navSel = j;
                    activate(htId[i]);
                    return;
                }
            }
        }
    }

    // ---------------------------------------------------------------- inventario
    int addItem(int id, int cnt, int dur) {
        int left = cnt;
        if (maxStack(id) > 1) {
            for (int i = 0; i < 36; i++) {
                if (slId[i] == id && slCnt[i] < 64) {
                    int mv = Math.min(64 - slCnt[i], left);
                    slCnt[i] += mv;
                    left -= mv;
                    if (left <= 0) return 0;
                }
            }
        }
        for (int i = 0; i < 36; i++) {
            if (slId[i] == 0) {
                int put = (maxStack(id) > 1) ? Math.min(64, left) : 1;
                slId[i] = id;
                slCnt[i] = put;
                slDur[i] = isTool(id) ? (dur > 0 ? dur : toolMax(id)) : 0;
                left -= put;
                if (left <= 0) return 0;
            }
        }
        return left;
    }

    // paleta del creativo: reemplaza los 27 slots principales del inventario (tecla 1/3 cambia de pagina)
    void initPalette() {
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
    }

    boolean palMode() { return creative && state == ST_INV && craftN == 2; }

    int palAt(int slot) {
        int k = palPage * 27 + (slot - 9);
        return (k >= 0 && k < palN) ? palIds[k] : 0;
    }

    void palFlip(int d) {
        int pages = (palN + 26) / 27;
        palPage = (palPage + d + pages) % pages;
    }

    void palTake(int slot, int cnt) {
        int pid = palAt(slot);
        if (pid == 0) return;
        if (heldId == pid && cnt > 1) { heldId = 0; heldCnt = 0; heldDur = 0; return; }   // mismo item: soltarlo
        if (heldId == pid && cnt == 1 && maxStack(pid) > 1) { if (heldCnt < 64) heldCnt++; return; }
        heldId = pid;
        heldCnt = (maxStack(pid) > 1) ? cnt : 1;
        heldDur = isTool(pid) ? toolMax(pid) : 0;
    }

    void clearSlot(int i) {
        slId[i] = 0; slCnt[i] = 0; slDur[i] = 0;
    }

    void openInv(int n) {
        craftN = n;
        state = ST_INV;
        clearKeys();
        cursor = sel;
        if (n == FURN) syncToSlots();
        layoutInv();
        updateOutput();
    }

    void closeInv() {
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

    void layoutInv() {
        int s = Math.min((sw - 8) / 9, (sh - 40) / 7);
        if (s > 30) s = 30;
        if (s < 10) s = 10;
        slS = s;
        int x0 = (sw - 9 * s) / 2;
        int y0 = 14;
        int ym = y0 + 3 * s + 8;
        int yh = ym + 3 * s + 4;
        for (int i = 0; i < NSLOT; i++) slActive[i] = false;
        for (int i = 0; i < 9; i++) {
            slx[i] = x0 + i * s; sly[i] = yh; slActive[i] = true;
        }
        for (int i = 9; i < 36; i++) {
            int r = (i - 9) / 9, c = (i - 9) % 9;
            slx[i] = x0 + c * s; sly[i] = ym + r * s; slActive[i] = true;
        }
        if (craftN == CHEST) {   // 27 slots del cofre arriba (3 filas), inventario abajo
            for (int i = 0; i < CHN; i++) {
                slx[CH0 + i] = x0 + (i % 9) * s; sly[CH0 + i] = y0 + (i / 9) * s; slActive[CH0 + i] = true;
            }
            return;
        }
        int n = (craftN == FURN || craftN == TRADE) ? 0 : craftN;
        int oy = (n == 2) ? s / 2 : 0;
        int gx0 = x0 + s + ((n == 2) ? s / 2 : 0);
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int idx = 36 + r * n + c;
                slx[idx] = gx0 + c * s; sly[idx] = y0 + oy + r * s; slActive[idx] = true;
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

    void moveCursor(int k) {
        int c = cursor, best = -1, bs = 0x7fffffff;
        for (int j = 0; j < NSLOT; j++) {
            if (!slActive[j] || j == c) continue;
            int dx = slx[j] - slx[c], dy = sly[j] - sly[c];
            boolean ok;
            if (k == 2) ok = dy < 0;
            else if (k == 8) ok = dy > 0;
            else if (k == 4) ok = dx < 0;
            else ok = dx > 0;
            if (!ok) continue;
            int score;
            if (k == 2 || k == 8) score = Math.abs(dy) + 3 * Math.abs(dx);
            else score = Math.abs(dx) + 3 * Math.abs(dy);
            if (score < bs) { bs = score; best = j; }
        }
        if (best >= 0) cursor = best;
    }

    int findRecipe() {
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
            boolean ok = true, okm = true;
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

    void updateOutput() {
        if (craftN == TRADE) { tradeOutput(); return; }
        int k = findRecipe();
        if (k < 0) { clearSlot(OUT); return; }
        slId[OUT] = rOut[k];
        slCnt[OUT] = rCnt[k];
        slDur[OUT] = isTool(rOut[k]) ? toolMax(rOut[k]) : 0;
    }

    void consumeGrid() {
        for (int i = 0; i < craftN * craftN; i++) {
            int s = 36 + i;
            if (slId[s] != 0) {
                slCnt[s]--;
                if (slCnt[s] <= 0) clearSlot(s);
            }
        }
    }

    void takeOutput() {
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

    void click(int idx) {
        if (palMode() && idx >= 9 && idx < 36) { palTake(idx, 64); return; }
        if (craftN == FURN && idx == 38) { takeFurnaceOut(); return; }
        if (idx == OUT) { takeOutput(); return; }
        int id = slId[idx], cnt = slCnt[idx], dur = slDur[idx];
        if (heldId == 0) {
            if (id != 0) {
                heldId = id; heldCnt = cnt; heldDur = dur;
                clearSlot(idx);
            }
        } else if (id == 0) {
            slId[idx] = heldId; slCnt[idx] = heldCnt; slDur[idx] = heldDur;
            heldId = 0; heldCnt = 0; heldDur = 0;
        } else if (id == heldId && maxStack(id) > 1) {
            int mv = Math.min(64 - cnt, heldCnt);
            slCnt[idx] += mv;
            heldCnt -= mv;
            if (heldCnt <= 0) { heldId = 0; heldCnt = 0; heldDur = 0; }
        } else {
            slId[idx] = heldId; slCnt[idx] = heldCnt; slDur[idx] = heldDur;
            heldId = id; heldCnt = cnt; heldDur = dur;
        }
        if (idx >= 36) updateOutput();
    }

    void clickSecondary(int idx) {
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
            boolean empty = (slId[idx] == 0);
            boolean same = (slId[idx] == heldId && slCnt[idx] < 64 && maxStack(heldId) > 1);
            if (empty || same) {
                if (empty) { slId[idx] = heldId; slDur[idx] = heldDur; slCnt[idx] = 0; }
                slCnt[idx]++;
                heldCnt--;
                if (heldCnt <= 0) { heldId = 0; heldCnt = 0; heldDur = 0; }
            }
        }
        if (idx >= 36) updateOutput();
    }

    // ---------------------------------------------------------------- logica
    void update() {
        synchronized (lock) {
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
            if (dim == 0 && tick % 200 == 0) growCrops();
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
    }

    void hurt(int n) {
        if (n <= 0 || creative) return;
        health -= n;
        dmgFlash = 6;
        if (health <= 0) {
            health = 0;
            state = ST_DEAD;
            clearKeys();
        }
    }

    void updatePlayer() {
        boolean fw, bk, jp, up, down;
        if (layout == 0) {
            fw = kd[2]; bk = kd[8]; jp = kd[5]; up = kd[7]; down = kd[9];
        } else {
            fw = kd[5]; bk = kd[7]; jp = kd[9]; up = kd[2]; down = kd[8];
        }
        if (kd[4]) yaw -= LOOK;
        if (kd[6]) yaw += LOOK;
        if (up) pitch += LOOK;
        if (down) pitch -= LOOK;
        if (pitch > 1.4f) pitch = 1.4f;
        if (pitch < -1.4f) pitch = -1.4f;

        float sn = (float) Math.sin(yaw), cs = (float) Math.cos(yaw);
        if (creative && flying) {
            // vuelo: avanzar sigue la mirada (arriba/abajo), saltar sube recto
            float sp2 = SPD * 1.6f;
            float cpc = (float) Math.cos(pitch), spc = (float) Math.sin(pitch);
            float fmx = 0, fmy = 0, fmz = 0;
            if (fw) { fmx += sn * cpc * sp2; fmz += cs * cpc * sp2; fmy += spc * sp2; }
            if (bk) { fmx -= sn * cpc * sp2; fmz -= cs * cpc * sp2; fmy -= spc * sp2; }
            if (jp) fmy += sp2;
            if (fmx != 0 && !boxCollides(px + fmx, py, pz, HW, 1.8f)) px += fmx;
            if (fmz != 0 && !boxCollides(px, py, pz + fmz, HW, 1.8f)) pz += fmz;
            if (fmy != 0 && !boxCollides(px, py + fmy, pz, HW, 1.8f)) py += fmy;
            if (py < 1f) py = 1f;
            vy = 0; fallDist = 0; onGround = false;
            moving = (fw || bk);
            return;
        }
        float mx = 0, mz = 0;
        if (fw) { mx += sn * SPD; mz += cs * SPD; }
        if (bk) { mx -= sn * SPD; mz -= cs * SPD; }
        moving = (fw || bk);
        boolean inW = waterAtF(px, py + 0.4f, pz) || waterAtF(px, py + 1.2f, pz)
                || lavaAtF(px, py + 0.4f, pz) || lavaAtF(px, py + 1.2f, pz);
        if (inW) { mx *= 0.5f; mz *= 0.5f; }

        boolean blockedX = mx != 0 && boxCollides(px + mx, py, pz, HW, 1.8f);
        boolean blockedZ = mz != 0 && boxCollides(px, py, pz + mz, HW, 1.8f);
        if (mx != 0 && !blockedX) px += mx;
        if (mz != 0 && !blockedZ) pz += mz;
        boolean autoJump = false;
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
            fallDist = 0f;
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
                if (fallDist > 3f) hurt((int) Math.ceil(fallDist - 3f));
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

    void updateMobs() {
        for (int i = mobs.size() - 1; i >= 0; i--) {
            Mob m = (Mob) mobs.elementAt(i);
            updateMob(m);
            if (m.type == 5 && ++m.fuse >= 80) explode(m);
            if ((m.type == 3 || m.type == 4) && bright >= 90 && m.hp > 0 && (tick % 20) == 0) { m.hp--; m.hurt = 6; }
            if (m.hp <= 0) mobs.removeElementAt(i);
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

    int countMobs(boolean hostile) {
        int n = 0;
        for (int i = 0; i < mobs.size(); i++) {
            Mob m = (Mob) mobs.elementAt(i);
            if (m.type == 5 || m.type == 6) continue;
            if ((m.type >= 3) == hostile) n++;
        }
        return n;
    }

    void updateMob(Mob m) {
        float hw = mobHW(m.type);
        float mh = mobH(m.type);
        if (m.hurt > 0) m.hurt--;
        if (m.flee > 0) m.flee--;
        boolean chase = (m.type == 3 || m.type == 4) && m.flee <= 0 && hostileAI(m);
        if (!chase && m.type != 5 && --m.timer <= 0) {
            if (rnd(3) == 0) { m.moving = false; m.timer = 20 + rnd(40); }
            else { m.moving = true; m.ang = rndf() * 6.2832f; m.timer = 20 + rnd(60); }
        }
        if (m.moving || m.flee > 0) {
            float sp = (m.flee > 0) ? 0.10f : mobSpeed(m.type);
            float mx = (float) Math.sin(m.ang) * sp, mz = (float) Math.cos(m.ang) * sp;
            boolean blocked = false;
            if (!boxCollides(m.x + mx, m.y, m.z, hw, mh)) m.x += mx; else blocked = true;
            if (!boxCollides(m.x, m.y, m.z + mz, hw, mh)) m.z += mz; else blocked = true;
            if (blocked) {
                if (m.ground) m.vy = JUMP;
                if (m.flee <= 0 && rnd(4) == 0) m.ang = rndf() * 6.2832f;
            }
        }
        m.vy = (m.vy - GRAV) * 0.98f;
        float ny = m.y + m.vy;
        m.ground = false;
        if (boxCollides(m.x, ny, m.z, hw, mh)) {
            if (m.vy < 0) {
                float sp = ifloor(ny) + 1.001f;
                if (!boxCollides(m.x, sp, m.z, hw, mh)) m.y = sp;
                m.ground = true;
            }
            m.vy = 0;
        } else {
            m.y = ny;
        }
    }

    void castRay() {
        float sy = (float) Math.sin(yaw), cy = (float) Math.cos(yaw);
        float sp = (float) Math.sin(pitch), cp = (float) Math.cos(pitch);
        float dx = sy * cp, dy = sp, dz = cy * cp;
        float ex = px, ey = py + EYE, ez = pz;
        tx = -1; ty = -1; tz = -1; ax = -1; ay = -1; az = -1;
        float tDist = 99f;
        int lx = ifloor(ex), ly = ifloor(ey), lz = ifloor(ez);
        float d = 0.05f;
        int doorB = 0;
        boolean bk = slId[sel] == I_BUCKET;
        int qx = lx, qy = ly, qz = lz;   // celda anterior a la actual
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
            // puerta abierta: solo cuenta el panel fino, no toda la celda
            if (doorB != 0 && rayInDoor(doorB, ix, iy, iz, ex + dx * d, ez + dz * d)) {
                tx = ix; ty = iy; tz = iz;
                ax = qx; ay = qy; az = qz;
                tDist = d;
                break;
            }
            d += 0.04f;
        }
        tMob = null;
        float best = MOB_REACH;
        if (tDist < best) best = tDist;
        for (int i = 0; i < mobs.size(); i++) {
            Mob m = (Mob) mobs.elementAt(i);
            if (m.hp <= 0 || m.type == 5) continue;
            float r = mobR(m.type);
            float h = mobH(m.type);
            if (blockAt(ifloor(m.x), ifloor(m.y + h * 0.5f), ifloor(m.z)) != 0) continue;
            r = r * 0.8f;
            float cx = m.x - ex, cyy = m.y + h * 0.5f - ey, czz = m.z - ez;
            float t = cx * dx + cyy * dy + czz * dz;
            if (t < 0.2f || t > best) continue;
            float d2 = cx * cx + cyy * cyy + czz * czz - t * t;
            if (d2 < r * r) { best = t; tMob = m; }
        }
    }

    int breakTicks(int b, int tool) {
        float secs = hard[b] * 1.5f;
        int tt = toolType(tool);
        boolean right = false;
        if (b == B_STONE || b == B_COBBLE || b == B_FURNACE || b == B_SANDSTONE || b == B_ICE
                || (b >= B_COAL_ORE && b <= B_DIAM_BLK) || b == B_NETHERRACK || b == B_OBSIDIAN) {
            right = (tt == 0);
            if (!right || toolLevel(tool) < needLevel(b)) { right = false; secs = hard[b] * 5f; }
        } else if (isLog(b) || isPlanks(b) || b == B_TABLE || b == B_CHEST || isDoor(b)) {
            right = (tt == 1);
        } else if (b == B_GRASS || b == B_DIRT || b == B_SAND || b == B_SNOW || b == B_GRAVEL || b == B_SOULSAND || b == B_FARM_DRY || b == B_FARM_WET) {
            right = (tt == 2);
        }
        if (right) secs = secs / toolSpeed(tool);
        return (int) (secs * 20f) + 1;
    }

    void damageTool(int amount) {
        if (creative) return;
        if (isTool(slId[sel])) {
            slDur[sel] -= amount;
            if (slDur[sel] <= 0) clearSlot(sel);
        }
    }

    void breakBlock(int x, int y, int z) {
        int b = blockAt(x, y, z);
        if (b == B_BEDROCK || b == B_LAVA || b == B_PORTAL) return;
        if ((b == B_FARM_DRY || b == B_FARM_WET) && blockAt(x, y + 1, z) >= B_CROP0 && blockAt(x, y + 1, z) <= B_CROP2) setBlock(x, y + 1, z, 0);
        if (b == B_CHEST && !creative) {   // un cofre con items no se rompe (asi no se pierde nada)
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
        boolean pick = (toolType(tl) == 0);
        int lvl = pick ? toolLevel(tl) : -1;
        int drop = b;
        if (b == B_GRASS) drop = B_DIRT;
        else if (b == B_STONE || b == B_COBBLE) drop = pick ? B_COBBLE : 0;
        else if (b == B_SANDSTONE) drop = pick ? B_SANDSTONE : 0;
        else if (isLeaves(b)) drop = 0;
        else if (b == B_FARM_DRY || b == B_FARM_WET) drop = B_DIRT;
        else if (b >= B_CROP0 && b <= B_CROP2) drop = I_SEEDS;
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
        if (isLeaves(b) && rnd(8) == 0) addItem(I_APPLE, 1, 0);
        if (isLeaves(b) && rnd(6) == 0) addItem(B_SAPLING, 1, 0);
        if (b == B_CROP2) {
            addItem(I_WHEAT, 1, 0);
            if (rnd(2) == 0) addItem(I_SEEDS, 1, 0);
        }
        damageTool(1);
    }

    void attackMob(Mob m) {
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
        m.hp -= dmg;
        m.hurt = 8;
        if (m.type < 3) {
            m.flee = 60;
            m.moving = true;
            m.ang = yaw + (rndf() - 0.5f) * 1.2f;
        }
        m.vy = 0.25f;
        float kx = (float) Math.sin(yaw) * 0.4f, kz = (float) Math.cos(yaw) * 0.4f;
        float hw = mobHW(m.type);
        float mh = mobH(m.type);
        if (!boxCollides(m.x + kx, m.y, m.z + kz, hw, mh)) { m.x += kx; m.z += kz; }
        damageTool(isTool(t) ? (toolType(t) == 3 ? 1 : 2) : 0);
        if (m.hp <= 0) {
            if (m.type == 0) {
                addItem(I_WOOL, 1, 0);
                addItem(I_MUTTON, 1 + rnd(2), 0);
            } else if (m.type == 1) {
                addItem(I_CHICKEN, 1, 0);
                int f = rnd(3);
                if (f > 0) addItem(I_FEATHER, f, 0);
            } else if (m.type == 2) {
                addItem(I_BEEF, 1 + rnd(3), 0);
            } else if (m.type == 3) {
                int f = rnd(3);
                if (f > 0) addItem(I_ROTTEN, f, 0);
                if (dim == 1 && rnd(4) == 0) addItem(I_GOLD_INGOT, 1, 0);
            } else if (m.type == 4) {
                int f = rnd(3);
                if (f > 0) addItem(I_GUNPOWDER, f, 0);
            }
            mobs.removeElement(m);
            tMob = null;
        }
    }

    void updateAction() {
        if (attackCd > 0) attackCd--;
        if (!kd[10]) return;
        starTicks++;
        if (tMob != null) {
            mineProg = 0;
            if (attackCd <= 0 && (tMob.type != 6 || starTicks > 6)) {
                attackMob(tMob);
                attackCd = 8;
                starConsumed = true;
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
                mineProg = 0;
                mineX = -1;
                starConsumed = true;
            }
        } else {
            mineProg = 0;
            mineX = -1;
        }
    }

    void consumeSel() {
        if (creative) return;
        slCnt[sel]--;
        if (slCnt[sel] <= 0) clearSlot(sel);
    }

    void doTap() {
        int id = slId[sel];
        if (tMob != null && tMob.type == 6) { openInv(TRADE); return; }
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
                hunger = Math.min(20, hunger + foodVal(id));
                if (id == I_ROTTEN) hungerFx = 300;
                consumeSel();
            }
            return;
        }
        if (isBlockItem(id) && tx >= 0) placeBlock(id);
    }

    void placeBlock(int id) {
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

    void updateSurvival() {
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
            if (air < 300) air = Math.min(300, air + 6);
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

    // ---------------------------------------------------------------- cofres
    // Cada cofre tiene un id fijo (0..63). La tabla id -> posicion vive en RAM (~1 KB) y se guarda en el registro 1
    // del almacen "mcc<mundo>". El CONTENIDO no vive en RAM: se lee del disco al abrir el cofre y se escribe al
    // cerrarlo (un registro por cofre, solo si cambio). Mientras esta abierto el mundo esta pausado (update() no corre
    // fuera de ST_PLAY) y solo queda cargado el chunk del jugador; al cerrar se recargan los demas.
    String chestStoreName() { return cat("mcc", curWorld != null ? curWorld.id : 0); }

    void clearChestTable() {
        for (int i = 0; i < MAX_CHESTS; i++) {
            cUsed[i] = false; cX[i] = 0; cY[i] = 0; cZ[i] = 0; cDim[i] = 0; cRid[i] = 0; cFill[i] = 0;
        }
        chestTabDirty = false;
        openChest = -1;
    }

    void loadChestTable() {
        clearChestTable();
        if (curWorld == null) return;
        RecordStore r = null;
        try {
            r = RecordStore.openRecordStore(chestStoreName(), false);
            byte[] b = r.getRecord(1);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
            in.readByte();   // version
            for (int i = 0; i < MAX_CHESTS; i++) {
                cUsed[i] = in.readByte() != 0;
                cX[i] = in.readInt(); cY[i] = in.readByte(); cZ[i] = in.readInt();
                cDim[i] = in.readByte(); cRid[i] = in.readInt(); cFill[i] = in.readByte();
            }
        } catch (Exception e) {
            clearChestTable();
        }
        if (r != null) { try { r.closeRecordStore(); } catch (Exception e) {} }
    }

    byte[] chestTableBytes() throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bo);
        o.writeByte(1);
        for (int i = 0; i < MAX_CHESTS; i++) {
            o.writeByte(cUsed[i] ? 1 : 0);
            o.writeInt(cX[i]); o.writeByte(cY[i]); o.writeInt(cZ[i]);
            o.writeByte(cDim[i]); o.writeInt(cRid[i]); o.writeByte(cFill[i]);
        }
        o.flush();
        return bo.toByteArray();
    }

    // escribe la tabla (registro 1) y, si data != null, el contenido del cofre id (su propio registro)
    boolean chestWrite(int id, byte[] data) {
        RecordStore r = null;
        boolean ok = false;
        try {
            r = RecordStore.openRecordStore(chestStoreName(), true);
            if (r.getNumRecords() == 0) { byte[] t0 = chestTableBytes(); r.addRecord(t0, 0, t0.length); }
            if (data != null) {
                if (cRid[id] == 0) cRid[id] = r.addRecord(data, 0, data.length);
                else r.setRecord(cRid[id], data, 0, data.length);
            }
            byte[] t1 = chestTableBytes();
            r.setRecord(1, t1, 0, t1.length);
            ok = true;
        } catch (Exception e) {
            ok = false;
        }
        if (r != null) { try { r.closeRecordStore(); } catch (Exception e) {} }
        return ok;
    }

    boolean chestSaveTable() {
        boolean ok = chestWrite(0, null);
        chestTabDirty = !ok;
        return ok;
    }

    int chestFind(int x, int y, int z) {
        for (int i = 0; i < MAX_CHESTS; i++) {
            if (cUsed[i] && cX[i] == x && cY[i] == y && cZ[i] == z && cDim[i] == dim) return i;
        }
        return -1;
    }

    // primer id libre; -1 si ya hay 64 cofres
    int chestAlloc(int x, int y, int z) {
        for (int i = 0; i < MAX_CHESTS; i++) {
            if (cUsed[i]) continue;
            cUsed[i] = true; cX[i] = x; cY[i] = y; cZ[i] = z; cDim[i] = dim; cFill[i] = 0;   // vacio: no se lee el disco
            chestTabDirty = true;
            chestSaveTable();
            return i;
        }
        return -1;
    }

    // el cofre desaparecio (rotura o explosion): su id queda libre (el registro en disco se reutiliza)
    void chestFree(int x, int y, int z) {
        int i = chestFind(x, y, z);
        if (i < 0) return;
        cUsed[i] = false; cFill[i] = 0;
        chestTabDirty = true;
        chestSaveTable();
    }

    byte[] chestPack() {
        byte[] d = new byte[CHN * 4];
        for (int i = 0; i < CHN; i++) {
            int s = CH0 + i;
            if (slId[s] == 0) continue;
            d[i * 4] = (byte) slId[s];
            d[i * 4 + 1] = (byte) slCnt[s];
            d[i * 4 + 2] = (byte) (slDur[s] >> 8);
            d[i * 4 + 3] = (byte) slDur[s];
        }
        return d;
    }

    // false = no se pudo leer: el cofre no se abre, asi nunca se pisa su contenido en disco
    boolean chestLoadSlots(int id) {
        for (int i = 0; i < CHN; i++) clearSlot(CH0 + i);
        byte[] d = new byte[CHN * 4];
        if (cFill[id] > 0 && cRid[id] != 0) {
            RecordStore r = null;
            boolean ok = false;
            try {
                r = RecordStore.openRecordStore(chestStoreName(), false);
                byte[] b = r.getRecord(cRid[id]);
                if (b != null && b.length == d.length) { System.arraycopy(b, 0, d, 0, d.length); ok = true; }
            } catch (Exception e) {
                ok = false;
            }
            if (r != null) { try { r.closeRecordStore(); } catch (Exception e) {} }
            if (!ok) return false;
        }
        for (int i = 0; i < CHN; i++) {
            int sid = d[i * 4] & 0xFF, cnt = d[i * 4 + 1];
            int dur = ((d[i * 4 + 2] & 0xFF) << 8) | (d[i * 4 + 3] & 0xFF);
            if (sid <= 0 || sid > 79 || cnt < 1 || cnt > 64) continue;
            slId[CH0 + i] = sid; slCnt[CH0 + i] = cnt; slDur[CH0 + i] = dur;
        }
        chestSnap = chestPack();
        return true;
    }

    // escribe el contenido del cofre abierto solo si cambio desde que se leyo; false = fallo de escritura
    boolean chestSave(int id) {
        byte[] d = chestPack();
        boolean same = true;
        for (int i = 0; i < d.length; i++) {
            if (d[i] != chestSnap[i]) { same = false; break; }
        }
        if (same) return true;
        int fill = 0;
        for (int i = 0; i < CHN; i++) if (slId[CH0 + i] != 0) fill++;
        int oldFill = cFill[id];
        cFill[id] = fill;
        if (chestWrite(id, d)) {
            chestSnap = d;
            return true;
        }
        cFill[id] = oldFill;
        return false;
    }

    // al irse la app con un cofre abierto: se guarda sin cerrarlo
    void chestCommit() {
        if (state == ST_INV && craftN == CHEST && openChest >= 0) chestSave(openChest);
    }

    void chestOpenAt(int x, int y, int z) {
        int id = chestFind(x, y, z);
        if (id < 0) id = chestAlloc(x, y, z);   // cofre sin id (puesto desde la paleta creativa, mundo viejo)
        if (id < 0) { say("Limite de cofres alcanzado (64)"); return; }
        if (!chestLoadSlots(id)) { say("No se pudo leer el cofre"); return; }
        openChest = id;
        // mundo en pausa: los chunks de alrededor se descargan y solo queda el del jugador
        int pcx = ifloor(px) >> 4, pcz = ifloor(pz) >> 4;
        for (int s = 0; s < nch; s++) {
            if (chCx[s] == NONE) continue;
            if (chCx[s] == pcx && chCz[s] == pcz) continue;
            unloadSlot(s);
        }
        if (pendBytes > 0 && !storeBroken) needFlush = true;   // los chunks editados bajan a disco (cartel "Guardando...")
        openInv(CHEST);
    }

    void chestClose() {
        int id = openChest;
        openChest = -1;
        if (id >= 0 && !chestSave(id)) {
            // no se pudo escribir: los items vuelven al inventario y el cofre queda vacio (nada se duplica)
            for (int i = 0; i < CHN; i++) {
                int s = CH0 + i;
                if (slId[s] != 0) addItem(slId[s], slCnt[s], slDur[s]);
            }
            cFill[id] = 0;
            chestTabDirty = true;
            say("Error al guardar el cofre");
        }
        for (int i = 0; i < CHN; i++) clearSlot(CH0 + i);
        quietLoad = true;           // recargar los chunks descargados sin generar animales nuevos
        loadMissing(nch);
        quietLoad = false;
    }

    // ---------------------------------------------------------------- horno
    int smeltResult(int id) {
        if (isLog(id)) return I_COAL;              // troncos -> carbon vegetal
        if (id == B_COBBLE) return B_STONE;          // adoquin -> piedra
        if (id == I_MUTTON) return I_COOKED_MUTTON;
        if (id == I_CHICKEN) return I_COOKED_CHICKEN;
        if (id == I_BEEF) return I_STEAK;
        if (id == B_IRON_ORE) return I_IRON_INGOT;   // mena -> lingote
        if (id == B_GOLD_ORE) return I_GOLD_INGOT;
        return 0;
    }

    int fuelTime(int id) {
        if (id == I_COAL) return 1600;
        if (isLog(id) || isPlanks(id) || id == B_TABLE) return 300;
        if (id == I_STICK || id == B_SAPLING) return 100;
        return 0;
    }

    void updateFurnace() {
        int res = smeltResult(fId[0]);
        boolean can = res != 0 && fCnt[0] > 0 && (fId[2] == 0 || (fId[2] == res && fCnt[2] < 64));
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

    void syncToSlots() {
        for (int i = 0; i < 3; i++) { slId[36 + i] = fId[i]; slCnt[36 + i] = fCnt[i]; slDur[36 + i] = 0; }
    }

    void syncFromSlots() {
        for (int i = 0; i < 3; i++) { fId[i] = slId[36 + i]; fCnt[i] = slCnt[36 + i]; }
    }

    void takeFurnaceOut() {
        if (slId[38] == 0) return;
        if (heldId == 0) {
            heldId = slId[38]; heldCnt = slCnt[38]; heldDur = 0;
            clearSlot(38);
        } else if (heldId == slId[38] && heldCnt + slCnt[38] <= 64) {
            heldCnt += slCnt[38];
            clearSlot(38);
        }
    }

    // ---------------------------------------------------------------- brotes (saplings): crecen a los 5 ticks
    void addSapling(int x, int y, int z) {
        if (sapN >= 32) return;
        sapX[sapN] = x; sapY[sapN] = y; sapZ[sapN] = z; sapT[sapN] = 5;
        sapN++;
    }

    void updateSaplings() {
        for (int i = sapN - 1; i >= 0; i--) {
            if (--sapT[i] > 0) continue;
            int x = sapX[i], y = sapY[i], z = sapZ[i];
            sapN--;
            sapX[i] = sapX[sapN]; sapY[i] = sapY[sapN]; sapZ[i] = sapZ[sapN]; sapT[i] = sapT[sapN];
            if (blockAt(x, y, z) == B_SAPLING) growTree(x, y, z);
        }
    }

    void growTree(int x, int y, int z) {
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
                    if (r == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2 && rnd(2) == 0) continue;
                    if (dy == th + 1 && Math.abs(dx) + Math.abs(dz) > 1) continue;
                    if (dx == 0 && dz == 0 && dy <= th) continue;
                    if (blockAt(x + dx, top + dy, z + dz) == 0) setBlock(x + dx, top + dy, z + dz, leavesOf(tv));
                }
            }
        }
        int g = 0;
        while (g++ < H && boxCollides(px, py, pz, HW, 1.8f)) py += 1f;
    }

    // ---------------------------------------------------------------- dia / noche
    int dayBright() {
        int tt = worldTime % DAY_LEN;
        if (tt < 12000) return 100;
        if (tt < 13800) return 100 - (tt - 12000) * 65 / 1800;
        if (tt < 22200) return 35;
        return 35 + (tt - 22200) * 65 / 1800;
    }

    static int mix(int a, int b, int p) {
        int r = (((a >> 16) & 255) * p + ((b >> 16) & 255) * (100 - p)) / 100;
        int g = (((a >> 8) & 255) * p + ((b >> 8) & 255) * (100 - p)) / 100;
        int bl = ((a & 255) * p + (b & 255) * (100 - p)) / 100;
        return (r << 16) | (g << 8) | bl;
    }

    int skyColor() {
        if (dim == 1) return 0x2A0808;
        int p = (bright - 35) * 100 / 65;
        if (p < 0) p = 0;
        if (p > 100) p = 100;
        return mix(0x87CEEB, 0x05081C, p);
    }

    void scanTorches() {
        torchN = 0;
        int bx = ifloor(px), bz = ifloor(pz);
        for (int x = bx - 14; x <= bx + 14 && torchN < 16; x++) {
            for (int z = bz - 14; z <= bz + 14 && torchN < 16; z++) {
                byte[] cd = chunkFor(x, z);
                if (cd == null) continue;
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

    // brillo (0-100) en un punto: luz del dia o la de la antorcha mas cercana
    int litPct(float x, float y, float z) {
        int p = bright;
        if (p >= 100) return 100;
        for (int i = 0; i < torchN; i++) {
            float dx = x - torchX[i], dy = y - torchY[i], dz = z - torchZ[i];
            float d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < 64f) {
                int tp = 100 - (int) (d2 * 1.5f);
                if (tp > p) p = tp;
            }
        }
        return p;
    }

    // ---------------------------------------------------------------- mobs: datos e IA hostil
    float mobHW(int t) { if (t == 5) return 0.49f; return t == 0 ? 0.35f : (t == 1 ? 0.2f : (t == 2 ? 0.4f : 0.3f)); }
    float mobH(int t) { if (t == 5) return 1.0f; return t == 0 ? 1.0f : (t == 1 ? 0.7f : (t == 2 ? 1.25f : ((t == 3 || t == 6) ? 1.8f : 1.6f))); }
    float mobR(int t) { if (t == 5) return 0f; return t == 0 ? 0.5f : (t == 1 ? 0.3f : (t == 2 ? 0.55f : (t == 3 ? 0.45f : 0.4f))); }
    float mobSpeed(int t) { return t == 3 ? 0.07f : (t == 4 ? 0.06f : 0.035f); }
    int mobHp(int t) { return t == 0 ? 8 : (t == 1 ? 4 : (t == 2 ? 10 : (t == 3 ? 12 : 10))); }

    float[][] mobParts(int t) {
        if (t == 0) return sheepParts;
        if (t == 1) return chickenParts;
        if (t == 2) return cowParts;
        if (t == 3) return zombieParts;
        if (t == 4) return creeperParts;
        if (t == 6) return villagerParts;
        return tntParts;
    }

    // atan2 aproximado (CLDC 1.1 no trae atan): devuelve el angulo cuyo seno ~ y y coseno ~ x
    float atan2f(float y, float x) {
        float ax = Math.abs(x), ay = Math.abs(y);
        if (ax < 0.0001f && ay < 0.0001f) return 0f;
        float a;
        if (ay <= ax) {
            float z = ay / ax;
            a = z / (1f + 0.28f * z * z);
        } else {
            float z = ax / ay;
            a = 1.5708f - z / (1f + 0.28f * z * z);
        }
        if (x < 0f) a = 3.14159f - a;
        if (y < 0f) a = -a;
        return a;
    }

    // concatenar sin StringBuilder (CLDC solo trae StringBuffer)
    static String cat(String a, String b) { return new StringBuffer(a).append(b).toString(); }
    static String cat(String a, int n) { return new StringBuffer(a).append(n).toString(); }

    // ---------------------------------------------------------------- nether y portales
    // vacia la ventana de chunks (lo modificado pasa a pending con la clave vieja) y cambia de dimension
    void switchDim(int nd) {
        for (int sl = 0; sl < nch; sl++) unloadSlot(sl);
        mobs.removeAllElements();
        sapN = 0; torchN = 0;
        dim = nd;
        portalT = 0;
    }

    boolean lavaAtF(float x, float y, float z) {
        return blockAt(ifloor(x), ifloor(y), ifloor(z)) == B_LAVA;
    }

    int pb(int axis, int u, int y, int f) { return axis == 0 ? blockAt(u, y, f) : blockAt(f, y, u); }

    // intenta encender un marco de obsidiana (interior 2-4 de ancho x 3-5 de alto) que contiene la celda de aire dada
    boolean lightPortal(int ax0, int ay0, int az0) {
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
            boolean ok = true;
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

    // plataforma de obsidiana con marco 4x5 (interior 2x3) a lo largo de X
    void buildPortal(int x, int y, int z) {
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
    void usePortal() {
        float nx = (dim == 0) ? px / 8f : px * 8f;
        float nz = (dim == 0) ? pz / 8f : pz * 8f;
        switchDim(1 - dim);
        px = nx; pz = nz;
        updateWindow(true);
        loadMissing(nch);
        int cx0 = ifloor(nx), cz0 = ifloor(nz);
        int fx = 0, fy = -1, fz = 0;
        for (int x = cx0 - 10; x <= cx0 + 10 && fy < 0; x++) {
            for (int z = cz0 - 10; z <= cz0 + 10 && fy < 0; z++) {
                if (chunkFor(x, z) == null) continue;
                for (int y = 1; y < H - 1; y++) {
                    if (blockAt(x, y, z) == B_PORTAL && blockAt(x, y - 1, z) == B_OBSIDIAN) { fx = x; fy = y; fz = z; break; }
                }
            }
        }
        if (fy >= 0) {
            px = fx + 0.5f; pz = fz + 0.5f; py = fy + 0.01f;
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

    void updatePortal() {
        boolean inP = blockAt(ifloor(px), ifloor(py + 0.5f), ifloor(pz)) == B_PORTAL
                || blockAt(ifloor(px), ifloor(py + 1.4f), ifloor(pz)) == B_PORTAL;
        if (!inP) { portalT = 0; portalLock = false; return; }
        if (portalLock) return;
        if (++portalT >= (creative ? 2 : 60)) { portalT = 0; usePortal(); }
    }

    // ---------------------------------------------------------------- puertas
    // orientacion derivada de las paredes vecinas (de la mitad inferior): true = el panel cerrado corre a lo largo de X
    boolean doorAlongX(int x, int ly, int z) {
        boolean wx = opaque(blockAt(x - 1, ly, z)) || opaque(blockAt(x + 1, ly, z));
        boolean wz = opaque(blockAt(x, ly, z - 1)) || opaque(blockAt(x, ly, z + 1));
        if (wz && !wx) return false;
        return true;
    }

    // la cama guarda el punto de reaparicion; de noche ademas se duerme hasta la manana
    void useBed(int x, int y, int z) {
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

    void toggleDoor(int x, int y, int z) {
        int b = blockAt(x, y, z);
        int ly = (b == B_DOOR_UC || b == B_DOOR_UO) ? y - 1 : y;
        int lb = blockAt(x, ly, z);
        if (!isDoor(lb)) { setBlock(x, y, z, 0); return; }   // mitad huerfana
        boolean open = (lb == B_DOOR_LO);
        if (open && px + HW > x && px - HW < x + 1 && pz + HW > z && pz - HW < z + 1
                && py + 1.8f > ly && py < ly + 2) return;   // no cerrar con el jugador adentro
        setBlock(x, ly, z, open ? B_DOOR_LC : B_DOOR_LO);
        if (isDoor(blockAt(x, ly + 1, z))) setBlock(x, ly + 1, z, open ? B_DOOR_UC : B_DOOR_UO);
    }

    boolean rayInDoor(int hb, int x, int y, int z, float rx, float rz) {
        int ly = (hb == B_DOOR_UO) ? y - 1 : y;
        boolean spanX = !doorAlongX(x, ly, z);   // abierta: el panel gira 90 grados
        if (spanX) return (rz - z) < 0.14f;
        return (rx - x) < 0.14f;
    }

    // true si no hay bloques solidos entre dos puntos (paredes, puertas cerradas)
    boolean losClear(float x0, float y0, float z0, float x1, float y1, float z1) {
        float ddx = x1 - x0, ddy = y1 - y0, ddz = z1 - z0;
        float len = (float) Math.sqrt((double) (ddx * ddx + ddy * ddy + ddz * ddz));
        int n = (int) (len * 3f) + 2;
        for (int i = 1; i < n; i++) {
            float t = (float) i / (float) n;
            if (solidAt(ifloor(x0 + ddx * t), ifloor(y0 + ddy * t), ifloor(z0 + ddz * t))) return false;
        }
        return true;
    }

    // true si el mob hostil persigue al jugador (fija ang y moving)
    boolean hostileAI(Mob m) {
        if (m.atk > 0) m.atk--;
        float dx = px - m.x, dz = pz - m.z;
        float d2 = dx * dx + dz * dz;
        float dy = py - m.y;   // diferencia entre pies
        float lim = (m.type == 3) ? 256f : 144f;
        if (d2 > lim) { m.fuse = 0; m.wind = 0; return false; }
        m.ang = atan2f(dx, dz);
        m.moving = true;
        if (m.type == 3) {
            // alcance ~1.3 bloques, casi a la misma altura y sin paredes en el medio
            boolean near = d2 < 1.7f && dy < 1.2f && dy > -1.2f
                    && losClear(m.x, m.y + 1.0f, m.z, px, py + 1.0f, pz);
            if (near) {
                m.moving = false;
                // tiempo de carga antes del golpe y enfriamiento largo entre golpes
                if (++m.wind >= 12 && m.atk <= 0) { hurt(3); m.atk = 40; m.wind = 0; }
            } else if (m.wind > 0) {
                m.wind--;
            }
        } else {
            // el creeper solo se enciende a ~2 bloques, a la misma altura y con vista libre
            boolean near = d2 < 4f && dy < 1.6f && dy > -1.6f
                    && losClear(m.x, m.y + 0.8f, m.z, px, py + 0.9f, pz);
            if (near) {
                m.moving = false;
                if (++m.fuse >= 30) explode(m);
            } else if (m.fuse > 0) {
                m.fuse--;
            }
        }
        return true;
    }

    void explode(Mob m) {
        boolean big = (m.type == 5);
        int rr = big ? 4 : 3;
        int lim = big ? 17 : 10;
        int cx = ifloor(m.x), cy = ifloor(m.y + (big ? 0.5f : 0.8f)), cz = ifloor(m.z);
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
        float ddx = px - m.x, ddy = (py + 0.9f) - (m.y + 0.8f), ddz = pz - m.z;
        float d = (float) Math.sqrt((double) (ddx * ddx + ddy * ddy + ddz * ddz));
        float reach = big ? 8f : 6f;
        if (d < reach && losClear(m.x, m.y + 0.8f, m.z, px, py + 0.9f, pz)) hurt((int) ((reach - d) * (big ? 2.2f : 1.8f)));
        if (big) {
            for (int i = 0; i < mobs.size(); i++) {
                Mob o = (Mob) mobs.elementAt(i);
                if (o == m || o.type == 5) continue;
                float ex = o.x - m.x, ey = o.y - m.y, ez = o.z - m.z;
                float dd = (float) Math.sqrt((double) (ex * ex + ey * ey + ez * ez));
                if (dd < 6f) { o.hp -= (int) ((6f - dd) * 2f) + 1; o.hurt = 8; }
            }
        }
        m.hp = -999;
    }

    void igniteTnt(int x, int y, int z, int startFuse) {
        setBlock(x, y, z, 0);
        Mob m = new Mob();
        m.type = 5;
        m.x = x + 0.5f; m.y = y + 0.001f; m.z = z + 0.5f;
        m.hp = 1;
        m.fuse = startFuse;
        mobs.addElement(m);
    }

    boolean waterAtF(float x, float y, float z) {
        return blockAt(ifloor(x), ifloor(y), ifloor(z)) == B_WATER;
    }

    boolean touchingCactus() {
        int x0 = ifloor(px - HW - 0.12f), x1 = ifloor(px + HW + 0.12f);
        int z0 = ifloor(pz - HW - 0.12f), z1 = ifloor(pz + HW + 0.12f);
        int y0 = ifloor(py), y1 = ifloor(py + 1.6f);
        for (int xx = x0; xx <= x1; xx++)
            for (int yy = y0; yy <= y1; yy++)
                for (int zz = z0; zz <= z1; zz++)
                    if (blockAt(xx, yy, zz) == B_CACTUS) return true;
        return false;
    }

    // ---------------------------------------------------------------- render 3D
    void toCam(float x, float y, float z, float[] o, int i) {
        float dx = x - camX, dy = y - camY, dz = z - camZ;
        float rx = dx * cosY - dz * sinY;
        float rz = dx * sinY + dz * cosY;
        o[i] = rx;
        o[i + 1] = dy * cosP - rz * sinP;
        o[i + 2] = dy * sinP + rz * cosP;
    }

    int clip(float[] in, int n, float[] out) {
        int m = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float az = in[i * 3 + 2], bz = in[j * 3 + 2];
            boolean ain = az >= NEAR, bin = bz >= NEAR;
            if (ain) {
                out[m * 3] = in[i * 3]; out[m * 3 + 1] = in[i * 3 + 1]; out[m * 3 + 2] = az;
                m++;
            }
            if (ain != bin) {
                float t = (NEAR - az) / (bz - az);
                out[m * 3] = in[i * 3] + (in[j * 3] - in[i * 3]) * t;
                out[m * 3 + 1] = in[i * 3 + 1] + (in[j * 3 + 1] - in[i * 3 + 1]) * t;
                out[m * 3 + 2] = NEAR;
                m++;
            }
        }
        return m;
    }

    boolean polyOk;   // true si el ultimo fillPoly dibujo un poligono completo (sin recorte) en sxs/sys

    void fillPoly(float[] v, int n, int color) {
        polyOk = false;
        int m = n;
        float[] p = v;
        boolean behind = false;
        for (int i = 0; i < n; i++) {
            if (v[i * 3 + 2] < NEAR) { behind = true; break; }
        }
        if (behind) {
            m = clip(v, n, clipA);
            if (m < 3) return;
            p = clipA;
        }
        int minx = 100000, maxx = -100000, miny = 100000, maxy = -100000;
        for (int i = 0; i < m; i++) {
            float iz = 1f / p[i * 3 + 2];
            int sx = (int) (halfW + p[i * 3] * iz * focal);
            int sy = (int) (halfH - p[i * 3 + 1] * iz * focal);
            if (sx < -3000) sx = -3000;
            if (sx > 3000) sx = 3000;
            if (sy < -3000) sy = -3000;
            if (sy > 3000) sy = 3000;
            sxs[i] = sx; sys[i] = sy;
            if (sx < minx) minx = sx;
            if (sx > maxx) maxx = sx;
            if (sy < miny) miny = sy;
            if (sy > maxy) maxy = sy;
        }
        if (maxx < 0 || minx > sw || maxy < 0 || miny > sh) return;
        polyOk = !behind;
        gg.setColor(color);
        for (int k = 1; k < m - 1; k++) {
            gg.fillTriangle(sxs[0], sys[0], sxs[k], sys[k], sxs[k + 1], sys[k + 1]);
        }
        if (p[2] < 14f) {
            gg.setColor(shade(color, 72));
            for (int i = 0; i < m; i++) {
                int j = (i + 1) % m;
                gg.drawLine(sxs[i], sys[i], sxs[j], sys[j]);
            }
        }
    }

    boolean opaque(int b) { return b != 0 && b != B_SAPLING && b != B_TORCH && b != B_WATER && !(b >= B_DOOR_LC && b <= B_DOOR_UO) && b != B_BED && b != B_LAVA && b != B_PORTAL && (b < B_CROP0 || b > B_CROP2); }

    void faceBlock(int x, int y, int z, int f, int color, int b) {
        for (int i = 0; i < 4; i++) {
            int c = FACE[f][i];
            toCam(x + (c & 1), y + ((c >> 1) & 1), z + ((c >> 2) & 1), quad, i * 3);
        }
        int p = litPct(x + 0.5f + NX[f], y + 0.5f + NY[f], z + 0.5f + NZ[f]);
        if (b == B_LAVA || b == B_GLOWSTONE) p = 100;   // brillan solos
        fillPoly(quad, 4, p >= 100 ? color : shade(color, p));
        if (polyOk && b != B_WATER && b != B_SNOW) {
            float ddx = x + 0.5f - camX, ddy = y + 0.5f - camY, ddz = z + 0.5f - camZ;
            if (ddx * ddx + ddy * ddy + ddz * ddz < 42f) detail(b, f, p);
        }
    }

    // ---------------------------------------------------------------- texturas procedurales (sin archivos)
    // Marcas dibujadas sobre la cara ya proyectada: u = 0..1 izquierda->derecha, v = 0..1 arriba->abajo
    int qa, qb, qc, qd, dSh, gx, gy;
    int curWv;
    boolean curLeaf;

    void detailInit(int f, int lp) {
        dSh = FSH[f] * lp / 100;
        if (f == 2 || f == 3) { qa = 0; qb = 1; qc = 2; qd = 3; return; }
        int i0 = 0;
        for (int i = 0; i < 4; i++) {
            if (((FACE[f][i] >> 1) & 1) == 0 && ((FACE[f][(i + 1) & 3] >> 1) & 1) == 0) { i0 = i; break; }
        }
        qd = i0; qc = (i0 + 1) & 3; qb = (i0 + 2) & 3; qa = (i0 + 3) & 3;
    }

    int mk(int c, int pct) { int r = tint(c, dSh * pct / 100); return curWv == 0 ? r : vt(r, curWv, curLeaf); }

    void pt(float u, float v) {
        float ux = sxs[qa] + (sxs[qb] - sxs[qa]) * u, uy = sys[qa] + (sys[qb] - sys[qa]) * u;
        float wx = sxs[qd] + (sxs[qc] - sxs[qd]) * u, wy = sys[qd] + (sys[qc] - sys[qd]) * u;
        gx = (int) (ux + (wx - ux) * v);
        gy = (int) (uy + (wy - uy) * v);
    }

    void dLine(float u0, float v0, float u1, float v1, int col) {
        pt(u0, v0);
        int xa = gx, ya = gy;
        pt(u1, v1);
        gg.setColor(col);
        gg.drawLine(xa, ya, gx, gy);
    }

    void dRect(float u0, float v0, float u1, float v1, int col) {
        pt(u0, v0); int xa = gx, ya = gy;
        pt(u1, v0); int xb = gx, yb = gy;
        pt(u1, v1); int xc = gx, yc = gy;
        pt(u0, v1);
        gg.setColor(col);
        gg.fillTriangle(xa, ya, xb, yb, xc, yc);
        gg.fillTriangle(xa, ya, xc, yc, gx, gy);
    }

    void dFrame(float u0, float v0, float u1, float v1, int col) {
        dLine(u0, v0, u1, v0, col);
        dLine(u1, v0, u1, v1, col);
        dLine(u1, v1, u0, v1, col);
        dLine(u0, v1, u0, v0, col);
    }

    void detail(int b, int f, int lp) {
        detailInit(f, lp);
        curWv = 0;
        if (b >= B_LOG_S && b <= B_PLANKS_S + 1) {
            int q = b - B_LOG_S;
            curWv = q % 2 + 1;
            curLeaf = q >= 2 && q < 4;
            b = q < 2 ? B_LOG : (q < 4 ? B_LEAVES : B_PLANKS);
        }
        boolean side = (f < 2 || f > 3);
        if (b == B_PLANKS) {
            int d = mk(0x7A5A2E, 100);
            dLine(0f, 0.25f, 1f, 0.25f, d); dLine(0f, 0.5f, 1f, 0.5f, d); dLine(0f, 0.75f, 1f, 0.75f, d);
            dLine(0.7f, 0f, 0.7f, 0.25f, d); dLine(0.25f, 0.25f, 0.25f, 0.5f, d);
            dLine(0.7f, 0.5f, 0.7f, 0.75f, d); dLine(0.25f, 0.75f, 0.25f, 1f, d);
        } else if (b == B_LOG) {
            if (side) {
                int d = mk(0x34240F, 100), l = mk(0x7C6038, 100);
                dLine(0.16f, 0f, 0.16f, 1f, d); dLine(0.40f, 0f, 0.40f, 1f, d);
                dLine(0.64f, 0f, 0.64f, 1f, d); dLine(0.86f, 0f, 0.86f, 1f, d);
                dLine(0.28f, 0f, 0.28f, 1f, l); dLine(0.75f, 0f, 0.75f, 1f, l);
            } else {
                dFrame(0.12f, 0.12f, 0.88f, 0.88f, mk(0x5E4426, 100));
                dFrame(0.28f, 0.28f, 0.72f, 0.72f, mk(0x7A5C2E, 100));
                dRect(0.43f, 0.43f, 0.57f, 0.57f, mk(0x7A5C2E, 100));
            }
        } else if (b == B_TABLE) {
            if (f == 2) {
                int d = mk(0x4A3018, 100);
                dFrame(0.06f, 0.06f, 0.94f, 0.94f, d);
                dLine(0.5f, 0.06f, 0.5f, 0.94f, d); dLine(0.06f, 0.5f, 0.94f, 0.5f, d);
            } else if (side) {
                int d = mk(0x4A3018, 100), g = mk(0xC8C8C8, 100);
                dRect(0f, 0f, 1f, 0.2f, d);
                dLine(0.05f, 0.2f, 0.05f, 1f, d); dLine(0.95f, 0.2f, 0.95f, 1f, d);
                dRect(0.18f, 0.42f, 0.40f, 0.52f, g); dRect(0.18f, 0.52f, 0.24f, 0.66f, mk(0x3A220E, 100));
                dRect(0.52f, 0.40f, 0.78f, 0.50f, g); dRect(0.62f, 0.50f, 0.68f, 0.86f, mk(0x3A220E, 100));
            }
        } else if (b == B_COBBLE) {
            int d = mk(0x3A3A3A, 100);
            dLine(0f, 0.33f, 1f, 0.33f, d); dLine(0f, 0.66f, 1f, 0.66f, d);
            dLine(0.5f, 0f, 0.5f, 0.33f, d); dLine(0.25f, 0.33f, 0.25f, 0.66f, d);
            dLine(0.75f, 0.33f, 0.75f, 0.66f, d); dLine(0.5f, 0.66f, 0.5f, 1f, d);
        } else if (b == B_STONE) {
            int d = mk(0x5A5A5A, 100), l = mk(0x959595, 100);
            dRect(0.15f, 0.2f, 0.27f, 0.3f, d); dRect(0.62f, 0.12f, 0.74f, 0.22f, d);
            dRect(0.4f, 0.62f, 0.52f, 0.72f, d); dRect(0.78f, 0.7f, 0.9f, 0.8f, d);
            dRect(0.3f, 0.4f, 0.4f, 0.48f, l); dRect(0.7f, 0.45f, 0.8f, 0.53f, l);
        } else if (b == B_FURNACE) {
            if (f == 2) {
                dRect(0.28f, 0.28f, 0.72f, 0.72f, mk(0x151515, 100));
                dFrame(0.2f, 0.2f, 0.8f, 0.8f, mk(0x555555, 100));
            } else if (side) {
                dRect(0.2f, 0.12f, 0.8f, 0.24f, mk(0x2A2A2A, 100));
                dRect(0.2f, 0.45f, 0.8f, 0.88f, mk(0x151515, 100));
                dRect(0.3f, 0.68f, 0.7f, 0.82f, mk(0xE07020, 100));
                dFrame(0.2f, 0.45f, 0.8f, 0.88f, mk(0x555555, 100));
            }
        } else if (b == B_CHEST) {
            int d = mk(0x4A3018, 100), g = mk(0xE0C040, 100);
            dFrame(0.04f, 0.04f, 0.96f, 0.96f, d);
            if (side) {
                dLine(0.04f, 0.38f, 0.96f, 0.38f, d);   // tapa
                dRect(0.44f, 0.30f, 0.56f, 0.50f, g);   // cerradura
            }
        } else if (b == B_GRASS) {
            if (side) {
                int g = mk(cTop[B_GRASS], 100);
                dRect(0f, 0f, 1f, 0.2f, g);
                dRect(0.08f, 0.2f, 0.3f, 0.32f, g); dRect(0.55f, 0.2f, 0.82f, 0.29f, g);
            } else if (f == 2) {
                dRect(0.2f, 0.25f, 0.28f, 0.33f, mk(0x3E8A22, 100)); dRect(0.65f, 0.6f, 0.73f, 0.68f, mk(0x6EC03E, 100));
            }
        } else if (b == B_DIRT) {
            int d = mk(0x5A4020, 100), l = mk(0x9A7444, 100);
            dRect(0.15f, 0.2f, 0.27f, 0.3f, d); dRect(0.62f, 0.15f, 0.72f, 0.25f, l);
            dRect(0.4f, 0.55f, 0.52f, 0.65f, d); dRect(0.75f, 0.7f, 0.87f, 0.8f, l);
        } else if (b == B_SAND) {
            int d = mk(0xB8AC70, 100);
            dRect(0.2f, 0.25f, 0.27f, 0.31f, d); dRect(0.6f, 0.5f, 0.67f, 0.56f, d); dRect(0.35f, 0.8f, 0.42f, 0.86f, d);
        } else if (b == B_SANDSTONE) {
            if (side) {
                int d = mk(0xAFA268, 100);
                dLine(0f, 0.3f, 1f, 0.3f, d); dLine(0f, 0.62f, 1f, 0.62f, d);
            }
        } else if (b == B_GRAVEL) {
            int d = mk(0x56524F, 100), l = mk(0xB0AAA6, 100);
            dRect(0.15f, 0.2f, 0.28f, 0.32f, d); dRect(0.6f, 0.15f, 0.72f, 0.27f, l);
            dRect(0.4f, 0.5f, 0.52f, 0.62f, d); dRect(0.75f, 0.6f, 0.88f, 0.72f, l); dRect(0.2f, 0.75f, 0.32f, 0.87f, l);
        } else if (b == B_CACTUS) {
            if (side) {
                int d = mk(0x1E5A1E, 100), l = mk(0xD8D8A0, 100);
                dLine(0.25f, 0f, 0.25f, 1f, d); dLine(0.5f, 0f, 0.5f, 1f, d); dLine(0.75f, 0f, 0.75f, 1f, d);
                dRect(0.35f, 0.3f, 0.4f, 0.35f, l); dRect(0.6f, 0.55f, 0.65f, 0.6f, l); dRect(0.12f, 0.75f, 0.17f, 0.8f, l);
            } else {
                dFrame(0.1f, 0.1f, 0.9f, 0.9f, mk(0x1E5A1E, 100));
                dRect(0.3f, 0.3f, 0.7f, 0.7f, mk(0x4FA04A, 100));
            }
        } else if (b == B_TNT) {
            if (side) {
                int w = mk(0xE8E8E8, 100), d = mk(0x202020, 100);
                dRect(0f, 0.34f, 1f, 0.66f, w);
                for (int i = 0; i < 3; i++) {
                    float u0 = 0.12f + i * 0.28f;
                    if (i == 1) {
                        dLine(u0, 0.42f, u0, 0.58f, d); dLine(u0 + 0.2f, 0.42f, u0 + 0.2f, 0.58f, d);
                        dLine(u0, 0.42f, u0 + 0.2f, 0.58f, d);
                    } else {
                        dRect(u0, 0.42f, u0 + 0.2f, 0.46f, d); dRect(u0 + 0.08f, 0.42f, u0 + 0.12f, 0.58f, d);
                    }
                }
            } else if (f == 2) {
                dRect(0.4f, 0.4f, 0.6f, 0.6f, mk(0x303030, 100));
            }
        } else if (b == B_LEAVES) {
            int d = mk(0x1E5A1C, 100), l = mk(0x58B84A, 100);
            dRect(0.15f, 0.2f, 0.3f, 0.33f, d); dRect(0.6f, 0.12f, 0.75f, 0.25f, d);
            dRect(0.35f, 0.55f, 0.5f, 0.68f, d); dRect(0.72f, 0.62f, 0.87f, 0.75f, d);
            dRect(0.2f, 0.78f, 0.33f, 0.9f, l); dRect(0.5f, 0.3f, 0.6f, 0.4f, l);
        } else if (b >= B_COAL_ORE && b <= B_DIAM_ORE) {
            int oc = (b == B_COAL_ORE) ? 0x1E1E1E : (b == B_IRON_ORE ? 0xD8A878 : (b == B_GOLD_ORE ? 0xF0D030 : 0x5AE8E0));
            int c0 = mk(oc, 100);
            dRect(0.12f, 0.15f, 0.32f, 0.32f, c0); dRect(0.55f, 0.1f, 0.75f, 0.26f, c0);
            dRect(0.35f, 0.5f, 0.55f, 0.66f, c0); dRect(0.72f, 0.55f, 0.9f, 0.72f, c0);
            dRect(0.1f, 0.7f, 0.28f, 0.86f, c0);
        } else if (b >= B_IRON_BLK && b <= B_DIAM_BLK) {
            dFrame(0.05f, 0.05f, 0.95f, 0.95f, mk(cSide[b], 70));
            dFrame(0.14f, 0.14f, 0.86f, 0.86f, mk(cSide[b], 118));
            dLine(0.25f, 0.75f, 0.75f, 0.25f, mk(cSide[b], 135));
        } else if (b == B_NETHERRACK) {
            int d = mk(0x4A1616, 100), l = mk(0x9A4040, 100);
            dRect(0.12f, 0.2f, 0.3f, 0.34f, d); dRect(0.6f, 0.12f, 0.8f, 0.28f, d); dRect(0.3f, 0.6f, 0.5f, 0.74f, d);
            dRect(0.7f, 0.55f, 0.88f, 0.7f, l); dRect(0.15f, 0.78f, 0.3f, 0.9f, l);
        } else if (b == B_LAVA) {
            int y0 = mk(0xFFD040, 100);
            dRect(0.15f, 0.2f, 0.4f, 0.35f, y0); dRect(0.6f, 0.15f, 0.85f, 0.3f, y0);
            dRect(0.3f, 0.6f, 0.55f, 0.75f, y0); dRect(0.7f, 0.65f, 0.9f, 0.8f, y0);
        } else if (b == B_OBSIDIAN) {
            int pv = mk(0x6A3FA0, 100);
            dLine(0.1f, 0.8f, 0.5f, 0.3f, pv); dLine(0.4f, 0.9f, 0.9f, 0.2f, pv); dLine(0.6f, 0.7f, 0.85f, 0.55f, pv);
        } else if (b == B_SOULSAND) {
            int d = mk(0x2E2218, 100);
            dRect(0.15f, 0.2f, 0.3f, 0.32f, d); dRect(0.55f, 0.15f, 0.7f, 0.27f, d);
            dRect(0.3f, 0.55f, 0.45f, 0.67f, d); dRect(0.7f, 0.6f, 0.85f, 0.72f, d);
        } else if (b == B_GLOWSTONE) {
            int l = mk(0xFFF0B0, 100), d = mk(0xC09A40, 100);
            dRect(0.12f, 0.15f, 0.3f, 0.3f, l); dRect(0.6f, 0.1f, 0.78f, 0.25f, l); dRect(0.35f, 0.55f, 0.55f, 0.7f, l);
            dRect(0.7f, 0.6f, 0.88f, 0.75f, d); dRect(0.15f, 0.7f, 0.3f, 0.85f, d);
        } else if (b == B_BEDROCK) {
            int d = mk(0x1A1A1A, 100), l = mk(0x6A6A6A, 100);
            dRect(0.12f, 0.2f, 0.3f, 0.34f, d); dRect(0.6f, 0.12f, 0.8f, 0.28f, d); dRect(0.3f, 0.6f, 0.5f, 0.74f, d);
            dRect(0.7f, 0.55f, 0.88f, 0.7f, l);
        } else if (b == B_ICE) {
            int l = mk(0xE8F6FF, 100);
            dLine(0.15f, 0.8f, 0.7f, 0.2f, l); dLine(0.35f, 0.9f, 0.85f, 0.4f, l);
        }
    }

    static int tint(int c, int pct) {
        int r = ((c >> 16) & 255) * pct / 100;
        int g = ((c >> 8) & 255) * pct / 100;
        int b = (c & 255) * pct / 100;
        if (r > 255) r = 255;
        if (g > 255) g = 255;
        if (b > 255) b = 255;
        return (r << 16) | (g << 8) | b;
    }

    // ruido determinista 0..255 por pixel
    static int tn(int u, int v, int salt) {
        int h = u * 73856093 ^ v * 19349663 ^ salt * 83492791;
        h = (h ^ (h >>> 13)) * 1274126177;
        h = h ^ (h >>> 16);
        return h & 255;
    }

    int plankPx(int u, int v, int var) {
        if (v % 4 == 3) return tint(0x7A5A2E, 100);
        if (((u + ((v / 4) & 1) * 4) & 7) == 0) return tint(0x8A6A38, 100);
        return tint(0xB8945A, var);
    }

    // pixel 8x8 de un bloque: face 0 = arriba, 1 = costado, 2 = abajo
    int texBlock(int b, int face, int u, int v) {
        if (b < B_LOG_S || b > B_PLANKS_S + 1) return texBlock0(b, face, u, v);
        int q = b - B_LOG_S;
        int c = texBlock0(q < 2 ? B_LOG : (q < 4 ? B_LEAVES : B_PLANKS), face, u, v);
        return vt(c, q % 2 + 1, q >= 2 && q < 4);
    }

    int texBlock0(int b, int face, int u, int v) {
        int n = tn(u, v, b * 7 + face);
        int base = face == 0 ? cTop[b] : (face == 2 ? cBot[b] : cSide[b]);
        int var = 88 + n / 8;
        int dark = (n > 205) ? 70 : var;
        if (b == B_GRASS) {
            if (face == 0 || (face == 1 && (v < 2 || (v == 2 && n > 110)))) return tint(cTop[B_GRASS], var);
            return tint(0x7B5B34, dark);
        }
        if (b == B_DIRT || b == B_STONE) return tint(base, dark);
        if (b == B_COBBLE) {
            boolean mortar = (v % 4 == 3) || ((u + (v / 4) * 3) % 6 == 5);
            return mortar ? tint(base, 62) : tint(base, var + 8);
        }
        if (b == B_LOG) {
            if (face != 1) {
                int d = Math.max(Math.abs(2 * u - 7), Math.abs(2 * v - 7));
                if (d >= 7) return tint(0x5E4426, var);
                if (d == 3) return tint(0x8E6F3C, var);
                return tint(0xB8945A, var);
            }
            return tint(0x5E4426, (u % 3 == 0) ? 70 + n / 16 : var);
        }
        if (b == B_LEAVES) return (n > 170) ? 0x1E5A1C : tint(0x3F9A36, var);
        if (b == B_PLANKS) return plankPx(u, v, var);
        if (b == B_TABLE) {
            if (face == 0) {
                if (u == 0 || v == 0 || u == 7 || v == 7) return tint(0x5E3E1E, var);
                if (u == 3 || v == 3) return tint(0x7A5A2E, 100);
                return tint(0xA47C44, var);
            }
            if (face == 2) return plankPx(u, v, var);
            if (v < 2 || u == 0 || u == 7) return tint(0x5E3E1E, var);
            if (v >= 3 && v <= 4 && u >= 2 && u <= 3) return 0xC8C8C8;
            if (u == 5 && v >= 3 && v <= 6) return 0x3A220E;
            if (v == 3 && u == 6) return 0xC8C8C8;
            return tint(base, var);
        }
        if (b == B_FURNACE) {
            if (face == 0) {
                if (u >= 2 && u <= 5 && v >= 2 && v <= 5) return 0x151515;
                return tint(0x8A8A8A, var);
            }
            if (face == 2) return tint(0x6A6A6A, var);
            if (u >= 2 && u <= 5 && v >= 3 && v <= 6) return (v >= 6) ? 0xE07020 : 0x181818;
            if (v < 2) return tint(0x3A3A3A, var);
            return tint(0x707070, (u == 0 || u == 7) ? 75 : var);
        }
        if (b == B_CHEST) {
            if (face != 1) return (u == 0 || v == 0 || u == 7 || v == 7) ? tint(0x4A3018, var) : tint(base, var);
            if (u == 0 || u == 7 || v == 0 || v == 7) return tint(0x4A3018, var);
            if (v == 3) return tint(0x4A3018, 100);
            if (u >= 3 && u <= 4 && v >= 3 && v <= 4) return 0xE0C040;
            return tint(base, var);
        }
        if (b == B_SAND) return tint(base, 92 + n / 12);
        if (b == B_SANDSTONE) return (face == 1 && (v == 2 || v == 5)) ? tint(base, 80) : tint(base, var + 6);
        if (b == B_CACTUS) {
            if (face != 1) return (u == 0 || v == 0 || u == 7 || v == 7) ? tint(base, 70) : tint(0x4FA04A, var);
            if (n > 235) return 0xD8D8A0;
            return ((u & 1) == 0) ? tint(base, 76) : tint(base, var);
        }
        if (b == B_TNT) {
            if (face == 0) return (u >= 3 && u <= 4 && v >= 3 && v <= 4) ? 0x303030 : tint(0xC8C8C8, var);
            if (face == 2) return tint(0x888888, var);
            if (v == 3) return ((u & 1) == 1) ? 0x303030 : 0xE8E8E8;
            if (v == 4) return (u == 2 || u == 5) ? 0x303030 : 0xE8E8E8;
            if (v == 2 || v == 5) return 0xE8E8E8;
            return tint(0xB22A2A, ((u & 1) == 0) ? var : var - 8);
        }
        if (b >= B_COAL_ORE && b <= B_DIAM_ORE) {
            int oc = (b == B_COAL_ORE) ? 0x1E1E1E : (b == B_IRON_ORE ? 0xD8A878 : (b == B_GOLD_ORE ? 0xF0D030 : 0x5AE8E0));
            if (tn(u >> 1, v, 77) > 185) return oc;
            return tint(0x7D7D7D, dark);
        }
        if (b >= B_IRON_BLK && b <= B_DIAM_BLK) {
            if (u == 0 || v == 0 || u == 7 || v == 7) return tint(base, 78);
            if (u == 1 || v == 1) return tint(base, 114);
            return tint(base, 96 + n / 32);
        }
        if (b == B_NETHERRACK) return (n > 200) ? 0x4A1616 : ((n < 40) ? 0x9A4040 : tint(base, var));
        if (b == B_LAVA) return ((u + v * 2 + n / 64) % 5 == 0) ? 0xFFD040 : tint(0xE0601A, var + 10);
        if (b == B_OBSIDIAN) return (n > 215) ? 0x6A3FA0 : tint(base, var - 6);
        if (b == B_SOULSAND) return (((u + v) % 3 == 0) && n > 100) ? 0x2E2218 : tint(base, var);
        if (b == B_GLOWSTONE) return (n > 160) ? 0xFFF0B0 : ((n < 60) ? 0xC09A40 : tint(base, var + 8));
        if (b == B_BEDROCK) return (n > 150) ? 0x1A1A1A : ((n < 60) ? 0x6A6A6A : 0x444444);
        if (b == B_SNOW) return tint(base, 96 + n / 32);
        if (b == B_GRAVEL) return (n > 170) ? 0x5E5A57 : ((n < 50) ? 0xA8A29E : tint(base, var));
        if (b == B_ICE) return ((u + v) % 5 == 0) ? 0xE8F6FF : tint(base, var + 6);
        return tint(base, var);
    }

    static int pal(char c) {
        switch (c) {
            case 'K': return 0x202020;
            case 'k': return 0x101010;
            case 'W': return 0xF0F0F0;
            case 'w': return 0xBDBDBD;
            case 'G': return 0x8A8A8A;
            case 'g': return 0x555555;
            case 'B': return 0x8B6B3A;
            case 'b': return 0x5E4426;
            case 'T': return 0xC49A55;
            case 'R': return 0xC83A34;
            case 'r': return 0x8E1A1A;
            case 'P': return 0xE9B9A3;
            case 'p': return 0xC48A78;
            case 'O': return 0xE0903A;
            case 'o': return 0x8A5A3A;
            case 'Y': return 0xF0C020;
            case 'N': return 0x3FA03A;
            case 'n': return 0x2A6A2A;
            case 'Z': return 0x6B7A3A;
            case 'z': return 0x4A5A28;
            case 'D': return 0xD02020;
            case 'I': return 0xD8D8D8;
            case 'i': return 0x9A9A9A;
            case 'y': return 0xB8901A;
            case 'C': return 0x4AE8E0;
            case 'c': return 0x20A8B0;
            case 'E': return 0x2AD36A;
            case 'e': return 0x15803F;
            case 'U': return 0x2A5CE0;
            case 'u': return 0x1A3CA0;
            default: return -1;
        }
    }

    // dibujos 8x8 de items ('.' = transparente)
    static String[] patOf(int id) {
        if (id == I_STICK) return new String[] { "......Bb", ".....Bb.", "....Bb..", "...Bb...", "..Bb....", ".Bb.....", "Bb......", "b......." };
        if (id == I_PICK) return new String[] { "..TTTT..", ".T..BT.T", "T..B...T", "..B....T", ".B......", "B.......", "........", "........" };
        if (id == I_AXE) return new String[] { "...TT...", "..TTTT..", "..TTBT..", "...B.T..", "..B.....", ".B......", "B.......", "........" };
        if (id == I_SHOVEL) return new String[] { "......TT", ".....TTT", "....TTT.", "...BT...", "..B.....", ".B......", "B.......", "........" };
        if (id == I_SWORD) return new String[] { "......TT", ".....TT.", "....TT..", "...TT...", ".b.T....", "..bB....", ".B.b....", "B......." };
        if (id == I_WOOL) return new String[] { "........", ".WWWWWW.", ".WwWWwW.", ".WWWWWW.", ".WwWWWW.", ".WWwWWW.", ".WWWWWW.", "........" };
        if (id == I_MUTTON) return new String[] { "........", "..RRRR..", ".RRPRRR.", ".RRRRRr.", ".RRRRrr.", "..rrrW..", "....WW..", "........" };
        if (id == I_CHICKEN) return new String[] { "........", "..PPP...", ".PPPPP..", ".PPPpP..", "..PPP...", "...W....", "..WW....", "........" };
        if (id == I_FEATHER) return new String[] { "......ww", ".....www", "....wWw.", "...wWw..", "..wWw...", ".wWw....", "wb......", "b......." };
        if (id == I_APPLE) return new String[] { "...nN...", "....b...", "..DDDD..", ".DDDDDD.", ".DDPDDD.", ".DDDDDr.", "..DDrr..", "........" };
        if (id == I_BEEF) return new String[] { "........", ".rRRRR..", ".RRPRRr.", ".RRRRRr.", ".RRRrrr.", "..RRrr..", "........", "........" };
        if (id == I_STEAK) return new String[] { "........", ".oooooo.", ".oOoKoo.", ".ooOoKo.", ".oKooOo.", ".ooKooo.", "..oooo..", "........" };
        if (id == I_COOKED_MUTTON) return new String[] { "........", "..oooo..", ".ooOooo.", ".oooooK.", ".ooooKK.", "..KKKW..", "....WW..", "........" };
        if (id == I_COOKED_CHICKEN) return new String[] { "........", "..OOO...", ".OOOOO..", ".OOOoO..", "..OOO...", "...W....", "..WW....", "........" };
        if (id == I_COAL) return new String[] { "........", "..Kgg...", ".KggKgK.", ".gKKgKg.", ".KgKggK.", "..KgKg..", "........", "........" };
        if (id == I_ROTTEN) return new String[] { "........", "..ZZZZ..", ".ZZNZZZ.", ".ZZZZzz.", ".ZNZzzz.", "..zzzW..", "....WW..", "........" };
        if (id == I_GUNPOWDER) return new String[] { "........", "........", "...gg...", "..gGGg..", ".gGGGGg.", ".gGgGGg.", "gGGGGGGg", "........" };
        if (id == I_DOOR) return new String[] { "..bbbb..", ".bBBBBb.", ".bBbbBb.", ".bBBBBb.", ".bBbbBb.", ".bBBBYb.", ".bBbbBb.", ".bBBBBb." };
        if (id >= I_TOOLS && id < I_TOOLS + 16) {
            int t = (id - I_TOOLS) / 4;
            String[] w = patOf(I_PICK + ((id - I_TOOLS) & 3));
            String[] o = new String[8];
            char ch = (t == 0) ? 'G' : (t == 1 ? 'I' : (t == 2 ? 'Y' : 'C'));
            for (int i = 0; i < 8; i++) o[i] = w[i].replace('T', ch);
            return o;
        }
        if (id == I_IRON_INGOT || id == I_GOLD_INGOT) {
            String[] g = { "........", "...LLLL.", "..LLLLL.", ".LLWLLLl", ".LLLLLll", ".llllll.", "........", "........" };
            char hi = (id == I_IRON_INGOT) ? 'I' : 'Y', lo = (id == I_IRON_INGOT) ? 'i' : 'y';
            for (int i = 0; i < 8; i++) g[i] = g[i].replace('L', hi).replace('l', lo);
            return g;
        }
        if (id == I_LIGHTER) return new String[] { ".....Y..", "....YOY.", "....OOY.", "..IIIII.", "..IiiiI.", "..IIIII.", "..iiiii.", "........" };
        if (id == I_DIAMOND) return new String[] { "........", "..CCCC..", ".CWCCCC.", ".CCCCCc.", "..CCCc..", "...Cc...", "........", "........" };
        if (id == I_HOE) return new String[] { "..TTT...", "....TT..", "...B.T..", "..B.....", ".B......", "B.......", "........", "........" };
        if (id == I_SEEDS) return new String[] { "........", "........", "..N..n..", ".n..N...", "....n.N.", "..N...n.", "........", "........" };
        if (id == I_WHEAT) return new String[] { "..Y..Y..", ".YY.YY..", ".Yy.yY..", "..yYy...", "...y....", "...y....", "..by....", "........" };
        if (id == I_BREAD) return new String[] { "........", "........", "..oooo..", ".oOOOOo.", ".oOOOOo.", "..oooo..", "........", "........" };
        if (id == I_EMERALD) return new String[] { "........", "..EEEE..", ".EWEEEE.", ".EEEEEe.", "..EEEe..", "...Ee...", "........", "........" };
        if (id >= I_BUCKET && id <= I_BUCKET_L) {
            String[] g = { "........", ".iUUUUi.", ".iUUUUi.", "..iUUi..", "..iIIi..", "...ii...", "........", "........" };
            char fl = id == I_BUCKET_W ? 'U' : (id == I_BUCKET_L ? 'O' : 'g');
            for (int i = 0; i < 8; i++) g[i] = g[i].replace('U', fl);
            return g;
        }
        if (id == B_BED) return new String[] { "........", "........", "bWWRRRRb", "bWWRRRRb", "bRRRRRRb", "bbbbbbbb", "b......b", "b......b" };
        return null;
    }

    int[][] iconPx = new int[128][];

    int[] iconOf(int id) {
        int[] px = iconPx[id];
        if (px != null) return px;
        px = new int[64];
        String[] pt = (id > 19) ? patOf(id) : null;
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                int col;
                if (pt != null) col = pal(pt[r].charAt(c));
                else if (id <= 19 || (id >= B_COAL_ORE && id <= B_DIAM_BLK) || (id >= B_NETHERRACK && id <= B_OBSIDIAN) || (id >= B_SOULSAND && id <= B_BEDROCK) || id == B_CHEST || (id >= B_LOG_S && id <= B_FARM_DRY)) col = (r < 2) ? texBlock(id, 0, c, r + 3) : texBlock(id, 1, c, r);
                else col = icol[id];
                px[r * 8 + c] = col;
            }
        }
        iconPx[id] = px;
        return px;
    }

    void drawBox(float x0, float y0, float z0, float x1, float y1, float z1, int base, int lp) {
        for (int f = 0; f < 6; f++) {
            boolean vis;
            if (f == 0) vis = camX > x1;
            else if (f == 1) vis = camX < x0;
            else if (f == 2) vis = camY > y1;
            else if (f == 3) vis = camY < y0;
            else if (f == 4) vis = camZ > z1;
            else vis = camZ < z0;
            if (!vis) continue;
            for (int i = 0; i < 4; i++) {
                int c = FACE[f][i];
                toCam(((c & 1) == 0) ? x0 : x1, (((c >> 1) & 1) == 0) ? y0 : y1,
                        (((c >> 2) & 1) == 0) ? z0 : z1, quad, i * 3);
            }
            fillPoly(quad, 4, shade(base, FSH[f] * lp / 100));
        }
    }

    // plantita y antorcha: cajas finas, no cubos
    void drawSmall(int x, int y, int z, int b) {
        int lp = litPct(x + 0.5f, y + 0.5f, z + 0.5f);
        float cx = x + 0.5f, cz = z + 0.5f;
        if (b == B_TORCH) {
            drawBox(cx - 0.07f, y, cz - 0.07f, cx + 0.07f, y + 0.55f, cz + 0.07f, 0x8B6B3A, lp);
            drawBox(cx - 0.09f, y + 0.55f, cz - 0.09f, cx + 0.09f, y + 0.75f, cz + 0.09f, 0xFFD040, 100);
        } else if (b >= B_CROP0 && b <= B_CROP2) {
            float hh = 0.25f + (b - B_CROP0) * 0.3f;
            int cl = b == B_CROP2 ? 0xC8B040 : 0x4FB03A;
            drawBox(cx - 0.3f, y, cz - 0.3f, cx - 0.12f, y + hh, cz - 0.12f, cl, lp);
            drawBox(cx + 0.12f, y, cz + 0.12f, cx + 0.3f, y + hh, cz + 0.3f, cl, lp);
            drawBox(cx - 0.09f, y, cz - 0.09f, cx + 0.09f, y + hh + 0.1f, cz + 0.09f, cl, lp);
        } else {
            drawBox(cx - 0.05f, y, cz - 0.05f, cx + 0.05f, y + 0.4f, cz + 0.05f, 0x6B4A2A, lp);
            drawBox(cx - 0.3f, y + 0.3f, cz - 0.3f, cx + 0.3f, y + 0.8f, cz + 0.3f, 0x3FA03A, lp);
        }
    }

    boolean showFace(int b, int n) {
        if (b == B_WATER || b == B_LAVA) return n != b && !opaque(n);
        return !opaque(n);
    }

    void drawBlock(int x, int y, int z, int b) {
        if (b == B_SAPLING || b == B_TORCH || (b >= B_CROP0 && b <= B_CROP2)) { drawSmall(x, y, z, b); return; }
        if (isDoor(b)) { drawDoor(x, y, z, b); return; }
        if (b == B_BED) { drawBed(x, y, z); return; }
        if (b == B_PORTAL) { drawPortal(x, y, z); return; }
        int[] sc = shadedCol[b];
        if (camX > x + 1 && showFace(b, blockAt(x + 1, y, z))) faceBlock(x, y, z, 0, sc[0], b);
        if (camX < x && showFace(b, blockAt(x - 1, y, z))) faceBlock(x, y, z, 1, sc[1], b);
        if (camY > y + 1 && showFace(b, blockAt(x, y + 1, z))) faceBlock(x, y, z, 2, sc[2], b);
        if (camY < y && showFace(b, blockAt(x, y - 1, z))) faceBlock(x, y, z, 3, sc[3], b);
        if (camZ > z + 1 && showFace(b, blockAt(x, y, z + 1))) faceBlock(x, y, z, 4, sc[4], b);
        if (camZ < z && showFace(b, blockAt(x, y, z - 1))) faceBlock(x, y, z, 5, sc[5], b);
    }

    boolean isFrameBlk(int b) { return b == B_OBSIDIAN || b == B_PORTAL; }

    // panel violeta que parpadea entre dos tonos
    void drawPortal(int x, int y, int z) {
        boolean alongX = isFrameBlk(blockAt(x - 1, y, z)) || isFrameBlk(blockAt(x + 1, y, z));
        int col = (((tick / 8) + x + y + z) & 1) == 0 ? 0x8A2BE2 : 0x6A1BC2;
        if (alongX) drawBox(x, y, z + 0.4f, x + 1, y + 1, z + 0.6f, col, 100);
        else drawBox(x + 0.4f, y, z, x + 0.6f, y + 1, z + 1, col, 100);
    }

    void drawBed(int x, int y, int z) {
        int lp = litPct(x + 0.5f, y + 0.5f, z + 0.5f);
        drawBox(x, y, z, x + 1, y + 0.22f, z + 1, 0x6B4A2A, lp);
        drawBox(x + 0.03f, y + 0.22f, z + 0.03f, x + 0.97f, y + 0.5f, z + 0.97f, 0xC83A34, lp);
        drawBox(x + 0.1f, y + 0.5f, z + 0.06f, x + 0.9f, y + 0.6f, z + 0.34f, 0xF0F0F0, lp);
    }

    // puerta: panel fino con paneles hundidos y manija; abierta gira 90 grados hacia el borde de la celda
    void drawDoor(int x, int y, int z, int b) {
        int lp = litPct(x + 0.5f, y + 0.5f, z + 0.5f);
        boolean open = (b == B_DOOR_LO || b == B_DOOR_UO);
        boolean upper = (b == B_DOOR_UC || b == B_DOOR_UO);
        int ly = upper ? y - 1 : y;
        boolean spanX = doorAlongX(x, ly, z) != open;
        float t0 = open ? 0f : 0.43f, t1 = open ? 0.13f : 0.57f;
        float pe = 0.03f;
        if (spanX) {
            drawBox(x, y, z + t0, x + 1, y + 1, z + t1, 0x8A6A36, lp);
            drawBox(x + 0.15f, y + 0.12f, z + t0 - pe, x + 0.85f, y + 0.88f, z + t1 + pe, 0x6E5128, lp);
            if (!upper) drawBox(x + 0.76f, y + 0.45f, z + t0 - 0.06f, x + 0.86f, y + 0.55f, z + t1 + 0.06f, 0xE0C040, lp);
        } else {
            drawBox(x + t0, y, z, x + t1, y + 1, z + 1, 0x8A6A36, lp);
            drawBox(x + t0 - pe, y + 0.12f, z + 0.15f, x + t1 + pe, y + 0.88f, z + 0.85f, 0x6E5128, lp);
            if (!upper) drawBox(x + t0 - 0.06f, y + 0.45f, z + 0.76f, x + t1 + 0.06f, y + 0.55f, z + 0.86f, 0xE0C040, lp);
        }
    }

    void drawMob(Mob m) {
        float[][] parts = (m.type == 3 && dim == 1) ? pigmanParts : mobParts(m.type);
        int mpct = litPct(m.x, m.y + 0.6f, m.z);
        float cs = (float) Math.cos(m.ang), sn = (float) Math.sin(m.ang);
        for (int pi = 0; pi < parts.length; pi++) {
            float[] p = parts[pi];
            float cx = m.x + p[0] * cs + p[2] * sn;
            float cz = m.z - p[0] * sn + p[2] * cs;
            float cy = m.y + p[1];
            for (int c = 0; c < 8; c++) {
                float lx = ((c & 1) == 0) ? -p[3] : p[3];
                float ly = ((c & 2) == 0) ? -p[4] : p[4];
                float lz = ((c & 4) == 0) ? -p[5] : p[5];
                toCam(cx + lx * cs + lz * sn, cy + ly, cz - lx * sn + lz * cs, cc, c * 3);
            }
            int base = (int) p[6];
            if (m.hurt > 0) base = 0xFF5555;
            else if (m.fuse > 0 && (m.fuse / 3) % 2 == 0) base = 0xFFFFFF;
            for (int f = 0; f < 6; f++) {
                float lcx = NX[f] * p[3], lcy = NY[f] * p[4], lcz = NZ[f] * p[5];
                float fx2 = cx + lcx * cs + lcz * sn;
                float fz2 = cz - lcx * sn + lcz * cs;
                float fy2 = cy + lcy;
                float nwx = NX[f] * cs + NZ[f] * sn;
                float nwz = -NX[f] * sn + NZ[f] * cs;
                float nwy = NY[f];
                if (nwx * (camX - fx2) + nwy * (camY - fy2) + nwz * (camZ - fz2) <= 0f) continue;
                for (int i = 0; i < 4; i++) {
                    int ci = FACE[f][i] * 3;
                    quad[i * 3] = cc[ci]; quad[i * 3 + 1] = cc[ci + 1]; quad[i * 3 + 2] = cc[ci + 2];
                }
                fillPoly(quad, 4, shade(base, FSH[f] * mpct / 100));
            }
        }
    }

    void line3(float[] v, int i, int j) {
        float ax1 = v[i], ay1 = v[i + 1], az1 = v[i + 2];
        float bx = v[j], by = v[j + 1], bz = v[j + 2];
        if (az1 < NEAR && bz < NEAR) return;
        if (az1 < NEAR) {
            float t = (NEAR - az1) / (bz - az1);
            ax1 = ax1 + (bx - ax1) * t; ay1 = ay1 + (by - ay1) * t; az1 = NEAR;
        } else if (bz < NEAR) {
            float t = (NEAR - bz) / (az1 - bz);
            bx = bx + (ax1 - bx) * t; by = by + (ay1 - by) * t; bz = NEAR;
        }
        int x1 = (int) (halfW + ax1 / az1 * focal), y1 = (int) (halfH - ay1 / az1 * focal);
        int x2 = (int) (halfW + bx / bz * focal), y2 = (int) (halfH - by / bz * focal);
        if (x1 < -3000 || x1 > 3000 || y1 < -3000 || y1 > 3000) return;
        if (x2 < -3000 || x2 > 3000 || y2 < -3000 || y2 > 3000) return;
        gg.drawLine(x1, y1, x2, y2);
    }

    void drawWire(int x, int y, int z) {
        for (int c = 0; c < 8; c++) {
            toCam(x + (c & 1), y + ((c >> 1) & 1), z + ((c >> 2) & 1), cc, c * 3);
        }
        gg.setColor(0xFFFFFF);
        for (int e = 0; e < 12; e++) line3(cc, WIRE[e][0] * 3, WIRE[e][1] * 3);
    }

    void buildOrder(int[] o, int n, int c) {
        if (c < 0) c = 0;
        if (c > n - 1) c = n - 1;
        int k = 0;
        for (int i = 0; i < c; i++) o[k++] = i;
        for (int i = n - 1; i > c; i--) o[k++] = i;
        o[k++] = c;
    }

    void paintWorld() {
        gg.setColor(skyColor());
        gg.fillRect(0, 0, sw, sh);

        camX = px; camY = py + EYE; camZ = pz;
        cosY = (float) Math.cos(yaw); sinY = (float) Math.sin(yaw);
        cosP = (float) Math.cos(pitch); sinP = (float) Math.sin(pitch);
        fx = sinY * cosP; fy = sinP; fz = cosY * cosP;
        halfW = sw / 2f; halfH = sh / 2f;
        focal = halfW / (float) Math.tan(FOV / 2f);

        int bx0 = ifloor(camX) - rd, bz0 = ifloor(camZ) - rd;
        buildOrder(oy, H, ifloor(camY));
        for (int i = 0; i < H; i++) invY[oy[i]] = i;

        // mobs dentro del area, ordenados segun el orden del pintor
        int nm = 0;
        int cnt = mobs.size();
        if (cnt > 64) cnt = 64;
        for (int i = 0; i < cnt; i++) {
            Mob m = (Mob) mobs.elementAt(i);
            int bi = ifloor(m.x) - bx0, bk = ifloor(m.z) - bz0;
            if (bi < 0 || bi >= nb || bk < 0 || bk >= nb) continue;
            int cy = ifloor(m.y + 0.4f);
            if (cy < 0) cy = 0;
            if (cy > H - 1) cy = H - 1;
            int key = (invX[bi] * nb + invZ[bk]) * H + invY[cy];
            int j = nm++;
            while (j > 0 && mobKey[j - 1] > key) {
                mobKey[j] = mobKey[j - 1];
                mobOrd[j] = mobOrd[j - 1];
                j--;
            }
            mobKey[j] = key;
            mobOrd[j] = i;
        }
        int mp = 0;

        float absFy = (fy < 0f) ? -fy : fy;
        float slack = absFy * (H + 4) + 1.8f;

        for (int a = 0; a < nb; a++) {
            int x = bx0 + ox[a];
            for (int b = 0; b < nb; b++) {
                int z = bz0 + oz[b];
                byte[] cd = chunkFor(x, z);
                if (cd == null) continue;
                float cdx = x + 0.5f - camX, cdz = z + 0.5f - camZ;
                if (cdx * fx + cdz * fz < -slack) continue;
                int base = (((x & 15) << 4) + (z & 15)) * H;
                int colKey = (a * nb + b) * H;
                for (int c = 0; c < H; c++) {
                    int y = oy[c];
                    int blk = cd[base + y];
                    if (blk != 0) {
                        float dx = cdx, dy = y + 0.5f - camY, dz = cdz;
                        float dot = dx * fx + dy * fy + dz * fz;
                        if (dot > -1.8f && dx * dx + dy * dy + dz * dz < cullSq) drawBlock(x, y, z, blk);
                    }
                    while (mp < nm && mobKey[mp] <= colKey + c) {
                        drawMob((Mob) mobs.elementAt(mobOrd[mp]));
                        mp++;
                    }
                }
            }
        }

        if (tx >= 0 && tMob == null) drawWire(tx, ty, tz);

        if (blockAt(ifloor(camX), ifloor(camY), ifloor(camZ)) == B_WATER) {
            if (tintBuf == null || tintBuf.length != sw * 16) {
                tintBuf = new int[sw * 16];
                for (int i = 0; i < tintBuf.length; i++) tintBuf[i] = 0x702050D0;
            }
            for (int yy = 0; yy < sh; yy += 16) gg.drawRGB(tintBuf, 0, sw, 0, yy, sw, Math.min(16, sh - yy), true);
        }
    }

    // ---------------------------------------------------------------- dibujo 2D
    void txt(String s, int x, int y, int c, int anchor) {
        s = L(s);
        gg.setColor(0x000000);
        gg.drawString(s, x + 1, y + 1, anchor);
        gg.setColor(c);
        gg.drawString(s, x, y, anchor);
    }

    void drawIcon(int id, int dur, int x, int y, int s) {
        if (id == 0) return;
        int m = s / 6;
        int ix = x + m, iy = y + m, is = s - 2 * m;
        if (id == B_TORCH) {
            gg.setColor(0x8B6B3A);
            gg.fillRect(ix + is / 2 - 1, iy + is / 3, 3, is - is / 3);
            gg.setColor(0xFFC030);
            gg.fillRect(ix + is / 2 - 2, iy, 5, is / 3 + 1);
        } else if (id == B_SAPLING) {
            gg.setColor(0x6B4A2A);
            gg.fillRect(ix + is / 2 - 1, iy + is / 2, 2, is - is / 2);
            gg.setColor(0x3FA03A);
            gg.fillRect(ix + is / 4, iy, is / 2, is / 2 + 1);
        } else {
            int[] px = iconOf(id);
            int c = (s - 2) / 8;
            if (c < 1) c = 1;
            int ox2 = x + (s - c * 8) / 2, oy2 = y + (s - c * 8) / 2;
            for (int r = 0; r < 8; r++) {
                int c0 = 0;
                while (c0 < 8) {
                    int col = px[r * 8 + c0];
                    int c1 = c0 + 1;
                    while (c1 < 8 && px[r * 8 + c1] == col) c1++;
                    if (col >= 0) {
                        gg.setColor(col);
                        gg.fillRect(ox2 + c0 * c, oy2 + r * c, (c1 - c0) * c, c);
                    }
                    c0 = c1;
                }
            }
            if (id <= B_ICE || (id >= B_COAL_ORE && id <= B_DIAM_BLK) || (id >= B_NETHERRACK && id <= B_OBSIDIAN) || (id >= B_SOULSAND && id <= B_BEDROCK) || id == B_CHEST || (id >= B_LOG_S && id <= B_FARM_DRY)) {
                gg.setColor(0x000000);
                gg.drawRect(ox2 - 1, oy2 - 1, c * 8 + 1, c * 8 + 1);
            }
            if (isTool(id) && dur > 0 && dur < toolMax(id)) {
                int bw = (is * dur) / toolMax(id);
                gg.setColor(dur * 3 < toolMax(id) ? 0xE02020 : 0x20C020);
                gg.fillRect(ix, y + s - 3, bw, 2);
            }
        }
    }

    void drawCount(int cnt, int x, int y, int s) {
        if (cnt > 1) txt(Integer.toString(cnt), x + s - 2, y + s - 1, 0xFFFFFF, Graphics.BOTTOM | Graphics.RIGHT);
    }

    void paintHud() {
        int cx = sw / 2, cy = sh / 2;
        gg.setColor(0xFFFFFF);
        gg.drawLine(cx - 5, cy, cx + 5, cy);
        gg.drawLine(cx, cy - 5, cx, cy + 5);

        if (mineProg > 0 && mineX >= 0) {
            int need = breakTicks(blockAt(mineX, mineY, mineZ), slId[sel]);
            int bw = 24 * mineProg / need;
            gg.setColor(0x000000);
            gg.fillRect(cx - 13, cy + 10, 26, 5);
            gg.setColor(0xFFFFFF);
            gg.fillRect(cx - 12, cy + 11, bw, 3);
        }

        int hs = Math.min(sw / 10, 24);
        int hx = (sw - 9 * hs) / 2;
        int hy = sh - hs - 3;
        for (int i = 0; i < 9; i++) {
            gg.setColor(0x404040);
            gg.fillRect(hx + i * hs, hy, hs, hs);
            gg.setColor(0x909090);
            gg.drawRect(hx + i * hs, hy, hs - 1, hs - 1);
            drawIcon(slId[i], slDur[i], hx + i * hs, hy, hs);
            drawCount(slCnt[i], hx + i * hs, hy, hs);
        }
        gg.setColor(0xFFFFFF);
        gg.drawRect(hx + sel * hs - 1, hy - 1, hs + 1, hs + 1);
        gg.drawRect(hx + sel * hs, hy, hs - 1, hs - 1);

        int ry = hy - 9;
        if (!creative) {
        for (int i = 0; i < 10; i++) {
            int x = hx + i * 8;
            gg.setColor(0x401010);
            gg.fillRect(x, ry, 7, 7);
            int hp2 = health - i * 2;
            gg.setColor(0xE02020);
            if (hp2 >= 2) gg.fillRect(x, ry, 7, 7);
            else if (hp2 == 1) gg.fillRect(x, ry, 4, 7);
        }
        for (int i = 0; i < 10; i++) {
            int x = hx + 9 * hs - (i + 1) * 8;
            gg.setColor(0x402810);
            gg.fillRect(x, ry, 7, 7);
            int hg = hunger - i * 2;
            gg.setColor(0xC08030);
            if (hg >= 2) gg.fillRect(x, ry, 7, 7);
            else if (hg == 1) gg.fillRect(x + 3, ry, 4, 7);
        }
        }
        if (air < 300) {
            int nbub = (air * 10 + 299) / 300;
            gg.setColor(0xA8DCFF);
            for (int i = 0; i < nbub; i++) gg.fillRect(hx + 9 * hs - 8 - i * 9, ry - 24, 7, 7);
        }
        if (hungerFx > 0) txt("Hambre", hx + 9 * hs, ry - 3, 0x9ACD32, Graphics.BOTTOM | Graphics.RIGHT);
        if (labelT > 0 && slId[sel] != 0) {
            txt(names[slId[sel]], sw / 2, ry - 3, 0xFFFFFF, Graphics.BOTTOM | Graphics.HCENTER);
        }
        if (msgT > 0) txt(msg, sw / 2, sh / 4, 0xFFFF80, Graphics.TOP | Graphics.HCENTER);
        if (creative) txt(flying ? "Creativo (vuelo)" : "Creativo", sw / 2, 2, 0xA0FFA0, Graphics.TOP | Graphics.HCENTER);
        txt(layout == 0 ? "[A]" : "[B]", sw - 3, 2, 0xFFFF55, Graphics.TOP | Graphics.RIGHT);

        if (portalT > 0) {
            int th = portalT / 4 + 2;
            gg.setColor(0x8A2BE2);
            gg.fillRect(0, 0, sw, th);
            gg.fillRect(0, sh - th, sw, th);
            gg.fillRect(0, 0, th, sh);
            gg.fillRect(sw - th, 0, th, sh);
        }
        if (dmgFlash > 0) {
            gg.setColor(0xFF0000);
            gg.fillRect(0, 0, sw, 4);
            gg.fillRect(0, sh - 4, sw, 4);
            gg.fillRect(0, 0, 4, sh);
            gg.fillRect(sw - 4, 0, 4, sh);
        }
    }

    void paintInv() {
        gg.setColor(0x202020);
        gg.fillRect(0, 0, sw, sh);
        boolean pm = palMode();
        String ttl = craftN == CHEST ? "Cofre" : (craftN == FURN ? "Horno" : (craftN == 3 ? "Mesa de crafteo" : "Inventario"));
        if (pm) ttl = new StringBuffer(L("Creativo")).append("  [1] ").append(palPage + 1).append('/').append((palN + 26) / 27).append(" [3]").toString();
        if (craftN == TRADE) ttl = "Aldeano  [1][3] lista";
        txt(ttl, sw / 2, 1, 0xFFFFFF, Graphics.TOP | Graphics.HCENTER);
        int s = slS;
        for (int i = 0; i < NSLOT; i++) {
            if (!slActive[i]) continue;
            gg.setColor(i == OUT ? 0x6B8B6B : 0x8B8B8B);
            gg.fillRect(slx[i], sly[i], s, s);
            gg.setColor(0x373737);
            gg.drawRect(slx[i], sly[i], s - 1, s - 1);
            if (pm && i >= 9 && i < 36) {
                int pid = palAt(i);
                drawIcon(pid, isTool(pid) ? toolMax(pid) : 0, slx[i], sly[i], s);
            } else {
                drawIcon(slId[i], slDur[i], slx[i], sly[i], s);
                drawCount(slCnt[i], slx[i], sly[i], s);
            }
        }
        if (craftN == FURN) {
            int bx = slx[36] + s + 4, bw = slx[38] - bx - 4, by = sly[38] + s / 2 - 3;
            gg.setColor(0x404040);
            gg.fillRect(bx, by, bw, 6);
            gg.setColor(0xF0F0F0);
            gg.fillRect(bx, by, bw * fProg / SMELT_T, 6);
            int fxx = slx[36] + s / 4, fyy = sly[36] + s + s / 4;
            gg.setColor(0x404040);
            gg.fillRect(fxx, fyy, s / 2, s / 2);
            if (fBurn > 0 && fBurnMax > 0) {
                int hh = (s / 2) * fBurn / fBurnMax;
                gg.setColor(0xFF8000);
                gg.fillRect(fxx, fyy + s / 2 - hh, s / 2, hh);
            }
        } else if (craftN == TRADE) {
            paintTrade();
        } else if (craftN != CHEST) {
            int ax0 = slx[OUT] - s;
            txt("=>", ax0 + s / 2, sly[OUT] + s / 2 - 5, 0xFFFFFF, Graphics.TOP | Graphics.HCENTER);
        }
        gg.setColor(0xFFFF00);
        gg.drawRect(slx[cursor], sly[cursor], s - 1, s - 1);
        gg.drawRect(slx[cursor] + 1, sly[cursor] + 1, s - 3, s - 3);
        if (heldId != 0) {
            int hx = slx[cursor] + s / 3, hy = sly[cursor] + s / 3;
            drawIcon(heldId, heldDur, hx, hy, s);
            drawCount(heldCnt, hx, hy, s);
        }
        int nid = (heldId != 0) ? heldId : ((pm && cursor >= 9 && cursor < 36) ? palAt(cursor) : slId[cursor]);
        if (nid != 0) {
            String nm = names[nid];
            if (cursor == OUT && heldId == 0) nm = new StringBuffer(L("Resultado: ")).append(nm).toString();
            txt(nm, sw / 2, sh - 2, 0xFFFFFF, Graphics.BOTTOM | Graphics.HCENTER);
        }
    }

    // ---------------------------------------------------------------- menus estilo Pocket Edition
    void addHit(int x, int y, int w, int h, int id) {
        if (htN >= 64) return;
        htX[htN] = x; htY[htN] = y; htW[htN] = w; htH[htN] = h; htId[htN] = id;
        htN++;
    }

    String fit(String s, int maxW) {
        if (font.stringWidth(s) <= maxW) return s;
        while (s.length() > 1 && font.stringWidth(cat(s, "..")) > maxW) s = s.substring(0, s.length() - 1);
        return cat(s, "..");
    }

    boolean isSel(int id) {
        return navN > 0 && navIds[navSel] == id;
    }

    void metrics() {
        btnH = font.getHeight() + 10;
        if (btnH < 22) btnH = 22;
        btnW = sw - 24;
        if (btnW > 200) btnW = 200;
        if (btnW < 80) btnW = sw - 4;
        btnX = (sw - btnW) / 2;
    }

    // fondo de tierra
    void drawDirt(int pct) {
        int t = 16;
        for (int ty = 0; ty * t < sh; ty++) {
            for (int tx = 0; tx * t < sw; tx++) {
                int h0 = tx * 73856093 ^ ty * 19349663;
                h0 = (h0 ^ (h0 >>> 13)) * 1274126177;
                int h = (h0 >>> 8) & 3;
                int c = (h == 0) ? 0x6B4F30 : ((h == 1) ? 0x7B5B34 : ((h == 2) ? 0x5E4426 : 0x6F5232));
                gg.setColor(shade(c, pct));
                gg.fillRect(tx * t, ty * t, t, t);
                gg.setColor(shade(c, pct * 70 / 100));
                gg.fillRect(tx * t + ((h0 >>> 12) & 7) + 2, ty * t + ((h0 >>> 16) & 7) + 2, 3, 3);
            }
        }
    }

    // oscurece lo que hay detras (rayas negras alternadas)
    void dim() {
        gg.setColor(0x000000);
        for (int y = 0; y < sh; y += 2) gg.drawLine(0, y, sw, y);
    }

    void title(String s, int y, Font f, int col, int shadow) {
        s = L(s);
        gg.setFont(f);
        gg.setColor(shadow);
        gg.drawString(s, sw / 2 + 2, y + 2, Graphics.TOP | Graphics.HCENTER);
        gg.setColor(col);
        gg.drawString(s, sw / 2, y, Graphics.TOP | Graphics.HCENTER);
        gg.setFont(font);
    }

    int drawLogo(int y) {
        title("MINECRAFT", y, fontBig, 0xC6C6C6, 0x2B2B2B);
        y += fontBig.getHeight() + 1;
        title("MICRO EDITION", y, fontMid, 0xFFE24A, 0x3A3000);
        return y + fontMid.getHeight();
    }

    void drawBtn(int x, int y, int w, int h, String label, boolean on) {
        label = L(label);
        int base = on ? 0x6F80C4 : 0x7C7C7C;
        int hi = on ? 0xA3B0E6 : 0xB4B4B4;
        int lo = on ? 0x3E4B8C : 0x4A4A4A;
        gg.setColor(0x000000);
        gg.fillRect(x, y, w, h);
        gg.setColor(base);
        gg.fillRect(x + 1, y + 1, w - 2, h - 2);
        gg.setColor(hi);
        gg.fillRect(x + 1, y + 1, w - 2, 2);
        gg.fillRect(x + 1, y + 1, 2, h - 2);
        gg.setColor(lo);
        gg.fillRect(x + 1, y + h - 3, w - 2, 2);
        gg.fillRect(x + w - 3, y + 1, 2, h - 2);
        txt(fit(label, w - 8), x + w / 2, y + (h - font.getHeight()) / 2, on ? 0xFFFFA0 : 0xFFFFFF,
                Graphics.TOP | Graphics.HCENTER);
    }

    void btn(int id, String label, int x, int y, int w, int h) {
        drawBtn(x, y, w, h, label, isSel(id));
        addHit(x, y, w, h, id);
    }

    void paintMenu() {
        metrics();
        drawDirt(55);
        int y = drawLogo(Math.max(6, sh / 14));
        y += Math.max(10, sh / 12);
        btn(A_PLAY, "Jugar", btnX, y, btnW, btnH);
        y += btnH + 8;
        btn(A_LANG, lang == 0 ? "Idioma: Espanol" : "Language: English", btnX, y, btnW, btnH);
        y += btnH + 8;
        btn(A_QUIT, "Salir", btnX, y, btnW, btnH);
        txt("En juego: L o R = pausa", sw / 2, sh - 3, 0xB0B0B0, Graphics.BOTTOM | Graphics.HCENTER);
    }

    void paintWorlds() {
        metrics();
        drawDirt(50);
        title("Selecciona un mundo", 4, fontMid, 0xFFFFFF, 0x202020);
        int y0 = 4 + fontMid.getHeight() + 6;
        btn(A_NEW, "+ Crear mundo nuevo", btnX, y0, btnW, btnH);
        int backY = sh - btnH - 4;
        btn(A_WBACK, "Atras", btnX, backY, btnW, btnH);
        int top = y0 + btnH + 6, bot = backY - 4, step = btnH + 3;
        int n = worlds.size();
        int vis = (bot - top + 3) / step;
        if (vis < 1) vis = 1;
        wVis = vis;
        int maxS = n - vis;
        if (maxS < 0) maxS = 0;
        if (wScroll > maxS) wScroll = maxS;
        if (wScroll < 0) wScroll = 0;
        if (n == 0) txt("No hay mundos todavia", sw / 2, top + 6, 0xDDDDDD, Graphics.TOP | Graphics.HCENTER);
        int xs = btnH - 6;
        for (int r = 0; r < vis; r++) {
            int i = wScroll + r;
            if (i >= n) break;
            WorldInfo w = (WorldInfo) worlds.elementAt(i);
            int y = top + r * step;
            int rw2 = btnW - xs - 4;
            drawBtn(btnX, y, rw2, btnH, w.name, isSel(100 + i));
            addHit(btnX, y, rw2, btnH, 100 + i);
            int bx = btnX + btnW - xs, by = y + 3;
            gg.setColor(0x000000);
            gg.fillRect(bx, by, xs, xs);
            gg.setColor(0x9A3030);
            gg.fillRect(bx + 1, by + 1, xs - 2, xs - 2);
            txt("X", bx + xs / 2, by + (xs - font.getHeight()) / 2, 0xFFFFFF, Graphics.TOP | Graphics.HCENTER);
            addHit(bx, y, xs, btnH, 200 + i);
        }
        if (wScroll > 0) txt("^", sw - 4, top, 0xFFFFFF, Graphics.TOP | Graphics.RIGHT);
        if (wScroll + vis < n) txt("v", sw - 4, bot - font.getHeight(), 0xFFFFFF, Graphics.TOP | Graphics.RIGHT);
    }

    void paintCreate() {
        metrics();
        drawDirt(50);
        title("Crear mundo nuevo", 4, fontMid, 0xFFFFFF, 0x202020);
        int y = 4 + fontMid.getHeight() + 14;
        btn(A_CNAME, cat(L("Nombre: "), cName), btnX, y, btnW, btnH);
        y += btnH + 6;
        btn(A_CSEED, cat(L("Semilla: "), cSeedTxt.length() == 0 ? L("aleatoria") : cSeedTxt), btnX, y, btnW, btnH);
        y += btnH + 6;
        btn(A_CMODE, cat(L("Modo: "), cMode == 1 ? L("Creativo") : L("Supervivencia")), btnX, y, btnW, btnH);
        y += btnH + 6;
        btn(A_CGO, "Crear", btnX, y, btnW, btnH);
        btn(A_CBACK, "Atras", btnX, sh - btnH - 4, btnW, btnH);
    }

    void paintConfirm() {
        metrics();
        drawDirt(40);
        String nm = (delTarget != null) ? delTarget.name : "";
        int y = sh / 5;
        title("Borrar mundo?", y, fontMid, 0xFFFFFF, 0x202020);
        y += fontMid.getHeight() + 8;
        txt(fit(nm, sw - 10), sw / 2, y, 0xFFFF80, Graphics.TOP | Graphics.HCENTER);
        y += font.getHeight() + 2;
        txt("No se puede deshacer", sw / 2, y, 0xFF8080, Graphics.TOP | Graphics.HCENTER);
        y += font.getHeight() + 14;
        btn(A_YES, "Si, borrar", btnX, y, btnW, btnH);
        y += btnH + 8;
        btn(A_NO, "Cancelar", btnX, y, btnW, btnH);
    }

    void paintPause() {
        paintWorld();
        dim();
        metrics();
        int total = fontMid.getHeight() + 14 + 3 * btnH + 2 * 8;
        int y = (sh - total) / 2;
        if (y < 2) y = 2;
        title("Juego en pausa", y, fontMid, 0xFFFFFF, 0x202020);
        y += fontMid.getHeight() + 14;
        btn(A_RESUME, "Reanudar", btnX, y, btnW, btnH);
        y += btnH + 8;
        btn(A_SETTINGS, "Ajustes", btnX, y, btnW, btnH);
        y += btnH + 8;
        btn(A_TOMENU, "Menu principal", btnX, y, btnW, btnH);
        if (storeBroken) txt("No se pudo guardar!", sw / 2, y + btnH + 4, 0xFF5555, Graphics.TOP | Graphics.HCENTER);
    }

    void paintSettings() {
        paintWorld();
        dim();
        metrics();
        int total = fontMid.getHeight() + 14 + btnH + 8 + 2 * font.getHeight() + 8 + btnH;
        int y = (sh - total) / 2;
        if (y < 2) y = 2;
        title("Ajustes", y, fontMid, 0xFFFFFF, 0x202020);
        y += fontMid.getHeight() + 14;
        btn(A_RD, new StringBuffer("Render: ").append(L(RD_NAME[rdIdx])).append(" (").append(RD_OPT[rdIdx]).append(")").toString(), btnX, y, btnW, btnH);
        y += btnH + 8;
        txt("4 / 6 o 5: cambiar", sw / 2, y, 0xDDDDDD, Graphics.TOP | Graphics.HCENTER);
        y += font.getHeight();
        txt("Mas lejos = mas lento", sw / 2, y, 0xDDDDDD, Graphics.TOP | Graphics.HCENTER);
        y += font.getHeight() + 8;
        btn(A_SETBACK, "Atras", btnX, y, btnW, btnH);
    }

    void paintDead() {
        int w = Math.min(sw - 10, 160), h = 50;
        int x = (sw - w) / 2, y = (sh - h) / 2;
        gg.setColor(0x600000);
        gg.fillRect(x, y, w, h);
        gg.setColor(0xFF4040);
        gg.drawRect(x, y, w - 1, h - 1);
        txt("MORISTE", sw / 2, y + 8, 0xFFFFFF, Graphics.TOP | Graphics.HCENTER);
        txt("5: reaparecer", sw / 2, y + 28, 0xFFFFFF, Graphics.TOP | Graphics.HCENTER);
    }

    void paintDebug() {
        long now = System.currentTimeMillis();
        dbgFrames++;
        if (now - dbgT0 >= 1000) {
            dbgFps = dbgFrames;
            dbgFrames = 0;
            dbgT0 = now;
            StringBuffer sb = new StringBuffer();
            if (now - dbgMemT >= 3000) {
                // memoria VIVA: se fuerza el GC y se resta lo que habia antes del juego
                dbgMemT = now;
                Runtime rt = Runtime.getRuntime();
                rt.gc();
                long live = rt.totalMemory() - rt.freeMemory() - Minecraft2D.memBase;
                sb.append("RAM juego ").append(live / 1024).append('K');
                dbgMem = sb.toString();
                sb.setLength(0);
            }
            sb.append("FPS ").append(dbgFps).append("  Mobs ").append(mobs.size());
            dbgInfo = sb.toString();
        }
        StringBuffer sc = new StringBuffer();
        sc.append("Ch ").append(nch).append(" pend ").append(pending.size()).append(" disco ").append(idx.size());
        String l3 = sc.toString();
        sc.setLength(0);
        sc.append("X").append(ifloor(px)).append(" Y").append(ifloor(py)).append(" Z").append(ifloor(pz));
        String l4 = sc.toString();

        int lh = font.getHeight();
        int w = font.stringWidth(dbgMem);
        int w2 = font.stringWidth(dbgInfo);
        int w3 = font.stringWidth(l3);
        int w4 = font.stringWidth(l4);
        if (w2 > w) w = w2;
        if (w3 > w) w = w3;
        if (w4 > w) w = w4;
        gg.setColor(0x000000);
        gg.fillRect(1, 1, w + 4, lh * 4 + 3);
        gg.setColor(0x55FF55);
        gg.drawString(dbgMem, 3, 2, Graphics.TOP | Graphics.LEFT);
        gg.drawString(dbgInfo, 3, 2 + lh, Graphics.TOP | Graphics.LEFT);
        gg.drawString(l3, 3, 2 + lh * 2, Graphics.TOP | Graphics.LEFT);
        gg.drawString(l4, 3, 2 + lh * 3, Graphics.TOP | Graphics.LEFT);
    }

    protected void paint(Graphics g) {
        synchronized (lock) {
            gg = g;
            sw = getWidth();
            sh = getHeight();
            g.setFont(font);
            htN = 0;
            if (state == ST_MENU) {
                paintMenu();
            } else if (state == ST_WORLDS) {
                paintWorlds();
            } else if (state == ST_CREATE) {
                paintCreate();
            } else if (state == ST_CONFIRM) {
                paintConfirm();
            } else if (state == ST_PAUSE) {
                paintPause();
            } else if (state == ST_SET) {
                paintSettings();
            } else if (state == ST_INV) {
                if (!slActive[OUT]) layoutInv();
                paintInv();
            } else {
                paintWorld();
                paintHud();
                if (Minecraft2D.DEBUG) paintDebug();
                if (state == ST_DEAD) paintDead();
            }
            if (busy != null) {
                int lh = font.getHeight();
                gg.setColor(0x000000);
                gg.fillRect(0, sh - lh - 4, sw, lh + 4);
                txt(busy, sw / 2, sh - 2, 0xFFFF55, Graphics.BOTTOM | Graphics.HCENTER);
            }
        }
    }
}
