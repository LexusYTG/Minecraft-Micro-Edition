import javax.microedition.lcdui.*;
import javax.microedition.midlet.*;
import javax.microedition.rms.*;
import java.io.ByteArrayInputStream;
import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.control.ToneControl;
import javax.microedition.media.control.VolumeControl;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Random;
import java.util.Vector;

public class Minecraft2D extends MIDlet {
    // true = ventanita de depuracion (RAM, FPS, chunks, posicion) mientras se juega
static final boolean DEBUG = false;
    static long memBase;   // memoria viva antes de crear el juego (solo DEBUG)

    private Display display;
    private Minecraft3DCanvas canvas;

    public void startApp() {
        display = Display.getDisplay(this);
        if (canvas == null) {
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
    int col;   // color del pez tropical (aleatorio al nacer)
    float sp;          // barco: velocidad actual
    boolean sunk;      // barco: hundido por una corriente
    float vx, vz, oa;  // proyectil/dragon: velocidad y angulo de orbita
    int st;            // enderman: 1 = enojado; dragon: 0 orbita, 1 pasada, 2 posado
    boolean spw;       // nacio de un spawner (maximo 3 vivos por tipo)
    int snd;           // cuenta atras del sonido ambiente del mob
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
    static final int H = 64;             // altura del mundo
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
            A_NEW = 8, A_WBACK = 9, A_CNAME = 10, A_CSEED = 11, A_CGO = 12, A_CBACK = 13, A_YES = 14, A_NO = 15, A_CMODE = 16, A_LANG = 17,
            A_CTRL = 18, A_KEY0 = 40, A_KRESET = 60, A_KBACK = 61, A_PICK0 = 70;   // A_KEY0..+13 = teclas del esquema; A_PICK0..+14 = acciones del selector
    static final int ST_KEYS = 9, ST_END = 10, ST_LANG = 11;   // ST_LANG: pantalla de inicio para elegir idioma
    static final int A_SND = 20, A_MUS = 21, A_SFX = 22, A_FOL = 23, A_CDIFF = 24;   // sonido: menu / volumen de musica / volumen de efectos
    // Controles: cada tecla logica (0-9, 10 = *, 11 = #, 12 = LSK, 13 = RSK) tiene UNA accion (km[tecla], -1 = ninguna);
    // una accion puede estar en varias teclas. El esquema del menu Controles edita km[].
    static final int NKEY = 14;
    static final int NACT = 14, ACT_FW = 0, ACT_BK = 1, ACT_TL = 2, ACT_TR = 3, ACT_JUMP = 4, ACT_UP = 5, ACT_DOWN = 6,
            ACT_MINE = 7, ACT_INV = 8, ACT_HPREV = 9, ACT_HNEXT = 10, ACT_PAUSE = 11, ACT_SL = 12, ACT_SR = 13;
    // por defecto: 0 saltar, 1 mirar abajo, 2 adelante, 3 mirar arriba, 4 girar izq., 5 romper/colocar, 6 girar der.,
    // 7 mover izq., 8 retroceder, 9 mover der., * hotbar anterior, # hotbar siguiente, LSK inventario, RSK pausa
    static final int[] KEY_DEF = { ACT_JUMP, ACT_DOWN, ACT_FW, ACT_UP, ACT_TL, ACT_MINE, ACT_TR, ACT_SL, ACT_BK, ACT_SR,
            ACT_HPREV, ACT_HNEXT, ACT_INV, ACT_PAUSE };
    static final String[] ACT_NAME = { "Adelante", "Retroceder", "Girar izq.", "Girar der.", "Saltar", "Mirar arriba",
            "Mirar abajo", "Romper / colocar", "Inventario", "Hotbar anterior", "Hotbar siguiente", "Pausa",
            "Mover izq.", "Mover der." };
    static final int J_ENTER = 1, J_APPLY = 2, J_LEAVE = 3, J_SAVE = 4, J_FLUSH = 5;

    static final int B_AIR = 0, B_GRASS = 1, B_DIRT = 2, B_STONE = 3, B_LOG = 4,
            B_LEAVES = 5, B_PLANKS = 6, B_TABLE = 7, B_COBBLE = 8,
            B_FURNACE = 9, B_SAPLING = 10, B_TORCH = 11,
            B_SAND = 12, B_SANDSTONE = 13, B_CACTUS = 14, B_TNT = 15,
            B_WATER = 16, B_SNOW = 17, B_GRAVEL = 18, B_ICE = 19;

    static final int SEA = 15;
    // update aquatic: algas gigantes, pasto marino y 5 bloques de coral
    static final int B_KELP = 97, B_SEAGRASS = 98, B_CORAL0 = 99, B_CORAL4 = 103;
    // items acuaticos; mobs: 7 bacalao, 8 salmon, 9 pez tropical, 10 arana
    static final int I_STRING = 104, I_SPIDER_EYE = 105, I_ROD = 106, I_COD = 107, I_SALMON = 108, I_TROPICAL = 109,
            I_COOKED_COD = 110, I_COOKED_SALMON = 111, I_DRIED_KELP = 112;
    // barco (item + mob tipo 11) y bloque de magma (corriente descendente bajo el agua)
    static final int I_BOAT = 113, B_MAGMA = 114;
    // el End y las fortalezas: bloques 115-123, items 124-127; mobs 12 enderman, 13 blaze, 14 dragon, 15 bola de fuego
    static final int B_ENDSTONE = 115, B_NBRICK = 116, B_SBRICK = 117, B_FRAME = 118, B_FRAME_EYE = 119, B_ENDPORTAL = 120,
            B_SPAWNER = 121, B_CRYSTAL = 122, B_EGG = 123, I_BLAZE_ROD = 124, I_BLAZE_POWDER = 125, I_PEARL = 126, I_EYE = 127;
    static final int FY = 11, DRAGON_HP = 120;   // FY = piso de la fortaleza del Nether

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
    // cana de azucar, pastel, mesa de encantamiento (bloques); azucar, papel, libro, cuero, cubeta con leche (items)
    static final int B_TUFT = 136, B_FLOWER_R = 137, B_FLOWER_Y = 138;   // pasto alto y flores decorativas (no solidos)
    static final int B_CANE = 128, B_CAKE = 129, B_ENCH = 130, I_SUGAR = 131, I_PAPER = 132, I_BOOK = 133, I_LEATHER = 134, I_BUCKET_M = 135;
    static final int ENCH = 7;   // craftN = 7: mesa de encantamiento (36 = item a encantar, 37 = item a vender, OUT = resultado, 38 = experiencia)
    // los encantamientos viajan en slDur: bits 0-10 = durabilidad, desde el bit 11 = 6 encantamientos x 3 bits de nivel
    static final int DM = 0x7FF, ESH = 11;
    static final String[] EN_NAME = { "Filo", "Retroceso", "Eficiencia", "Irrompibilidad", "Fortuna", "Botin" };
    static final int[] EN_MAX = { 5, 2, 5, 3, 3, 3 };
    static final String[] ROM = { "", "I", "II", "III", "IV", "V" };
    static final int[] RK_ID = { B_DIAM_BLK, I_EYE, I_DIAMOND, I_PEARL, I_EMERALD, I_BLAZE_ROD, I_GOLD_INGOT, I_IRON_INGOT };
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
    float[] cO = new float[3], eX = new float[3], eY = new float[3], eZ = new float[3];   // origen y ejes de un bloque en camara
    byte[][] colTop = new byte[16][];   // altura (y+1) de la capa no vacia mas alta por columna, cache por chunk
    int[] topCx = new int[16], topCz = new int[16], topG = new int[16];
    int topGen = 1, topW = -1, camC;
    float cullK = 1f;                                  // distancia de dibujo adaptativa (baja sola si hay pocos FPS)
    boolean[] opq = new boolean[256];
    boolean opqOk;
    boolean[] spc = new boolean[256];      // bloques especiales (plantas, puertas...): sin mascara de caras
    byte[][] colSol = new byte[16][];      // altura de la capa solida contigua desde el fondo, por columna
    int lastSlot;
    float cullNow;
    Mob ride;                              // barco que se esta montando
    int palPress;                          // boton tactil de pagina presionado (1 izq, 2 der)
    int tick;

    int state = ST_MENU;
    int[] km = { ACT_JUMP, ACT_DOWN, ACT_FW, ACT_UP, ACT_TL, ACT_MINE, ACT_TR, ACT_SL, ACT_BK, ACT_SR,
            ACT_HPREV, ACT_HNEXT, ACT_INV, ACT_PAUSE };   // tecla logica -> accion (-1 = ninguna)
    // esquema de controles: ksel = celda elegida (0-13 teclas, 14 = Restaurar, 15 = Atras); kpick = selector de accion abierto
    int ksel = 5, kpSel;
    boolean kpick, kpFollow;
    boolean flatFol;                  // follaje plano: algas, pasto marino, cana y cultivos como 1-2 planos en vez de cajas 3D
    int pkRow = 16;
    int[] kbX = new int[16], kbY = new int[16], kbW = new int[16], kbH = new int[16];
    boolean mineOn;                  // romper/colocar mantenido (tecla o tactil)
    boolean[] kd = new boolean[14];   // 0-9, 10 = *, 11 = #, 12 = L, 13 = R
    int layout = 0;                   // 0 = A (caminar), 1 = B (camara)
    boolean dbgOn = Minecraft2D.DEBUG;
    int musLv = 3, sfxLv = 5;         // volumen de musica / efectos: 0..5 (x20 = %)
    boolean wasInW;                   // estaba en el agua (para el chapoteo)
    float stepD;                      // distancia caminada desde el ultimo paso

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
    int spawnTimer, hostTimer, fishState, fishX, fishY, fishZ, fishT;   // fishState: 0 nada, 1 esperando, 3 minijuego
    float fgM, fgD = 1f, fgC = 0.5f, fgS = 0.3f, fgTC = 0.5f, fgTS = 0.3f;   // minijuego: marca, sentido, centro y tamano de la zona verde (+ objetivos)
    int fgYou, fgFish, fgT, fgFlash, fgLast;
    boolean fgOk;
    int fwTick = -100, shX, shZ, shY;   // correr (doble clic adelante) y naufragio actual
    boolean sprint;
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
    float[][] boatParts, codParts, salmonParts, tropParts, spiderParts, cowParts, zombieParts, creeperParts, tntParts, pigmanParts, villagerParts, endermanParts, blazeParts, dragonParts, shotParts;
    int tSel, vilX, vilZ, vilY, vilN;
    int eSel, xpPts, xpLvl;   // mesa de encantamiento: fila elegida; experiencia (puntos dentro del nivel y nivel)
    int dim, portalT, lavaT;      // dim: 0 = overworld, 1 = nether, 2 = el End
    int endFlags;                 // bit0 dragon muerto, bit1 final visto, bit2 portal de salida hecho, bits 3-12 cristales rotos
    int stX, stZ, endT;           // fortaleza (stronghold) y temporizador de endermans
    boolean stOk;
    Mob dragon;
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
    // tactil: se activa con el primer toque en pantalla
    boolean touch, tFw, tBk, tL, tR, tJp, tcLook, tcInvDown, tcDropped;
    int tcMode, tcLX, tcLY, tcSlot = -1, tcPressSlot = -1;   // tcMode: 1 cruceta, 2 salto, 3 camara/accion, 4 boton
    long tcNext;
    int tcB, tcDX, tcDY, tcJX, tcJY, tcJS, tcSb, tcPX, tcPY, tcGX, tcIX, tcIY, tcIS, tcHx, tcHy, tcHs;
    int[] tcBuf, tcBufOn;
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
    int starTicks, attackCd, mineProg, mineX = NONE, mineY = -1, mineZ = -1;
    boolean starConsumed;

    // inventario: 0-35 inventario (0-8 hotbar), 36-44 grilla, 45 resultado
    int[] slId = new int[NSLOT], slCnt = new int[NSLOT], slDur = new int[NSLOT];
    int sel, labelT, msgT;
    String msg = "";
    boolean bedOn;
    int bedX, bedY, bedZ;
    // modo creativo
    boolean creative, flying;
    int jumpTick = -100, palPage, cMode, cDiff, diff, peaceT;   // diff: 0 normal, 1 pacifico, 2 dificil (bits 1-2 del byte de modo del mundo)
    int[] palIds = new int[200];
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
    byte[] chestSnap = new byte[CHN * 6];   // contenido tal como se leyo: se guarda solo si cambio

    // datos de items
    String[] names = new String[256];
    String[] tag = new String[256];
    int[] icol = new int[256];
    int[] cTop = new int[256], cSide = new int[256], cBot = new int[256];
    int[][] shadedCol = new int[256][6];
    float[] hard = new float[256];
    float[][] sheepParts, chickenParts;

    // recetas
    int nrec;
    int[] rw = new int[96], rh = new int[96], rOut = new int[96], rCnt = new int[96];
    int[][] rPat = new int[96][];

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
        sndApply();
        applyLang();
        setupWindow(RD_OPT[rdIdx]);
        go(ST_LANG);
        navSel = Math.max(0, langPos());
    }

    protected void sizeChanged(int w, int h) {
        sw = w;
        sh = h;
    }

    protected void showNotify() {
        setFullScreenMode(true);
        Sfx.pause(false);
    }

    // al irse la app a segundo plano: pausa y guarda el mundo
    protected void hideNotify() {
        Sfx.pause(true);
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
        Sfx.begin();
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
        Sfx.end();
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
        return cd[(((x & 15) << 4) + (z & 15)) * H + y] & 0xFF;
    }

    void setBlock(int x, int y, int z, int b) {
        if (y < 0 || y >= H) return;
        int cx = x >> 4, cz = z >> 4;
        int s = slotOf(cx, cz);
        if (chCx[s] != cx || chCz[s] != cz) return;
        int bi = (((x & 15) << 4) + (z & 15)) * H + y;
        if (chData[s][bi] == B_CHEST && b != B_CHEST) chestFree(x, y, z);
        if (y + 1 < H && b != B_GRASS && b != B_DIRT) {   // pasto alto y flores se caen si les sacan el piso
            int ab = chData[s][bi + 1] & 0xFF;
            if (ab >= B_TUFT && ab <= B_FLOWER_Y) chData[s][bi + 1] = 0;
        }
        chData[s][bi] = (byte) b;
        topGen++;   // invalida el cache de alturas
        chDirty[s] = true;
    }

    boolean solidAt(int x, int y, int z) {
        if (y < 0) return true;
        if (y >= H) return false;
        byte[] cd = chunkFor(x, z);
        if (cd == null) return true;
        int b = cd[(((x & 15) << 4) + (z & 15)) * H + y] & 0xFF;
        return b != 0 && b != B_CANE && b != B_SAPLING && b != B_TORCH && b != B_WATER && b != B_DOOR_LO && b != B_DOOR_UO && b != B_LAVA && b != B_PORTAL && b != B_ENDPORTAL && (b < B_CROP0 || b > B_CROP2) && b != B_KELP && b != B_SEAGRASS && (b < B_TUFT || b > B_FLOWER_Y);
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
        for (int y = H - 1; y >= 0; y--) { int tb = blockAt(x, y, z); if (tb != 0 && (tb < B_TUFT || tb > B_FLOWER_Y)) return y; }   // las plantitas decorativas no cuentan como suelo
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
    // ---------------------------------------------------------------- idiomas
    // Todos los idiomas viven en UN archivo dentro del JAR: /leng/idiomas.txt (el formato esta explicado
    // en el propio archivo). Para agregar un idioma solo se edita ese archivo, no este codigo.
    //
    // L_ES = textos originales en espanol. Sirven de clave en el codigo (L("Jugar")) y de respaldo si a un
    // idioma le falta una linea. El ID de cada texto en idiomas.txt es su posicion + 1:
    //   NO reordenar ni borrar entradas; un texto nuevo se agrega AL FINAL y tambien a cada idioma del archivo.
    // IDs 1..999 = interfaz; NAME_BASE + id de bloque/item (1000..1127) = nombres.
    // rutas donde se busca el archivo dentro del JAR (se usa la primera que exista)
    static final String[] LANG_FILES = { "/leng/idiomas.txt", "/idiomas.txt", "/res/leng/idiomas.txt", "/lang/idiomas.txt" };
    boolean langFound;                          // false = no se encontro idiomas.txt en el JAR
    static final int NAME_BASE = 1000, TX_MAX = NAME_BASE + 256, MAX_LANGS = 32;
    int lang;                                   // ID del idioma activo (el de lang[ID] en el archivo)
    int langN;                                  // cantidad de idiomas en la lista
    int[] langIds = new int[MAX_LANGS];
    String[] langNames = new String[MAX_LANGS];
    boolean langListed;
    int langLastSel = -1;                       // pantalla de idioma: ultima fila seleccionada (para mantenerla a la vista)
    String[] ltxt = new String[TX_MAX];           // textos del idioma activo por ID (null = usar el original)
    Hashtable txKey;                            // texto original -> ID
    String[] namesEs;
    int lfSec;                                  // lectura del archivo: bloque actual (-1 = cabecera, -2 = invalido)

    static final String[] L_ES = {
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
        "Portal del End activado",   // 96
        "El ojo apunta a la fortaleza",   // 97
        "El ojo se rompio",   // 98
        "Entraste al End",   // 99
        "Ender Dragon derrotado!",   // 100
        "FIN",   // 101
        "Derrotaste al Ender Dragon",   // 102
        "Gracias por jugar",   // 103
        "5: continuar",   // 104
        "Toca: continuar",   // 105
        "Cristales: ",   // 106
        "Musica: ",   // 107
        "Efectos: ",   // 108
        "Sonido",   // 109
        "Mover izq.",   // 110
        "Mover der.",   // 111
        "Sin accion",   // 112
        "Flechas: tecla / 5: cambiar",   // 113
        "2/8: elegir / 5: asignar",   // 114
        "Sin tecla: ",   // 115
        "Tecla: ",   // 116
        "Follaje: ",   // 117
        "3D",   // 118
        "Plano",   // 119
        "Mesa de encantamiento",   // 120
        "Nivel",   // 121
        "Filo",   // 122
        "Retroceso",   // 123
        "Eficiencia",   // 124
        "Irrompibilidad",   // 125
        "Fortuna",   // 126
        "Botin",   // 127
        "Tu",   // 128
        "Pez",   // 129
        "Pica! Zona verde",   // 130
        "Dificultad: ",   // 131
        "Pacifico",   // 132
        "Dificil"   // 133
    };

    String L(String t) {
        if (t == null || txKey == null) return t;
        Integer k = (Integer) txKey.get(t);
        if (k == null) return t;
        String s = ltxt[k.intValue()];
        if (s == null) return t;
        if (t.charAt(t.length() - 1) == ' ' && s.charAt(s.length() - 1) != ' ') return cat(s, " ");
        return s;
    }

    int langPos() {
        for (int i = 0; i < langN; i++) if (langIds[i] == lang) return i;
        return -1;
    }

    void nextLang() {
        lang = langIds[(langPos() + 1) % langN];
    }

    void applyLang() {
        if (txKey == null) {
            txKey = new Hashtable();
            for (int i = 0; i < L_ES.length; i++) if (!txKey.containsKey(L_ES[i])) txKey.put(L_ES[i], new Integer(i + 1));
        }
        if (!langListed) {
            langListed = true;
            readLangFile(true, -1);
            if (langN == 0) { langN = 1; langIds[0] = 0; langNames[0] = "Espanol"; }
        }
        if (langPos() < 0) lang = langIds[0];
        if (namesEs == null) {
            namesEs = new String[256];
            for (int i = 0; i < 256; i++) namesEs[i] = names[i];
        }
        for (int i = 0; i < TX_MAX; i++) ltxt[i] = null;
        readLangFile(false, lang);
        for (int i = 0; i < 256; i++) {
            String s = ltxt[NAME_BASE + i];
            names[i] = (s != null) ? s : namesEs[i];
        }
    }

    // lee idiomas.txt: list = arma la lista de idiomas (cabecera); si no, carga en ltxt[] el bloque [id]
    void readLangFile(boolean list, int id) {
        java.io.InputStream in = null;
        lfSec = -1;
        try {
            for (int i = 0; i < LANG_FILES.length && in == null; i++) in = getClass().getResourceAsStream(LANG_FILES[i]);
            if (in == null) return;
            langFound = true;
            byte[] buf = new byte[512], lb = new byte[256];
            int n, ln = 0;
            boolean done = false;
            while (!done && (n = in.read(buf)) > 0) {
                for (int i = 0; i < n && !done; i++) {
                    byte c = buf[i];
                    if (c == '\n') { done = langLine(utf8(lb, ln), list, id); ln = 0; }
                    else if (c != '\r') {
                        if (ln == lb.length) { byte[] nb = new byte[ln * 2]; System.arraycopy(lb, 0, nb, 0, ln); lb = nb; }
                        lb[ln++] = c;
                    }
                }
            }
            if (!done && ln > 0) langLine(utf8(lb, ln), list, id);
        } catch (Exception e) {}
        if (in != null) { try { in.close(); } catch (Exception e) {} }
    }

    // procesa una linea; devuelve true cuando ya no hace falta seguir leyendo
    boolean langLine(String s, boolean list, int id) {
        s = langTrim(s);
        if (s.length() == 0) return false;
        char c0 = s.charAt(0);
        if (c0 == '#') return false;
        if (c0 == '[') {
            if (list) return true;                          // la cabecera termina en el primer bloque
            if (s.indexOf(']') < 0) return false;
            if (lfSec == id) return true;                   // termino el bloque pedido
            lfSec = num(s.substring(1, s.indexOf(']')));
            return false;
        }
        int eq = s.indexOf('=');
        if (eq <= 0) return false;
        if (list) {
            int e = s.indexOf(']');
            if (s.startsWith("lang[") && e > 5 && e < eq && langN < MAX_LANGS) {
                int lid = num(s.substring(5, e));
                String nm = s.substring(eq + 1).trim();
                if (lid >= 0 && lid < 128 && nm.length() > 0) {
                    boolean dup = false;
                    for (int i = 0; i < langN; i++) if (langIds[i] == lid) dup = true;
                    if (!dup) { langIds[langN] = lid; langNames[langN] = nm; langN++; }
                }
            }
        } else if (lfSec == id) {
            int tid = num(s.substring(0, eq));
            String t = s.substring(eq + 1).trim();
            if (tid > 0 && tid < TX_MAX && t.length() > 0) ltxt[tid] = t;
        }
        return false;
    }

    // quita espacios y el comentario del final (un '#' precedido de espacio o tab)
    static String langTrim(String s) {
        s = s.trim();
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '#' && (s.charAt(i - 1) == ' ' || s.charAt(i - 1) == '\t')) { s = s.substring(0, i).trim(); break; }
        }
        return s;
    }

    static int num(String s) {
        s = s.trim();
        int n = s.length();
        if (n == 0 || n > 5) return -2;
        int v = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return -2;
            v = v * 10 + (c - '0');
        }
        return v;
    }

    // UTF-8 -> String (CLDC no siempre trae el decodificador "UTF-8")
    static String utf8(byte[] b, int n) {
        StringBuffer sb = new StringBuffer(n);
        int i = 0;
        if (n >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) i = 3;   // BOM
        while (i < n) {
            int c = b[i++] & 0xFF;
            if (c < 0x80) sb.append((char) c);
            else if (c >= 0xC0 && c < 0xE0 && i < n) sb.append((char) (((c & 0x1F) << 6) | (b[i++] & 0x3F)));
            else if (c >= 0xE0 && c < 0xF0 && i + 1 < n) { sb.append((char) (((c & 0x0F) << 12) | ((b[i] & 0x3F) << 6) | (b[i + 1] & 0x3F))); i += 2; }
            else if (c >= 0xF0 && c < 0xF8) { sb.append('?'); i += 3; }
            else sb.append('?');
        }
        return sb.toString();
    }

    void say(String t) { msg = L(t); msgT = 70; }
    boolean isBlockItem(int id) { return (id >= 1 && id <= 19) || id == I_DOOR || (id >= B_COAL_ORE && id <= B_BED)
                || (id >= B_NETHERRACK && id <= B_OBSIDIAN) || id == B_SOULSAND || id == B_GLOWSTONE || id == B_CHEST || id == B_MAGMA
                || (id >= B_LOG_S && id <= B_FARM_DRY) || id == I_SEEDS || (id >= B_KELP && id <= B_CORAL4)
                || (id >= B_ENDSTONE && id <= B_EGG && id != B_ENDPORTAL) || (id >= B_CANE && id <= B_ENCH) || (id >= B_TUFT && id <= B_FLOWER_Y); }
    boolean isDoor(int b) { return b >= B_DOOR_LC && b <= B_DOOR_UO; }
    boolean isFood(int id) {
        return id == I_APPLE || id == I_BREAD || id == I_MUTTON || id == I_CHICKEN || id == I_BEEF || id == I_STEAK
                || id == I_COOKED_MUTTON || id == I_COOKED_CHICKEN || id == I_ROTTEN
                || id == I_COD || id == I_SALMON || id == I_TROPICAL
                || id == I_COOKED_COD || id == I_COOKED_SALMON || id == I_DRIED_KELP;
    }
    int foodVal(int id) {
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
    int maxStack(int id) { return (isTool(id) || id == I_BOAT || (id >= I_BUCKET && id <= I_BUCKET_L) || id == I_BUCKET_M || id == I_BOOK) ? 1 : 64; }

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
                int b = d[i] & 0xFF;
                if ((b == B_CROP0 || b == B_CROP1) && d[i - 1] == B_FARM_WET) {
                    d[i] = (byte) (b + 1);
                    chDirty[s] = true;
                } else if (b == B_CANE) {
                    int below = d[i - 1] & 0xFF;
                    if ((i % H) == 0 || (below != B_SAND && below != B_DIRT && below != B_GRASS && below != B_CANE)) {
                        d[i] = 0; chDirty[s] = true;   // sin base: se cae
                    } else if ((i % H) < H - 1 && d[i + 1] == 0 && below != B_CANE || (below == B_CANE && (i % H) > 1 && (d[i - 2] & 0xFF) != B_CANE && (i % H) < H - 1 && d[i + 1] == 0)) {
                        if (rnd(3) == 0) { d[i + 1] = (byte) B_CANE; chDirty[s] = true; }
                    }
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
        Sfx.play(Sfx.mob(6, 3), 80);
        int o = slId[OUT], c = slCnt[OUT];
        if (heldId == 0) { heldId = o; heldCnt = c; heldDur = 0; }
        else if (heldId == o && maxStack(o) > 1 && heldCnt + c <= 64) heldCnt += c;
        else return;
        int n = c / T_OUTC[tSel];
        slCnt[36] -= n * T_INC[tSel];
        if (slCnt[36] <= 0) clearSlot(36);
        updateOutput();
    }

    // ---------------------------------------------------------------- experiencia y encantamientos
    int xpNeed(int lvl) { return 10 + lvl * 5; }

    void addXp(int n) {
        if (creative || n <= 0) return;
        xpPts += n;
        while (xpPts >= xpNeed(xpLvl) && xpLvl < 999) {
            xpPts -= xpNeed(xpLvl);
            xpLvl++;
            Sfx.play(Sfx.S_POP, 60);
        }
    }

    int enLv(int d, int e) { return (d >> (ESH + e * 3)) & 7; }
    int enSet(int d, int e, int lv) { int sh = ESH + e * 3; return (d & ~(7 << sh)) | (lv << sh); }
    int enchCost(int lv) { return 1 + lv * 2; }   // niveles de experiencia para llegar al nivel lv

    String enSuffix(String nm, int d) {
        StringBuffer sb = new StringBuffer(nm);
        for (int e = 0; e < 6; e++) {
            int lv = enLv(d, e);
            if (lv > 0) sb.append(' ').append(L(EN_NAME[e])).append(' ').append(ROM[lv]);
        }
        return sb.toString();
    }

    boolean enApplies(int id, int e) {
        if (id == I_BOOK) return true;
        int t = toolType(id);
        if (t < 0) return false;
        if (e == 0 || e == 1 || e == 5) return t == 3;   // Filo, Retroceso, Botin: espada
        if (e == 2) return t <= 2;                       // Eficiencia: pico, hacha, pala
        if (e == 3) return true;                         // Irrompibilidad: cualquier herramienta
        return t == 0;                                   // Fortuna: pico
    }

    int enchListN() {
        int id = slId[36];
        if (id == 0) return 0;
        int n = 0;
        for (int e = 0; e < 6; e++) if (enApplies(id, e)) n++;
        return n;
    }

    int enchNth(int k) {
        int id = slId[36];
        for (int e = 0; e < 6; e++) {
            if (enApplies(id, e)) { if (k == 0) return e; k--; }
        }
        return -1;
    }

    void enchMove(int d) {
        int n = enchListN();
        if (n > 0) eSel = (eSel + d + n) % n;
        updateOutput();
    }

    void enchOutput() {
        int n = enchListN();
        if (n == 0) { clearSlot(OUT); return; }
        if (eSel >= n) eSel = n - 1;
        if (eSel < 0) eSel = 0;
        int e = enchNth(eSel);
        int cur = enLv(slDur[36], e);
        if (cur >= EN_MAX[e]) { clearSlot(OUT); return; }
        slId[OUT] = slId[36];
        slCnt[OUT] = 1;
        slDur[OUT] = enSet(slDur[36], e, cur + 1);
    }

    void enchTake() {
        if (slId[OUT] == 0 || heldId != 0) return;
        int e = enchNth(eSel);
        int nl = enLv(slDur[OUT], e);
        int cost = enchCost(nl);
        if (!creative && xpLvl < cost) { say("Faltan niveles de experiencia"); return; }
        if (!creative) xpLvl -= cost;
        heldId = slId[OUT]; heldCnt = 1; heldDur = slDur[OUT];
        clearSlot(36);
        Sfx.play(Sfx.S_POP, 80);
        updateOutput();
    }

    // experiencia que da vender un item (rango segun RK_ID; los libros encantados valen por sus niveles)
    int sellValue(int id, int dur) {
        if (id == I_BOOK) {
            int sum = 0;
            for (int e = 0; e < 6; e++) sum += enLv(dur, e);
            return sum * 6;
        }
        for (int k = 0; k < RK_ID.length; k++) if (RK_ID[k] == id) return (RK_ID.length - k) * 2 + (k == 0 ? 20 : 0);
        return 0;
    }

    void enchSell() {
        if (slId[37] == 0) return;
        int v = sellValue(slId[37], slDur[37]);
        if (v <= 0) { say("No lo quieren"); return; }
        addXp(v * slCnt[37]);
        clearSlot(37);
        Sfx.play(Sfx.mob(6, 3), 80);
    }

    void paintEnch() {
        int s = slS, x = slx[9], r = s * 3 / 4, w = 5 * s;
        int vis = (sly[9] - 22) / r;
        if (vis < 1) vis = 1;
        int n = enchListN();
        int first = eSel - vis + 1;
        if (first < 0) first = 0;
        for (int i = 0; i < vis && first + i < n; i++) {
            int t = first + i, y = 14 + i * r;
            int e = enchNth(t), cur = enLv(slDur[36], e);
            boolean maxed = cur >= EN_MAX[e];
            gg.setColor(t == eSel ? 0x5A3A5A : 0x3A3A3A);
            gg.fillRect(x, y, w, r - 1);
            if (t == eSel) {
                gg.setColor(0xFFFF00);
                gg.drawRect(x, y, w - 1, r - 2);
            }
            String nm = new StringBuffer(L(EN_NAME[e])).append(' ').append(ROM[maxed ? cur : cur + 1]).toString();
            txt(nm, x + 3, y + r / 2 - 5, maxed ? 0x909090 : 0xFFFFFF, Graphics.TOP | Graphics.LEFT);
            if (!maxed) {
                int c = enchCost(cur + 1);
                txt(Integer.toString(c), x + w - 3, y + r / 2 - 5, (creative || xpLvl >= c) ? 0x80FF20 : 0xFF6060, Graphics.TOP | Graphics.RIGHT);
            }
        }
        txt("v", slx[36] + s / 2, sly[36] + s + s / 2 - 5, 0xFFFFFF, Graphics.TOP | Graphics.HCENTER);
        txt("v", slx[37] + s / 2, sly[37] + s + s / 2 - 5, 0xFFFFFF, Graphics.TOP | Graphics.HCENTER);
        if (slId[38] == 0) txt("XP", slx[38] + s / 2, sly[38] + s / 2 - 5, 0x205020, Graphics.TOP | Graphics.HCENTER);
        txt(new StringBuffer("Niv ").append(xpLvl).toString(), slx[38] + s / 2, sly[38] + s + 1, 0x80FF20, Graphics.TOP | Graphics.HCENTER);
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

    // naufragio: casco de tablones de 11x5 en el fondo del oceano (>= 7 de profundidad), uno por region con 1/4 de probabilidad
    boolean shipFor(int x0, int z0) {
        int kx = x0 >> 6, kz = z0 >> 6;
        if (hash(kx, kz, 41) % 4 != 0) return false;
        shX = (kx << 6) + 12 + hash(kx, kz, 42) % 40;
        shZ = (kz << 6) + 12 + hash(kx, kz, 43) % 40;
        if (x0 + CS <= shX - 6 || x0 > shX + 6 || z0 + CS <= shZ - 3 || z0 > shZ + 3) return false;
        shY = heightAt(shX, shZ);
        return shY <= SEA - 7 && biomeAt(shX, shZ) != 4;
    }

    void buildShip(byte[] d, int x0, int z0) {
        int y0 = shY;
        for (int dx = -5; dx <= 5; dx++) {
            int hw = (dx == -5 || dx == 5) ? 0 : ((dx == -4 || dx == 4) ? 1 : 2);   // proa y popa afinadas
            for (int dz = -hw; dz <= hw; dz++) {
                int x = shX + dx, z = shZ + dz;
                if (x < x0 || x >= x0 + CS || z < z0 || z >= z0 + CS) continue;
                for (int y = heightAt(x, z); y <= y0; y++) putTree(d, x0, z0, x, y, z, B_PLANKS, true);
                putTree(d, x0, z0, x, y0 + 1, z, B_PLANKS, true);
                boolean edge = dz == hw || dz == -hw || dx == 5 || dx == -5;
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
    void seedWreck() {
        int n = 3 + rnd(3);
        for (int i = 0; i < n; i++) {
            int r = rnd(10), it = r < 3 ? I_STRING : (r < 5 ? I_STICK : (r < 7 ? I_COD : (r < 8 ? I_APPLE : (r < 9 ? I_COAL : I_EMERALD))));
            int sl = CH0 + rnd(CHN);
            if (slId[sl] == 0) { slId[sl] = it; slCnt[sl] = 1 + rnd(3); slDur[sl] = 0; }
        }
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
        for (int i = 0; i < 256; i++) { names[i] = "?"; tag[i] = ""; icol[i] = 0x808080; }
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
        cTop[B_IRON_BLK] = 0xD8D8D8; cSide[B_IRON_BLK] = 0xD8D8D8; cBot[B_IRON_BLK] = 0xD8D8D8;
        cTop[B_GOLD_BLK] = 0xF2D040; cSide[B_GOLD_BLK] = 0xF2D040; cBot[B_GOLD_BLK] = 0xF2D040;
        cTop[B_DIAM_BLK] = 0x5AE8E0; cSide[B_DIAM_BLK] = 0x5AE8E0; cBot[B_DIAM_BLK] = 0x5AE8E0;
        cTop[B_BED] = 0xC83A34; cSide[B_BED] = 0x6B4A2A; cBot[B_BED] = 0x6B4A2A;
        cTop[B_NETHERRACK] = 0x7A2E2E; cSide[B_NETHERRACK] = 0x7A2E2E; cBot[B_NETHERRACK] = 0x7A2E2E;
        cTop[B_LAVA] = 0xE0601A; cSide[B_LAVA] = 0xE0601A; cBot[B_LAVA] = 0xE0601A;
        cTop[B_OBSIDIAN] = 0x1C0F2E; cSide[B_OBSIDIAN] = 0x1C0F2E; cBot[B_OBSIDIAN] = 0x1C0F2E;
        cTop[B_SOULSAND] = 0x5A4636; cSide[B_SOULSAND] = 0x5A4636; cBot[B_SOULSAND] = 0x5A4636;
        cTop[B_GLOWSTONE] = 0xF2D488; cSide[B_GLOWSTONE] = 0xF2D488; cBot[B_GLOWSTONE] = 0xF2D488;
        cTop[B_MAGMA] = 0x7A2A10; cSide[B_MAGMA] = 0x7A2A10; cBot[B_MAGMA] = 0x7A2A10; icol[I_BOAT] = 0x8B6B3A;
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
        cTop[B_KELP] = 0x2F8A3A; cSide[B_KELP] = 0x2F8A3A; cBot[B_KELP] = 0x2F8A3A;
        cTop[B_SEAGRASS] = 0x3FA03A; cSide[B_SEAGRASS] = 0x3FA03A; cBot[B_SEAGRASS] = 0x3FA03A;
        int[] coralC = { 0xD85A9A, 0x3A5AD8, 0xA040C8, 0xD83A2A, 0xE0C030 };
        String[] coralN = { "Coral cerebro", "Coral tubo", "Coral burbuja", "Coral de fuego", "Coral cuerno" };
        for (int k = 0; k < 5; k++) {
            int cb = B_CORAL0 + k;
            cTop[cb] = coralC[k]; cSide[cb] = coralC[k]; cBot[cb] = coralC[k];
            hard[cb] = 1.5f; names[cb] = coralN[k]; icol[cb] = coralC[k];
        }
        names[B_KELP] = "Alga marina"; names[B_SEAGRASS] = "Pasto marino";
        hard[B_KELP] = 0f; hard[B_SEAGRASS] = 0f;
        cTop[B_TUFT] = 0x4DAA3A; cSide[B_TUFT] = 0x4DAA3A; cBot[B_TUFT] = 0x4DAA3A;
        cTop[B_FLOWER_R] = 0xD03030; cSide[B_FLOWER_R] = 0x3FA03A; cBot[B_FLOWER_R] = 0x3FA03A;
        cTop[B_FLOWER_Y] = 0xF0D020; cSide[B_FLOWER_Y] = 0x3FA03A; cBot[B_FLOWER_Y] = 0x3FA03A;
        names[B_TUFT] = "Pasto alto"; names[B_FLOWER_R] = "Amapola"; names[B_FLOWER_Y] = "Diente de leon";
        hard[B_TUFT] = 0f; hard[B_FLOWER_R] = 0f; hard[B_FLOWER_Y] = 0f;
        icol[B_TUFT] = 0x4DAA3A; icol[B_FLOWER_R] = 0xD03030; icol[B_FLOWER_Y] = 0xF0D020;
        icol[B_KELP] = 0x2F8A3A; icol[B_SEAGRASS] = 0x3FA03A;
        hard[B_LOG_S] = 2f; hard[B_LOG_S + 1] = 2f; hard[B_LEAVES_S] = 0.2f; hard[B_LEAVES_S + 1] = 0.2f;
        hard[B_PLANKS_S] = 2f; hard[B_PLANKS_S + 1] = 2f; hard[B_FARM_DRY] = 0.6f; hard[B_FARM_WET] = 0.6f;
        int[] nbc = { 0xDCD9A0, 0x3A1A22, 0x6E7468, 0x1F5C4A, 0x2FA882, 0x0A0A1A, 0x151C28, 0xE060FF, 0x1A0E24 };
        String[] nbn = { "Piedra del End", "Ladrillo del Nether", "Ladrillo de piedra", "Marco del portal del End", "Marco con Ojo de Ender",
                "Portal del End", "Generador de monstruos", "Cristal del End", "Huevo de dragon" };
        float[] nbh = { 3f, 2f, 2.2f, 100000f, 100000f, 0f, 5f, 0.1f, 3f };
        for (int k = 0; k < 9; k++) {
            int nb2 = B_ENDSTONE + k;
            cTop[nb2] = nbc[k]; cSide[nb2] = nbc[k]; cBot[nb2] = nbc[k]; names[nb2] = nbn[k]; hard[nb2] = nbh[k];
        }
        names[I_BLAZE_ROD] = "Vara de blaze"; names[I_BLAZE_POWDER] = "Polvo de blaze"; names[I_PEARL] = "Perla de Ender"; names[I_EYE] = "Ojo de Ender";
        icol[I_BLAZE_ROD] = 0xE8A020; icol[I_BLAZE_POWDER] = 0xF0C040; icol[I_PEARL] = 0x147A66; icol[I_EYE] = 0x30D0A0;
        names[B_CANE] = "Cana de azucar"; names[B_CAKE] = "Pastel"; names[B_ENCH] = "Mesa de encantamiento";
        names[I_SUGAR] = "Azucar"; names[I_PAPER] = "Papel"; names[I_BOOK] = "Libro"; names[I_LEATHER] = "Cuero"; names[I_BUCKET_M] = "Cubeta con leche";
        cTop[B_CANE] = 0x7CCB4A; cSide[B_CANE] = 0x7CCB4A; cBot[B_CANE] = 0x7CCB4A;
        cTop[B_CAKE] = 0xF8F0F0; cSide[B_CAKE] = 0xE8D8C0; cBot[B_CAKE] = 0xB08850;
        cTop[B_ENCH] = 0x9B1C31; cSide[B_ENCH] = 0x24182E; cBot[B_ENCH] = 0x24182E;
        icol[B_CANE] = 0x7CCB4A; icol[B_CAKE] = 0xE8D8C0; icol[B_ENCH] = 0x9B1C31;
        icol[I_SUGAR] = 0xF5F5F5; icol[I_PAPER] = 0xEDEDED; icol[I_BOOK] = 0x8B2A2A; icol[I_LEATHER] = 0xA0622D; icol[I_BUCKET_M] = 0xF0F0F0;
        hard[B_CANE] = 0f; hard[B_CAKE] = 0.5f; hard[B_ENCH] = 5f;
        for (int b = 1; b < 256; b++) {
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
        names[B_MAGMA] = "Bloque de magma"; names[I_BOAT] = "Barco"; hard[B_MAGMA] = 0.5f; names[B_PORTAL] = "Portal"; names[B_SOULSAND] = "Arena de almas"; names[B_GLOWSTONE] = "Piedra luminosa";
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
        codParts = new float[][] {
            { 0f, 0.15f, 0f, 0.08f, 0.10f, 0.22f, 0xB8996A },
            { 0f, 0.15f, -0.28f, 0.02f, 0.10f, 0.07f, 0x8A7040 }
        };
        salmonParts = new float[][] {
            { 0f, 0.15f, 0f, 0.09f, 0.11f, 0.30f, 0xC96A5A },
            { 0f, 0.15f, -0.36f, 0.02f, 0.11f, 0.08f, 0x8E4A40 }
        };
        tropParts = new float[][] {
            { 0f, 0.15f, 0f, 0.04f, 0.12f, 0.15f, 0xE0903A },
            { 0f, 0.15f, -0.19f, 0.02f, 0.10f, 0.05f, 0xFFFFFF },
            { 0f, 0.28f, 0f, 0.01f, 0.05f, 0.10f, 0xE0903A }
        };
        spiderParts = new float[][] {
            { 0f, 0.35f, 0f, 0.20f, 0.14f, 0.25f, 0x3A2E2A },
            { 0f, 0.38f, 0.38f, 0.14f, 0.12f, 0.12f, 0x2A2020 },
            { 0.06f, 0.42f, 0.50f, 0.03f, 0.03f, 0.02f, 0xFF2020 },
            { -0.06f, 0.42f, 0.50f, 0.03f, 0.03f, 0.02f, 0xFF2020 },
            { 0.38f, 0.25f, 0.15f, 0.22f, 0.03f, 0.04f, 0x2A2020 },
            { -0.38f, 0.25f, 0.15f, 0.22f, 0.03f, 0.04f, 0x2A2020 },
            { 0.38f, 0.25f, -0.15f, 0.22f, 0.03f, 0.04f, 0x2A2020 },
            { -0.38f, 0.25f, -0.15f, 0.22f, 0.03f, 0.04f, 0x2A2020 }
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
        boatParts = new float[][] {
            { 0f, 0.08f, 0f, 0.55f, 0.06f, 0.8f, 0x9A7A44 },
            { -0.55f, 0.3f, 0f, 0.06f, 0.22f, 0.8f, 0x7A5A2E },
            { 0.55f, 0.3f, 0f, 0.06f, 0.22f, 0.8f, 0x7A5A2E },
            { 0f, 0.3f, 0.8f, 0.55f, 0.22f, 0.06f, 0x8A6A36 },
            { 0f, 0.3f, -0.8f, 0.55f, 0.22f, 0.06f, 0x8A6A36 }
        };
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
        endermanParts = new float[][] {
            { 0.1f, 0.7f, 0f, 0.05f, 0.7f, 0.05f, 0x151515 },
            { -0.1f, 0.7f, 0f, 0.05f, 0.7f, 0.05f, 0x151515 },
            { 0f, 1.8f, 0f, 0.2f, 0.4f, 0.1f, 0x1C1C1C },
            { 0.28f, 1.7f, 0f, 0.04f, 0.55f, 0.04f, 0x151515 },
            { -0.28f, 1.7f, 0f, 0.04f, 0.55f, 0.04f, 0x151515 },
            { 0f, 2.45f, 0f, 0.2f, 0.25f, 0.2f, 0x1C1C1C },
            { 0.1f, 2.48f, 0.2f, 0.07f, 0.03f, 0.02f, 0xE040FF },
            { -0.1f, 2.48f, 0.2f, 0.07f, 0.03f, 0.02f, 0xE040FF }
        };
        blazeParts = new float[][] {
            { 0f, 1.45f, 0f, 0.2f, 0.2f, 0.2f, 0xE8B020 },
            { 0.08f, 1.5f, 0.2f, 0.05f, 0.04f, 0.02f, 0x201000 },
            { -0.08f, 1.5f, 0.2f, 0.05f, 0.04f, 0.02f, 0x201000 },
            { 0.4f, 0.5f, 0f, 0.05f, 0.3f, 0.05f, 0xF0C030 },
            { -0.4f, 0.5f, 0f, 0.05f, 0.3f, 0.05f, 0xF0C030 },
            { 0f, 0.5f, 0.4f, 0.05f, 0.3f, 0.05f, 0xF0C030 },
            { 0f, 0.5f, -0.4f, 0.05f, 0.3f, 0.05f, 0xF0C030 },
            { 0.28f, 1.0f, 0.28f, 0.05f, 0.3f, 0.05f, 0xD8A020 },
            { -0.28f, 1.0f, 0.28f, 0.05f, 0.3f, 0.05f, 0xD8A020 },
            { 0.28f, 1.0f, -0.28f, 0.05f, 0.3f, 0.05f, 0xD8A020 },
            { -0.28f, 1.0f, -0.28f, 0.05f, 0.3f, 0.05f, 0xD8A020 }
        };
        dragonParts = new float[][] {
            { 0f, 1.0f, 0f, 0.7f, 0.5f, 1.6f, 0x1A1A22 },
            { 0f, 1.3f, 2.2f, 0.35f, 0.3f, 0.6f, 0x24242E },
            { 0f, 1.1f, 2.9f, 0.25f, 0.12f, 0.4f, 0x2E2E3A },
            { 0.18f, 1.45f, 2.55f, 0.07f, 0.07f, 0.03f, 0xE040FF },
            { -0.18f, 1.45f, 2.55f, 0.07f, 0.07f, 0.03f, 0xE040FF },
            { 1.9f, 1.4f, 0.2f, 1.2f, 0.06f, 0.9f, 0x2A2A36 },
            { -1.9f, 1.4f, 0.2f, 1.2f, 0.06f, 0.9f, 0x2A2A36 },
            { 3.4f, 1.5f, 0.6f, 0.8f, 0.05f, 0.6f, 0x34344A },
            { -3.4f, 1.5f, 0.6f, 0.8f, 0.05f, 0.6f, 0x34344A },
            { 0f, 0.9f, -2.5f, 0.3f, 0.25f, 1.0f, 0x1A1A22 },
            { 0f, 0.8f, -3.8f, 0.18f, 0.18f, 0.5f, 0x1A1A22 }
        };
        shotParts = new float[][] {
            { 0f, 0.15f, 0f, 0.15f, 0.15f, 0.15f, 0xFF8020 },
            { 0f, 0.15f, 0f, 0.1f, 0.18f, 0.1f, 0xFFE060 }
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
        rec(3, 3, new int[] { 0, 0, S, 0, S, I_STRING, S, 0, I_STRING }, I_ROD, 1);
        rec(1, 1, new int[] { B_LOG_S }, B_PLANKS_S, 4);
        rec(1, 1, new int[] { B_LOG_S + 1 }, B_PLANKS_S + 1, 4);
        rec(2, 3, new int[] { P, P, 0, S, 0, S }, I_HOE, 1);
        rec(3, 2, new int[] { I_IRON_INGOT, 0, I_IRON_INGOT, 0, I_IRON_INGOT, 0 }, I_BUCKET, 1);
        rec(3, 1, new int[] { I_WHEAT, I_WHEAT, I_WHEAT }, I_BREAD, 1);
        rec(3, 2, new int[] { P, 0, P, P, P, P }, I_BOAT, 1);
        rec(1, 1, new int[] { I_BLAZE_ROD }, I_BLAZE_POWDER, 2);
        rec(1, 2, new int[] { I_PEARL, I_BLAZE_POWDER }, I_EYE, 1);
        rec(1, 1, new int[] { B_CANE }, I_SUGAR, 1);
        rec(3, 1, new int[] { B_CANE, B_CANE, B_CANE }, I_PAPER, 3);
        rec(2, 2, new int[] { I_PAPER, I_PAPER, I_PAPER, I_LEATHER }, I_BOOK, 1);
        rec(3, 3, new int[] { I_BUCKET_M, I_BUCKET_M, I_BUCKET_M, I_SUGAR, I_SEEDS, I_SUGAR, I_WHEAT, I_WHEAT, I_WHEAT }, B_CAKE, 1);
        rec(3, 3, new int[] { 0, I_BOOK, 0, I_DIAMOND, B_OBSIDIAN, I_DIAMOND, B_OBSIDIAN, B_OBSIDIAN, B_OBSIDIAN }, B_ENCH, 1);
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
        // biomas grandes y coherentes: temperatura (frio -> nieve, calor+seco -> desierto) y humedad (bosque)
        if (noise(x, z, 7, 53) > 0.68f) return 4;                 // montanas
        float t = noise(x, z, 8, 13);
        if (t < 0.36f) return 2;                                  // nieve
        if (t > 0.64f && noise(x, z, 8, 29) < 0.5f) return 1;     // desierto
        return noise(x, z, 8, 41) > 0.58f ? 3 : 0;                // bosque / llanura
    }

    boolean desertAt(int x, int z) {
        return biomeAt(x, z) == 1;
    }

    boolean cactusAt(int x, int z) {
        if (hash(x, z, 10) % 36 != 0) return false;
        return Math.abs(x) + Math.abs(z) >= 5;
    }

    float riverV(int x, int z) {
        float r = noise(x, z, 7, 21) - 0.5f;
        return r < 0f ? -r : r;
    }

    int heightAt(int x, int z) {
        float v1 = noise(x, z, 4, 1), v2 = noise(x, z, 6, 2);
        int h = 12 + (int) (v1 * 3f + v2 * 8f);
        float cont = 0.38f - noise(x, z, 8, 61);
        if (cont > 0f) h -= (int) (cont * 56f);   // oceanos mas hondos
        float mn = noise(x, z, 7, 53);
        if (mn > 0.68f) {
            h += (int) ((mn - 0.68f) * 70f);
            if (h > H - 4) h = H - 4;
        } else {
            float rv = riverV(x, z);
            if (rv < 0.018f) {
                if (h > SEA - 2) h = SEA - 2;
            } else if (rv < 0.045f) {
                int cap = SEA + 1 + (int) ((rv - 0.018f) * 100f);
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

    // vida marina segun la profundidad del agua: costa (pasto marino), arrecife de coral, oceano profundo (algas gigantes)
    void seaLife(byte[] d, int base, int x, int z, int h) {
        int dp = SEA - h, top = SEA - 2;   // top = ultimo nivel por debajo de la superficie
        if (h > top) return;
        int r = hash(x, z, 14);
        if (dp >= 3) {   // parches de magma (corriente descendente) y arena de almas (ascendente) en el fondo
            int q = hash(x >> 1, z >> 1, 77) % 70;
            if (q == 0) { d[base + h - 1] = B_MAGMA; return; }
            if (q == 1) { d[base + h - 1] = B_SOULSAND; return; }
        }
        if (dp <= 2 || (dp <= 7 && noise(x, z, 5, 71) <= 0.5f)) {
            if (r % 5 == 0) d[base + h] = B_SEAGRASS;
        } else if (dp <= 7) {
            if (hash(x, z, 16) % 5 < 2) {   // formacion de coral: color por zona de 4x4, con variacion
                int ch = 1 + hash(x, z, 17) % 3;
                int ty = (hash(x >> 2, z >> 2, 18) + (hash(x, z, 19) % 4 == 0 ? 1 : 0)) % 5;
                for (int k = 0; k < ch && h + k <= top; k++) d[base + h + k] = (byte) (B_CORAL0 + ty);
            } else if (r % 6 == 0) {
                d[base + h] = B_SEAGRASS;
            }
        } else if (noise(x, z, 4, 72) > 0.45f) {   // bosque de algas: del fondo casi hasta la superficie
            if (r % 4 == 0) {
                int kt = top - ((hash(x, z, 15) % 3 == 0) ? 1 + hash(x, z, 20) % 2 : 0);
                for (int y = h; y <= kt; y++) d[base + y] = B_KELP;
            }
        } else if (r % 9 == 0) {
            d[base + h] = B_SEAGRASS;
        }
    }

    // hay agua a 1 o 2 bloques (la cana crece junto al agua)
    boolean caneWater(int x, int z) {
        return heightAt(x + 1, z) < SEA || heightAt(x - 1, z) < SEA || heightAt(x, z + 1) < SEA || heightAt(x, z - 1) < SEA
                || heightAt(x + 2, z) < SEA || heightAt(x - 2, z) < SEA || heightAt(x, z + 2) < SEA || heightAt(x, z - 2) < SEA;
    }

    void genChunk(int s, int cx, int cz) {
        if (dim == 1) { genNether(s, cx, cz); genFort(chData[s], cx, cz); return; }
        if (dim == 2) { genEnd(s, cx, cz); return; }
        byte[] d = chData[s];
        for (int i = 0; i < CHB; i++) d[i] = 0;
        int x0 = cx << 4, z0 = cz << 4;
        for (int lx = 0; lx < CS; lx++) {
            for (int lz = 0; lz < CS; lz++) {
                int x = x0 + lx, z = z0 + lz;
                int h = heightAt(x, z);
                int bio = biomeAt(x, z);
                boolean chan = bio != 4 && riverV(x, z) < 0.018f;
                int base = (lx * CS + lz) * H;
                for (int y = 0; y < h; y++) {
                    int dep = h - 1 - y;
                    int blk;
                    if (bio == 1) blk = dep <= 1 ? B_SAND : (dep == 2 ? B_SANDSTONE : B_STONE);
                    else if (bio == 4) blk = (dep == 0 && h >= 24) ? B_SNOW : B_STONE;
                    else if (h <= SEA + 1) blk = dep <= 2 ? ((chan || (h < SEA - 7 && noise(x, z, 3, 81) > 0.5f)) ? B_GRAVEL : B_SAND) : B_STONE;
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
                } else if ((h == SEA + 1 || h == SEA + 2) && bio != 4 && bio != 2 && hash(x, z, 120) % 4 == 0 && caneWater(x, z)) {
                    int ch = 1 + (hash(x, z, 121) % 3);
                    for (int k = 0; k < ch && h + k < H; k++) d[base + h + k] = (byte) B_CANE;
                } else if (h < SEA && bio != 4 && riverV(x, z) >= 0.045f) {
                    seaLife(d, base, x, z, h);
                } else if (h > SEA + 1 && bio != 1 && bio != 2 && bio != 4 && h < H - 1) {   // pasto alto y flores sobre el pasto
                    int pr = (hash(x, z, 150) & 0x7fffffff) % 100;
                    if (pr < 12) d[base + h] = (byte) B_TUFT;
                    else if (pr < 15 && noise(x, z, 4, 151) > 0.55f) d[base + h] = (byte) (((hash(x >> 2, z >> 2, 152) & 1) == 0) ? B_FLOWER_R : B_FLOWER_Y);
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
        if (shipFor(x0, z0)) buildShip(d, x0, z0);
        genStrong(d, cx, cz);
    }

    int chunkKey(int cx, int cz) {
        int kz = (dim == 1) ? (cz ^ 0x8000) : (dim == 2 ? (cz ^ 0x4000) : cz);   // el Nether usa claves distintas en el mismo almacen
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
            o.writeByte(6);
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
            o.writeInt(endFlags);
            o.writeInt(xpLvl); o.writeInt(xpPts);
            for (int i = 0; i < 36; i++) o.writeInt(slDur[i] >>> 16);
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
            if (ver < 1 || ver > 6) return false;
            seed = in.readInt();
            px = in.readFloat(); py = in.readFloat(); pz = in.readFloat();
            yaw = in.readFloat(); pitch = in.readFloat();
            health = in.readInt(); hunger = in.readInt(); sel = in.readInt(); layout = in.readInt();
            for (int i = 0; i < 36; i++) {
                slId[i] = in.readByte() & 0xFF;
                slCnt[i] = in.readByte();
                slDur[i] = in.readShort() & 0xFFFF;
                if (slId[i] < 0 || slId[i] > 135 || slCnt[i] < 0 || slCnt[i] > 64) { slId[i] = 0; slCnt[i] = 0; slDur[i] = 0; }
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
                    if (fId[i] < 0 || fId[i] > 127 || fCnt[i] < 0 || fCnt[i] > 64) { fId[i] = 0; fCnt[i] = 0; }
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
                if (dim < 0 || dim > 2) dim = 0;
            }
            if (ver >= 5) endFlags = in.readInt();
            if (ver >= 6) {
                xpLvl = in.readInt(); xpPts = in.readInt();
                for (int i = 0; i < 36; i++) {
                    int hi = in.readInt();
                    if (slId[i] != 0) slDur[i] = (slDur[i] & 0xFFFF) | (hi << 16);
                }
                if (xpLvl < 0 || xpLvl > 999) xpLvl = 0;
                if (xpPts < 0 || xpPts >= xpNeed(xpLvl)) xpPts = 0;
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
            if (m.type != 14 && chunkFor(ifloor(m.x), ifloor(m.z)) == null) mobs.removeElementAt(i);
        }
    }

    void spawnMobs() {
        mobs.removeAllElements();
        if (dim == 2) { spawnEnd(); return; }
        bright = dayBright();
        for (int i = 0; i < 7; i++) spawnMob(0, false);
        for (int i = 0; i < 6; i++) spawnMob(1, false);
        for (int i = 0; i < 5; i++) spawnMob(2, false);
        if (bright < 70) for (int i = 0; i < 3; i++) spawnMob(hostType(), false);
    }

    boolean hostileMob(int t) { return t == 3 || t == 4 || t == 10 || t == 12 || t == 13; }

    // dano de mobs: en dificil pega 50% mas
    void hurtM(int n) { if (diff == 2) n += (n + 1) / 2; hurt(n); }

    boolean spawnAt(int type, int x, int z) {
        if (diff == 1 && hostileMob(type)) return false;   // pacifico: sin monstruos
        if (mobs.size() >= 40) return false;
        if (chunkFor(x, z) == null) return false;
        if (type >= 7 && type <= 9) {   // peces: solo en agua de 4+ bloques de profundidad
            if (dim != 0) return false;
            int nf = 0;
            for (int i = 0; i < mobs.size(); i++) { int mt = ((Mob) mobs.elementAt(i)).type; if (mt >= 7 && mt <= 9) nf++; }
            int h = heightAt(x, z), dp = SEA - h;
            if (nf >= 12 || dp < 4) return false;
            int y = h + 2 + rnd(dp - 3);
            if (blockAt(x, y, z) != B_WATER || blockAt(x, y + 1, z) != B_WATER) return false;
            Mob f = new Mob();
            f.type = type; f.x = x + 0.5f; f.z = z + 0.5f; f.y = y + 0.2f;
            f.ang = rndf() * 6.2832f; f.hp = mobHp(type); f.timer = 10 + rnd(40);
            if (type == 9) f.col = (rnd(256) << 16) | (rnd(256) << 8) | rnd(256);
            mobs.addElement(f);
            return true;
        }
        if (dim == 2) return false;
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
        int a1 = blockAt(x, top + 1, z);
        if ((a1 != 0 && (a1 < B_TUFT || a1 > B_FLOWER_Y)) || blockAt(x, top + 2, z) != 0) return false;
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
        if (dim != 0) return;
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
        mineOn = false;
        tFw = false; tBk = false; tL = false; tR = false; tJp = false; tcMode = 0; tcInvDown = false;
        starTicks = 0; starConsumed = false; mineProg = 0; mineX = NONE;
    }

    void resetPlayerState() {
        ride = null;
        for (int i = 0; i < NSLOT; i++) { slId[i] = 0; slCnt[i] = 0; slDur[i] = 0; }
        heldId = 0; heldCnt = 0; heldDur = 0;
        xpLvl = 0; xpPts = 0; eSel = 0;
        sel = 0; health = 20; hunger = 20;
        hungerTimer = 0; regenTimer = 0; starveTimer = 0; dmgFlash = 0;
        layout = 0;
        worldTime = 1000; bright = 100;
        for (int i = 0; i < 3; i++) { fId[i] = 0; fCnt[i] = 0; }
        fBurn = 0; fBurnMax = 0; fProg = 0;
        sapN = 0; torchN = 0; hostTimer = 0;
        bedOn = false; msgT = 0; flying = false;
        dim = 0; portalT = 0; portalLock = false; lavaT = 0; endFlags = 0; dragon = null;
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
        creative = (w.mode & 1) == 1;
        diff = (w.mode >> 1) & 3;
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
        initStrong();
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
                if (b.length > 1 && b[1] >= 0) lang = b[1];   // ID de idioma; applyLang() lo valida
                if (b.length >= 5 + NKEY && b[4] == 2) {   // formato 2: b[2]/b[3] = volumenes, b[5..] = accion de cada tecla
                    if (b[2] >= 0 && b[2] <= 5) musLv = b[2];
                    if (b[3] >= 0 && b[3] <= 5) sfxLv = b[3];
                    for (int i = 0; i < NKEY; i++) if (b[5 + i] >= -1 && b[5 + i] < NACT) km[i] = b[5 + i];
                    if (b.length > 5 + NKEY) flatFol = b[5 + NKEY] != 0;
                } else if (b.length >= 16) {   // formato viejo (accion -> tecla): se conservan los volumenes, los controles quedan por defecto
                    if (b[14] >= 0 && b[14] <= 5) musLv = b[14];
                    if (b[15] >= 0 && b[15] <= 5) sfxLv = b[15];
                }
            }
        } catch (Exception e) {}
        if (r != null) { try { r.closeRecordStore(); } catch (Exception e) {} }
        rdApplied = rdIdx;
    }

    void saveSettings() {
        RecordStore r = null;
        try {
            r = RecordStore.openRecordStore(CFG_STORE, true);
            byte[] b = new byte[6 + NKEY];
            b[0] = (byte) rdIdx; b[1] = (byte) lang;
            b[2] = (byte) musLv; b[3] = (byte) sfxLv; b[4] = 2;
            for (int i = 0; i < NKEY; i++) b[5 + i] = (byte) km[i];
            b[5 + NKEY] = (byte) (flatFol ? 1 : 0);
            if (r.getNumRecords() > 0) r.setRecord(1, b, 0, b.length);
            else r.addRecord(b, 0, b.length);
        } catch (Exception e) {}
        if (r != null) { try { r.closeRecordStore(); } catch (Exception e) {} }
    }

    // ---------------------------------------------------------------- navegacion de menus
    boolean isMenu() {
        return state == ST_MENU || state >= ST_PAUSE;
    }

    void buildNav() {
        if (state == ST_MENU) navIds = new int[] { A_PLAY, A_CTRL, A_SND, A_QUIT };
        else if (state == ST_PAUSE) navIds = new int[] { A_RESUME, A_SETTINGS, A_LANG, A_TOMENU };
        else if (state == ST_SET) navIds = inWorld ? new int[] { A_RD, A_FOL, A_MUS, A_SFX, A_SETBACK } : new int[] { A_FOL, A_MUS, A_SFX, A_SETBACK };
        else if (state == ST_CREATE) navIds = new int[] { A_CNAME, A_CSEED, A_CMODE, A_CDIFF, A_CGO, A_CBACK };
        else if (state == ST_CONFIRM) navIds = new int[] { A_YES, A_NO };
        else if (state == ST_LANG) {
            navIds = new int[langN];
            for (int i = 0; i < langN; i++) navIds[i] = 300 + i;
        }
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
        cMode = 0; cDiff = 0;
        go(ST_CREATE);
        navSel = 3;
    }

    void openPause() {
        clearKeys();
        go(ST_PAUSE);
        job = J_SAVE;
    }

    void closeSettings() {
        saveSettings();
        if (!inWorld) { go(ST_MENU); navSel = 2; return; }   // ajustes abiertos desde el menu principal
        go(ST_PAUSE);
        navSel = 1;
        if (rdIdx != rdApplied) job = J_APPLY;
    }

    void rdStep(int d) {
        rdIdx = (rdIdx + d + RD_OPT.length) % RD_OPT.length;
    }

    // ---------------------------------------------------------------- sonido
    void sndApply() { Sfx.setLevels(musLv * 20, sfxLv * 20); }

    void lvStep(int which, int d) {
        if (which == 0) musLv = (musLv + d + 6) % 6; else sfxLv = (sfxLv + d + 6) % 6;
        sndApply();
        if (which == 1) Sfx.play(Sfx.S_POP, 100);   // prueba del volumen de efectos
    }

    // que tema de musica corresponde ahora: 0 dia, 1 noche, 2 nether, 3 end
    void musicCtx() {
        int c = 0;
        if (state == ST_END) c = 3;
        else if (inWorld) {
            if (dim == 1) c = 2;
            else if (dim == 2) c = 3;
            else { int t = worldTime % DAY_LEN; if (t >= 13000 && t < 22800) c = 1; }
        }
        Sfx.ctx(c);
    }

    // sonidos de ambiente al azar: cuevas, Nether y End
    void ambientSnd() {
        if (dim == 1) { if (rnd(700) == 0) Sfx.play(Sfx.S_NETHER, 70); }
        else if (dim == 2) { if (rnd(600) == 0) Sfx.play(Sfx.S_END_AMB, 60); }
        else if (rnd(900) == 0 && py < topY(ifloor(px), ifloor(pz)) - 4) Sfx.play(Sfx.S_CAVE, 70);
    }

    // grupo de sonido de un bloque (-1 = silencio)
    int grp(int b) {
        if (b <= 0 || b == B_WATER || b == B_LAVA || b == B_PORTAL || b == B_ENDPORTAL) return -1;
        if (isLog(b) || isPlanks(b) || b == B_TABLE || b == B_CHEST || b == B_TORCH || isDoor(b)) return Sfx.G_WOOD;
        if (b == B_GRASS || isLeaves(b) || b == B_SAPLING || b == B_CACTUS || b == B_BED || b == B_TNT || b == B_KELP
                || b == B_SEAGRASS || (b >= B_CROP0 && b <= B_CROP2) || (b >= B_TUFT && b <= B_FLOWER_Y)) return Sfx.G_GRASS;
        if (b == B_DIRT || b == B_FARM_DRY || b == B_FARM_WET || b == B_SOULSAND) return Sfx.G_DIRT;
        if (b == B_SAND || b == B_GRAVEL || b == B_SNOW) return Sfx.G_SAND;
        if ((b >= B_IRON_BLK && b <= B_DIAM_BLK) || b == B_SPAWNER) return Sfx.G_METAL;
        if (b == B_ICE || b == B_GLOWSTONE || b == B_CRYSTAL) return Sfx.G_GLASS;
        return Sfx.G_STONE;
    }

    void bSnd(int b, int kind, int vol) {
        int g = grp(b);
        if (g >= 0) Sfx.play(Sfx.blk(g, kind), vol);
    }

    // sonido de un mob con volumen segun la distancia (el dragon se oye de mas lejos)
    void mobSnd(Mob m, int kind) {
        int t = m.type;
        if (t == 5 || t == 11 || t == 15) return;
        float dx = m.x - px, dy = m.y - py, dz = m.z - pz;
        float d = (float) Math.sqrt((double) (dx * dx + dy * dy + dz * dz));
        float far = (t == 14) ? 60f : 18f;
        if (d > far) return;
        int vol = (int) (100f - d * 92f / far);
        if (t >= 7 && t <= 9) { if (kind > 0) Sfx.play(Sfx.S_SPLASH, vol / 2); return; }
        Sfx.play(Sfx.mob(t, kind), vol);
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
        jobWorld = addWorld(cName, sd, cMode | (cDiff << 1));
        job = J_ENTER;
    }

    // abre el editor de texto del sistema (nombre o semilla)
    void editText(int which) {
        editing = which;
        TextBox tb = new TextBox(L(which == 1 ? "Nombre del mundo" : "Semilla (vacio = aleatoria)"),
                which == 1 ? cName : cSeedTxt, which == 1 ? 20 : 12, TextField.ANY);
        cmdCancel = new Command(L("Cancelar"), Command.CANCEL, 2);
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
        Sfx.play(Sfx.S_CLICK, 60);
        if (id == A_SND) { go(ST_SET); return; }
        if (id == A_MUS) { lvStep(0, 1); return; }
        if (id == A_SFX) { lvStep(1, 1); return; }
        if (id == A_FOL) { flatFol = !flatFol; saveSettings(); return; }
        if (id >= A_KEY0 && id < A_KEY0 + NKEY) { ksel = id - A_KEY0; openKeyPick(); return; }
        if (id >= A_PICK0 && id <= A_PICK0 + NACT) { pickAssign(id - A_PICK0); return; }
        if (id >= 300 && id < 300 + MAX_LANGS) {   // pantalla de inicio: elegir idioma
            int li = id - 300;
            if (li < langN) {
                lang = langIds[li];
                applyLang();
                saveSettings();
                go(ST_MENU);
            }
            return;
        }
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
            case A_LANG: nextLang(); applyLang(); saveSettings(); break;
            case A_CTRL: kpick = false; ksel = 5; go(ST_KEYS); break;
            case A_KRESET: System.arraycopy(KEY_DEF, 0, km, 0, NKEY); kpick = false; saveSettings(); break;
            case A_KBACK: menuBack(); break;
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
            case A_CDIFF: cDiff = (cDiff == 1) ? 0 : (cDiff == 0 ? 2 : 1); break;   // pacifico -> normal -> dificil
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
        else if (state == ST_KEYS) {
            if (kpick) kpick = false;
            else { saveSettings(); go(ST_MENU); navSel = 1; }
        }
        else if (state == ST_WORLDS) openMain();
        else if (state == ST_CREATE || state == ST_CONFIRM) openWorlds();
    }

    // ---------------------------------------------------------------- controles: mapeo tecla -> accion
    boolean actDown(int a) {
        for (int k = 0; k < NKEY; k++) if (km[k] == a && kd[k]) return true;
        return false;
    }

    boolean actBound(int a) {
        for (int k = 0; k < NKEY; k++) if (km[k] == a) return true;
        return false;
    }

    // primera accion sin ninguna tecla (-1 = todas tienen)
    int firstUnbound() {
        for (int a = 0; a < NACT; a++) if (!actBound(a)) return a;
        return -1;
    }

    static String keyShort(int k) {
        if (k >= 0 && k <= 9) return String.valueOf(k);
        return k == 10 ? "*" : (k == 11 ? "#" : (k == 12 ? "LSK" : "RSK"));
    }

    // posiciones del esquema: fila 0 = LSK . RSK; filas 1-4 = teclado numerico; fila 5 = Restaurar / Atras (celdas 14 y 15)
    void layoutKeys() {
        int lh = font.getHeight();
        int gap = 3;
        int top = 3 + fontMid.getHeight() + 4;
        int bottom = sh - lh * 2 - 6;
        int bh = lh + 8;
        int cw = (sw - 8 - gap * 2) / 3;
        if (cw > 80) cw = 80;
        int ch = (bottom - top - gap * 5 - bh) / 5;
        if (ch > 40) ch = 40;
        if (ch < lh + 4) ch = lh + 4;
        int gw = cw * 3 + gap * 2, x0 = (sw - gw) / 2;
        int[][] g = { { 12, -1, 13 }, { 1, 2, 3 }, { 4, 5, 6 }, { 7, 8, 9 }, { 10, 0, 11 } };
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 3; c++) {
                int k = g[r][c];
                if (k < 0) continue;
                kbX[k] = x0 + c * (cw + gap); kbY[k] = top + r * (ch + gap); kbW[k] = cw; kbH[k] = ch;
            }
        }
        int y5 = top + 5 * (ch + gap), hw = (gw - gap) / 2;
        kbX[14] = x0; kbY[14] = y5; kbW[14] = hw; kbH[14] = bh;
        kbX[15] = x0 + hw + gap; kbY[15] = y5; kbW[15] = gw - hw - gap; kbH[15] = bh;
    }

    void keyNav(int k) {
        layoutKeys();
        int c = ksel, best = -1, bs = 0x7fffffff;
        int cx = kbX[c] + kbW[c] / 2, cy = kbY[c] + kbH[c] / 2;
        for (int j = 0; j < 16; j++) {
            if (j == c || kbW[j] <= 0) continue;
            int dx = kbX[j] + kbW[j] / 2 - cx, dy = kbY[j] + kbH[j] / 2 - cy;
            boolean ok = (k == 2) ? dy < -2 : ((k == 8) ? dy > 2 : ((k == 4) ? dx < -2 : dx > 2));
            if (!ok) continue;
            if ((k == 4 || k == 6) && Math.abs(dy) > 2) continue;   // izquierda/derecha: solo dentro de la misma fila
            int score = (k == 2 || k == 8) ? Math.abs(dy) + 3 * Math.abs(dx) : Math.abs(dx);
            if (score < bs) { bs = score; best = j; }
        }
        if (best >= 0) ksel = best;
    }

    void openKeyPick() {
        kpick = true;
        kpFollow = true;
        kpSel = (km[ksel] >= 0) ? km[ksel] : NACT;   // la ultima fila es "Sin accion"
    }

    void kpMove(int d) {
        kpSel = (kpSel + d + NACT + 1) % (NACT + 1);
        kpFollow = true;
    }

    void pickAssign(int i) {
        km[ksel] = (i < NACT) ? i : -1;
        kpick = false;
        saveSettings();
    }

    void keysKey(int k) {
        if (kpick) {
            if (k == 2) kpMove(-1);
            else if (k == 8) kpMove(1);
            else if (k == 5) pickAssign(kpSel);
            else if (k == 0 || k == 12 || k == 13) kpick = false;
            return;
        }
        if (k == 2 || k == 4 || k == 6 || k == 8) keyNav(k);
        else if (k == 5) activate(ksel < NKEY ? A_KEY0 + ksel : (ksel == 14 ? A_KRESET : A_KBACK));
        else if (k == 0 || k == 12 || k == 13) menuBack();
    }

    void menuKey(int k) {
        if (state == ST_KEYS) { keysKey(k); return; }
        if (k == 2) navMove(-1);
        else if (k == 8) navMove(1);
        else if (k == 5) activate(navIds[navSel]);
        else if (k == 4 || k == 6) {
            int na = navIds[navSel];
            if (na == A_RD) rdStep(k == 6 ? 1 : -1);
            else if (na == A_MUS) lvStep(0, k == 6 ? 1 : -1);
            else if (na == A_SFX) lvStep(1, k == 6 ? 1 : -1);
            else if (na == A_FOL) { flatFol = !flatFol; saveSettings(); }
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
                int a = km[k];
                // si ninguna tecla tiene "Pausa", RSK pausa igual (para no quedar sin menu)
                if (a == ACT_PAUSE || (k == 13 && !actBound(ACT_PAUSE))) { openPause(); return; }
                kd[k] = true;
                if (a == ACT_FW) { if (tick - fwTick < 8) sprint = true; fwTick = tick; }
                if (creative && a == ACT_JUMP) {   // doble toque de salto: volar / aterrizar
                    if (tick - jumpTick < 8) { flying = !flying; vy = 0; fallDist = 0; jumpTick = -100; }
                    else jumpTick = tick;
                }
                if (a == ACT_INV) openInv(2);
                else if (a == ACT_HPREV) { sel = (sel + 8) % 9; labelT = 40; }
                else if (a == ACT_HNEXT) { sel = (sel + 1) % 9; labelT = 40; }
                else if (a == ACT_MINE) {
                    if (fishState == 3) fishHit();
                    else { mineOn = true; starTicks = 1; starConsumed = false; mineProg = 0; }
                }
            } else if (state == ST_END) {
                if (k == 5 || k == 12 || k == 13) endBack();
            } else if (state == ST_DEAD) {
                if (k == 5) respawn();
            } else if (state == ST_INV) {
                if (k == 0 || km[k] == ACT_INV || k == 12 || k == 13) closeInv();
                else if (craftN == TRADE && (k == 1 || k == 3)) tradeMove(k == 3 ? 1 : -1);
                else if (craftN == ENCH && (k == 1 || k == 3)) enchMove(k == 3 ? 1 : -1);
                else if (palMode() && (k == 1 || k == 3)) palFlip(k == 3 ? 1 : -1);
                else if (k == 2 || k == 4 || k == 6 || k == 8) moveCursor(k);
                else if (k == 5) click(cursor);
                else if (k == 10) clickSecondary(cursor);
                else if (k == 11) dbgOn = !dbgOn;
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
            if (km[k] == ACT_MINE && !actDown(ACT_MINE)) {
                if (state == ST_PLAY && !starConsumed && starTicks > 0 && starTicks <= 6) doTap();
                mineOn = false; starTicks = 0; starConsumed = false; mineProg = 0; mineX = NONE;
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
            touch = true;
            if (state == ST_PLAY) tcPress(x, y);
            else if (state == ST_INV) tcInvPress(x, y);
            else if (state == ST_DEAD) respawn();
            else if (state == ST_END) endBack();
        }
    }

    protected void pointerDragged(int x, int y) {
        if (busy != null) return;
        synchronized (lock) {
            if (state == ST_PLAY) { tcDrag(x, y); return; }
            if (state == ST_INV) { tcInvDrag(x, y); return; }
            if (!tpDown) return;
            if (Math.abs(y - tpY) > 8) tpDrag = true;
            if (tpDrag && (state == ST_WORLDS || state == ST_LANG || (state == ST_KEYS && kpick))) {
                int step = (state == ST_KEYS) ? pkRow + 1 : btnH + 3;
                if (step < 4) step = 4;
                wScroll = tpScroll0 + (tpY - y) / step;
            }
        }
    }

    protected void pointerReleased(int x, int y) {
        if (busy != null) {   // el juego esta congelado: soltar todo para que no quede nada pegado
            tFw = false; tBk = false; tL = false; tR = false; tJp = false; mineOn = false; tcMode = 0; tcInvDown = false;
            return;
        }
        synchronized (lock) {
            if (state == ST_PLAY) { tcRelease(); tpDown = false; return; }
            if (state == ST_INV) { tcInvRelease(); tpDown = false; return; }
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
            if (state == ST_KEYS && kpick) kpick = false;   // tocar fuera del selector lo cierra
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
                slDur[i] = isTool(id) ? ((dur & DM) > 0 ? dur : (dur | toolMax(id))) : (id == I_BOOK ? dur : 0);
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
        for (int id = B_KELP; id <= B_CORAL4; id++) palIds[palN++] = id;
        for (int id = I_STRING; id <= I_DRIED_KELP; id++) palIds[palN++] = id;
        palIds[palN++] = B_MAGMA; palIds[palN++] = I_BOAT;
        for (int id = B_ENDSTONE; id <= B_EGG; id++) if (id != B_ENDPORTAL) palIds[palN++] = id;
        for (int id = I_BLAZE_ROD; id <= I_EYE; id++) palIds[palN++] = id;
        for (int id = B_CANE; id <= I_BUCKET_M; id++) palIds[palN++] = id;
        for (int id = B_TUFT; id <= B_FLOWER_Y; id++) palIds[palN++] = id;
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
        int mw = (touch && palMode()) ? Math.max(14, sw / 10) : 4;   // margen lateral: botones de pagina en creativo tactil
        int s = Math.min((sw - 2 * mw) / 9, (sh - 40 - (touch ? Math.min(sw / 11, 24) + 6 : 0)) / 7);
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
        int n = (craftN == FURN || craftN == TRADE || craftN == ENCH) ? 0 : craftN;
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
        if (craftN == ENCH) {   // izquierda: lista; columna 1: item a encantar -> resultado; columna 2: item a vender -> experiencia
            slx[36] = x0 + 5 * s + s / 2; sly[36] = y0; slActive[36] = true;
            slx[OUT] = x0 + 5 * s + s / 2; sly[OUT] = y0 + 2 * s; slActive[OUT] = true;
            slx[37] = x0 + 7 * s + s / 2; sly[37] = y0; slActive[37] = true;
            slx[38] = x0 + 7 * s + s / 2; sly[38] = y0 + 2 * s; slActive[38] = true;
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
        if (craftN == FURN || craftN == CHEST || craftN == ENCH || craftN == TRADE) return -1;
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
        if (craftN == ENCH) { enchOutput(); return; }
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
                if (slId[s] == I_BUCKET_M) addItem(I_BUCKET, 1, 0);
                slCnt[s]--;
                if (slCnt[s] <= 0) clearSlot(s);
            }
        }
    }

    void takeOutput() {
        if (craftN == TRADE) { tradeTake(); return; }
        if (craftN == ENCH) { enchTake(); return; }
        if (slId[OUT] == 0) return;
        Sfx.play(Sfx.S_POP, 50);
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
        Sfx.play(Sfx.S_CLICK, 35);
        if (palMode() && idx >= 9 && idx < 36) { palTake(idx, 64); return; }
        if (craftN == FURN && idx == 38) { takeFurnaceOut(); return; }
        if (craftN == ENCH && idx == 38) { enchSell(); return; }
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
        Sfx.play(Sfx.S_CLICK, 35);
        if (palMode() && idx >= 9 && idx < 36) { palTake(idx, 1); return; }
        if (craftN == FURN && idx == 38) { takeFurnaceOut(); return; }
        if (craftN == ENCH && idx == 38) { enchSell(); return; }
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
            // red de seguridad: si el dedo ya no esta apoyado, ningun control tactil puede quedar activo
            if (!tpDown && (tcMode != 0 || tFw || tBk || tL || tR || tJp)) tcCancel();
            if (labelT > 0) labelT--;
            if (msgT > 0) msgT--;
            if (dmgFlash > 0) dmgFlash--;
            if (tick % 20 == 0) musicCtx();
            if (state == ST_INV && craftN == FURN) { syncFromSlots(); updateFurnace(); syncToSlots(); if (fBurn > 0 && tick % 70 == 0) Sfx.play(Sfx.S_CRACKLE, 70); }
            if (state == ST_INV) tcInvTick();
            if (state != ST_PLAY) return;
            worldTime++;
            bright = (dim == 1) ? 45 : (dim == 2 ? 100 : dayBright());
            if (bright < 100 && tick % 5 == 0) scanTorches();
            updateFurnace();
            updateSaplings();
            if (dim == 0 && tick % 200 == 0) growCrops();
            if (++autoT >= 2400) { autoT = 0; needSave = true; }
            updatePlayer();
            if (dim == 2 && py < 8f) {   // cayo al vacio
                if (creative) { px = 34.5f; pz = 0.5f; py = 34f; vy = 0; fallDist = 0; }
                else hurt(99);
            }
            updatePortal();
            updateWindow(false);
            loadMissing(1);
            updateMobs();
            castRay();
            updateAction();
            updateSurvival();
            ambientSnd();
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
            Sfx.play(Sfx.S_DEATH, 100);
        } else Sfx.play(Sfx.S_HURT, 100);
    }

    void updatePlayer() {
        boolean fw, bk, jp, up, down;
        fw = actDown(ACT_FW); bk = actDown(ACT_BK); jp = actDown(ACT_JUMP);
        up = actDown(ACT_UP); down = actDown(ACT_DOWN);
        fw |= tFw; bk |= tBk; jp |= tJp;
        boolean sl = actDown(ACT_SL), sr = actDown(ACT_SR);   // desplazamiento lateral
        float sd = (sr ? 1f : 0f) - (sl ? 1f : 0f);
        if (!fw) sprint = false;
        if (actDown(ACT_TL) || tL) yaw -= LOOK;
        if (actDown(ACT_TR) || tR) yaw += LOOK;
        if (up) pitch += LOOK;
        if (down) pitch -= LOOK;
        if (pitch > 1.4f) pitch = 1.4f;
        if (pitch < -1.4f) pitch = -1.4f;
        if (ride != null) {
            if (mobs.contains(ride)) { rideBoat(fw, bk); return; }
            ride = null;
        }

        float sn = (float) Math.sin(yaw), cs = (float) Math.cos(yaw);
        if (creative && flying) {
            // vuelo: avanzar sigue la mirada (arriba/abajo), saltar sube recto
            float sp2 = SPD * (sprint ? 2.4f : 1.6f);
            float cpc = (float) Math.cos(pitch), spc = (float) Math.sin(pitch);
            float fmx = 0, fmy = 0, fmz = 0;
            if (fw) { fmx += sn * cpc * sp2; fmz += cs * cpc * sp2; fmy += spc * sp2; }
            if (bk) { fmx -= sn * cpc * sp2; fmz -= cs * cpc * sp2; fmy -= spc * sp2; }
            if (jp) fmy += sp2;
            if (sd != 0f) { fmx += cs * sd * sp2; fmz -= sn * sd * sp2; }
            if (fmx != 0 && !boxCollides(px + fmx, py, pz, HW, 1.8f)) px += fmx;
            if (fmz != 0 && !boxCollides(px, py, pz + fmz, HW, 1.8f)) pz += fmz;
            if (fmy != 0 && !boxCollides(px, py + fmy, pz, HW, 1.8f)) py += fmy;
            if (py < 1f && dim != 2) py = 1f;
            vy = 0; fallDist = 0; onGround = false;
            moving = (fw || bk || sd != 0f);
            return;
        }
        float stx0 = px, stz0 = pz;
        float mx = 0, mz = 0;
        float fdr = (fw ? 1f : 0f) - (bk ? 1f : 0f);
        mx = (sn * fdr + cs * sd) * SPD;
        mz = (cs * fdr - sn * sd) * SPD;
        if (fdr != 0f && sd != 0f) { mx *= 0.7071f; mz *= 0.7071f; }   // en diagonal no va mas rapido
        moving = (fw || bk || sd != 0f);
        boolean inW = waterAtF(px, py + 0.4f, pz) || waterAtF(px, py + 1.2f, pz)
                || lavaAtF(px, py + 0.4f, pz) || lavaAtF(px, py + 1.2f, pz);
        boolean swim = inW && !lavaAtF(px, py + 0.4f, pz) && !lavaAtF(px, py + 1.2f, pz)
                && waterAtF(px, py + 0.4f, pz) && waterAtF(px, py + 1.2f, pz);
        int bc = inW ? bubbleAt(ifloor(px), ifloor(py + 0.3f), ifloor(pz)) : 0;   // corriente de burbujas bajo el jugador
        if (inW != wasInW) {
            wasInW = inW;
            if (inW) Sfx.play((lavaAtF(px, py + 0.4f, pz) || lavaAtF(px, py + 1.2f, pz)) ? Sfx.S_LAVA : Sfx.S_SPLASH, 80);
        }
        if (swim && (fw || bk)) {
            // nadar: igual que volar, avanza hacia donde miras; doble clic adelante = mas rapido
            float s2 = SPD * ((sprint && fw) ? 1.2f : 0.6f);
            float cpc = (float) Math.cos(pitch), spc = (float) Math.sin(pitch), dr = fw ? 1f : -1f;
            float smx = dr * sn * cpc * s2, smz = dr * cs * cpc * s2, smy = dr * spc * s2;
            if (sd != 0f) { smx += cs * sd * s2; smz -= sn * sd * s2; }
            if (smy > 0 && !waterAtF(px, py + 1.9f, pz)) smy = 0;
            boolean bx = boxCollides(px + smx, py, pz, HW, 1.8f), bz = boxCollides(px, py, pz + smz, HW, 1.8f);
            if (!bx) px += smx;
            if (!bz) pz += smz;
            if (smy != 0 && !boxCollides(px, py + smy, pz, HW, 1.8f)) py += smy;
            if ((bx || bz) && dr > 0 && !boxCollides(px + smx, py + 1.0f, pz + smz, HW, 1.8f) && !boxCollides(px, py + 0.15f, pz, HW, 1.8f)) py += 0.15f;
            if (bc != 0) { float bpy = bc * 0.3f; if (!boxCollides(px, py + bpy, pz, HW, 1.8f)) py += bpy; }
            vy = 0; fallDist = 0; onGround = false;
            return;
        }
        if (inW) { mx *= 0.5f; mz *= 0.5f; }
        else if (sprint && fw) { mx *= 1.4f; mz *= 1.4f; }

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
            if (bc > 0) vy += 0.12f; else if (bc < 0) vy -= 0.1f;
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
                if (fallDist > 3f) { Sfx.play(Sfx.S_FALL, 100); hurt((int) Math.ceil(fallDist - 3f)); }
                else if (fallDist > 1.2f) bSnd(blockAt(ifloor(px), ifloor(py - 0.1f), ifloor(pz)), Sfx.K_PLACE, 70);
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
        if (onGround && !inW) {   // pasos segun el bloque que se pisa
            float sdx = px - stx0, sdz = pz - stz0;
            stepD += (float) Math.sqrt((double) (sdx * sdx + sdz * sdz));
            if (stepD >= 1.8f) { stepD = 0f; bSnd(blockAt(ifloor(px), ifloor(py - 0.1f), ifloor(pz)), Sfx.K_STEP, 100); }
        } else if (!onGround) stepD = 0f;
    }

    // ---------------------------------------------------------------- barco y corrientes de burbujas
    // -1 = corriente descendente (magma al fondo de la columna de agua), +1 = ascendente (arena de almas), 0 = nada
    int bubbleAt(int x, int y, int z) {
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

    boolean boatOk(float x, float z, int by) {
        int ix = ifloor(x), iz = ifloor(z);
        return wtr(blockAt(ix, by, iz)) && !opq[blockAt(ix, by + 1, iz)];
    }

    void floatBoat(Mob m, int bx, int by, int bz) {
        int cy = by, n = 0;
        while (n < 20 && wtr(blockAt(bx, cy + 1, bz))) { cy++; n++; }
        float t = cy + 0.9f;
        if (m.y < t) { m.y += 0.08f; if (m.y > t) m.y = t; }
        else if (m.y > t) { m.y -= 0.08f; if (m.y < t) m.y = t; }
    }

    void sinkBoat(Mob m, int bx, int bz) {
        float ny = m.y - 0.12f;
        if (wtr(blockAt(bx, ifloor(ny), bz))) m.y = ny;
        else m.y = ifloor(ny) + 1f;   // apoyado sobre el bloque del fondo
    }

    // barco sin jinete: flota en la superficie, se hunde en una corriente descendente, cae si no hay agua
    void updateBoat(Mob m) {
        if (m.hurt > 0) m.hurt--;
        if (m == ride) return;
        int bx = ifloor(m.x), bz = ifloor(m.z), by = ifloor(m.y + 0.05f);
        m.sp = 0;
        if (wtr(blockAt(bx, by, bz))) {
            m.vy = 0;
            if (bubbleAt(bx, by, bz) < 0) { m.sunk = true; sinkBoat(m, bx, bz); }
            else { m.sunk = false; floatBoat(m, bx, by, bz); }
        } else {
            m.sunk = false;
            m.vy = (m.vy - GRAV) * 0.98f;
            float ny = m.y + m.vy;
            if (boxCollides(m.x, ny, m.z, 0.6f, 0.45f)) { if (m.vy < 0) m.y = ifloor(ny) + 1.001f; m.vy = 0; }
            else m.y = ny;
        }
    }

    // el jugador maneja el barco como su propio cuerpo: no sale del agua, mas rapido, flota siempre
    void rideBoat(boolean fw, boolean bk) {
        Mob m = ride;
        float sn = (float) Math.sin(yaw), cs = (float) Math.cos(yaw);
        float tg = fw ? 0.26f : (bk ? -0.1f : 0f);
        m.sp += (tg - m.sp) * 0.2f;
        m.ang = yaw;
        int by = ifloor(m.y + 0.05f);
        float mx = sn * m.sp, mz = cs * m.sp;
        float k = m.sp >= 0f ? 0.7f : -0.7f, qx = sn * k, qz = cs * k;
        if (boatOk(m.x + mx + qx, m.z + mz + qz, by)) { m.x += mx; m.z += mz; }
        else if (boatOk(m.x + mx + qx, m.z + qz, by)) m.x += mx;
        else if (boatOk(m.x + qx, m.z + mz + qz, by)) m.z += mz;
        else m.sp = 0f;
        int bx = ifloor(m.x), bz = ifloor(m.z);
        by = ifloor(m.y + 0.05f);
        if (bubbleAt(bx, by, bz) < 0) {   // corriente descendente: el barco se hunde y el jugador se baja
            ride = null;
            m.sunk = true;
            m.sp = 0f;
            px = m.x; pz = m.z; py = m.y + 0.3f; vy = 0f;
            say("El barco se hundio!");
            return;
        }
        floatBoat(m, bx, by, bz);
        px = m.x; pz = m.z; py = m.y + 0.14f;
        vy = 0f; fallDist = 0f; onGround = false;
        moving = fw || bk;
    }

    void mount(Mob m) {
        ride = m;
        m.sunk = false; m.sp = 0f; m.ang = yaw;
        px = m.x; pz = m.z; py = m.y + 0.14f;
        vy = 0f; fallDist = 0f; flying = false; sprint = false;
        say("Montaste el barco");
    }

    void dismount() {
        Mob m = ride;
        ride = null;
        if (m == null || !mobs.contains(m)) return;
        for (int i = 0; i < 8; i++) {
            float a = i * 0.7854f;
            float nx = m.x + (float) Math.sin(a) * 1.4f, nz = m.z + (float) Math.cos(a) * 1.4f;
            for (int k = 0; k <= 1; k++) {
                int fy = ifloor(m.y) + k;
                if (opq[blockAt(ifloor(nx), fy, ifloor(nz))] && !boxCollides(nx, fy + 1.001f, nz, HW, 1.8f)) {
                    px = nx; pz = nz; py = fy + 1.001f; vy = 0f; fallDist = 0f;
                    return;
                }
            }
        }
        py = m.y + 0.3f;   // sin orilla cerca: queda nadando
    }

    void placeBoat() {
        float x, y, z;
        if (blockAt(tx, ty, tz) == B_WATER) {
            int cy = ty, n = 0;
            while (n < 20 && wtr(blockAt(tx, cy + 1, tz))) { cy++; n++; }
            x = tx + 0.5f; z = tz + 0.5f; y = cy + 0.9f;
        } else {
            if (ay < 0 || blockAt(ax, ay, az) != 0) return;
            x = ax + 0.5f; z = az + 0.5f; y = ay + 0.001f;
        }
        Mob nm = new Mob();
        Sfx.play(Sfx.S_SPLASH, 50);
        nm.type = 11; nm.x = x; nm.y = y; nm.z = z; nm.ang = yaw; nm.hp = 1000;
        mobs.addElement(nm);
        consumeSel();
    }

    void updateMobs() {
        updateFishing();
        for (int i = mobs.size() - 1; i >= 0; i--) {
            Mob m = (Mob) mobs.elementAt(i);
            updateMob(m);
            if (diff == 1 && m.hp > 0 && hostileMob(m.type)) m.hp = 0;
            if (m.hp > 0 && --m.snd <= 0) { m.snd = 90 + rnd(260); if (rnd(2) == 0) mobSnd(m, 0); }
            if (m.y < (dim == 2 ? 6f : -10f) && m.type != 14) m.hp = 0;
            if (m.type == 14 && m.hp <= 0 && dragon != null) dragonDie(m);
            if (m.type == 12 && dim == 0 && bright >= 90 && m.hp > 0 && m.st == 0 && (tick % 100) == 0) m.hp = 0;
            if (m.type == 5 && ++m.fuse >= 80) explode(m);
            if ((m.type == 3 || m.type == 4) && bright >= 90 && m.hp > 0 && (tick % 20) == 0) { m.hp--; m.hurt = 6; mobSnd(m, m.hp > 0 ? 1 : 2); }
            if (m.hp <= 0) mobs.removeElementAt(i);
        }
        if (++spawnTimer >= 400) {
            spawnTimer = 0;
            if (dim == 0 && countMobs(false) < 10) spawnMob(rnd(3), true);
        }
        if (bright < 70 && ++hostTimer >= 100) {
            hostTimer = 0;
            if (countMobs(true) < (diff == 2 ? 9 : 6)) spawnMob(dim == 1 ? 3 : hostType(), true);
        }
        if (tick % 200 == 0) updateSpawner();
        if (dim == 2) updateEnd();
    }

    int countMobs(boolean hostile) {
        int n = 0;
        for (int i = 0; i < mobs.size(); i++) {
            Mob m = (Mob) mobs.elementAt(i);
            if (m.type == 5 || m.type == 6 || m.type == 11 || m.type >= 13 || (m.type >= 7 && m.type <= 9)) continue;
            if ((m.type >= 3) == hostile) n++;
        }
        return n;
    }

    boolean wtr(int b) { return b == B_WATER || b == B_KELP || b == B_SEAGRASS; }

    int hostType() { if (dim == 0 && rnd(8) == 0) return 12; int r = rnd(3); return r == 0 ? 3 : (r == 1 ? 4 : 10); }

    // peces: nadan sin gravedad dentro del agua y rebotan contra la orilla/fondo/superficie
    void updateFish(Mob m) {
        if (m.hurt > 0) m.hurt--;
        if (--m.timer <= 0) { m.ang = rndf() * 6.2832f; m.vy = (rndf() - 0.5f) * 0.03f; m.timer = 30 + rnd(60); }
        float nx = m.x + (float) Math.sin(m.ang) * 0.04f, nz = m.z + (float) Math.cos(m.ang) * 0.04f, ny = m.y + m.vy;
        if (wtr(blockAt(ifloor(nx), ifloor(ny + 0.1f), ifloor(nz))) && wtr(blockAt(ifloor(nx), ifloor(ny + 0.45f), ifloor(nz)))) {
            m.x = nx; m.y = ny; m.z = nz;
        } else { m.ang += 2.5f; m.vy = -m.vy; m.timer = 10; }
    }

    // cana de pescar: clic lanza al agua; al picar empieza el minijuego (5 puntos para el jugador = pesca, 5 para el pez = se escapa)
    void useRod() {
        if (fishState == 3) { fishHit(); return; }
        if (fishState == 0) {
            float sy = (float) Math.sin(yaw), cy = (float) Math.cos(yaw), sp = (float) Math.sin(pitch), cp = (float) Math.cos(pitch);
            for (int i = 2; i <= 16; i++) {
                float d = i * 0.5f;
                int x = ifloor(px + sy * cp * d), y = ifloor(py + EYE + sp * d), z = ifloor(pz + cy * cp * d);
                int b = blockAt(x, y, z);
                if (b == B_WATER) { fishX = x; fishY = y; fishZ = z; fishState = 1; fishT = 60 + rnd(140); say("Sedal lanzado"); Sfx.play(Sfx.S_CAST, 90); return; }
                if (b != 0 && b != B_KELP && b != B_SEAGRASS) break;
            }
            say("Apunta al agua");
            return;
        }
        say("Nada todavia");   // recoger el sedal antes de que pique
        fishState = 0;
    }

    // tecla de accion durante el minijuego: marca dentro de la zona verde = punto del jugador, fuera = punto del pez
    void fishHit() {
        if (fishState != 3 || tick - fgLast < 5) return;
        fgLast = tick;
        fgOk = fgM >= fgC - fgS / 2f && fgM <= fgC + fgS / 2f;
        fgFlash = 8;
        if (fgOk) { fgYou++; Sfx.play(Sfx.S_POP, 60); } else { fgFish++; Sfx.play(Sfx.S_SPLASH, 40); }
        if (fgYou >= 5) {
            int r = rnd(100);
            addItem(r < 50 ? I_COD : (r < 80 ? I_SALMON : (r < 95 ? I_TROPICAL : I_STRING)), 1, 0);
            Sfx.play(Sfx.S_SPLASH, 70);
            say("Pescaste algo!");
            fishState = 0;
        } else if (fgFish >= 5) {
            say("Se escapo");
            fishState = 0;
        }
    }

    float fgStep(float cur, float target, float st) {
        float d = target - cur;
        return d > st ? cur + st : (d < -st ? cur - st : target);
    }

    void updateFishing() {
        if (fishState == 0) return;
        if (slId[sel] != I_ROD || blockAt(fishX, fishY, fishZ) != B_WATER
                || Math.abs(fishX + 0.5f - px) > 20f || Math.abs(fishZ + 0.5f - pz) > 20f) { fishState = 0; return; }
        if (fishState == 1) {
            if (--fishT <= 0) {
                fishState = 3; fgYou = 0; fgFish = 0; fgM = 0.5f; fgD = 1f; fgC = 0.5f; fgS = 0.32f; fgTC = 0.5f; fgTS = 0.32f; fgT = 20; fgFlash = 0; fgLast = tick;
                say("Pica! Zona verde"); Sfx.play(Sfx.S_BITE, 100);
            }
            return;
        }
        // minijuego: la marca sube y baja (mas rapido con cada punto) y la zona verde se mueve y cambia de tamano
        float sp = 0.022f + 0.003f * (fgYou + fgFish);
        fgM += fgD * sp;
        if (fgM >= 1f) { fgM = 1f; fgD = -1f; } else if (fgM <= 0f) { fgM = 0f; fgD = 1f; }
        if (--fgT <= 0) { fgTS = 0.16f + rndf() * 0.2f; fgTC = fgTS / 2f + rndf() * (1f - fgTS); fgT = 30 + rnd(50); }
        fgS = fgStep(fgS, fgTS, 0.004f);
        fgC = fgStep(fgC, fgTC, 0.009f);
        if (fgC < fgS / 2f) fgC = fgS / 2f;
        if (fgC > 1f - fgS / 2f) fgC = 1f - fgS / 2f;
        if (fgFlash > 0) fgFlash--;
    }

    void updateMob(Mob m) {
        if (m.type == 11) { updateBoat(m); return; }
        if (m.type >= 7 && m.type <= 9) { updateFish(m); return; }
        if (m.type == 13) { updateBlaze(m); return; }
        if (m.type == 14) { updateDragon(m); return; }
        if (m.type == 15) { updateShot(m); return; }
        float hw = mobHW(m.type);
        float mh = mobH(m.type);
        if (m.hurt > 0) m.hurt--;
        if (m.flee > 0) m.flee--;
        boolean chase = (m.type == 3 || m.type == 4 || m.type == 10) && m.flee <= 0 && hostileAI(m);
        if (m.type == 12) chase = enderAI(m);
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
        boolean bk = slId[sel] == I_BUCKET || slId[sel] == I_BOAT;
        int qx = lx, qy = ly, qz = lz;   // celda anterior a la actual
        while (d < REACH) {
            int ix = ifloor(ex + dx * d), iy = ifloor(ey + dy * d), iz = ifloor(ez + dz * d);
            if (ix != lx || iy != ly || iz != lz) {
                int hb = blockAt(ix, iy, iz);
                if (hb != 0 && (bk || (hb != B_WATER && hb != B_LAVA)) && hb != B_PORTAL && hb != B_DOOR_LO && hb != B_DOOR_UO ) {
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
            if (m.hp <= 0 || m.type == 5 || m.type == 15 || m == ride) continue;
            float r = mobR(m.type);
            float h = mobH(m.type);
            if (!((m.type >= 7 && m.type <= 9) || m.type == 11) && blockAt(ifloor(m.x), ifloor(m.y + h * 0.5f), ifloor(m.z)) != 0) continue;
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
                || (b >= B_COAL_ORE && b <= B_DIAM_BLK) || b == B_NETHERRACK || b == B_OBSIDIAN || (b >= B_CORAL0 && b <= B_CORAL4) || b == B_ENDSTONE || b == B_NBRICK || b == B_SBRICK || b == B_SPAWNER) {
            right = (tt == 0);
            if (!right || toolLevel(tool) < needLevel(b)) { right = false; secs = hard[b] * 5f; }
        } else if (isLog(b) || isPlanks(b) || b == B_TABLE || b == B_CHEST || isDoor(b)) {
            right = (tt == 1);
        } else if (b == B_GRASS || b == B_DIRT || b == B_SAND || b == B_SNOW || b == B_GRAVEL || b == B_SOULSAND || b == B_FARM_DRY || b == B_FARM_WET) {
            right = (tt == 2);
        }
        if (right) {
            float spd = toolSpeed(tool);
            int ef = (tool == slId[sel]) ? enLv(slDur[sel], 2) : 0;
            if (ef > 0) spd += ef * ef + 1;
            secs = secs / spd;
        }
        return (int) (secs * 20f) + 1;
    }

    void damageTool(int amount) {
        if (creative) return;
        if (isTool(slId[sel])) {
            int u = enLv(slDur[sel], 3);
            if (u > 0) { int a = 0; for (int i = 0; i < amount; i++) if (rnd(u + 1) == 0) a++; amount = a; }
            if ((slDur[sel] & DM) - amount <= 0) { clearSlot(sel); return; }
            slDur[sel] -= amount;
        }
    }

    void breakBlock(int x, int y, int z) {
        int b = blockAt(x, y, z);
        if (b == B_CRYSTAL) { crystalPop(x, y, z); return; }
        if (b == B_BEDROCK || b == B_LAVA || b == B_PORTAL || b == B_ENDPORTAL || b == B_FRAME || b == B_FRAME_EYE) return;
        if ((b == B_FARM_DRY || b == B_FARM_WET) && blockAt(x, y + 1, z) >= B_CROP0 && blockAt(x, y + 1, z) <= B_CROP2) setBlock(x, y + 1, z, 0);
        if (b == B_CHEST && !creative) {   // un cofre con items no se rompe (asi no se pierde nada)
            int cid = chestFind(x, y, z);
            if (cid >= 0 && cFill[cid] > 0) { say("El cofre no esta vacio"); return; }
        }
        bSnd(b, Sfx.K_BREAK, 100);
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
        boolean pick = (toolType(tl) == 0);
        int lvl = pick ? toolLevel(tl) : -1;
        int enF = pick ? enLv(slDur[sel], 4) : 0;
        int drop = b;
        if (b == B_GRASS) drop = B_DIRT;
        else if (b == B_STONE || b == B_COBBLE) drop = pick ? B_COBBLE : 0;
        else if (b == B_SANDSTONE) drop = pick ? B_SANDSTONE : 0;
        else if (isLeaves(b)) drop = 0;
        else if (b == B_FARM_DRY || b == B_FARM_WET) drop = B_DIRT;
        else if (b >= B_CROP0 && b <= B_CROP2) drop = I_SEEDS;
        else if (b == B_ICE) drop = 0;
        else if (b == B_SEAGRASS) drop = 0;
        else if (b == B_TUFT) drop = (rnd(8) == 0) ? I_SEEDS : 0;
        else if (b == B_SNOW) drop = (toolType(tl) == 2) ? B_SNOW : 0;
        else if (b == B_COAL_ORE) drop = (lvl >= 0) ? I_COAL : 0;
        else if (b == B_IRON_ORE) drop = (lvl >= 1) ? B_IRON_ORE : 0;
        else if (b == B_GOLD_ORE) drop = (lvl >= 2) ? B_GOLD_ORE : 0;
        else if (b == B_DIAM_ORE) drop = (lvl >= 2) ? I_DIAMOND : 0;
        else if (b >= B_IRON_BLK && b <= B_DIAM_BLK) drop = (lvl >= needLevel(b)) ? b : 0;
        else if (b == B_OBSIDIAN) drop = (lvl >= 3) ? b : 0;
        else if (b == B_NETHERRACK) drop = pick ? b : 0;
        else if (b == B_ENDSTONE || b == B_NBRICK || b == B_SBRICK) drop = pick ? b : 0;
        else if (b == B_SPAWNER) drop = 0;
        else if (b == B_CAKE) drop = 0;
        if (b == B_BED && dim == 0 && bedOn && bedX == x && bedY == y && bedZ == z) { bedOn = false; say("Cama destruida: sin punto de reaparicion"); }
        setBlock(x, y, z, fillOf(b));
        if (drop != 0) {
            int dn = 1;
            if (enF > 0 && (b == B_COAL_ORE || b == B_DIAM_ORE) && rnd(enF + 2) > 1) dn += 1 + rnd(enF);
            addItem(drop, dn, 0);
            Sfx.play(Sfx.S_POP, 40);
            if (b == B_COAL_ORE) addXp(1 + rnd(3));
            else if (b == B_IRON_ORE || b == B_GOLD_ORE) addXp(2 + rnd(2));
            else if (b == B_DIAM_ORE) addXp(4 + rnd(4));
        }
        if (isLeaves(b) && rnd(8) == 0) addItem(I_APPLE, 1, 0);
        if (isLeaves(b) && rnd(6) == 0) addItem(B_SAPLING, 1, 0);
        if (b == B_CROP2) {
            addItem(I_WHEAT, 1, 0);
            if (rnd(2) == 0) addItem(I_SEEDS, 1, 0);
        }
        damageTool(1);
    }

    void attackMob(Mob m) {
        if (m.type == 11) {   // el barco no se daña: se recoge
            if (m == ride) return;
            mobs.removeElement(m);
            tMob = null;
            addItem(I_BOAT, 1, 0);
            Sfx.play(Sfx.S_POP, 50);
            return;
        }
        int t = slId[sel];
        int sd = slDur[sel];   // encantamientos de la herramienta (antes de que se rompa)
        int dmg = 1;
        int ttp = toolType(t);
        if (ttp == 3) dmg = 4;
        else if (ttp == 1) dmg = 3;
        else if (ttp == 0 || ttp == 2) dmg = 2;
        if (ttp >= 0) {
            int tr = toolTier(t);
            dmg += (tr == 1) ? 1 : ((tr == 2) ? 2 : ((tr == 4) ? 3 : 0));
        }
        if (toolType(t) == 3) dmg += (enLv(sd, 0) + 1) / 2;
        m.hp -= dmg;
        Sfx.play(Sfx.S_HIT, 100);
        if (m.hp > 0) mobSnd(m, 1); else if (m.type != 14) mobSnd(m, 2);
        m.hurt = 8;
        if (m.type < 3) {
            m.flee = 60;
            m.moving = true;
            m.ang = yaw + (rndf() - 0.5f) * 1.2f;
        }
        m.vy = 0.25f;
        float kbk = 0.4f + 0.5f * enLv(sd, 1);
        float kx = (float) Math.sin(yaw) * kbk, kz = (float) Math.cos(yaw) * kbk;
        float hw = mobHW(m.type);
        float mh = mobH(m.type);
        if (!boxCollides(m.x + kx, m.y, m.z + kz, hw, mh)) { m.x += kx; m.z += kz; }
        damageTool(isTool(t) ? (toolType(t) == 3 ? 1 : 2) : 0);
        if (m.type == 12) { m.st = 1; m.atk = 30; if (m.hp > 0 && rnd(2) == 0) enderTp(m, false); }
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
                int lt = rnd(3);
                if (lt > 0) addItem(I_LEATHER, lt, 0);
            } else if (m.type == 3) {
                int f = rnd(3);
                if (f > 0) addItem(I_ROTTEN, f, 0);
                if (dim == 1 && rnd(4) == 0) addItem(I_GOLD_INGOT, 1, 0);
            } else if (m.type == 4) {
                int f = rnd(3);
                if (f > 0) addItem(I_GUNPOWDER, f, 0);
            } else if (m.type == 7) {
                addItem(I_COD, 1, 0);
            } else if (m.type == 8) {
                addItem(I_SALMON, 1, 0);
            } else if (m.type == 9) {
                addItem(I_TROPICAL, 1, 0);
            } else if (m.type == 10) {
                int f = rnd(3);
                if (f > 0) addItem(I_STRING, f, 0);
                if (rnd(3) == 0) addItem(I_SPIDER_EYE, 1, 0);
            } else if (m.type == 12) {
                if (rnd(5) < 3) addItem(I_PEARL, 1, 0);
            } else if (m.type == 13) {
                if (rnd(5) < 3) addItem(I_BLAZE_ROD, 1, 0);
            } else if (m.type == 14) {
                dragonDie(m);
            }
            int lo = enLv(sd, 5);
            if (lo > 0 && rnd(3) < lo) {
                int bt = m.type;
                if (bt == 0) addItem(I_MUTTON, 1, 0);
                else if (bt == 1) addItem(I_CHICKEN, 1, 0);
                else if (bt == 2) addItem(I_BEEF, 1, 0);
                else if (bt == 3) addItem(I_ROTTEN, 1, 0);
                else if (bt == 4) addItem(I_GUNPOWDER, 1, 0);
                else if (bt == 10) addItem(I_STRING, 1, 0);
                else if (bt == 12) addItem(I_PEARL, 1, 0);
                else if (bt == 13) addItem(I_BLAZE_ROD, 1, 0);
            }
            if (m.type == 14) addXp(300);
            else if (m.type >= 12) addXp(15);
            else if (m.type >= 3 && m.type <= 10 && m.type != 6 && !(m.type >= 7 && m.type <= 9)) addXp(5);
            else if (m.type < 3) addXp(1 + rnd(3));
            mobs.removeElement(m);
            tMob = null;
        }
    }

    void updateAction() {
        if (attackCd > 0) attackCd--;
        if (!mineOn) return;
        starTicks++;
        if (tMob != null) {
            mineProg = 0;
            if (attackCd <= 0 && (tMob.type == 11 ? starTicks > 9 : (tMob.type != 6 || starTicks > 6))) {
                attackMob(tMob);
                attackCd = 8;
                starConsumed = true;
            }
        } else if (ty >= 0 && !(slId[sel] == I_BOAT && blockAt(tx, ty, tz) == B_WATER)) {
            if (tx != mineX || ty != mineY || tz != mineZ) {
                mineX = tx; mineY = ty; mineZ = tz; mineProg = 0;
            }
            mineProg++;
            if ((mineProg & 3) == 1) bSnd(blockAt(tx, ty, tz), Sfx.K_HIT, 80);
            hungerTimer++;
            int need = creative ? 4 : breakTicks(blockAt(tx, ty, tz), slId[sel]);
            if (mineProg >= need) {
                breakBlock(tx, ty, tz);
                mineProg = 0;
                mineX = NONE;
                starConsumed = true;
            }
        } else {
            mineProg = 0;
            mineX = NONE;
        }
    }

    void consumeSel() {
        if (creative) return;
        slCnt[sel]--;
        if (slCnt[sel] <= 0) clearSlot(sel);
    }

    void doTap() {
        int id = slId[sel];
        if (fishState == 3) { fishHit(); return; }
        if (ride != null) { dismount(); return; }
        if (tMob != null && tMob.type == 11) { mount(tMob); return; }
        if (id == I_BOAT && ty >= 0) { placeBoat(); return; }
        if (id == I_ROD) { useRod(); return; }
        if (tMob != null && tMob.type == 2 && id == I_BUCKET) { slId[sel] = I_BUCKET_M; Sfx.play(Sfx.S_BUCKET_F, 90); return; }
        if (tMob != null && tMob.type == 6) { openInv(TRADE); return; }
        if (ty >= 0 && blockAt(tx, ty, tz) == B_TABLE) { openInv(3); return; }
        if (ty >= 0 && blockAt(tx, ty, tz) == B_ENCH) { eSel = 0; openInv(ENCH); return; }
        if (ty >= 0 && blockAt(tx, ty, tz) == B_CAKE && id != B_CAKE) {
            if (hunger < 20) {
                hunger = Math.min(20, hunger + 10);
                setBlock(tx, ty, tz, 0);
                Sfx.play(Sfx.S_EAT, 100);
            }
            return;
        }
        if (ty >= 0 && blockAt(tx, ty, tz) == B_FURNACE) { openInv(FURN); return; }
        if (ty >= 0 && blockAt(tx, ty, tz) == B_CHEST) { chestOpenAt(tx, ty, tz); return; }
        if (ty >= 0 && blockAt(tx, ty, tz) == B_TNT) { igniteTnt(tx, ty, tz, 1); return; }
        if (ty >= 0 && isDoor(blockAt(tx, ty, tz))) { toggleDoor(tx, ty, tz); return; }
        if (ty >= 0 && blockAt(tx, ty, tz) == B_BED) { useBed(tx, ty, tz); return; }
        if (id == I_EYE) { useEye(); return; }
        if (ty >= 0 && slId[sel] == I_LIGHTER) {
            Sfx.play(Sfx.S_IGNITE, 90);
            if (lightPortal(ax, ay, az)) say("Portal encendido");
            else if (blockAt(tx, ty, tz) == B_OBSIDIAN) say("Marco de portal incompleto");
            return;
        }
        if (ty >= 0 && id == I_HOE) {
            int hb = blockAt(tx, ty, tz);
            if ((hb == B_GRASS || hb == B_DIRT) && blockAt(tx, ty + 1, tz) == 0) {
                setBlock(tx, ty, tz, wetNear(tx, ty, tz) ? B_FARM_WET : B_FARM_DRY);
                damageTool(1);
                bSnd(B_DIRT, Sfx.K_PLACE, 80);
            }
            return;
        }
        if (ty >= 0 && id == I_BUCKET) {
            int hb = blockAt(tx, ty, tz);
            if (hb == B_WATER || hb == B_LAVA) {
                setBlock(tx, ty, tz, 0);
                slId[sel] = hb == B_WATER ? I_BUCKET_W : I_BUCKET_L;
                Sfx.play(Sfx.S_BUCKET_F, 90);
            }
            return;
        }
        if (ty >= 0 && (id == I_BUCKET_W || id == I_BUCKET_L)) {
            int cur = blockAt(ax, ay, az);
            if (cur == 0 || cur == B_WATER || cur == B_LAVA) {
                setBlock(ax, ay, az, id == I_BUCKET_W ? B_WATER : B_LAVA);
                slId[sel] = I_BUCKET;
                Sfx.play(Sfx.S_BUCKET_E, 90);
            }
            return;
        }
        if (id == I_BUCKET_M) {   // beber la leche quita el efecto de la carne podrida
            hungerFx = 0;
            slId[sel] = I_BUCKET;
            Sfx.play(Sfx.S_EAT, 100);
            return;
        }
        if (isFood(id)) {
            if (hunger < 20) {
                hunger = Math.min(20, hunger + foodVal(id));
                Sfx.play(Sfx.S_EAT, 100);
                if (id == I_ROTTEN) hungerFx = 300;
                consumeSel();
            }
            return;
        }
        if (isBlockItem(id) && ty >= 0) placeBlock(id);
    }

    void placeBlock(int id) {
        if (ty >= 0 && blockAt(tx, ty, tz) == B_TUFT) { ax = tx; ay = ty; az = tz; }   // el pasto alto se reemplaza al colocar
        if (ay < 0 || ay >= H) return;
        int cur = blockAt(ax, ay, az);
        if (cur != 0 && cur != B_WATER && cur != B_LAVA && cur != B_TUFT) return;
        if (id == I_DOOR) {
            if (ay + 1 >= H) return;
            int up = blockAt(ax, ay + 1, az);
            if (up != 0 && up != B_WATER) return;
            if (!solidAt(ax, ay - 1, az)) return;   // necesita piso firme
            if (px + HW > ax && px - HW < ax + 1 && pz + HW > az && pz - HW < az + 1
                    && py + 1.8f > ay && py < ay + 2) return;
            setBlock(ax, ay, az, B_DOOR_LC);
            setBlock(ax, ay + 1, az, B_DOOR_UC);
            bSnd(B_DOOR_LC, Sfx.K_PLACE, 90);
            consumeSel();
            return;
        }
        if (px + HW > ax && px - HW < ax + 1 && pz + HW > az && pz - HW < az + 1
                && py + 1.8f > ay && py < ay + 1) return;
        if (id == B_SAPLING || (id >= B_TUFT && id <= B_FLOWER_Y)) {
            int below = blockAt(ax, ay - 1, az);
            if (below != B_GRASS && below != B_DIRT) return;
        }
        if ((id == B_KELP || id == B_SEAGRASS) && cur != B_WATER) return;   // solo bajo el agua
        if (id == B_CANE) {
            int bw = blockAt(ax, ay - 1, az);
            if (bw != B_SAND && bw != B_DIRT && bw != B_GRASS && bw != B_CANE) return;
        }
        if (id == B_CAKE && !solidAt(ax, ay - 1, az)) return;
        if (id == I_SEEDS) {
            int below = blockAt(ax, ay - 1, az);
            if (below != B_FARM_DRY && below != B_FARM_WET) return;
        }
        if (id == B_CHEST && chestAlloc(ax, ay, az) < 0) { say("Limite de cofres alcanzado (64)"); return; }
        setBlock(ax, ay, az, id == I_SEEDS ? B_CROP0 : (id == B_FARM_DRY ? (wetNear(ax, ay, az) ? B_FARM_WET : B_FARM_DRY) : id));
        if (id == B_CHEST && blockAt(ax, ay, az) != B_CHEST) chestFree(ax, ay, az);
        if (id == B_SAPLING) addSapling(ax, ay, az);
        bSnd(id == I_SEEDS ? B_CROP0 : id, Sfx.K_PLACE, 90);
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
        if (diff == 1) {   // pacifico: sin hambre y la vida se regenera sola
            hunger = 20; hungerFx = 0; starveTimer = 0;
            if (health < 20 && ++peaceT >= 60) { peaceT = 0; health++; }
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
        byte[] d = new byte[CHN * 6];
        for (int i = 0; i < CHN; i++) {
            int s = CH0 + i;
            if (slId[s] == 0) continue;
            d[i * 4] = (byte) slId[s];
            d[i * 4 + 1] = (byte) slCnt[s];
            d[i * 4 + 2] = (byte) (slDur[s] >> 8);
            d[i * 4 + 3] = (byte) slDur[s];
            d[CHN * 4 + i * 2] = (byte) (slDur[s] >>> 24);
            d[CHN * 4 + i * 2 + 1] = (byte) (slDur[s] >>> 16);
        }
        return d;
    }

    // false = no se pudo leer: el cofre no se abre, asi nunca se pisa su contenido en disco
    boolean chestLoadSlots(int id) {
        for (int i = 0; i < CHN; i++) clearSlot(CH0 + i);
        byte[] d = new byte[CHN * 6];
        if (cFill[id] > 0 && cRid[id] != 0) {
            RecordStore r = null;
            boolean ok = false;
            try {
                r = RecordStore.openRecordStore(chestStoreName(), false);
                byte[] b = r.getRecord(cRid[id]);
                if (b != null && (b.length == d.length || b.length == CHN * 4)) { System.arraycopy(b, 0, d, 0, b.length); ok = true; }
            } catch (Exception e) {
                ok = false;
            }
            if (r != null) { try { r.closeRecordStore(); } catch (Exception e) {} }
            if (!ok) return false;
        }
        for (int i = 0; i < CHN; i++) {
            int sid = d[i * 4] & 0xFF, cnt = d[i * 4 + 1];
            int dur = ((d[i * 4 + 2] & 0xFF) << 8) | (d[i * 4 + 3] & 0xFF);
            if (sid <= 0 || sid > 135 || cnt < 1 || cnt > 64) continue;
            dur |= (((d[CHN * 4 + i * 2] & 0xFF) << 8) | (d[CHN * 4 + i * 2 + 1] & 0xFF)) << 16;
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
        boolean fresh = id < 0;
        if (id < 0) id = chestAlloc(x, y, z);   // cofre sin id (puesto desde la paleta creativa, mundo viejo)
        if (id < 0) { say("Limite de cofres alcanzado (64)"); return; }
        if (!chestLoadSlots(id)) { say("No se pudo leer el cofre"); return; }
        if (fresh && blockAt(x, y - 1, z) == B_GRAVEL) seedWreck();
        openChest = id;
        // mundo en pausa: los chunks de alrededor se descargan y solo queda el del jugador
        int pcx = ifloor(px) >> 4, pcz = ifloor(pz) >> 4;
        for (int s = 0; s < nch; s++) {
            if (chCx[s] == NONE) continue;
            if (chCx[s] == pcx && chCz[s] == pcz) continue;
            unloadSlot(s);
        }
        if (pendBytes > 0 && !storeBroken) needFlush = true;   // los chunks editados bajan a disco (cartel "Guardando...")
        Sfx.play(Sfx.S_CHEST_O, 90);
        openInv(CHEST);
    }

    void chestClose() {
        Sfx.play(Sfx.S_CHEST_C, 90);
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
        if (id == I_COD) return I_COOKED_COD;         // pescado -> cocido
        if (id == I_SALMON) return I_COOKED_SALMON;
        if (id == B_KELP) return I_DRIED_KELP;        // alga -> alga seca
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
        if (dim == 2) return 0x0C0814;
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
    float mobHW(int t) { if (t >= 12) return t == 12 ? 0.3f : (t == 13 ? 0.35f : (t == 14 ? 2.2f : 0.15f)); if (t == 11) return 0.6f; if (t >= 7 && t <= 9) return 0.15f; if (t == 10) return 0.5f; if (t == 5) return 0.49f; return t == 0 ? 0.35f : (t == 1 ? 0.2f : (t == 2 ? 0.4f : 0.3f)); }
    float mobH(int t) { if (t >= 12) return t == 12 ? 2.7f : (t == 13 ? 1.7f : (t == 14 ? 2.0f : 0.3f)); if (t == 11) return 0.45f; if (t >= 7 && t <= 9) return 0.3f; if (t == 10) return 0.7f; if (t == 5) return 1.0f; return t == 0 ? 1.0f : (t == 1 ? 0.7f : (t == 2 ? 1.25f : ((t == 3 || t == 6) ? 1.8f : 1.6f))); }
    float mobR(int t) { if (t >= 12) return t == 12 ? 0.45f : (t == 13 ? 0.5f : (t == 14 ? 2.6f : 0f)); if (t == 11) return 0.8f; if (t == 5) return 0f; return t == 0 ? 0.5f : (t == 1 ? 0.3f : (t == 2 ? 0.55f : (t == 3 ? 0.45f : 0.4f))); }
    float mobSpeed(int t) { if (t == 12) return 0.07f; if (t >= 7 && t <= 9) return 0.04f; if (t == 10) return 0.08f; return t == 3 ? 0.07f : (t == 4 ? 0.06f : 0.035f); }
    int mobHp(int t) { if (t == 12) return 28; if (t == 13) return 20; if (t == 14) return DRAGON_HP; if (t == 15) return 1; if (t == 11) return 1000; if (t >= 7 && t <= 9) return 3; if (t == 10) return 8; return t == 0 ? 8 : (t == 1 ? 4 : (t == 2 ? 10 : (t == 3 ? 12 : 10))); }

    float[][] mobParts(int t) {
        if (t == 0) return sheepParts;
        if (t == 1) return chickenParts;
        if (t == 2) return cowParts;
        if (t == 3) return zombieParts;
        if (t == 4) return creeperParts;
        if (t == 6) return villagerParts;
        if (t == 7) return codParts;
        if (t == 8) return salmonParts;
        if (t == 9) return tropParts;
        if (t == 10) return spiderParts;
        if (t == 11) return boatParts;
        if (t == 12) return endermanParts;
        if (t == 13) return blazeParts;
        if (t == 14) return dragonParts;
        if (t == 15) return shotParts;
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
        dragon = null;
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
        Sfx.play(Sfx.S_TRAVEL, 100);
        say(dim == 1 ? "Entraste al Nether" : "Volviste al Overworld");
    }

    void updatePortal() {
        int b0 = blockAt(ifloor(px), ifloor(py + 0.5f), ifloor(pz)), b1 = blockAt(ifloor(px), ifloor(py + 1.4f), ifloor(pz));
        boolean inE = b0 == B_ENDPORTAL || b1 == B_ENDPORTAL;
        boolean inP = inE || b0 == B_PORTAL || b1 == B_PORTAL;
        if (!inP) { portalT = 0; portalLock = false; return; }
        if (portalLock) return;
        if (inE) { portalT = 0; useEndPortal(); return; }
        if (portalT == 0) Sfx.play(Sfx.S_PORTAL, 80);
        if (++portalT >= (creative ? 2 : 60)) { portalT = 0; usePortal(); }
    }

    // ---------------------------------------------------------------- el End, fortalezas, spawners y mobs nuevos
    static final int[] PCX = { 24, -12, -12 }, PCZ = { 0, 21, -21 };
    static final int[] PTOP = { 38, 40, 42 };

    // isla principal (radio 34-42, superficie y = 32) con 10 pilares de obsidiana y cristales, mas islas flotantes en celdas de 64x64
    void genEnd(int s, int cx, int cz) {
        byte[] d = chData[s];
        for (int i = 0; i < CHB; i++) d[i] = 0;
        int x0 = cx << 4, z0 = cz << 4;
        boolean near = !(x0 + 15 < -43 || x0 > 43 || z0 + 15 < -43 || z0 > 43);
        int gx = cx >> 2, gz = cz >> 2;   // celda de 64 bloques de este chunk
        boolean isl = !((gx == 0 || gx == -1) && (gz == 0 || gz == -1)) && hash(gx, gz, 90) % 2 == 0;
        int icx = 0, icz = 0, ir = 0, itop = 0;
        if (isl) {
            icx = (gx << 6) + 32 + hash(gx, gz, 91) % 25 - 12; icz = (gz << 6) + 32 + hash(gx, gz, 92) % 25 - 12;
            ir = 8 + hash(gx, gz, 93) % 9; itop = 24 + hash(gx, gz, 94) % 17;
        }
        if (!near && !isl) return;
        for (int lx = 0; lx < CS; lx++) {
            for (int lz = 0; lz < CS; lz++) {
                int x = x0 + lx, z = z0 + lz, base = (lx * CS + lz) * H;
                if (near) {
                    float r = (float) Math.sqrt((double) (x * x + z * z)), rr = 34f + noise(x, z, 4, 91) * 8f;
                    if (r < rr) {
                        int th = (int) ((rr - r) * 0.4f) + 1;
                        for (int y = 33 - th; y <= 32; y++) d[base + y] = (byte) B_ENDSTONE;
                    }
                }
                if (isl) {
                    int ddx = x - icx, ddz = z - icz, q = ddx * ddx + ddz * ddz;
                    if (q < ir * ir) {
                        int th = (int) (((float) ir - (float) Math.sqrt((double) q)) * 0.6f) + 1;
                        for (int y = itop - th + 1; y <= itop; y++) d[base + y] = (byte) B_ENDSTONE;
                    }
                }
            }
        }
        if (!near) return;
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                if ((i >> 4) != cx || (j >> 4) != cz) continue;
                int base = (((i & 15) << 4) + (j & 15)) * H;
                d[base + 32] = (byte) B_BEDROCK;
                if ((i == -2 || i == 2) != (j == -2 || j == 2)) d[base + 33] = (byte) B_BEDROCK;
                if (i == 0 && j == 0) { d[base + 33] = (byte) B_BEDROCK; d[base + 34] = (byte) B_BEDROCK; d[base + 35] = (byte) B_BEDROCK; }
            }
        }
        for (int k = 0; k < 3; k++) {
            for (int ix = -1; ix <= 1; ix++) {
                for (int iz = -1; iz <= 1; iz++) {
                    int x = PCX[k] + ix, z = PCZ[k] + iz;
                    if ((x >> 4) != cx || (z >> 4) != cz) continue;
                    int base = (((x & 15) << 4) + (z & 15)) * H;
                    for (int y = 33; y <= PTOP[k]; y++) d[base + y] = (byte) B_OBSIDIAN;
                    if (ix == 0 && iz == 0 && (endFlags & (8 << k)) == 0) d[base + PTOP[k] + 1] = (byte) B_CRYSTAL;
                }
            }
        }
    }

    // fortaleza del Nether: una por region de 128x128 (50%), cruz de pasillos de ladrillo con sala central y spawner de blazes
    int fortX(int rx, int rz) { return (rx << 7) + 48 + hash(rx, rz, 62) % 32; }
    int fortZ(int rx, int rz) { return (rz << 7) + 48 + hash(rx, rz, 63) % 32; }
    boolean fortIn(int rx, int rz) { return hash(rx, rz, 61) % 2 == 0; }

    void genFort(byte[] d, int cx, int cz) {
        int rx = cx >> 3, rz = cz >> 3;
        if (!fortIn(rx, rz)) return;
        int fx0 = fortX(rx, rz), fz0 = fortZ(rx, rz), x0 = cx << 4, z0 = cz << 4;
        if (x0 + 15 < fx0 - 46 || x0 > fx0 + 46 || z0 + 15 < fz0 - 46 || z0 > fz0 + 46) return;
        for (int lx = 0; lx < CS; lx++) {
            for (int lz = 0; lz < CS; lz++) {
                int dx = x0 + lx - fx0, dz = z0 + lz - fz0;
                if (dx < 0) dx = -dx;
                if (dz < 0) dz = -dz;
                if (!((dx <= 7 && dz <= 7) || (dz <= 3 && dx <= 45) || (dx <= 3 && dz <= 45))) continue;
                boolean inn = (dx <= 6 && dz <= 6) || (dz <= 2 && dx <= 45) || (dx <= 2 && dz <= 45);
                int base = (lx * CS + lz) * H;
                for (int y = FY; y <= FY + 5; y++) {
                    int b = (inn && y > FY && y < FY + 5) ? 0 : B_NBRICK;
                    if (b == 0 && dx == 0 && dz == 0 && y == FY + 1) b = B_SPAWNER;
                    d[base + y] = (byte) b;
                }
            }
        }
    }

    // fortaleza del Overworld: una sola por mundo, a 400+ bloques del origen, bajo tierra (y 3..11)
    void initStrong() {
        stOk = false;
        for (int t = 0; t < 40 && !stOk; t++) {
            int x = hash(t, 1, 70) % 1400 - 700, z = hash(t, 2, 70) % 1400 - 700;
            if (x * x + z * z < 160000) continue;
            int hh = heightAt(x, z);
            if (hh < 18 || hh > 50) continue;
            stX = x; stZ = z; stOk = true;
        }
        if (!stOk) { stX = 600; stZ = 600; stOk = true; }
    }

    // sala de 23x23 (portal del End con marco de 12 ojos, 10% ya puestos, sobre lava; antorchas; spawner)
    // y una rampa de 3 de ancho que sube hacia +X desde la pared este hasta la superficie
    void genStrong(byte[] d, int cx, int cz) {
        if (!stOk) return;
        int x0 = cx << 4, z0 = cz << 4;
        if (x0 + 15 < stX - 11 || x0 > stX + 80 || z0 + 15 < stZ - 11 || z0 > stZ + 11) return;
        for (int lx = 0; lx < CS; lx++) {
            for (int lz = 0; lz < CS; lz++) {
                int x = x0 + lx, z = z0 + lz, dx = x - stX, dz = z - stZ;
                int qx = dx < 0 ? -dx : dx, qz = dz < 0 ? -dz : dz;
                int base = (lx * CS + lz) * H;
                if (qx <= 11 && qz <= 11) {
                    boolean wall = qx == 11 || qz == 11;
                    for (int y = 3; y <= 11; y++) {
                        int b = (wall || y == 3 || y == 11) ? B_SBRICK : 0;
                        if (b == 0 && qx <= 2 && qz <= 2) {
                            if (y == 4) b = (qx <= 1 && qz <= 1) ? B_LAVA : B_SBRICK;
                            else if (y == 5 && (qx == 2 || qz == 2) && !(qx == 2 && qz == 2)) b = (hash(x, z, 80) % 10 == 0) ? B_FRAME_EYE : B_FRAME;
                        } else if (b == 0 && y == 4 && dx == 0 && dz == 5) {
                            b = B_SPAWNER;
                        } else if (b == 0 && y == 4 && qx == 10 && qz == 10) {
                            b = B_TORCH;
                        }
                        d[base + y] = (byte) b;
                    }
                }
                if (dx >= 11 && qz <= 1) {
                    int fl = 4 + dx - 11;
                    if (fl + 3 < H && fl <= heightAt(x, z) + 1) {
                        d[base + fl - 1] = (byte) B_SBRICK;
                        for (int y = fl; y <= fl + 3; y++) d[base + y] = 0;
                    }
                }
            }
        }
    }

    // spawner cercano al jugador (16 bloques): nether = blazes en la fortaleza, overworld = aranas en la fortaleza del End
    void updateSpawner() {
        if (mobs.size() >= 40) return;
        int sx, sy, sz, mt;
        if (dim == 1) {
            int rx = ifloor(px) >> 7, rz = ifloor(pz) >> 7;
            if (!fortIn(rx, rz)) return;
            sx = fortX(rx, rz); sz = fortZ(rx, rz); sy = FY + 1; mt = 13;
        } else if (dim == 0 && stOk) {
            sx = stX; sz = stZ + 5; sy = 4; mt = 10;
        } else {
            return;
        }
        float dx = sx + 0.5f - px, dz = sz + 0.5f - pz;
        if (dx * dx + dz * dz > 256f || py > sy + 24 || py < sy - 24) return;
        if (blockAt(sx, sy, sz) != B_SPAWNER) return;
        int n = 0;
        for (int i = 0; i < mobs.size(); i++) {
            Mob o = (Mob) mobs.elementAt(i);
            if (o.spw && o.type == mt) n++;
        }
        if (n >= 3) return;
        for (int t = 0; t < 8; t++) {
            int x = sx - 3 + rnd(7), z = sz - 3 + rnd(7);
            if (blockAt(x, sy, z) != 0 || blockAt(x, sy + 1, z) != 0 || !solidAt(x, sy - 1, z)) continue;
            Mob m = new Mob();
            m.type = mt; m.x = x + 0.5f; m.z = z + 0.5f; m.y = sy + 0.001f;
            m.ang = rndf() * 6.2832f; m.hp = mobHp(mt); m.timer = 10 + rnd(40);
            m.spw = true; m.atk = 60 + rnd(60);
            mobs.addElement(m);
            return;
        }
    }

    // ---- el End
    void spawnEnd() {
        dragon = null;
        if ((endFlags & 1) == 0) {
            Mob d = new Mob();
            d.type = 14; d.x = 0f; d.y = 44f; d.z = 0f; d.hp = mobHp(14); d.timer = 200;
            mobs.addElement(d);
            dragon = d;
        }
        for (int i = 0; i < 5; i++) spawnEnder();
    }

    void spawnEnder() {
        for (int t = 0; t < 12; t++) {
            int x = ifloor(px) + rnd(41) - 20, z = ifloor(pz) + rnd(41) - 20;
            float dx = x + 0.5f - px, dz = z + 0.5f - pz;
            if (dx * dx + dz * dz < 64f || chunkFor(x, z) == null) continue;
            for (int y = 52; y > 10; y--) {
                if (blockAt(x, y, z) == 0 && blockAt(x, y + 1, z) == 0 && blockAt(x, y + 2, z) == 0 && blockAt(x, y - 1, z) != 0 && solidAt(x, y - 1, z)) {
                    Mob m = new Mob();
                    m.type = 12; m.x = x + 0.5f; m.z = z + 0.5f; m.y = y + 0.001f;
                    m.ang = rndf() * 6.2832f; m.hp = mobHp(12); m.timer = 10 + rnd(40);
                    mobs.addElement(m);
                    return;
                }
            }
        }
    }

    void updateEnd() {
        if ((endFlags & 5) == 1 && tick % 20 == 0 && chunkFor(0, 0) != null && chunkFor(-1, 0) != null
                && chunkFor(0, -1) != null && chunkFor(-1, -1) != null) buildExit();
        if (++endT >= 160) {
            endT = 0;
            int n = 0;
            for (int i = 0; i < mobs.size(); i++) if (((Mob) mobs.elementAt(i)).type == 12) n++;
            if (n < 6) spawnEnder();
        }
    }

    // portal de salida + huevo de dragon en el centro de la isla
    void buildExit() {
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) if (i != 0 || j != 0) setBlock(i, 32, j, B_ENDPORTAL);
        setBlock(0, 36, 0, B_EGG);
        endFlags |= 4;
        needSave = true;
    }

    void dragonDie(Mob m) {
        Sfx.play(Sfx.mob(14, 2), 100);
        endFlags |= 1;
        dragon = null;
        needSave = true;
        say("Ender Dragon derrotado!");
    }

    // romper un cristal: explota (dana al jugador y al dragon cercano) y deja de curar al dragon
    void crystalMark(int x, int z) {
        for (int k = 0; k < 3; k++) if (PCX[k] == x && PCZ[k] == z) { endFlags |= (8 << k); needSave = true; }
    }

    void crystalPop(int x, int y, int z) {
        crystalMark(x, z);
        Mob e = new Mob();
        e.type = 4; e.x = x + 0.5f; e.y = y + 0.5f; e.z = z + 0.5f;
        explode(e);
        needSave = true;
    }

    // ojo de Ender: sobre un marco lo rellena (y enciende el portal al completar 12); en el aire senala la fortaleza
    void useEye() {
        if (ty >= 0 && blockAt(tx, ty, tz) == B_FRAME) {
            setBlock(tx, ty, tz, B_FRAME_EYE);
            Sfx.play(Sfx.S_EYE, 90);
            consumeSel();
            for (int cx = tx - 3; cx <= tx + 3; cx++) {
                for (int cz = tz - 3; cz <= tz + 3; cz++) {
                    if (!ringFull(cx, ty, cz)) continue;
                    for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) setBlock(cx + i, ty, cz + j, B_ENDPORTAL);
                    Sfx.play(Sfx.S_END_ON, 100);
                    say("Portal del End activado");
                    return;
                }
            }
            return;
        }
        if (dim != 0 || !stOk) return;
        yaw = atan2f(stX - px, stZ - pz);
        Sfx.play(Sfx.S_EYE, 90);
        say("El ojo apunta a la fortaleza");
        if (rnd(5) == 0) { say("El ojo se rompio"); consumeSel(); }
    }

    boolean ringFull(int cx, int y, int cz) {
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                boolean edgeI = i == -2 || i == 2, edgeJ = j == -2 || j == 2;
                if ((!edgeI && !edgeJ) || (edgeI && edgeJ)) continue;
                if (blockAt(cx + i, y, cz + j) != B_FRAME_EYE) return false;
            }
        }
        return true;
    }

    void useEndPortal() {
        Sfx.play(Sfx.S_TRAVEL, 100);
        portalLock = true; portalT = 0; needSave = true;
        if (dim == 2) {
            if ((endFlags & 1) != 0 && (endFlags & 2) == 0) { endFlags |= 2; state = ST_END; clearKeys(); return; }
            endBack();
            return;
        }
        switchDim(2);
        px = 34.5f; pz = 0.5f;
        updateWindow(true);
        loadMissing(nch);
        py = 33.01f; yaw = -1.5708f; pitch = 0f;
        vy = 0; fallDist = 0; onGround = false;
        portalLock = true;
        spawnEnd();
        say("Entraste al End");
    }

    // vuelve al Overworld (cama o spawn) conservando vida y hambre
    void endBack() {
        int h = health, hu = hunger;
        respawn();
        health = h; hunger = hu;
        state = ST_PLAY;
        needSave = true;
        say("Volviste al Overworld");
    }

    void paintEnd() {
        gg.setColor(0x05030C);
        gg.fillRect(0, 0, sw, sh);
        int y = sh / 3, lh = font.getHeight() + 6;
        txt("FIN", sw / 2, y, 0xE060FF, Graphics.TOP | Graphics.HCENTER);
        txt("Derrotaste al Ender Dragon", sw / 2, y + lh, 0xFFFFFF, Graphics.TOP | Graphics.HCENTER);
        txt("Gracias por jugar", sw / 2, y + lh * 2, 0xA0A0A0, Graphics.TOP | Graphics.HCENTER);
        txt(touch ? "Toca: continuar" : "5: continuar", sw / 2, y + lh * 4, 0xFFFF55, Graphics.TOP | Graphics.HCENTER);
    }

    // ---- mobs: proyectiles, blaze, dragon, enderman
    void shoot(float x, float y, float z, int dmg, float spd) {
        if (mobs.size() >= 56) return;
        float dx = px - x, dy = py + 1.0f - y, dz = pz - z;
        float len = (float) Math.sqrt((double) (dx * dx + dy * dy + dz * dz));
        if (len < 0.1f) return;
        Mob s = new Mob();
        s.type = 15; s.x = x; s.y = y; s.z = z;
        s.vx = dx / len * spd; s.vy = dy / len * spd; s.vz = dz / len * spd;
        s.atk = dmg; s.hp = 1; s.timer = 100;
        mobs.addElement(s);
        Sfx.play(Sfx.mob(13, 3), 85);
    }

    void updateShot(Mob m) {
        m.x += m.vx; m.y += m.vy; m.z += m.vz;
        float dx = px - m.x, dy = py + 0.9f - m.y, dz = pz - m.z;
        if (dx * dx + dy * dy + dz * dz < 0.64f) { hurtM(m.atk); m.hp = 0; return; }
        if (--m.timer <= 0 || solidAt(ifloor(m.x), ifloor(m.y), ifloor(m.z))) m.hp = 0;
    }

    void updateBlaze(Mob m) {
        if (m.hurt > 0) m.hurt--;
        float dx = px - m.x, dz = pz - m.z, d2 = dx * dx + dz * dz;
        if (d2 > 400f) return;
        m.ang = atan2f(dx, dz);
        float sp = 0f;
        if (d2 > 64f) sp = 0.035f; else if (d2 < 25f) sp = -0.035f;
        float mx = (float) Math.sin(m.ang) * sp, mz = (float) Math.cos(m.ang) * sp;
        float my = (py + 2.2f - m.y) * 0.04f;
        if (my > 0.05f) my = 0.05f; else if (my < -0.05f) my = -0.05f;
        if (!boxCollides(m.x + mx, m.y, m.z, 0.35f, 1.7f)) m.x += mx;
        if (!boxCollides(m.x, m.y, m.z + mz, 0.35f, 1.7f)) m.z += mz;
        if (!boxCollides(m.x, m.y + my, m.z, 0.35f, 1.7f)) m.y += my;
        if (m.atk > 0) m.atk--;
        else if (d2 < 196f && losClear(m.x, m.y + 1.2f, m.z, px, py + 1f, pz)) { shoot(m.x, m.y + 1.2f, m.z, 2, 0.2f); m.atk = 140 + rnd(80); }
    }

    // dragon: orbita arriba, hace pasadas hacia el jugador y cada dos pasadas se posa sobre el monumento del centro
    // (ahi se puede golpear y los endermans cercanos la atacan)
    void updateDragon(Mob m) {
        if (m.hurt > 0) m.hurt--;
        if (m.atk > 0) m.atk--;
        if (tick % 40 == 0) mobSnd(m, 3);
        if (tick % 20 == 0 && m.hp < DRAGON_HP && (endFlags & 0x38) != 0x38) m.hp++;   // los cristales la curan
        float gx, gy, gz, sp;
        if (m.st == 0) {
            m.oa += 0.02f;
            gx = 22f * (float) Math.cos((double) m.oa); gz = 22f * (float) Math.sin((double) m.oa);
            gy = 44f + 4f * (float) Math.sin((double) (m.oa * 2f));
            sp = 0.2f;
            if (--m.timer <= 0) { m.st = 1; m.timer = 120; }
            if (tick % 200 == 0) shoot(m.x, m.y + 1f, m.z, 3, 0.2f);
        } else if (m.st == 1) {
            gx = px; gz = pz; gy = py + 1.5f; sp = 0.26f;
            if (--m.timer <= 0) {
                m.wind++;
                if (m.wind % 2 == 0) { m.st = 2; m.timer = 300; } else { m.st = 0; m.timer = 200 + rnd(100); }
            }
        } else {
            gx = 0f; gz = 0f; gy = 34.5f; sp = 0.2f;
            if (--m.timer <= 0) { m.st = 0; m.timer = 200 + rnd(100); }
            if (tick % 30 == 0) {
                for (int i = 0; i < mobs.size(); i++) {
                    Mob o = (Mob) mobs.elementAt(i);
                    if (o.type != 12) continue;
                    float ox = o.x - m.x, oy = o.y - m.y, oz = o.z - m.z;
                    if (ox * ox + oz * oz < 64f && oy < 6f && oy > -6f) { m.hp -= 2; m.hurt = 6; }
                }
            }
        }
        float dx = gx - m.x, dy = gy - m.y, dz = gz - m.z;
        float len = (float) Math.sqrt((double) (dx * dx + dy * dy + dz * dz));
        if (len < 0.01f) len = 0.01f;
        if (m.st == 2 && len < 3f) sp = 0.04f;
        m.vx += (dx / len * sp - m.vx) * 0.06f;
        m.vy += (dy / len * sp - m.vy) * 0.06f;
        m.vz += (dz / len * sp - m.vz) * 0.06f;
        m.x += m.vx; m.y += m.vy; m.z += m.vz;
        if (m.vx * m.vx + m.vz * m.vz > 0.0004f) m.ang = atan2f(m.vx, m.vz);
        float ex = px - m.x, ey = py + 0.9f - (m.y + 1f), ez = pz - m.z;
        if (m.atk <= 0 && ex * ex + ey * ey + ez * ez < 9f) {
            hurtM(3); m.atk = 100; vy = 0.3f;
            if (m.st == 1) { m.st = 0; m.timer = 150 + rnd(80); }
        }
    }

    // enderman: neutral; se enoja solo si lo golpeas o si le sostienes la mirada (~1 s); si la dragona se posa, va a atacarla
    boolean enderAI(Mob m) {
        if (m.atk > 0) m.atk--;
        if (dragon != null && dragon.st == 2) {
            float ex = dragon.x - m.x, ez = dragon.z - m.z, e2 = ex * ex + ez * ez;
            if (e2 < 900f) { m.ang = atan2f(ex, ez); m.moving = e2 > 16f; return true; }
        }
        float dx = px - m.x, dz = pz - m.z, dy = py - m.y, d2 = dx * dx + dz * dz;
        if (m.st == 0) {
            boolean look = false;
            if (d2 < 225f) {
                float ex = m.x - px, ey = m.y + 2.4f - (py + EYE), ez = m.z - pz;
                float el = (float) Math.sqrt((double) (ex * ex + ey * ey + ez * ez));
                float cp = (float) Math.cos(pitch);
                look = el > 0.1f && (ex * (float) Math.sin(yaw) * cp + ey * (float) Math.sin(pitch) + ez * (float) Math.cos(yaw) * cp) / el > 0.99f
                        && losClear(px, py + EYE, pz, m.x, m.y + 2.4f, m.z);
            }
            if (look) { if (++m.wind >= 25) { m.st = 1; m.atk = 30; } } else m.wind = 0;
            return false;
        }
        if (d2 > 900f) { m.st = 0; m.wind = 0; return false; }
        m.ang = atan2f(dx, dz);
        m.moving = true;
        if (d2 < 3f && dy < 1.5f && dy > -1.5f) {
            m.moving = false;
            if (m.atk <= 0) { hurtM(3); m.atk = 70; }
        } else if (d2 > 16f && rnd(200) == 0) {
            enderTp(m, true);
        }
        return true;
    }

    void enderTp(Mob m, boolean nearPlayer) {
        float cx0 = nearPlayer ? px : m.x, cz0 = nearPlayer ? pz : m.z, cy0 = nearPlayer ? py : m.y;
        for (int t = 0; t < 12; t++) {
            int x = ifloor(cx0) + rnd(13) - 6, z = ifloor(cz0) + rnd(13) - 6;
            if (nearPlayer) { int ddx = x - ifloor(px), ddz = z - ifloor(pz); if (ddx * ddx + ddz * ddz < 9) continue; }
            if (chunkFor(x, z) == null) continue;
            for (int y = ifloor(cy0) + 4; y > ifloor(cy0) - 6 && y > 1; y--) {
                if (blockAt(x, y, z) == 0 && blockAt(x, y + 1, z) == 0 && blockAt(x, y + 2, z) == 0 && solidAt(x, y - 1, z)) {
                    m.x = x + 0.5f; m.y = y + 0.001f; m.z = z + 0.5f; m.vy = 0f;
                    mobSnd(m, 3);
                    return;
                }
            }
        }
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
        Sfx.play(Sfx.S_CHIME, 90);
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
        Sfx.play(open ? Sfx.S_DOOR_C : Sfx.S_DOOR_O, 90);
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
        float lim = (m.type == 3 || m.type == 10) ? 256f : 144f;
        if (d2 > lim) { m.fuse = 0; m.wind = 0; return false; }
        m.ang = atan2f(dx, dz);
        m.moving = true;
        if (m.type == 3 || m.type == 10) {
            // alcance ~1.3 bloques, casi a la misma altura y sin paredes en el medio
            boolean near = d2 < 1.7f && dy < 1.2f && dy > -1.2f
                    && losClear(m.x, m.y + 1.0f, m.z, px, py + 1.0f, pz);
            if (near) {
                m.moving = false;
                // tiempo de carga antes del golpe y enfriamiento largo entre golpes
                if (++m.wind >= 12 && m.atk <= 0) { hurtM(m.type == 10 ? 2 : 3); m.atk = 40; m.wind = 0; }
            } else if (m.wind > 0) {
                m.wind--;
            }
        } else {
            // el creeper solo se enciende a ~2 bloques, a la misma altura y con vista libre
            boolean near = d2 < 4f && dy < 1.6f && dy > -1.6f
                    && losClear(m.x, m.y + 0.8f, m.z, px, py + 0.9f, pz);
            if (near) {
                m.moving = false;
                if (m.fuse == 0) mobSnd(m, 3);
                if (++m.fuse >= 30) explode(m);
            } else if (m.fuse > 0) {
                m.fuse--;
            }
        }
        return true;
    }

    void explode(Mob m) {
        float edx = m.x - px, edy = m.y - py, edz = m.z - pz;
        float ed = (float) Math.sqrt((double) (edx * edx + edy * edy + edz * edz));
        if (ed < 48f) Sfx.play(Sfx.S_EXPLODE, (int) (100f - ed * 2f));
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
                    if (b == 0 || b == B_WATER || b == B_LAVA || b == B_PORTAL || b == B_BEDROCK || b == B_OBSIDIAN || b == B_ENDPORTAL || b == B_FRAME || b == B_FRAME_EYE) continue;
                    if (b == B_TNT) igniteTnt(cx + dx, yy, cz + dz, 50 + rnd(20));
                    else { if (b == B_CRYSTAL) crystalMark(cx + dx, cz + dz); setBlock(cx + dx, yy, cz + dz, 0); }
                }
            }
        }
        float ddx = px - m.x, ddy = (py + 0.9f) - (m.y + 0.8f), ddz = pz - m.z;
        float d = (float) Math.sqrt((double) (ddx * ddx + ddy * ddy + ddz * ddz));
        float reach = big ? 8f : 6f;
        if (d < reach && losClear(m.x, m.y + 0.8f, m.z, px, py + 0.9f, pz)) hurtM((int) ((reach - d) * (big ? 2.2f : 1.8f)));
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
        if (startFuse < 10) Sfx.play(Sfx.S_FUSE, 100);
        setBlock(x, y, z, 0);
        Mob m = new Mob();
        m.type = 5;
        m.x = x + 0.5f; m.y = y + 0.001f; m.z = z + 0.5f;
        m.hp = 1;
        m.fuse = startFuse;
        mobs.addElement(m);
    }

    boolean waterAtF(float x, float y, float z) {
        int b = blockAt(ifloor(x), ifloor(y), ifloor(z));
        return b == B_WATER || b == B_KELP || b == B_SEAGRASS;   // las algas estan bajo el agua
    }

    // al romper: el hueco queda con agua si era una planta marina (o hielo)
    int fillOf(int b) { return (b == B_ICE || b == B_KELP || b == B_SEAGRASS) ? B_WATER : 0; }

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

    boolean opaque(int b) { return b != 0 && b != B_CANE && b != B_CAKE && b != B_SAPLING && b != B_TORCH && b != B_WATER && !(b >= B_DOOR_LC && b <= B_DOOR_UO) && b != B_BED && b != B_LAVA && b != B_PORTAL && b != B_ENDPORTAL && (b < B_CROP0 || b > B_CROP2) && b != B_KELP && b != B_SEAGRASS && b != B_ENCH && b != B_FRAME && b != B_FRAME_EYE && b != B_EGG && (b < B_TUFT || b > B_FLOWER_Y); }

    int columnTop(int x, int z, byte[] cd) {
        int cx = x >> 4, cz = z >> 4, s = slotOf(cx, cz);
        lastSlot = s;
        byte[] t = colTop[s];
        if (t == null) t = colTop[s] = new byte[256];
        byte[] sl = colSol[s];
        if (sl == null) sl = colSol[s] = new byte[256];
        if (topCx[s] != cx || topCz[s] != cz || topG[s] != topGen) {
            for (int c = 0; c < 256; c++) {
                int b0 = c * H, y = H - 1;
                while (y >= 0 && cd[b0 + y] == 0) y--;
                t[c] = (byte) (y + 1);
                int k = 0;
                while (k < H && opq[cd[b0 + k] & 0xFF]) k++;
                sl[c] = (byte) k;   // celdas 0..k-1 opacas
            }
            topCx[s] = cx; topCz[s] = cz; topG[s] = topGen;
        }
        return t[((x & 15) << 4) + (z & 15)];
    }

    int solidAt(int x, int z) {
        byte[] cd = chunkFor(x, z);
        if (cd == null) return 0;
        columnTop(x, z, cd);
        return colSol[lastSlot][((x & 15) << 4) + (z & 15)];
    }

    // bloque opaco rodeado de opacos: no tiene ninguna cara visible, se evita todo el costo de drawBlock
    boolean buried(byte[] cd, int base, int x, int y, int z) {
        if (y <= 0 || y >= H - 1) return false;
        if (!opq[cd[base + y + 1] & 0xFF] || !opq[cd[base + y - 1] & 0xFF]) return false;
        int lx = x & 15, lz = z & 15;
        if (!opq[lx < 15 ? (cd[base + 16 * H + y] & 0xFF) : blockAt(x + 1, y, z)]) return false;
        if (!opq[lx > 0 ? (cd[base - 16 * H + y] & 0xFF) : blockAt(x - 1, y, z)]) return false;
        if (!opq[lz < 15 ? (cd[base + H + y] & 0xFF) : blockAt(x, y, z + 1)]) return false;
        return opq[lz > 0 ? (cd[base - H + y] & 0xFF) : blockAt(x, y, z - 1)];
    }

    void faceBlock(int x, int y, int z, int f, int color, int b) {
        toCam(x, y, z, cO, 0);   // un solo toCam por cara; las demas esquinas se arman sumando los ejes ya rotados
        for (int i = 0; i < 4; i++) {
            int c = FACE[f][i], k = i * 3;
            float qx = cO[0], qy = cO[1], qz = cO[2];
            if ((c & 1) != 0) { qx += eX[0]; qy += eX[1]; qz += eX[2]; }
            if ((c & 2) != 0) { qx += eY[0]; qy += eY[1]; qz += eY[2]; }
            if ((c & 4) != 0) { qx += eZ[0]; qy += eZ[1]; qz += eZ[2]; }
            quad[k] = qx; quad[k + 1] = qy; quad[k + 2] = qz;
        }
        int p = bright >= 100 ? 100 : litPct(x + 0.5f + NX[f], y + 0.5f + NY[f], z + 0.5f + NZ[f]);
        if (b == B_LAVA || b == B_GLOWSTONE || b == B_MAGMA) p = 100;   // brillan solos
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
        if (b == B_MAGMA) {
            int kk = mk(0x2A0C06, 100), dd = mk(0xF07A1A, 100);
            dRect(0.08f, 0.08f, 0.4f, 0.36f, kk); dRect(0.56f, 0.52f, 0.92f, 0.82f, kk); dRect(0.12f, 0.62f, 0.4f, 0.92f, kk);
            dLine(0.1f, 0.46f, 0.5f, 0.5f, dd); dLine(0.5f, 0.5f, 0.6f, 0.1f, dd); dLine(0.5f, 0.5f, 0.45f, 0.95f, dd);
            dRect(0.45f, 0.45f, 0.55f, 0.55f, mk(0xFFC040, 100));
        } else if (b == B_PLANKS) {
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
        } else if (b == B_ENDSTONE) {
            int l = mk(0xEFEDB8, 100), dk = mk(0xAEAB6A, 100);
            dRect(0.1f, 0.15f, 0.3f, 0.3f, l); dRect(0.6f, 0.1f, 0.85f, 0.25f, dk); dRect(0.35f, 0.5f, 0.55f, 0.65f, l);
            dRect(0.7f, 0.6f, 0.9f, 0.75f, dk); dRect(0.12f, 0.75f, 0.3f, 0.9f, dk);
        } else if (b == B_NBRICK || b == B_SBRICK) {
            int ln = mk(b == B_NBRICK ? 0x120609 : 0x3E423A, 100);
            dLine(0f, 0.25f, 1f, 0.25f, ln); dLine(0f, 0.5f, 1f, 0.5f, ln); dLine(0f, 0.75f, 1f, 0.75f, ln);
            dLine(0.5f, 0f, 0.5f, 0.25f, ln); dLine(0.25f, 0.25f, 0.25f, 0.5f, ln); dLine(0.75f, 0.25f, 0.75f, 0.5f, ln);
            dLine(0.5f, 0.5f, 0.5f, 0.75f, ln); dLine(0.25f, 0.75f, 0.25f, 1f, ln); dLine(0.75f, 0.75f, 0.75f, 1f, ln);
        } else if (b == B_FRAME || b == B_FRAME_EYE) {
            int gr = mk(0x9AB88A, 100);
            if (f == 2) {
                dFrame(0.06f, 0.06f, 0.94f, 0.94f, gr);
                if (b == B_FRAME_EYE) { dRect(0.25f, 0.25f, 0.75f, 0.75f, mk(0x35E0B0, 100)); dRect(0.4f, 0.4f, 0.6f, 0.6f, mk(0x103028, 100)); }
                else dRect(0.3f, 0.3f, 0.7f, 0.7f, mk(0x06261C, 100));
            } else {
                dRect(0.08f, 0.2f, 0.92f, 0.34f, gr); dRect(0.08f, 0.66f, 0.92f, 0.8f, gr);
            }
        } else if (b == B_SPAWNER) {
            int br = mk(0x04060A, 100);
            dLine(0.2f, 0f, 0.2f, 1f, br); dLine(0.5f, 0f, 0.5f, 1f, br); dLine(0.8f, 0f, 0.8f, 1f, br);
            dLine(0f, 0.25f, 1f, 0.25f, br); dLine(0f, 0.5f, 1f, 0.5f, br); dLine(0f, 0.75f, 1f, 0.75f, br);
            dRect(0.35f, 0.35f, 0.65f, 0.65f, mk(0x4A90E0, 100));
        } else if (b == B_CRYSTAL) {
            int w = mk(0xFFD8FF, 100);
            dLine(0f, 0f, 1f, 1f, w); dLine(1f, 0f, 0f, 1f, w); dRect(0.4f, 0.4f, 0.6f, 0.6f, mk(0xFFFFFF, 100));
        } else if (b == B_EGG) {
            int pp = mk(0x8A3AD0, 100);
            dRect(0.2f, 0.2f, 0.35f, 0.35f, pp); dRect(0.6f, 0.15f, 0.75f, 0.3f, pp); dRect(0.4f, 0.55f, 0.55f, 0.7f, pp); dRect(0.7f, 0.65f, 0.85f, 0.8f, pp);
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
        } else if (b >= B_CORAL0 && b <= B_CORAL4) {
            int d = mk(cSide[b], 62), l = mk(cSide[b], 140);
            dRect(0.15f, 0.2f, 0.3f, 0.35f, d); dRect(0.6f, 0.12f, 0.78f, 0.3f, l);
            dRect(0.35f, 0.55f, 0.55f, 0.72f, d); dRect(0.7f, 0.6f, 0.88f, 0.78f, l); dRect(0.12f, 0.75f, 0.28f, 0.9f, l);
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
        if (b == B_NBRICK || b == B_SBRICK) return (((v & 3) == 0) || (((u + ((v >> 2) << 1)) & 3) == 0)) ? tint(base, 62) : tint(base, var);
        if (b == B_ENDSTONE) return (n > 190) ? 0xB8B57A : ((n < 50) ? 0xEFEDB8 : tint(base, var));
        if ((b == B_FRAME || b == B_FRAME_EYE) && face == 0) {
            if (u >= 2 && u <= 5 && v >= 2 && v <= 5) return b == B_FRAME_EYE ? 0x30D0A0 : 0x0A3A2A;
            return tint(0x7A8F6A, var);
        }
        if (b == B_SPAWNER) return (((u + v) & 1) == 0) ? 0x10161F : tint(base, var);
        if (b == B_CRYSTAL) return (n > 150) ? 0xFFB0FF : tint(base, var + 10);
        if (b == B_NETHERRACK) return (n > 200) ? 0x4A1616 : ((n < 40) ? 0x9A4040 : tint(base, var));
        if (b == B_LAVA) return ((u + v * 2 + n / 64) % 5 == 0) ? 0xFFD040 : tint(0xE0601A, var + 10);
        if (b == B_OBSIDIAN) return (n > 215) ? 0x6A3FA0 : tint(base, var - 6);
        if (b == B_MAGMA) return (n > 170) ? 0xFFB030 : ((n < 90) ? 0x2A0C06 : tint(base, var));
        if (b == B_SOULSAND) return (((u + v) % 3 == 0) && n > 100) ? 0x2E2218 : tint(base, var);
        if (b == B_GLOWSTONE) return (n > 160) ? 0xFFF0B0 : ((n < 60) ? 0xC09A40 : tint(base, var + 8));
        if (b == B_BEDROCK) return (n > 150) ? 0x1A1A1A : ((n < 60) ? 0x6A6A6A : 0x444444);
        if (b >= B_CORAL0 && b <= B_CORAL4) return (n > 180) ? tint(base, 130) : ((n < 60) ? tint(base, 68) : tint(base, var));
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
        if (id == I_COOKED_COD) return new String[] { "........", "..ooo.o.", ".oooTooo", "ooKooTo.", ".oooTooo", "..ooo.o.", "........", "........" };
        if (id == I_COOKED_SALMON) return new String[] { "........", "..OOO.o.", ".OOOOOoo", "OOKOOOo.", ".OOOOOoo", "..OOO.o.", "........", "........" };
        if (id == I_DRIED_KELP) return new String[] { "........", ".nnnnnn.", ".nzznzn.", ".nnznnn.", ".nznzzn.", ".nnnnnn.", "........", "........" };
        if (id == I_STRING) return new String[] { "......w.", ".....w..", "....W...", "...w....", "..W.....", ".w......", "W.......", "w......." };
        if (id == I_SPIDER_EYE) return new String[] { "........", "..rRRr..", ".rRRRRr.", ".RRKKRR.", ".RRKKRR.", ".rRRRRr.", "..rRRr..", "........" };
        if (id == I_ROD) return new String[] { "......Bb", ".....BbW", "....BbW.", "...BbW..", "..BbW...", ".BbW....", "Bb.W....", "b..W...." };
        if (id == I_COD) return new String[] { "........", "..TTT.T.", ".TTTTTTT", "TTKTTTT.", ".TTTTTTT", "..TTT.T.", "........", "........" };
        if (id == I_SALMON) return new String[] { "........", "..RRR.r.", ".RRRRRrr", "RRKRRRr.", ".RRRRRrr", "..RRR.r.", "........", "........" };
        if (id == I_TROPICAL) return new String[] { "...O....", "..OOW.O.", ".OOWOOOO", "OOKWOOO.", ".OOWOOOO", "..OOW.O.", "........", "........" };
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
        if ((id >= I_BUCKET && id <= I_BUCKET_L) || id == I_BUCKET_M) {
            String[] g = { "........", ".iUUUUi.", ".iUUUUi.", "..iUUi..", "..iIIi..", "...ii...", "........", "........" };
            char fl = id == I_BUCKET_W ? 'U' : (id == I_BUCKET_L ? 'O' : (id == I_BUCKET_M ? 'W' : 'g'));
            for (int i = 0; i < 8; i++) g[i] = g[i].replace('U', fl);
            return g;
        }
        if (id == B_KELP) return new String[] { "...N....", "..Nn....", "...nN...", "...Nn...", "..nN....", "..Nn....", "...nN...", "...Nn..." };
        if (id == B_SEAGRASS) return new String[] { "........", "...N....", "..N.N...", "..N.N...", ".nN.Nn..", ".nN.Nn..", ".nNnNn..", "..nNn..." };
        if (id == B_BED) return new String[] { "........", "........", "bWWRRRRb", "bWWRRRRb", "bRRRRRRb", "bbbbbbbb", "b......b", "b......b" };
        if (id == I_BLAZE_ROD) return new String[] { ".....OY.", "....OY..", "...OY...", "..OY....", ".OY.....", "OY......", "........", "........" };
        if (id == I_BLAZE_POWDER) return new String[] { "........", "........", "...Y....", "..YOY...", ".YOYOY..", ".OYOYO..", "..OYO...", "........" };
        if (id == I_PEARL) return new String[] { "........", "..nNNn..", ".nNNNNn.", ".NNNNNN.", ".NNNNNN.", ".nNNNNn.", "..nnnn..", "........" };
        if (id == I_EYE) return new String[] { "........", "..nNNn..", ".nNYYNn.", ".NYKKYN.", ".NYKKYN.", ".nNYYNn.", "..nnnn..", "........" };
        if (id == B_TUFT) return new String[] { "N...N...", ".N.N.N..", ".NNNnN.N", "..NnNNN.", "..nNnN..", "...nN...", "...nn...", "........" };
        if (id == B_FLOWER_R) return new String[] { "...RR...", "..RDDR..", "..RDDR..", "...RR...", "...N....", "..nN.N..", "...NNn..", "...N...." };
        if (id == B_FLOWER_Y) return new String[] { "...YY...", "..YOOY..", "..YOOY..", "...YY...", "...N....", "..nN.N..", "...NNn..", "...N...." };
        if (id == B_CANE) return new String[] { "...N.N..", "...N.N..", "..nN.Nn.", "...NnN..", "...N.N..", "..nNnN..", "...N.N..", "...N.N.." };
        if (id == B_CAKE) return new String[] { "........", "..WWRW..", ".WWWWWW.", ".TTTTTT.", ".TwTwTw.", ".TTTTTT.", ".bbbbbb.", "........" };
        if (id == B_ENCH) return new String[] { "........", "..bRRb..", "..bWWb..", ".rrrrrr.", "rRRRRRRr", "KKKKKKKK", "KkCKKCkK", "KKKKKKKK" };
        if (id == I_SUGAR) return new String[] { "........", "..W.W...", ".WwWW.W.", "..WwW...", ".W.WW.W.", "..wW.W..", "........", "........" };
        if (id == I_PAPER) return new String[] { "........", ".WWWWWw.", ".WwwwwW.", ".WWWWWw.", ".WwwwwW.", ".WWWWWw.", "........", "........" };
        if (id == I_BOOK) return new String[] { "........", ".bBBBBb.", ".bRRRWb.", ".bRRRWb.", ".bRRRWb.", ".bRRRWb.", ".bBBBBb.", "........" };
        if (id == I_LEATHER) return new String[] { "........", ".oo..oo.", ".oBBBBo.", "..BBBB..", "..BoBB..", "..BBBB..", "...oo...", "........" };
        return null;
    }

    int[][] iconPx = new int[256][];

    int[] iconOf(int id) {
        int[] px = iconPx[id];
        if (px != null) return px;
        px = new int[64];
        if (id == I_BOAT) {
            String[] bt = { "........", "........", "d......d", "db....bd", "dbbbbbbd", ".dbbbbd.", "..dddd..", "........" };
            for (int r = 0; r < 8; r++)
                for (int c = 0; c < 8; c++) {
                    char ch = bt[r].charAt(c);
                    px[r * 8 + c] = ch == '.' ? -1 : (ch == 'd' ? 0x6B4A2A : 0xA88650);
                }
            iconPx[id] = px;
            return px;
        }
        String[] pt = (id > 19) ? patOf(id) : null;
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                int col;
                if (pt != null) col = pal(pt[r].charAt(c));
                else if (id <= 19 || (id >= B_COAL_ORE && id <= B_DIAM_BLK) || (id >= B_NETHERRACK && id <= B_OBSIDIAN) || (id >= B_SOULSAND && id <= B_BEDROCK) || id == B_CHEST || (id >= B_LOG_S && id <= B_FARM_DRY) || (id >= B_CORAL0 && id <= B_CORAL4) || id == B_MAGMA || (id >= B_ENDSTONE && id <= B_EGG)) col = (r < 2) ? texBlock(id, 0, c, r + 3) : texBlock(id, 1, c, r);
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
    // plano vertical (rectangulo o triangulo-hoja) entre (ax,az) y (bx,bz)
    void plane(float ax, float az, float bx, float bz, float y, float h, boolean tri, float tipX, float tipZ, int col) {
        toCam(ax, y, az, quad, 0);
        toCam(bx, y, bz, quad, 3);
        if (tri) {
            toCam(tipX, y + h, tipZ, quad, 6);
            fillPoly(quad, 3, col);
        } else {
            toCam(bx, y + h, bz, quad, 6);
            toCam(ax, y + h, az, quad, 9);
            fillPoly(quad, 4, col);
        }
    }

    // follaje plano: dos planos cruzados (4 vertices cada uno) en vez de 3-15 caras de cajas
    boolean drawFlat(int x, int y, int z, int b) {
        int col;
        float h, w;
        boolean tri = false;
        if (b == B_KELP) { col = 0x2F8A3A; h = 1f; w = 0.3f; }
        else if (b == B_CANE) { col = 0x7CCB4A; h = 1f; w = 0.42f; }
        else if (b == B_SEAGRASS) { col = 0x3FA03A; h = 0.75f; w = 0.38f; tri = true; }
        else if (b >= B_CROP0 && b <= B_CROP2) { col = (b == B_CROP2) ? 0xC8B040 : 0x4FB03A; h = 0.35f + (b - B_CROP0) * 0.3f; w = 0.45f; }
        else if (b == B_TUFT) { col = 0x4DAA3A; h = 0.6f; w = 0.45f; tri = true; }
        else if (b == B_FLOWER_R || b == B_FLOWER_Y) {   // tallo + cabeza: 4 planos en total
            int lq = litPct(x + 0.5f, y + 0.5f, z + 0.5f);
            int hc = (b == B_FLOWER_R) ? 0xD03030 : 0xF0D020;
            float fx0 = x + 0.5f, fz0 = z + 0.5f;
            plane(fx0 - 0.2f, fz0 - 0.2f, fx0 + 0.2f, fz0 + 0.2f, y, 0.5f, true, fx0, fz0, shade(0x3FA03A, lq));
            plane(fx0 - 0.2f, fz0 + 0.2f, fx0 + 0.2f, fz0 - 0.2f, y, 0.5f, true, fx0, fz0, shade(0x3FA03A, 88 * lq / 100));
            plane(fx0 - 0.17f, fz0 - 0.17f, fx0 + 0.17f, fz0 + 0.17f, y + 0.36f, 0.24f, false, fx0, fz0, shade(hc, lq));
            plane(fx0 - 0.17f, fz0 + 0.17f, fx0 + 0.17f, fz0 - 0.17f, y + 0.36f, 0.24f, false, fx0, fz0, shade(hc, 88 * lq / 100));
            return true;
        }
        else return false;
        int lp = litPct(x + 0.5f, y + 0.5f, z + 0.5f);
        int c1 = lp >= 100 ? col : shade(col, lp);
        int c2 = shade(col, 88 * lp / 100);
        float cx = x + 0.5f, cz = z + 0.5f;
        plane(cx - w, cz - w, cx + w, cz + w, y, h, tri, cx, cz, c1);
        plane(cx - w, cz + w, cx + w, cz - w, y, h, tri, cx, cz, c2);
        return true;
    }

    void drawSmall(int x, int y, int z, int b) {
        if ((flatFol || (b >= B_TUFT && b <= B_FLOWER_Y)) && drawFlat(x, y, z, b)) return;
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
        } else if (b == B_CANE) {
            drawBox(cx - 0.34f, y, cz - 0.34f, cx - 0.20f, y + 1f, cz - 0.20f, 0x7CCB4A, lp);
            drawBox(cx + 0.18f, y, cz + 0.16f, cx + 0.32f, y + 1f, cz + 0.30f, 0x6BB83E, lp);
            drawBox(cx - 0.07f, y, cz - 0.05f, cx + 0.07f, y + 1f, cz + 0.09f, 0x8AD655, lp);
            drawBox(cx - 0.36f, y + 0.45f, cz - 0.36f, cx - 0.18f, y + 0.5f, cz - 0.18f, 0x4F9A2F, lp);
            drawBox(cx + 0.16f, y + 0.7f, cz + 0.14f, cx + 0.34f, y + 0.75f, cz + 0.32f, 0x4F9A2F, lp);
        } else if (b == B_CAKE) {
            drawBox(cx - 0.44f, y, cz - 0.44f, cx + 0.44f, y + 0.5f, cz + 0.44f, 0xE8D8C0, lp);
            drawBox(cx - 0.44f, y + 0.5f, cz - 0.44f, cx + 0.44f, y + 0.62f, cz + 0.44f, 0xF8F0F0, lp);
            drawBox(cx - 0.05f, y + 0.62f, cz - 0.05f, cx + 0.05f, y + 0.78f, cz + 0.05f, 0xD02020, lp);
        } else if (b == B_ENCH) {   // mesa de encantamiento: base de obsidiana 3/4 de alto, tapa roja, diamantes y libro flotante
            drawBox(x, y, z, x + 1, y + 0.75f, z + 1, 0x24182E, lp);
            drawBox(x + 0.04f, y + 0.75f, z + 0.04f, x + 0.96f, y + 0.8f, z + 0.96f, 0x9B1C31, lp);
            drawBox(cx - 0.45f, y + 0.8f, cz - 0.05f, cx - 0.35f, y + 0.9f, cz + 0.05f, 0x4AE8E0, 100);
            drawBox(cx + 0.35f, y + 0.8f, cz - 0.05f, cx + 0.45f, y + 0.9f, cz + 0.05f, 0x4AE8E0, 100);
            drawBox(cx - 0.26f, y + 1.0f, cz - 0.17f, cx + 0.26f, y + 1.06f, cz + 0.17f, 0x9B1C31, lp);
            drawBox(cx - 0.23f, y + 1.06f, cz - 0.14f, cx + 0.23f, y + 1.1f, cz + 0.14f, 0xF0E8D0, lp);
        } else if (b == B_FRAME || b == B_FRAME_EYE) {   // marco del portal del End: 13/16 de alto, con hueco para el ojo
            drawBox(x, y, z, x + 1, y + 0.8125f, z + 1, 0x7A8F6A, lp);
            drawBox(x + 0.2f, y + 0.8125f, z + 0.2f, x + 0.8f, y + 0.84f, z + 0.8f, 0x06261C, lp);
            if (b == B_FRAME_EYE) drawBox(cx - 0.2f, y + 0.84f, cz - 0.2f, cx + 0.2f, y + 0.98f, cz + 0.2f, 0x35E0B0, 100);
        } else if (b == B_EGG) {   // huevo de dragon: capas apiladas
            drawBox(cx - 0.22f, y, cz - 0.22f, cx + 0.22f, y + 0.14f, cz + 0.22f, 0x14101C, lp);
            drawBox(cx - 0.36f, y + 0.14f, cz - 0.36f, cx + 0.36f, y + 0.42f, cz + 0.36f, 0x14101C, lp);
            drawBox(cx - 0.3f, y + 0.42f, cz - 0.3f, cx + 0.3f, y + 0.68f, cz + 0.3f, 0x1B1426, lp);
            drawBox(cx - 0.2f, y + 0.68f, cz - 0.2f, cx + 0.2f, y + 0.86f, cz + 0.2f, 0x14101C, lp);
            drawBox(cx - 0.1f, y + 0.86f, cz - 0.1f, cx + 0.1f, y + 0.94f, cz + 0.1f, 0x8A3AD0, 100);
            drawBox(cx - 0.3f, y + 0.2f, cz + 0.2f, cx - 0.2f, y + 0.3f, cz + 0.38f, 0x8A3AD0, 100);
        } else if (b == B_KELP) {
            float o = (((x * 3 + z * 5 + y) & 3) - 1.5f) * 0.05f;
            drawBox(cx - 0.07f + o, y, cz - 0.07f - o, cx + 0.07f + o, y + 1f, cz + 0.07f - o, 0x2F8A3A, lp);
            drawBox(cx + 0.07f + o, y + 0.35f, cz - 0.04f, cx + 0.22f + o, y + 0.55f, cz + 0.04f, 0x4FB04A, lp);
        } else if (b == B_SEAGRASS) {
            drawBox(cx - 0.25f, y, cz - 0.05f, cx - 0.15f, y + 0.55f, cz + 0.05f, 0x3FA03A, lp);
            drawBox(cx - 0.05f, y, cz - 0.05f, cx + 0.05f, y + 0.75f, cz + 0.05f, 0x2F8A3A, lp);
            drawBox(cx + 0.15f, y, cz, cx + 0.25f, y + 0.45f, cz + 0.1f, 0x4FB04A, lp);
        } else {
            drawBox(cx - 0.05f, y, cz - 0.05f, cx + 0.05f, y + 0.4f, cz + 0.05f, 0x6B4A2A, lp);
            drawBox(cx - 0.3f, y + 0.3f, cz - 0.3f, cx + 0.3f, y + 0.8f, cz + 0.3f, 0x3FA03A, lp);
        }
    }

    boolean showFace(int b, int n) {
        if (b == B_WATER || b == B_LAVA) return n != b && !opq[n] && !(b == B_WATER && (n == B_KELP || n == B_SEAGRASS));
        return !opq[n];
    }

    // vecinos por indice directo dentro del chunk; solo se dibuja lo expuesto (sin float hasta saber que hay caras)
    void cellDraw(byte[] cd, int base, int x, int y, int z, float cdx, float cdz) {
        int blk = cd[base + y] & 0xFF;
        int m = 63;
        if (!spc[blk]) {
            int lx = x & 15, lz = z & 15, n;
            m = 0;
            n = lx < 15 ? (cd[base + 16 * H + y] & 0xFF) : blockAt(x + 1, y, z);
            if (showFace(blk, n)) m = 1;
            n = lx > 0 ? (cd[base - 16 * H + y] & 0xFF) : blockAt(x - 1, y, z);
            if (showFace(blk, n)) m |= 2;
            n = y + 1 < H ? (cd[base + y + 1] & 0xFF) : 0;
            if (showFace(blk, n)) m |= 4;
            n = y > 0 ? (cd[base + y - 1] & 0xFF) : 0;
            if (showFace(blk, n)) m |= 8;
            n = lz < 15 ? (cd[base + H + y] & 0xFF) : blockAt(x, y, z + 1);
            if (showFace(blk, n)) m |= 16;
            n = lz > 0 ? (cd[base - H + y] & 0xFF) : blockAt(x, y, z - 1);
            if (showFace(blk, n)) m |= 32;
            if (m == 0) return;
        }
        float dy = y + 0.5f - camY;
        float dd2 = cdx * cdx + dy * dy + cdz * cdz;
        if (cdx * fx + dy * fy + cdz * fz > -1.8f && dd2 < cullNow && (blk < B_TUFT || blk > B_FLOWER_Y || dd2 < 110f)) drawBlockM(x, y, z, blk, m);
    }

    void drawBlockM(int x, int y, int z, int b, int m) {
        if (spc[b]) { drawBlock(x, y, z, b); return; }
        int[] sc = shadedCol[b];
        if ((m & 1) != 0 && camX > x + 1) faceBlock(x, y, z, 0, sc[0], b);
        if ((m & 2) != 0 && camX < x) faceBlock(x, y, z, 1, sc[1], b);
        if ((m & 4) != 0 && camY > y + 1) faceBlock(x, y, z, 2, sc[2], b);
        if ((m & 8) != 0 && camY < y) faceBlock(x, y, z, 3, sc[3], b);
        if ((m & 16) != 0 && camZ > z + 1) faceBlock(x, y, z, 4, sc[4], b);
        if ((m & 32) != 0 && camZ < z) faceBlock(x, y, z, 5, sc[5], b);
    }

    void drawBlock(int x, int y, int z, int b) {
        if (b == B_CANE || b == B_CAKE || b == B_SAPLING || b == B_TORCH || (b >= B_CROP0 && b <= B_CROP2) || b == B_KELP || b == B_SEAGRASS
                || b == B_ENCH || b == B_FRAME || b == B_FRAME_EYE || b == B_EGG || (b >= B_TUFT && b <= B_FLOWER_Y)) { drawSmall(x, y, z, b); return; }
        if (isDoor(b)) { drawDoor(x, y, z, b); return; }
        if (b == B_BED) { drawBed(x, y, z); return; }
        if (b == B_ENDPORTAL) { drawBox(x, y + 0.7f, z, x + 1, y + 0.85f, z + 1, (((tick / 8) + x + z) & 1) == 0 ? 0x0B3C50 : 0x145070, 100); return; }
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

    // punto en el espacio de la camara: a = adelante, b = derecha, c = arriba
    void camPt(float a, float b, float c, float[] o) {
        float ux = -sinY * sinP, uy = cosP, uz = -cosY * sinP;
        o[0] = camX + fx * a + cosY * b + ux * c;
        o[1] = camY + fy * a + uy * c;
        o[2] = camZ + fz * a - sinY * b + uz * c;
    }

    // tira fina que mira a la camara entre dos puntos del mundo
    void thick(float ax, float ay, float az, float bx, float by, float bz, float w, int col) {
        float rx = cosY * w, rz = -sinY * w;
        toCam(ax + rx, ay, az + rz, quad, 0);
        toCam(ax - rx, ay, az - rz, quad, 3);
        toCam(bx - rx, by, bz - rz, quad, 6);
        toCam(bx + rx, by, bz + rz, quad, 9);
        fillPoly(quad, 4, col);
    }

    float[] pA = new float[3], pB = new float[3];

    // cana en la mano + sedal + anzuelo (flotador) en el agua
    void drawRodAndLine() {
        if (slId[sel] != I_ROD) return;
        camPt(0.45f, 0.36f, -0.40f, pA);   // base de la cana
        camPt(1.05f, 0.24f, 0.02f, pB);    // punta de la cana
        thick(pA[0], pA[1], pA[2], pB[0], pB[1], pB[2], 0.018f, 0x6B4A2A);
        if (fishState == 0) return;
        int cy = fishY, n = 0;
        while (n < 20 && wtr(blockAt(fishX, cy + 1, fishZ))) { cy++; n++; }
        float bx = fishX + 0.5f, bz = fishZ + 0.5f, by = cy + 0.88f;
        if (fishState == 3) by -= 0.07f + 0.05f * (float) Math.sin(tick * 0.9f);   // pica: se hunde y tiembla
        else by += 0.02f * (float) Math.sin(tick * 0.2f);
        float sag = fishState == 3 ? 0.08f : 0.35f;   // sedal flojo mientras espera, tenso al picar
        float px0 = pB[0], py0 = pB[1], pz0 = pB[2];
        for (int i = 1; i <= 8; i++) {
            float t = i / 8f, t0 = (i - 1) / 8f;
            float x1 = pB[0] + (bx - pB[0]) * t, z1 = pB[2] + (bz - pB[2]) * t;
            float y1 = pB[1] + (by - pB[1]) * t - 4f * t * (1f - t) * sag;
            thick(px0, py0, pz0, x1, y1, z1, 0.006f, 0xE8E8E8);
            px0 = x1; py0 = y1; pz0 = z1;
        }
        drawBox(bx - 0.07f, by - 0.02f, bz - 0.07f, bx + 0.07f, by + 0.06f, bz + 0.07f, 0xF0F0F0, 100);
        drawBox(bx - 0.07f, by + 0.06f, bz - 0.07f, bx + 0.07f, by + 0.14f, bz + 0.07f, 0xD02020, 100);
    }

    // barra vertical del minijuego (izquierda): zona verde = punto tuyo, roja = punto del pez
    void paintFishGame() {
        if (fishState != 3) return;
        int lh = font.getHeight();
        int bw = 14, bh = Math.max(60, sh * 50 / 100), bx = 8, by = (sh - bh) / 2;
        gg.setColor(0x000000);
        gg.fillRect(bx - 2, by - 2, bw + 4, bh + 4);
        gg.setColor(0xB02828);
        gg.fillRect(bx, by, bw, bh);
        int z0 = by + (int) ((fgC - fgS / 2f) * bh), z1 = by + (int) ((fgC + fgS / 2f) * bh);
        gg.setColor(0x30C030);
        gg.fillRect(bx, z0, bw, Math.max(2, z1 - z0));
        int my = by + (int) (fgM * (bh - 1));
        gg.setColor(0x000000);
        gg.fillRect(bx - 5, my - 2, bw + 10, 5);
        gg.setColor(fgFlash > 0 ? (fgOk ? 0xFFFFFF : 0xFF6060) : 0xFFE040);
        gg.fillRect(bx - 4, my - 1, bw + 8, 3);
        txt(cat(cat(L("Tu"), ": "), Integer.toString(fgYou)), bx - 4, by - lh - 4, 0x80FF80, Graphics.TOP | Graphics.LEFT);
        txt(cat(cat(L("Pez"), ": "), Integer.toString(fgFish)), bx - 4, by + bh + 5, 0xFF8080, Graphics.TOP | Graphics.LEFT);
    }

    // el item elegido, en grande en la mano (la cana se dibuja en 3D)
    void paintHeld(int hy) {
        int id = slId[sel];
        if (id == 0 || id == I_ROD) return;
        int bs = Math.min(sw / 4, 56);
        int bob = (int) (Math.sin(tick * 0.15f) * 1.5f);
        drawIcon(id, 0, sw - bs - 4, hy - bs - 12 + bob, bs);
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
        if ((m.x - camX) * fx + (m.y + 0.8f - camY) * fy + (m.z - camZ) * fz < -1.6f) return;   // detras de la camara
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
            if (m.type == 9) base = (pi == 1) ? 0xFFFFFF - m.col : m.col;
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
        toCam(camX + 1f, camY, camZ, eX, 0);
        toCam(camX, camY + 1f, camZ, eY, 0);
        toCam(camX, camY, camZ + 1f, eZ, 0);
        if (!opqOk) {
            for (int i = 0; i < 256; i++) {
                opq[i] = opaque(i);
                spc[i] = (i == B_CANE || i == B_CAKE || i == B_SAPLING || i == B_TORCH || (i >= B_CROP0 && i <= B_CROP2) || i == B_KELP || i == B_SEAGRASS || isDoor(i) || i == B_BED || i == B_PORTAL || i == B_ENDPORTAL || i == B_ENCH || i == B_FRAME || i == B_FRAME_EYE || i == B_EGG || (i >= B_TUFT && i <= B_FLOWER_Y));
            }
            opqOk = true;
        }
        long t0 = System.currentTimeMillis();
        float cs2 = cullSq * cullK;

        int bx0 = ifloor(camX) - rd, bz0 = ifloor(camZ) - rd;
        buildOrder(oy, H, ifloor(camY));
        for (int i = 0; i < H; i++) invY[oy[i]] = i;
        camC = ifloor(camY);
        if (camC < 0) camC = 0;
        if (camC > H - 1) camC = H - 1;
        int wk = seed * 2 + dim;
        if (wk != topW) { topW = wk; topGen++; }

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

        cullNow = cs2;
        for (int a = 0; a < nb; a++) {
            int x = bx0 + ox[a];
            for (int b = 0; b < nb; b++) {
                int z = bz0 + oz[b];
                byte[] cd = chunkFor(x, z);
                if (cd == null) continue;
                float cdx = x + 0.5f - camX, cdz = z + 0.5f - camZ;
                if (cdx * fx + cdz * fz < -slack) continue;
                int li = ((x & 15) << 4) + (z & 15);
                int base = li * H;
                int colKey = (a * nb + b) * H;
                boolean mobHere = mp < nm && mobKey[mp] <= colKey + H - 1;
                if (!mobHere && cdx * cdx + cdz * cdz > cs2) continue;   // columna fuera de alcance
                int top = columnTop(x, z, cd);
                if (mobHere) {   // hay mobs en la columna: recorrido completo en orden del pintor
                    int lim = (camC < top) ? H - 1 : top - 1;
                    for (int c = 0; c <= lim; c++) {
                        int y = oy[c];
                        if (cd[base + y] != 0) cellDraw(cd, base, x, y, z, cdx, cdz);
                        while (mp < nm && mobKey[mp] <= colKey + c) {
                            drawMob((Mob) mobs.elementAt(mobOrd[mp]));
                            mp++;
                        }
                    }
                } else {
                    // sin mobs: solo celdas bajo la superficie y fuera del macizo enterrado (mismo orden del pintor)
                    byte[] sol = colSol[lastSlot];
                    int lx = x & 15, lz = z & 15;
                    int sm = sol[li], t;
                    t = lx < 15 ? sol[li + 16] : solidAt(x + 1, z); if (t < sm) sm = t;
                    t = lx > 0 ? sol[li - 16] : solidAt(x - 1, z); if (t < sm) sm = t;
                    t = lz < 15 ? sol[li + 1] : solidAt(x, z + 1); if (t < sm) sm = t;
                    t = lz > 0 ? sol[li - 1] : solidAt(x, z - 1); if (t < sm) sm = t;
                    int sk = sm - 2;   // celdas 1..sk rodeadas de solido: no se ven
                    int e1 = (camC < top) ? camC : top;
                    if (e1 > 0 && cd[base] != 0) cellDraw(cd, base, x, 0, z, cdx, cdz);
                    for (int y = (sk < 1) ? 1 : sk + 1; y < e1; y++) {
                        if (cd[base + y] != 0) cellDraw(cd, base, x, y, z, cdx, cdz);
                    }
                    if (camC < top) {
                        for (int y = top - 1; y > camC && y > sk; y--) {
                            if (cd[base + y] != 0) cellDraw(cd, base, x, y, z, cdx, cdz);
                        }
                        if (cd[base + camC] != 0) cellDraw(cd, base, x, camC, z, cdx, cdz);
                    }
                }
            }
        }

        while (mp < nm) { drawMob((Mob) mobs.elementAt(mobOrd[mp])); mp++; }
        drawRodAndLine();
        long dt = System.currentTimeMillis() - t0;
        if (dt > 110) { cullK *= 0.94f; if (cullK < 0.3f) cullK = 0.3f; }
        else if (dt < 70 && cullK < 1f) { cullK *= 1.03f; if (cullK > 1f) cullK = 1f; }

        if (ty >= 0 && tMob == null) drawWire(tx, ty, tz);

        if (waterAtF(camX, camY, camZ)) {
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
        if ((anchor & Graphics.HCENTER) != 0) {
            // centrado a mano: sin espacios de los bordes (ej. "Nombre: " sin nombre) y contando la sombra de 1 px
            s = s.trim();
            x -= (gg.getFont().stringWidth(s) + 1) / 2;
            anchor = (anchor & ~Graphics.HCENTER) | Graphics.LEFT;
        }
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
            if (id <= B_ICE || (id >= B_COAL_ORE && id <= B_DIAM_BLK) || (id >= B_NETHERRACK && id <= B_OBSIDIAN) || (id >= B_SOULSAND && id <= B_BEDROCK) || id == B_CHEST || id == B_MAGMA || (id >= B_ENDSTONE && id <= B_EGG) || (id >= B_LOG_S && id <= B_FARM_DRY)) {
                gg.setColor(0x000000);
                gg.drawRect(ox2 - 1, oy2 - 1, c * 8 + 1, c * 8 + 1);
            }
            int dd = dur & DM;
            if (isTool(id) && dd > 0 && dd < toolMax(id)) {
                int bw = (is * dd) / toolMax(id);
                gg.setColor(dd * 3 < toolMax(id) ? 0xE02020 : 0x20C020);
                gg.fillRect(ix, y + s - 3, bw, 2);
            }
        }
    }

    void drawCount(int cnt, int x, int y, int s) {
        if (cnt > 1) txt(Integer.toString(cnt), x + s - 2, y + s - 1, 0xFFFFFF, Graphics.BOTTOM | Graphics.RIGHT);
    }

    void paintHud() {
        if (touch) layoutTouch();
        if (dragon != null && dragon.hp > 0) {   // barra de vida del dragon
            int bw = sw * 3 / 5, bx = (sw - bw) / 2;
            gg.setColor(0x000000);
            gg.fillRect(bx - 1, 1, bw + 2, 6);
            gg.setColor(0xB020D0);
            gg.fillRect(bx, 2, bw * dragon.hp / DRAGON_HP, 4);
            int cr = 0;
            for (int k = 0; k < 3; k++) if ((endFlags & (8 << k)) == 0) cr++;
            txt(cat(L("Cristales: "), cr), sw / 2, 9, 0xE060FF, Graphics.TOP | Graphics.HCENTER);
        }
        int cx = sw / 2, cy = sh / 2;
        gg.setColor(0xFFFFFF);
        gg.drawLine(cx - 5, cy, cx + 5, cy);
        gg.drawLine(cx, cy - 5, cx, cy + 5);

        if (mineProg > 0 && mineX != NONE) {
            int need = breakTicks(blockAt(mineX, mineY, mineZ), slId[sel]);
            int bw = 24 * mineProg / need;
            gg.setColor(0x000000);
            gg.fillRect(cx - 13, cy + 10, 26, 5);
            gg.setColor(0xFFFFFF);
            gg.fillRect(cx - 12, cy + 11, bw, 3);
        }

        paintFishGame();
        int hs = touch ? Math.min(sw / 11, 24) : Math.min(sw / 10, 24);
        paintHeld(sh - hs - 3);
        int hx = touch ? (sw - 10 * hs) / 2 : (sw - 9 * hs) / 2;
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
        if (!creative) {   // barra de experiencia
            int xw = 9 * hs, xy = ry - 7;
            gg.setColor(0x203010);
            gg.fillRect(hx, xy, xw, 4);
            gg.setColor(0x80E020);
            gg.fillRect(hx, xy, xw * xpPts / xpNeed(xpLvl), 4);
            if (xpLvl > 0) txt(Integer.toString(xpLvl), hx + xw / 2, xy - 1, 0x80FF20, Graphics.BOTTOM | Graphics.HCENTER);
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
        if (creative) txt(flying ? "Creativo (vuelo)" : "Creativo", sw / 2, touch ? tcSb + 6 : 2, 0xA0FFA0, Graphics.TOP | Graphics.HCENTER);

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
        if (touch && state == ST_PLAY) paintTouch();
    }

    // ---------------------------------------------------------------- controles tactiles
    boolean hit(int x, int y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    void layoutTouch() {
        int m = Math.min(sw, sh) / 8;
        if (m < 22) m = 22;
        if (m > 40) m = 40;
        tcB = m;
        tcHs = Math.min(sw / 11, 24);
        tcHx = (sw - 10 * tcHs) / 2;
        tcHy = sh - tcHs - 3;
        tcIX = tcHx + 9 * tcHs; tcIY = tcHy; tcIS = tcHs;   // boton de inventario: a la derecha de la barra
        int bot = tcHy - 24;                                // por encima de corazones y hambre
        tcDX = 6 + tcB * 3 / 2; tcDY = bot - tcB * 3 / 2;   // centro de la cruceta
        tcJS = tcB * 3 / 2; tcJX = sw - 6 - tcJS; tcJY = bot - tcJS;
        tcSb = tcB * 3 / 4; tcPX = sw / 2 - tcSb - 3; tcGX = sw / 2 + 3; tcPY = 3;
        int need = tcJS * tcJS;
        if (tcBuf == null || tcBuf.length < need) {
            tcBuf = new int[need];
            tcBufOn = new int[need];
            for (int i = 0; i < need; i++) { tcBuf[i] = 0x66000000; tcBufOn[i] = 0x99909090; }
        }
    }

    // cruceta: arriba/abajo = avanzar/retroceder, izquierda/derecha = girar (admite diagonales)
    boolean tcDpad(int x, int y) {
        int dx = x - tcDX, dy = y - tcDY, lim = tcB * 3 / 2 + tcB / 3, t = tcB / 2;
        boolean in = dx >= -lim && dx <= lim && dy >= -lim && dy <= lim;
        boolean wasFw = tFw;
        tFw = in && dy < -t;
        if (tFw && !wasFw) { if (tick - fwTick < 8) sprint = true; fwTick = tick; }
        tBk = in && dy > t; tL = in && dx < -t; tR = in && dx > t;
        return in;
    }

    void tcPress(int x, int y) {
        layoutTouch();
        tcMode = 0;
        if (hit(x, y, tcPX - 3, 0, tcSb + 6, tcSb + 8)) { tpDown = false; openPause(); return; }
        if (hit(x, y, tcGX - 3, 0, tcSb + 6, tcSb + 8)) { dbgOn = !dbgOn; tcMode = 4; return; }
        if (x >= tcIX && y >= tcIY - tcIS / 2) { tpDown = false; openInv(2); return; }
        if (y >= tcHy - 2 && x >= tcHx && x < tcHx + 9 * tcHs) { sel = (x - tcHx) / tcHs; labelT = 40; tcMode = 4; return; }
        if (tcDpad(x, y)) { tcMode = 1; return; }
        if (hit(x, y, tcJX - 6, tcJY - 6, tcJS + 12, tcJS + 12)) {
            tcMode = 2; tJp = true;
            if (creative) {   // doble toque de salto: volar / aterrizar
                if (tick - jumpTick < 8) { flying = !flying; vy = 0; fallDist = 0; jumpTick = -100; }
                else jumpTick = tick;
            }
            return;
        }
        // resto de la pantalla: mantener = romper / atacar, toque corto = colocar / usar, arrastrar = mirar
        tcMode = 3; tcLX = x; tcLY = y; tcLook = false;
        mineOn = true; starTicks = 1; starConsumed = false; mineProg = 0;
    }

    void tcDrag(int x, int y) {
        if (tcMode == 1) { tcDpad(x, y); return; }
        if (tcMode != 3) return;
        if (!tcLook) {
            if (starTicks > 6) return;   // pulsacion larga: sigue minando aunque muevas el dedo
            int ddx = x - tcLX, ddy = y - tcLY;
            if (ddx * ddx + ddy * ddy < 100) return;
            tcLook = true;
            mineOn = false; starTicks = 0; starConsumed = false; mineProg = 0; mineX = NONE;
        }
        yaw += (x - tcLX) * 0.012f;
        pitch -= (y - tcLY) * 0.012f;
        if (pitch > 1.4f) pitch = 1.4f;
        if (pitch < -1.4f) pitch = -1.4f;
        tcLX = x; tcLY = y;
    }

    void tcCancel() {
        tFw = false; tBk = false; tL = false; tR = false; tJp = false;
        if (tcMode == 3) { mineOn = false; starTicks = 0; starConsumed = false; mineProg = 0; mineX = NONE; }
        tcMode = 0;
    }

    void tcRelease() {
        if (tcMode == 1) { tFw = false; tBk = false; tL = false; tR = false; }
        else if (tcMode == 2) tJp = false;
        else if (tcMode == 3) {
            if (!tcLook && !starConsumed && starTicks > 0 && starTicks <= 8) doTap();
            mineOn = false; starTicks = 0; starConsumed = false; mineProg = 0; mineX = NONE;
        }
        tcMode = 0;
    }

    int slotAt(int x, int y) {
        for (int j = 0; j < NSLOT; j++) {
            if (slActive[j] && x >= slx[j] && x < slx[j] + slS && y >= sly[j] && y < sly[j] + slS) return j;
        }
        return -1;
    }

    // se puede soltar de a uno en este slot (no en resultados ni en la paleta creativa)
    boolean dropOk(int j) {
        if (j < 0 || j == OUT || (craftN == FURN && j == 38)) return false;
        if (palMode() && j >= 9 && j < 36) return false;
        return true;
    }

    void tcInvPress(int x, int y) {
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
                if (t < T_IN.length) { tSel = t; updateOutput(); }
                return;
            }
        }
        if (craftN == ENCH) {
            int r = slS * 3 / 4, w = 5 * slS, vis = (sly[9] - 22) / r;
            if (vis < 1) vis = 1;
            int first = eSel - vis + 1;
            if (first < 0) first = 0;
            if (hit(x, y, slx[9], 14, w, vis * r)) {
                int t = first + (y - 14) / r;
                if (t < enchListN()) { eSel = t; updateOutput(); }
                return;
            }
        }
        int j = slotAt(x, y);
        if (j < 0) return;
        tcSlot = j; tcPressSlot = j; cursor = j; tcInvDown = true;
        tcNext = System.currentTimeMillis() + 1000;
    }

    void tcInvDrag(int x, int y) {
        if (!tcInvDown) return;
        int j = slotAt(x, y);
        if (j != tcSlot) { tcSlot = j; if (j >= 0) cursor = j; }
    }

    // con una pila en la mano: cada segundo sostenido suelta un bloque en el slot que hay bajo el dedo
    void tcInvTick() {
        if (!tcInvDown || heldId == 0 || !dropOk(tcSlot)) { if (tcInvDown) tcNext = System.currentTimeMillis() + 1000; return; }
        long now = System.currentTimeMillis();
        if (now >= tcNext) {
            tcNext = now + 1000;
            clickSecondary(tcSlot);
            tcDropped = true;
        }
    }

    void tcInvRelease() {
        palPress = 0;
        if (tcInvDown && !tcDropped && tcPressSlot >= 0 && tcSlot == tcPressSlot) click(tcPressSlot);   // toque = pila entera
        tcInvDown = false;
    }

    void tcBtn(int x, int y, int w, int h, boolean on) {
        gg.drawRGB(on ? tcBufOn : tcBuf, 0, w, x, y, w, h, true);
        gg.setColor(0xDDDDDD);
        gg.drawRect(x, y, w - 1, h - 1);
    }

    // dir: 0 arriba, 1 abajo, 2 izquierda, 3 derecha
    void tcArrow(int x, int y, int w, int h, int dir) {
        int a = w / 4, b = h / 4;
        gg.setColor(0xFFFFFF);
        if (dir == 0) gg.fillTriangle(x + w / 2, y + b, x + a, y + h - b, x + w - a, y + h - b);
        else if (dir == 1) gg.fillTriangle(x + a, y + b, x + w - a, y + b, x + w / 2, y + h - b);
        else if (dir == 2) gg.fillTriangle(x + a, y + h / 2, x + w - a, y + b, x + w - a, y + h - b);
        else gg.fillTriangle(x + w - a, y + h / 2, x + a, y + b, x + a, y + h - b);
    }

    void paintInvBtn(boolean on) {
        int x = tcIX, y = tcIY, s = tcIS;
        tcBtn(x, y, s, s, on);
        gg.setColor(0xFFFFFF);
        gg.drawRect(x + s / 5, y + s * 2 / 5, s * 3 / 5, s * 2 / 5);
        gg.drawRect(x + s * 2 / 5, y + s / 4, s / 5, s / 7 + 1);
    }

    void paintTouch() {
        int b = tcB, h2 = b / 2;
        int x = tcDX - h2, y = tcDY - h2;
        tcBtn(x, tcDY - b - h2, b, b, tFw); tcArrow(x, tcDY - b - h2, b, b, 0);
        tcBtn(x, tcDY + h2, b, b, tBk); tcArrow(x, tcDY + h2, b, b, 1);
        tcBtn(tcDX - b - h2, y, b, b, tL); tcArrow(tcDX - b - h2, y, b, b, 2);
        tcBtn(tcDX + h2, y, b, b, tR); tcArrow(tcDX + h2, y, b, b, 3);
        tcBtn(tcJX, tcJY, tcJS, tcJS, tJp); tcArrow(tcJX, tcJY, tcJS, tcJS, 0);
        int sb = tcSb, bw = sb / 8 + 1;
        tcBtn(tcPX, tcPY, sb, sb, false);   // pausa
        gg.setColor(0xFFFFFF);
        gg.fillRect(tcPX + sb * 3 / 10, tcPY + sb / 4, bw, sb / 2);
        gg.fillRect(tcPX + sb * 6 / 10, tcPY + sb / 4, bw, sb / 2);
        tcBtn(tcGX, tcPY, sb, sb, dbgOn);   // depuracion: llave inglesa
        gg.setColor(0xFFFFFF);
        gg.drawLine(tcGX + sb / 4, tcPY + sb * 3 / 4, tcGX + sb * 5 / 8, tcPY + sb * 3 / 8);
        gg.drawLine(tcGX + sb / 4 + 1, tcPY + sb * 3 / 4, tcGX + sb * 5 / 8 + 1, tcPY + sb * 3 / 8);
        gg.drawArc(tcGX + sb / 2, tcPY + sb / 6, sb / 3, sb / 3, 0, 360);
        paintInvBtn(false);
    }

    void paintInv() {
        gg.setColor(0x202020);
        gg.fillRect(0, 0, sw, sh);
        boolean pm = palMode();
        String ttl = craftN == CHEST ? "Cofre" : (craftN == FURN ? "Horno" : (craftN == 3 ? "Mesa de crafteo" : "Inventario"));
        if (pm) ttl = new StringBuffer(L("Creativo")).append("  [1] ").append(palPage + 1).append('/').append((palN + 26) / 27).append(" [3]").toString();
        if (craftN == TRADE) ttl = "Aldeano  [1][3] lista";
        if (craftN == ENCH) ttl = new StringBuffer(L("Mesa de encantamiento")).append("  [1][3]").toString();
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
        } else if (craftN == ENCH) {
            paintEnch();
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
            if (!(pm && cursor >= 9 && cursor < 36) && (isTool(nid) || nid == I_BOOK)) nm = enSuffix(nm, heldId != 0 ? heldDur : slDur[cursor]);
            if (cursor == OUT && heldId == 0) nm = new StringBuffer(L("Resultado: ")).append(nm).toString();
            txt(nm, sw / 2, sh - 2, 0xFFFFFF, Graphics.BOTTOM | Graphics.HCENTER);
        }
        if (pm && touch) paintPalBtns();
        if (touch) { layoutTouch(); paintInvBtn(true); }
    }

    // botones tactiles de pagina (creativo): franja izquierda y derecha junto a la grilla
    void paintPalBtns() {
        int x0 = slx[9], x1 = x0 + 9 * slS;
        int bw = x0 - 3, by = sly[9], bh = 3 * slS;
        if (bw < 8) return;
        int a = Math.max(3, Math.min(bw / 3, 8));
        for (int i = 0; i < 2; i++) {
            int bx = (i == 0) ? 1 : x1 + 2;
            boolean on = palPress == i + 1;
            gg.setColor(on ? 0x6FA0E8 : 0x3E6EB8);
            gg.fillRoundRect(bx, by, bw, bh, 8, 8);
            gg.setColor(on ? 0xFFFFFF : 0xA8C8F0);
            gg.drawRoundRect(bx, by, bw - 1, bh - 1, 8, 8);
            int cx = bx + bw / 2, cy = by + bh / 2;
            gg.setColor(0xFFFFFF);
            if (i == 0) gg.fillTriangle(cx - a, cy, cx + a, cy - a - a / 2, cx + a, cy + a + a / 2);
            else gg.fillTriangle(cx + a, cy, cx - a, cy - a - a / 2, cx - a, cy + a + a / 2);
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
        s = s.trim();
        int tx = (sw - f.stringWidth(s) - 2) / 2;   // centrado contando la sombra de 2 px
        gg.setColor(shadow);
        gg.drawString(s, tx + 2, y + 2, Graphics.TOP | Graphics.LEFT);
        gg.setColor(col);
        gg.drawString(s, tx, y, Graphics.TOP | Graphics.LEFT);
        gg.setFont(font);
    }

    int drawLogo(int y) {
        title("MINECRAFT", y, fontBig, 0xC6C6C6, 0x2B2B2B);
        y += fontBig.getHeight() + 1;
        title("MICRO EDITION", y, fontMid, 0xFFE24A, 0x3A3000);
        return y + fontMid.getHeight();
    }

    boolean btnBad;   // fila marcada en rojo (tecla repetida)

    void drawBtn(int x, int y, int w, int h, String label, boolean on) {
        label = L(label);
        int base = on ? 0x6F80C4 : 0x7C7C7C;
        int hi = on ? 0xA3B0E6 : 0xB4B4B4;
        int lo = on ? 0x3E4B8C : 0x4A4A4A;
        if (btnBad) { base = on ? 0xC25858 : 0xA03C3C; hi = 0xE08888; lo = 0x6A1C1C; }
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
        btn(A_CTRL, "Controles", btnX, y, btnW, btnH);
        y += btnH + 8;
        btn(A_SND, "Sonido", btnX, y, btnW, btnH);
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

    // pantalla de inicio: el jugador elige el idioma entre los de idiomas.txt
    void paintLang() {
        metrics();
        drawDirt(50);
        title("Idioma / Language", 4, fontMid, 0xFFFFFF, 0x202020);
        int top = 4 + fontMid.getHeight() + 10, bot = sh - 4, step = btnH + 3;
        int vis = (bot - top + 3) / step;
        if (vis < 1) vis = 1;
        int maxS = langN - vis;
        if (maxS < 0) maxS = 0;
        if (navSel != langLastSel) {
            if (navSel < wScroll) wScroll = navSel;
            if (navSel >= wScroll + vis) wScroll = navSel - vis + 1;
            langLastSel = navSel;
        }
        if (wScroll > maxS) wScroll = maxS;
        if (wScroll < 0) wScroll = 0;
        for (int r = 0; r < vis; r++) {
            int i = wScroll + r;
            if (i >= langN) break;
            int y = top + r * step;
            drawBtn(btnX, y, btnW, btnH, langNames[i], isSel(300 + i));
            addHit(btnX, y, btnW, btnH, 300 + i);
        }
        if (wScroll > 0) txt("^", sw - 4, top, 0xFFFFFF, Graphics.TOP | Graphics.RIGHT);
        if (wScroll + vis < langN) txt("v", sw - 4, bot - font.getHeight(), 0xFFFFFF, Graphics.TOP | Graphics.RIGHT);
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
        btn(A_CDIFF, cat(L("Dificultad: "), cDiff == 1 ? L("Pacifico") : (cDiff == 2 ? L("Dificil") : L("Normal"))), btnX, y, btnW, btnH);
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
        int total = fontMid.getHeight() + 14 + 4 * btnH + 3 * 8;
        int y = (sh - total) / 2;
        if (y < 2) y = 2;
        title("Juego en pausa", y, fontMid, 0xFFFFFF, 0x202020);
        y += fontMid.getHeight() + 14;
        btn(A_RESUME, "Reanudar", btnX, y, btnW, btnH);
        y += btnH + 8;
        btn(A_SETTINGS, "Ajustes", btnX, y, btnW, btnH);
        y += btnH + 8;
        btn(A_LANG, cat(cat(L("Idioma: "), langNames[Math.max(0, langPos())]), langFound ? "" : " (sin idiomas.txt)"), btnX, y, btnW, btnH);
        y += btnH + 8;
        btn(A_TOMENU, "Menu principal", btnX, y, btnW, btnH);
        if (storeBroken) txt("No se pudo guardar!", sw / 2, y + btnH + 4, 0xFF5555, Graphics.TOP | Graphics.HCENTER);
    }

    // esquema del teclado: cada celda muestra la tecla y su accion; 5 / toque abre el selector de accion
    void paintKeys() {
        metrics();
        drawDirt(55);
        layoutKeys();
        title("Controles", 3, fontMid, 0xFFFFFF, 0x202020);
        int lh = font.getHeight();
        for (int c = 0; c < 16; c++) {
            if (kbW[c] <= 0) continue;
            boolean on = (ksel == c);
            if (c < NKEY) {
                drawBtn(kbX[c], kbY[c], kbW[c], kbH[c], "", on);
                int a = km[c];
                String an = (a >= 0) ? fit(L(ACT_NAME[a]), kbW[c] - 6) : "-";
                int cx0 = kbX[c] + kbW[c] / 2;
                if (kbH[c] >= lh * 2 + 4) {
                    int ty0 = kbY[c] + (kbH[c] - lh * 2) / 2;
                    txt(keyShort(c), cx0, ty0, on ? 0xFFFFA0 : 0xFFFFFF, Graphics.TOP | Graphics.HCENTER);
                    txt(an, cx0, ty0 + lh, a >= 0 ? 0xDDDDDD : 0x909090, Graphics.TOP | Graphics.HCENTER);
                } else {
                    txt(fit(cat(cat(keyShort(c), " "), an), kbW[c] - 6), cx0, kbY[c] + (kbH[c] - lh) / 2,
                            on ? 0xFFFFA0 : 0xFFFFFF, Graphics.TOP | Graphics.HCENTER);
                }
            } else {
                drawBtn(kbX[c], kbY[c], kbW[c], kbH[c], c == 14 ? "Restaurar" : "Atras", on);
            }
            if (!kpick) addHit(kbX[c], kbY[c], kbW[c], kbH[c], c < NKEY ? A_KEY0 + c : (c == 14 ? A_KRESET : A_KBACK));
        }
        if (ksel < NKEY) {
            int a = km[ksel];
            String sel2 = cat(cat(L("Tecla: "), keyShort(ksel)), cat(" = ", a >= 0 ? L(ACT_NAME[a]) : L("Sin accion")));
            txt(fit(sel2, sw - 6), sw / 2, sh - lh * 2 - 3, 0xFFFFFF, Graphics.TOP | Graphics.HCENTER);
        }
        int fu = firstUnbound();
        if (fu >= 0) txt(fit(cat(L("Sin tecla: "), L(ACT_NAME[fu])), sw - 6), sw / 2, sh - 2, 0xFF5555, Graphics.BOTTOM | Graphics.HCENTER);
        else txt(kpick ? "2/8: elegir / 5: asignar" : "Flechas: tecla / 5: cambiar", sw / 2, sh - 2, 0xB0B0B0, Graphics.BOTTOM | Graphics.HCENTER);
        if (kpick) paintKeyPick();
    }

    // selector de accion para la tecla elegida (lista con scroll; la ultima fila quita la accion)
    void paintKeyPick() {
        int lh = font.getHeight();
        int rows = NACT + 1;
        int rowH = Math.max(lh + 6, 16);
        pkRow = rowH;
        int w = Math.min(sw - 8, 200), x = (sw - w) / 2;
        int head = lh + 8;
        int vis = (sh - 8 - head) / (rowH + 1);
        if (vis > rows) vis = rows;
        if (vis < 1) vis = 1;
        wVis = vis;
        int h = head + vis * (rowH + 1) + 3;
        int y = (sh - h) / 2;
        if (kpFollow) {
            kpFollow = false;
            if (kpSel < wScroll) wScroll = kpSel;
            if (kpSel >= wScroll + vis) wScroll = kpSel - vis + 1;
        }
        int maxS = rows - vis;
        if (wScroll > maxS) wScroll = maxS;
        if (wScroll < 0) wScroll = 0;
        gg.setColor(0x000000);
        gg.fillRect(x - 2, y - 2, w + 4, h + 4);
        gg.setColor(0x303030);
        gg.fillRect(x, y, w, h);
        gg.setColor(0x909090);
        gg.drawRect(x, y, w - 1, h - 1);
        txt(cat(L("Tecla: "), keyShort(ksel)), sw / 2, y + 4, 0xFFFF80, Graphics.TOP | Graphics.HCENTER);
        int cur = km[ksel];
        for (int r = 0; r < vis; r++) {
            int i = wScroll + r;
            if (i >= rows) break;
            int yy = y + head + r * (rowH + 1);
            String lab = (i < NACT) ? L(ACT_NAME[i]) : L("Sin accion");
            boolean isCur = (i < NACT) ? cur == i : cur < 0;
            if (isCur) lab = cat("* ", lab);
            drawBtn(x + 3, yy, w - 6, rowH, lab, i == kpSel);
            addHit(x + 3, yy, w - 6, rowH, A_PICK0 + i);
        }
        addHit(x, y, w, h, 0);   // tocar el panel no hace nada; tocar fuera lo cierra
        if (wScroll > 0) txt("^", x + 6, y + 4, 0xFFFFFF, Graphics.TOP | Graphics.LEFT);
        if (wScroll + vis < rows) txt("v", x + w - 6, y + 4, 0xFFFFFF, Graphics.TOP | Graphics.RIGHT);
    }

    void paintSettings() {
        if (inWorld) { paintWorld(); dim(); } else drawDirt(55);
        metrics();
        int rows = inWorld ? 4 : 3;
        int total = fontMid.getHeight() + 14 + rows * (btnH + 8) + font.getHeight() + (inWorld ? font.getHeight() : 0) + 8 + btnH;
        int y = (sh - total) / 2;
        if (y < 2) y = 2;
        title("Ajustes", y, fontMid, 0xFFFFFF, 0x202020);
        y += fontMid.getHeight() + 14;
        if (inWorld) {
            btn(A_RD, new StringBuffer(L("Render: ")).append(L(RD_NAME[rdIdx])).append(" (").append(RD_OPT[rdIdx]).append(")").toString(), btnX, y, btnW, btnH);
            y += btnH + 8;
        }
        btn(A_FOL, new StringBuffer(L("Follaje: ")).append(L(flatFol ? "Plano" : "3D")).toString(), btnX, y, btnW, btnH);
        y += btnH + 8;
        btn(A_MUS, new StringBuffer(L("Musica: ")).append(musLv * 20).append('%').toString(), btnX, y, btnW, btnH);
        y += btnH + 8;
        btn(A_SFX, new StringBuffer(L("Efectos: ")).append(sfxLv * 20).append('%').toString(), btnX, y, btnW, btnH);
        y += btnH + 8;
        txt("4 / 6 o 5: cambiar", sw / 2, y, 0xDDDDDD, Graphics.TOP | Graphics.HCENTER);
        y += font.getHeight();
        if (inWorld) {
            txt("Mas lejos = mas lento", sw / 2, y, 0xDDDDDD, Graphics.TOP | Graphics.HCENTER);
            y += font.getHeight();
        }
        y += 8;
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
        txt(touch ? "Toca: reaparecer" : "5: reaparecer", sw / 2, y + 28, 0xFFFFFF, Graphics.TOP | Graphics.HCENTER);
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
            } else if (state == ST_KEYS) {
                paintKeys();
            } else if (state == ST_LANG) {
                paintLang();
            } else if (state == ST_END) {
                paintEnd();
            } else if (state == ST_INV) {
                if (!slActive[OUT]) layoutInv();
                paintInv();
            } else {
                paintWorld();
                paintHud();
                if (dbgOn) paintDebug();
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

// ===================================================================== SONIDO
// Todo el audio se genera por codigo (el JAR no lleva archivos de sonido): los efectos son WAV sintetizados
// y la musica es un MIDI compuesto al vuelo (cada tema es distinto). Un hilo propio crea y reproduce los
// sonidos, asi el juego nunca se frena. Si el telefono no tiene MMAPI el juego sigue sin sonido.
class Sfx implements Runnable {
    // grupos de material (rotura / colocado / golpeo al picar / paso)
    static final int G_STONE = 0, G_DIRT = 1, G_GRASS = 2, G_WOOD = 3, G_SAND = 4, G_METAL = 5, G_GLASS = 6;
    static final int K_BREAK = 0, K_PLACE = 1, K_HIT = 2, K_STEP = 3;
    // sonidos sueltos
    static final int S_CLICK = 100, S_POP = 101, S_EAT = 102, S_HURT = 103, S_DEATH = 104, S_FALL = 105, S_SPLASH = 106,
            S_DOOR_O = 107, S_DOOR_C = 108, S_CHEST_O = 109, S_CHEST_C = 110, S_FUSE = 111, S_EXPLODE = 112,
            S_PORTAL = 113, S_TRAVEL = 114, S_IGNITE = 115, S_END_ON = 116, S_EYE = 117, S_BUCKET_F = 118,
            S_BUCKET_E = 119, S_CAST = 120, S_BITE = 121, S_CHIME = 122, S_CRACKLE = 123, S_CAVE = 124,
            S_NETHER = 125, S_END_AMB = 126, S_HIT = 127, S_BURN = 128, S_LAVA = 129;
    static final int NS = 130, POOL = 18;

    static int blk(int g, int k) { return g * 4 + k; }
    // sonidos de mob: kind 0 = idle, 1 = herido, 2 = muerte, 3 = especial (creeper: mecha, enderman: teletransporte,
    // blaze: disparo, dragon: aleteo, aldeano: trato)
    static int mob(int type, int kind) { return 32 + type * 4 + kind; }

    static final Object lk = new Object();
    static boolean alive, paused, volDirty;
    static int gen;
    static int[] qId = new int[64], qVol = new int[64];
    static int qh, qt;
    static long[] lastT = new long[NS];
    static int musLv = 60, sfxLv = 100;   // 0..100
    static int wantCtx;                   // 0 dia, 1 noche, 2 nether, 3 end
    static int flip;
    static Random rng = new Random();

    // estado del hilo de sonido
    int myGen;
    Player[] pl = new Player[NS * 2];
    long[] used = new long[NS * 2];
    int nLive, wavFail, musCtx = -1, musFail;
    Player mus;
    long musEnd, gapUntil, pausedAt;
    boolean stoppedForPause, musDead;
    String wavType = "audio/x-wav";

    // ---------------------------------------------------------------- API (hilo del juego)
    static void begin() {
        synchronized (lk) {
            if (alive) return;
            alive = true;
            qh = 0; qt = 0;
            paused = false;
            gen++;
            Sfx s = new Sfx();
            s.myGen = gen;
            try {
                new Thread(s).start();
            } catch (Throwable t) { alive = false; }
        }
    }

    static void end() {
        synchronized (lk) { alive = false; lk.notifyAll(); }
    }

    static void pause(boolean p) {
        synchronized (lk) { paused = p; lk.notifyAll(); }
    }

    static void setLevels(int music, int sfx) {
        synchronized (lk) { musLv = music; sfxLv = sfx; volDirty = true; lk.notifyAll(); }
    }

    static void ctx(int c) { wantCtx = c; }

    static void play(int id) { play(id, 100); }

    // vol 0..100 (se multiplica por el volumen de efectos de Ajustes)
    static void play(int id, int vol) {
        if (id < 0 || id >= NS || vol <= 0) return;
        int lv = vol * sfxLv / 100;
        if (lv < 4) return;
        long now = System.currentTimeMillis();
        synchronized (lk) {
            if (!alive) return;
            if (now - lastT[id] < 90) return;   // evita ametrallar el mismo sonido
            lastT[id] = now;
            int nt = (qt + 1) & 63;
            if (nt == qh) return;
            qId[qt] = id; qVol[qt] = lv; qt = nt;
            lk.notify();
        }
    }

    // ---------------------------------------------------------------- hilo de sonido
    public void run() {
        while (true) {
            int id = -1, vol = 0;
            synchronized (lk) {
                if (!alive || myGen != gen) break;
                if (qh == qt) { try { lk.wait(250); } catch (InterruptedException e) {} }
                if (!alive || myGen != gen) break;
                if (qh != qt) { id = qId[qh]; vol = qVol[qh]; qh = (qh + 1) & 63; }
            }
            try {
                if (id >= 0) playNow(id, vol);
                tickMusic();
            } catch (Throwable t) {}
        }
        stopMusic();
        for (int k = 0; k < pl.length; k++) drop(k);
    }

    void playNow(int id, int vol) {
        if (wavFail >= 4) return;
        int var = (id < 28 && (id & 3) != K_PLACE) ? (flip++ & 1) : 0;   // dos variantes: pasos y golpes no suenan siempre igual
        int key = id * 2 + var;
        Player p = pl[key];
        if (p == null) {
            p = makeWav(id, var);
            if (p == null) { wavFail++; return; }
            wavFail = 0;
            if (nLive >= POOL) evict();
            pl[key] = p;
            nLive++;
        }
        used[key] = System.currentTimeMillis();
        try {
            if (p.getState() == Player.STARTED) p.stop();
            setVol(p, vol);
            p.setMediaTime(0);
            p.start();
        } catch (Throwable t) { drop(key); }
    }

    Player makeWav(int id, int var) {
        byte[] w = Synth.wav(id, var);
        Player p = null;
        String[] types = { wavType, "audio/x-wav", "audio/wav" };
        for (int i = 0; i < types.length && p == null; i++) {
            try {
                p = Manager.createPlayer(new ByteArrayInputStream(w), types[i]);
                p.realize();
                p.prefetch();
                wavType = types[i];
            } catch (Throwable t) {
                if (p != null) { try { p.close(); } catch (Throwable t2) {} }
                p = null;
            }
        }
        return p;
    }

    void evict() {
        int best = -1;
        long bt = Long.MAX_VALUE;
        for (int k = 0; k < pl.length; k++) if (pl[k] != null && used[k] < bt) { bt = used[k]; best = k; }
        if (best >= 0) drop(best);
    }

    void drop(int k) {
        Player p = pl[k];
        pl[k] = null;
        if (p == null) return;
        nLive--;
        try { p.close(); } catch (Throwable t) {}
    }

    static void setVol(Player p, int lv) {
        try {
            VolumeControl vc = (VolumeControl) p.getControl("VolumeControl");
            if (vc != null) vc.setLevel(lv);
        } catch (Throwable t) {}
    }

    // ---------------------------------------------------------------- musica
    // cada tema suena una vez (1-2 min), hay un silencio de 8-23 s y se compone otro; al cambiar de dimension se corta
    void tickMusic() {
        long now = System.currentTimeMillis();
        boolean pz, vd;
        synchronized (lk) { pz = paused; vd = volDirty; volDirty = false; }
        if (pz) {
            if (!stoppedForPause) {
                stoppedForPause = true;
                pausedAt = now;
                if (mus != null) { try { mus.stop(); } catch (Throwable t) {} }
            }
            return;
        }
        if (stoppedForPause) {
            stoppedForPause = false;
            long dt = now - pausedAt;
            musEnd += dt;
            gapUntil += dt;
            if (mus != null) { try { mus.start(); } catch (Throwable t) { stopMusic(); } }
        }
        if (vd && mus != null) setVol(mus, musLv);
        if (musLv <= 0 || musDead) { if (mus != null) stopMusic(); return; }
        int w = wantCtx;
        if (mus != null && w != musCtx && (w >= 2 || musCtx >= 2)) { stopMusic(); gapUntil = now + 700; }
        if (mus != null && now >= musEnd) { stopMusic(); gapUntil = now + 8000 + rng.nextInt(15000); }
        if (mus == null && now >= gapUntil) startMusic(w);
    }

    void startMusic(int ctx) {
        long now = System.currentTimeMillis();
        byte[] m = Mus.midi(ctx, now ^ (ctx * 7919L));
        long ms = Mus.lastMs;
        Player p = null;
        try {
            p = Manager.createPlayer(new ByteArrayInputStream(m), "audio/midi");
            p.realize();
            p.prefetch();
        } catch (Throwable t) {
            if (p != null) { try { p.close(); } catch (Throwable t2) {} }
            p = null;
            try {   // sin MIDI: secuencia de tonos (una sola voz)
                p = Manager.createPlayer(Manager.TONE_DEVICE_LOCATOR);
                p.realize();
                ToneControl tc = (ToneControl) p.getControl("ToneControl");
                tc.setSequence(Mus.tone());
                p.prefetch();
            } catch (Throwable t3) {
                if (p != null) { try { p.close(); } catch (Throwable t4) {} }
                p = null;
            }
        }
        if (p == null) {
            if (++musFail >= 3) musDead = true;
            gapUntil = now + 5000;
            return;
        }
        setVol(p, musLv);
        try {
            p.start();
        } catch (Throwable t) {
            try { p.close(); } catch (Throwable t2) {}
            if (++musFail >= 3) musDead = true;
            gapUntil = now + 5000;
            return;
        }
        musFail = 0;
        mus = p;
        musCtx = ctx;
        musEnd = now + ms + 1000;
    }

    void stopMusic() {
        Player p = mus;
        mus = null;
        musCtx = -1;
        if (p == null) return;
        try { p.stop(); } catch (Throwable t) {}
        try { p.close(); } catch (Throwable t) {}
    }
}

// ===================================================================== sintesis de efectos (WAV 16 bit, 11025 Hz)
class Synth {
    static final int SR = 11025;
    static float[] b;
    static float gain, pj;
    static int seed;

    static float nz() {
        seed = seed * 1103515245 + 12345;
        return (((seed >>> 16) & 0x7fff) / 16384f) - 1f;
    }

    static float rf() { return (nz() + 1f) * 0.5f; }

    static void alloc(float sec) { b = new float[(int) (sec * SR)]; }

    // capa: t0/d = inicio y duracion (s); w = onda (0 seno, 1 cuadrada, 2 sierra, 3 triangular); f0->f1 = tono (Hz);
    // ta/na = volumen del tono / del ruido; lp > 0 pasa-bajos, lp < 0 pasa-altos (coeficiente 0..1);
    // att = ataque (s); pw = caida (0 = sostenido, mayor = mas seco); vib = vibrato
    static void lay(float t0, float d, int w, float f0, float f1, float ta, float na, float lp, float att, int pw, float vib) {
        int s0 = (int) (t0 * SR), n = (int) (d * SR);
        if (n < 2) return;
        boolean hp = lp < 0f;
        float c = hp ? -lp : lp;
        float ph = 0f, y = 0f;
        f0 *= pj; f1 *= pj;
        int an = (int) (att * SR);
        for (int i = 0; i < n; i++) {
            int bi = s0 + i;
            if (bi >= b.length) break;
            float t = (float) i / n;
            float x = 0f;
            if (ta != 0f) {
                float f = f0 + (f1 - f0) * t;
                if (vib != 0f) f *= 1f + vib * (float) Math.sin(i * 0.0034f);
                ph += f * 6.2832f / SR;
                if (ph > 6.2832f) ph -= 6.2832f;
                float s;
                if (w == 0) s = (float) Math.sin(ph);
                else if (w == 1) s = (ph < 3.1416f) ? 0.6f : -0.6f;
                else if (w == 2) s = ph / 3.1416f - 1f;
                else s = (ph < 3.1416f) ? (ph / 1.5708f - 1f) : (3f - ph / 1.5708f);
                x = s * ta;
            }
            if (na != 0f) x += nz() * na;
            if (c < 1f) { y += c * (x - y); x = hp ? x - y : y; }
            float e = 1f - t;
            float env = 1f;
            for (int k = 0; k < pw; k++) env *= e;
            if (i < an) env *= (float) i / an;
            if (e < 0.08f) env *= e / 0.08f;
            b[bi] += x * env;
        }
    }

    static byte[] wav(int id, int var) {
        seed = id * 7919 + var * 104729 + 17;
        pj = (var == 0) ? 1f : 1.09f;
        gain = 1f;
        b = null;
        if (id < 28) block(id >> 2, id & 3);
        else if (id >= 32 && id < 92) mobSnd((id - 32) >> 2, (id - 32) & 3);
        else misc(id);
        if (b == null) alloc(0.05f);
        int n = b.length;
        float pk = 0.0001f;
        for (int i = 0; i < n; i++) { float a = b[i] < 0f ? -b[i] : b[i]; if (a > pk) pk = a; }
        float sc = 0.9f / pk * gain;
        byte[] w = new byte[44 + n * 2];
        int dl = n * 2;
        put(w, 0, 0x52); put(w, 1, 0x49); put(w, 2, 0x46); put(w, 3, 0x46);            // RIFF
        put32(w, 4, 36 + dl);
        put(w, 8, 0x57); put(w, 9, 0x41); put(w, 10, 0x56); put(w, 11, 0x45);          // WAVE
        put(w, 12, 0x66); put(w, 13, 0x6d); put(w, 14, 0x74); put(w, 15, 0x20);        // fmt
        put32(w, 16, 16);
        put(w, 20, 1); put(w, 21, 0);          // PCM
        put(w, 22, 1); put(w, 23, 0);          // mono
        put32(w, 24, SR);
        put32(w, 28, SR * 2);
        put(w, 32, 2); put(w, 33, 0);          // alineacion
        put(w, 34, 16); put(w, 35, 0);         // 16 bits
        put(w, 36, 0x64); put(w, 37, 0x61); put(w, 38, 0x74); put(w, 39, 0x61);        // data
        put32(w, 40, dl);
        for (int i = 0; i < n; i++) {
            float v = b[i] * sc;
            int fade = n - i;
            if (fade < 40) v *= fade / 40f;
            int s = (int) (v * 32000f);
            if (s > 32767) s = 32767;
            if (s < -32768) s = -32768;
            w[44 + i * 2] = (byte) (s & 0xFF);
            w[45 + i * 2] = (byte) ((s >> 8) & 0xFF);
        }
        b = null;
        return w;
    }

    static void put(byte[] w, int i, int v) { w[i] = (byte) v; }
    static void put32(byte[] w, int i, int v) {
        w[i] = (byte) (v & 0xFF); w[i + 1] = (byte) ((v >> 8) & 0xFF); w[i + 2] = (byte) ((v >> 16) & 0xFF); w[i + 3] = (byte) ((v >> 24) & 0xFF);
    }

    // ---------------------------------------------------------------- bloques
    static void block(int g, int k) {
        float d = (k == 0) ? 0.26f : (k == 1 ? 0.14f : (k == 2 ? 0.10f : 0.075f));
        float pk = (k == 1) ? 0.8f : 1f;
        gain = (k == 0) ? 1f : (k == 1 ? 0.9f : (k == 2 ? 0.7f : 0.45f));
        alloc(d + 0.16f);
        switch (g) {
            case 0:   // piedra: crujido seco + "toc" grave
                lay(0f, d, 4, 0, 0, 0f, 1f, 0.55f, 0f, 3, 0f);
                lay(0f, d * 0.8f, 0, 150f * pk, 90f * pk, 0.8f, 0f, 1f, 0f, 3, 0f);
                if (k == 0) lay(0.05f, 0.14f, 4, 0, 0, 0f, 0.8f, 0.3f, 0f, 2, 0f);
                break;
            case 1:   // tierra / grava fina: golpe sordo
                lay(0f, d, 4, 0, 0, 0f, 1.4f, 0.12f, 0f, 2, 0f);
                lay(0f, d * 0.9f, 0, 95f * pk, 60f * pk, 0.9f, 0f, 1f, 0f, 3, 0f);
                if (k == 0) lay(0.06f, 0.12f, 4, 0, 0, 0f, 1f, 0.15f, 0f, 2, 0f);
                break;
            case 2:   // pasto / hojas / plantas: roce suave
                lay(0f, d * 1.1f, 4, 0, 0, 0f, 1f, -0.28f, 0.015f, 1, 0f);
                if (k == 0) lay(0.07f, 0.14f, 4, 0, 0, 0f, 0.7f, -0.22f, 0.01f, 1, 0f);
                break;
            case 3:   // madera: toc hueco
                lay(0f, d, 0, 210f * pk, 130f * pk, 1f, 0f, 1f, 0f, 3, 0f);
                lay(0f, d * 0.6f, 4, 0, 0, 0f, 0.5f, 0.25f, 0f, 3, 0f);
                if (k == 0) { lay(0.07f, 0.12f, 0, 170f, 100f, 0.9f, 0f, 1f, 0f, 3, 0f); lay(0.07f, 0.08f, 4, 0, 0, 0f, 0.5f, 0.25f, 0f, 3, 0f); }
                break;
            case 4:   // arena / nieve / grava: siseo granulado
                lay(0f, d * 1.2f, 4, 0, 0, 0f, 1f, 0.5f, 0.01f, 1, 0f);
                lay(0f, d * 0.6f, 4, 0, 0, 0f, 0.5f, -0.4f, 0f, 2, 0f);
                break;
            case 5:   // metal: golpe + campanilla
                lay(0f, d * 0.5f, 4, 0, 0, 0f, 0.6f, 0.5f, 0f, 3, 0f);
                lay(0f, d + 0.1f, 0, 1100f * pk, 1060f * pk, 0.5f, 0f, 1f, 0f, 3, 0f);
                lay(0f, d + 0.08f, 0, 1650f * pk, 1600f * pk, 0.3f, 0f, 1f, 0f, 2, 0f);
                break;
            default:  // vidrio / hielo: tintineo
                lay(0f, d * 0.7f, 4, 0, 0, 0f, 0.7f, -0.6f, 0f, 3, 0f);
                lay(0f, d + 0.1f, 0, 2400f * pk, 2380f * pk, 0.45f, 0f, 1f, 0f, 3, 0f);
                lay(0f, d + 0.06f, 0, 3300f * pk, 3280f * pk, 0.35f, 0f, 1f, 0f, 2, 0f);
                if (k == 0) for (int i = 1; i < 6; i++) lay(i * 0.025f, 0.06f, 0, 2600f + rf() * 2200f, 2400f + rf() * 2000f, 0.3f, 0.3f, -0.5f, 0f, 3, 0f);
                break;
        }
    }

    // ---------------------------------------------------------------- mobs (0 oveja, 1 pollo, 2 vaca, 3 zombie, 4 creeper, 6 aldeano,
    // 10 arana, 12 enderman, 13 blaze, 14 dragon)
    static void mobSnd(int t, int k) {
        switch (t) {
            case 0:   // oveja: "beee"
                if (k == 0) { alloc(0.6f); lay(0f, 0.5f, 2, 330f, 270f, 0.55f, 0.06f, 0.3f, 0.03f, 1, 0.07f); lay(0f, 0.5f, 2, 339f, 276f, 0.45f, 0f, 0.3f, 0.03f, 1, 0.07f); }
                else if (k == 1) { alloc(0.35f); lay(0f, 0.28f, 2, 430f, 340f, 0.6f, 0.05f, 0.3f, 0.01f, 1, 0.07f); lay(0f, 0.28f, 2, 440f, 346f, 0.45f, 0f, 0.3f, 0.01f, 1, 0.07f); }
                else { alloc(0.7f); lay(0f, 0.6f, 2, 320f, 140f, 0.55f, 0.05f, 0.3f, 0.01f, 1, 0.08f); lay(0f, 0.6f, 2, 328f, 144f, 0.45f, 0f, 0.3f, 0.01f, 1, 0.08f); }
                break;
            case 1:   // pollo: "pio / kok"
                if (k == 0) { alloc(0.3f); lay(0f, 0.07f, 1, 1100f, 800f, 0.35f, 0.08f, 0.5f, 0.004f, 1, 0f); lay(0.11f, 0.08f, 1, 1000f, 700f, 0.35f, 0.08f, 0.5f, 0.004f, 1, 0f); }
                else if (k == 1) { alloc(0.35f); lay(0f, 0.28f, 1, 1500f, 900f, 0.3f, 0.2f, 0.6f, 0.004f, 1, 0.2f); }
                else { alloc(0.45f); lay(0f, 0.4f, 1, 1300f, 500f, 0.3f, 0.2f, 0.6f, 0.004f, 1, 0.15f); }
                break;
            case 2:   // vaca: "muuu"
                if (k == 0) { alloc(0.9f); lay(0f, 0.8f, 2, 115f, 92f, 0.7f, 0.05f, 0.14f, 0.1f, 1, 0.06f); lay(0f, 0.8f, 0, 230f, 185f, 0.3f, 0f, 1f, 0.1f, 1, 0.06f); }
                else if (k == 1) { alloc(0.5f); lay(0f, 0.4f, 2, 160f, 115f, 0.7f, 0.08f, 0.16f, 0.02f, 1, 0.06f); }
                else { alloc(0.9f); lay(0f, 0.8f, 2, 135f, 55f, 0.7f, 0.08f, 0.14f, 0.03f, 1, 0.07f); }
                break;
            case 3:   // zombie: gemido grave
                if (k == 0) { alloc(0.9f); lay(0f, 0.8f, 2, 90f, 65f, 0.7f, 0.12f, 0.12f, 0.15f, 1, 0.08f); lay(0f, 0.8f, 2, 93f, 68f, 0.5f, 0f, 0.12f, 0.15f, 1, 0.08f); }
                else if (k == 1) { alloc(0.35f); lay(0f, 0.28f, 2, 140f, 80f, 0.7f, 0.2f, 0.2f, 0.01f, 1, 0.05f); }
                else { alloc(1f); lay(0f, 0.9f, 2, 100f, 40f, 0.7f, 0.15f, 0.15f, 0.02f, 1, 0.1f); }
                break;
            case 4:   // creeper: siseo
                if (k == 1) { alloc(0.35f); lay(0f, 0.3f, 4, 0, 0, 0f, 0.9f, -0.35f, 0f, 1, 0f); lay(0f, 0.3f, 1, 200f, 100f, 0.2f, 0f, 0.4f, 0f, 1, 0f); }
                else if (k == 2) { alloc(0.4f); lay(0f, 0.35f, 4, 0, 0, 0f, 0.9f, -0.3f, 0f, 1, 0f); }
                else if (k == 3) { alloc(1.2f); lay(0f, 1.1f, 4, 0, 0, 0f, 1f, -0.4f, 0.25f, 0, 0f); }
                else alloc(0.05f);
                break;
            case 6:   // aldeano: "hmmm"
                if (k == 0) { alloc(0.6f); lay(0f, 0.22f, 2, 175f, 205f, 0.5f, 0.05f, 0.25f, 0.02f, 1, 0.04f); lay(0.27f, 0.25f, 2, 170f, 145f, 0.5f, 0.05f, 0.25f, 0.02f, 1, 0.04f); }
                else if (k == 1) { alloc(0.4f); lay(0f, 0.3f, 2, 230f, 150f, 0.5f, 0.1f, 0.3f, 0.01f, 1, 0.05f); }
                else if (k == 2) { alloc(0.55f); lay(0f, 0.45f, 2, 200f, 100f, 0.5f, 0.1f, 0.3f, 0.01f, 1, 0.05f); }
                else { alloc(0.5f); lay(0f, 0.18f, 2, 180f, 240f, 0.5f, 0.04f, 0.25f, 0.01f, 1, 0.03f); lay(0.22f, 0.22f, 2, 200f, 290f, 0.5f, 0.04f, 0.25f, 0.01f, 1, 0.03f); }
                break;
            case 10:  // arana: siseo y chasquidos
                if (k == 0) { alloc(0.5f); lay(0f, 0.4f, 4, 0, 0, 0f, 0.7f, -0.5f, 0.02f, 2, 0f); lay(0.05f, 0.03f, 1, 2400f, 2300f, 0.3f, 0f, 1f, 0f, 1, 0f); lay(0.15f, 0.03f, 1, 2500f, 2300f, 0.3f, 0f, 1f, 0f, 1, 0f); }
                else if (k == 1) { alloc(0.35f); lay(0f, 0.3f, 4, 0, 0, 0f, 0.8f, -0.45f, 0f, 2, 0f); lay(0f, 0.25f, 1, 420f, 250f, 0.3f, 0f, 0.5f, 0f, 1, 0f); }
                else { alloc(0.55f); lay(0f, 0.5f, 4, 0, 0, 0f, 0.8f, -0.4f, 0f, 1, 0f); lay(0f, 0.5f, 1, 360f, 120f, 0.3f, 0f, 0.5f, 0f, 1, 0f); }
                break;
            case 12:  // enderman
                if (k == 0) { alloc(0.7f); lay(0f, 0.6f, 0, 350f, 950f, 0.5f, 0.05f, 1f, 0.12f, 1, 0.08f); lay(0f, 0.6f, 0, 700f, 1900f, 0.2f, 0f, 1f, 0.12f, 1, 0.08f); }
                else if (k == 1) { alloc(0.6f); lay(0f, 0.5f, 2, 900f, 1700f, 0.5f, 0.3f, 0.5f, 0.02f, 1, 0.15f); lay(0f, 0.5f, 1, 1300f, 2300f, 0.2f, 0f, 0.6f, 0.02f, 1, 0.15f); }
                else if (k == 2) { alloc(0.85f); lay(0f, 0.75f, 2, 1600f, 350f, 0.5f, 0.3f, 0.5f, 0.02f, 1, 0.15f); }
                else { alloc(0.45f); lay(0f, 0.32f, 0, 2000f, 250f, 0.7f, 0.1f, 1f, 0.004f, 2, 0f); lay(0f, 0.15f, 4, 0, 0, 0f, 0.6f, -0.4f, 0.004f, 2, 0f); }
                break;
            case 13:  // blaze
                if (k == 0) { alloc(1f); lay(0f, 0.9f, 4, 0, 0, 0f, 0.8f, 0.18f, 0.3f, 1, 0f); lay(0f, 0.9f, 0, 90f, 80f, 0.25f, 0f, 1f, 0.3f, 1, 0f); }
                else if (k == 1) { alloc(0.4f); lay(0f, 0.3f, 4, 0, 0, 0f, 0.9f, -0.3f, 0f, 1, 0f); lay(0f, 0.3f, 1, 700f, 350f, 0.25f, 0f, 0.5f, 0f, 1, 0f); }
                else if (k == 2) { alloc(0.8f); lay(0f, 0.7f, 4, 0, 0, 0f, 0.9f, 0.25f, 0.02f, 1, 0f); lay(0f, 0.7f, 1, 800f, 150f, 0.25f, 0f, 0.5f, 0f, 1, 0f); }
                else { alloc(0.55f); lay(0f, 0.45f, 4, 0, 0, 0f, 0.9f, 0.35f, 0.08f, 1, 0f); lay(0f, 0.45f, 0, 250f, 700f, 0.35f, 0f, 1f, 0.05f, 1, 0f); }
                break;
            case 14:  // ender dragon
                if (k == 0) { alloc(1.4f); lay(0f, 1.3f, 2, 65f, 50f, 0.7f, 0.1f, 0.1f, 0.2f, 1, 0.1f); lay(0f, 1.3f, 2, 68f, 52f, 0.5f, 0f, 0.1f, 0.2f, 1, 0.1f); lay(0f, 1.3f, 4, 0, 0, 0f, 0.8f, 0.07f, 0.2f, 1, 0f); }
                else if (k == 1) { alloc(0.8f); lay(0f, 0.7f, 2, 130f, 70f, 0.7f, 0.3f, 0.15f, 0.02f, 1, 0.08f); }
                else if (k == 2) { alloc(2.7f); lay(0f, 2.6f, 2, 110f, 28f, 0.7f, 0.3f, 0.12f, 0.05f, 1, 0.1f); lay(0f, 2.6f, 4, 0, 0, 0f, 0.8f, 0.07f, 0.1f, 1, 0f); }
                else { alloc(0.8f); lay(0f, 0.7f, 4, 0, 0, 0f, 1.3f, 0.06f, 0.25f, 1, 0f); lay(0f, 0.7f, 0, 55f, 45f, 0.5f, 0f, 1f, 0.2f, 1, 0f); }
                break;
            default:
                alloc(0.05f);
                break;
        }
    }

    // ---------------------------------------------------------------- sonidos sueltos
    static void misc(int id) {
        switch (id) {
            case Sfx.S_CLICK: alloc(0.07f); gain = 0.55f; lay(0f, 0.05f, 0, 1500f, 800f, 0.6f, 0.1f, 1f, 0f, 3, 0f); break;
            case Sfx.S_POP: alloc(0.14f); gain = 0.6f; lay(0f, 0.1f, 0, 400f, 1100f, 0.7f, 0f, 1f, 0f, 2, 0f); break;
            case Sfx.S_EAT:
                alloc(0.55f); gain = 0.8f;
                for (int i = 0; i < 3; i++) { lay(i * 0.14f, 0.09f, 4, 0, 0, 0f, 1f, 0.4f, 0f, 2, 0f); lay(i * 0.14f, 0.07f, 0, 120f, 80f, 0.5f, 0f, 1f, 0f, 2, 0f); }
                break;
            case Sfx.S_HURT: alloc(0.32f); lay(0f, 0.26f, 2, 230f, 140f, 0.6f, 0.3f, 0.25f, 0.005f, 2, 0.03f); break;
            case Sfx.S_DEATH: alloc(0.65f); lay(0f, 0.58f, 2, 220f, 70f, 0.6f, 0.25f, 0.25f, 0.01f, 1, 0.05f); break;
            case Sfx.S_FALL: alloc(0.35f); lay(0f, 0.28f, 4, 0, 0, 0f, 1.3f, 0.15f, 0f, 3, 0f); lay(0f, 0.25f, 0, 75f, 40f, 1f, 0f, 1f, 0f, 2, 0f); break;
            case Sfx.S_SPLASH: alloc(0.65f); lay(0f, 0.55f, 4, 0, 0, 0f, 1f, 0.3f, 0.05f, 2, 0f); lay(0f, 0.28f, 4, 0, 0, 0f, 0.6f, -0.3f, 0.01f, 2, 0f); break;
            case Sfx.S_DOOR_O: alloc(0.4f); gain = 0.8f; lay(0f, 0.3f, 2, 160f, 260f, 0.3f, 0.15f, 0.3f, 0.02f, 1, 0.03f); lay(0.25f, 0.08f, 0, 200f, 120f, 0.6f, 0f, 1f, 0f, 3, 0f); break;
            case Sfx.S_DOOR_C: alloc(0.3f); gain = 0.85f; lay(0f, 0.1f, 2, 240f, 170f, 0.25f, 0.1f, 0.3f, 0.01f, 1, 0.03f); lay(0.08f, 0.1f, 0, 180f, 90f, 0.9f, 0.5f, 0.4f, 0f, 3, 0f); break;
            case Sfx.S_CHEST_O: alloc(0.55f); gain = 0.8f; lay(0f, 0.4f, 2, 110f, 210f, 0.35f, 0.2f, 0.3f, 0.03f, 1, 0.04f); lay(0.4f, 0.06f, 0, 600f, 300f, 0.5f, 0.2f, 1f, 0f, 3, 0f); break;
            case Sfx.S_CHEST_C: alloc(0.25f); gain = 0.85f; lay(0f, 0.15f, 0, 150f, 80f, 0.9f, 0.4f, 0.3f, 0f, 3, 0f); lay(0.12f, 0.06f, 0, 600f, 300f, 0.4f, 0.2f, 1f, 0f, 3, 0f); break;
            case Sfx.S_FUSE: alloc(1.5f); gain = 0.8f; lay(0f, 1.45f, 4, 0, 0, 0f, 1f, -0.3f, 0.1f, 0, 0f); break;
            case Sfx.S_EXPLODE:
                alloc(1.5f); gain = 1.1f;
                lay(0f, 1.4f, 4, 0, 0, 0f, 1.2f, 0.12f, 0.005f, 2, 0f);
                lay(0f, 0.9f, 0, 70f, 25f, 1f, 0f, 1f, 0.004f, 2, 0f);
                lay(0f, 0.5f, 4, 0, 0, 0f, 0.8f, 0.45f, 0.002f, 3, 0f);
                break;
            case Sfx.S_PORTAL: alloc(1.7f); gain = 0.8f; lay(0f, 1.6f, 0, 220f, 330f, 0.45f, 0f, 1f, 0.4f, 1, 0.2f); lay(0f, 1.6f, 4, 0, 0, 0f, 0.7f, 0.06f, 0.5f, 1, 0f); break;
            case Sfx.S_TRAVEL: alloc(1.3f); gain = 0.9f; lay(0f, 1.2f, 0, 150f, 900f, 0.35f, 0.2f, 1f, 0.4f, 1, 0.1f); lay(0f, 1.2f, 4, 0, 0, 0f, 0.9f, 0.12f, 0.4f, 1, 0f); break;
            case Sfx.S_IGNITE: alloc(0.55f); lay(0f, 0.03f, 4, 0, 0, 0f, 1f, 0.8f, 0f, 3, 0f); lay(0.05f, 0.45f, 4, 0, 0, 0f, 0.9f, 0.25f, 0.08f, 2, 0f); break;
            case Sfx.S_END_ON: alloc(1.9f); gain = 0.9f; lay(0f, 1.8f, 0, 200f, 800f, 0.5f, 0f, 1f, 0.3f, 1, 0.08f); lay(0.2f, 1.6f, 0, 300f, 1200f, 0.3f, 0f, 1f, 0.3f, 1, 0.08f); lay(0f, 1.8f, 4, 0, 0, 0f, 0.6f, 0.08f, 0.5f, 1, 0f); break;
            case Sfx.S_EYE: alloc(0.55f); lay(0f, 0.45f, 4, 0, 0, 0f, 0.8f, 0.5f, 0.15f, 1, 0f); lay(0f, 0.4f, 0, 1200f, 2000f, 0.3f, 0f, 1f, 0.05f, 2, 0f); break;
            case Sfx.S_BUCKET_F: alloc(0.55f); lay(0f, 0.45f, 4, 0, 0, 0f, 0.9f, 0.2f, 0.05f, 2, 0f); lay(0f, 0.4f, 0, 300f, 520f, 0.3f, 0f, 1f, 0.05f, 2, 0.2f); break;
            case Sfx.S_BUCKET_E: alloc(0.7f); lay(0f, 0.6f, 4, 0, 0, 0f, 0.9f, 0.3f, 0.1f, 1, 0f); lay(0f, 0.5f, 0, 500f, 280f, 0.25f, 0f, 1f, 0.05f, 2, 0.2f); break;
            case Sfx.S_CAST: alloc(0.4f); gain = 0.8f; lay(0f, 0.35f, 4, 0, 0, 0f, 0.8f, -0.2f, 0.05f, 1, 0f); break;
            case Sfx.S_BITE: alloc(0.3f); lay(0f, 0.15f, 0, 500f, 200f, 0.9f, 0.1f, 1f, 0f, 3, 0f); lay(0f, 0.12f, 4, 0, 0, 0f, 0.5f, 0.3f, 0f, 3, 0f); break;
            case Sfx.S_CHIME:
                alloc(1f); gain = 0.7f;
                lay(0f, 0.8f, 0, 880f, 880f, 0.5f, 0f, 1f, 0.002f, 2, 0f);
                lay(0.1f, 0.7f, 0, 1320f, 1320f, 0.3f, 0f, 1f, 0.002f, 2, 0f);
                lay(0.22f, 0.7f, 0, 1760f, 1760f, 0.25f, 0f, 1f, 0.002f, 2, 0f);
                break;
            case Sfx.S_CRACKLE:
                alloc(0.6f); gain = 0.8f;
                for (int i = 0; i < 9; i++) lay(rf() * 0.5f, 0.025f, 4, 0, 0, 0f, 1f, 0.5f, 0f, 2, 0f);
                break;
            case Sfx.S_CAVE: alloc(2.3f); gain = 0.7f; lay(0f, 2.2f, 4, 0, 0, 0f, 0.8f, 0.04f, 0.6f, 1, 0f); lay(0f, 2.2f, 0, 55f, 45f, 0.5f, 0f, 1f, 0.6f, 1, 0.1f); break;
            case Sfx.S_NETHER:
                alloc(2.7f); gain = 0.7f;
                lay(0f, 2.6f, 0, 70f, 58f, 0.5f, 0f, 1f, 0.7f, 1, 0.2f);
                lay(0f, 2.6f, 4, 0, 0, 0f, 0.7f, 0.03f, 0.7f, 1, 0f);
                lay(0.5f, 1.6f, 0, 140f, 120f, 0.2f, 0f, 1f, 0.6f, 1, 0.3f);
                break;
            case Sfx.S_END_AMB:
                alloc(2.7f); gain = 0.6f;
                lay(0f, 2.6f, 0, 400f, 430f, 0.25f, 0f, 1f, 0.7f, 1, 0.3f);
                lay(0.3f, 2.1f, 0, 610f, 590f, 0.2f, 0f, 1f, 0.7f, 1, 0.3f);
                break;
            case Sfx.S_HIT: alloc(0.16f); lay(0f, 0.08f, 4, 0, 0, 0f, 1f, 0.5f, 0f, 3, 0f); lay(0f, 0.1f, 0, 160f, 90f, 0.8f, 0f, 1f, 0f, 3, 0f); break;
            case Sfx.S_BURN: alloc(0.35f); lay(0f, 0.3f, 4, 0, 0, 0f, 1f, -0.25f, 0.01f, 2, 0f); break;
            case Sfx.S_LAVA: alloc(0.35f); lay(0f, 0.12f, 0, 150f, 350f, 0.8f, 0.2f, 0.5f, 0f, 3, 0f); lay(0f, 0.25f, 4, 0, 0, 0f, 0.7f, 0.3f, 0f, 2, 0f); break;
            default: alloc(0.05f); break;
        }
    }
}

// ===================================================================== musica: composicion MIDI al vuelo
class Mus {
    static final int RES = 96;   // ticks por negra
    static int[] et = new int[2400], ek = new int[2400];
    static int en, endTick, bpm;
    static long lastMs;
    static final int[] MAJ = { 0, 2, 4, 5, 7, 9, 11 }, MIN = { 0, 2, 3, 5, 7, 8, 10 },
            PHR = { 0, 1, 3, 5, 7, 8, 10 }, LYD = { 0, 2, 4, 6, 7, 9, 11 };
    static final int[][] P_DAY = { { 0, 5, 3, 4 }, { 0, 3, 4, 3 }, { 0, 5, 1, 4 }, { 5, 3, 0, 4 }, { 0, 2, 5, 4 } };
    static final int[][] P_NIGHT = { { 0, 5, 2, 6 }, { 0, 3, 6, 4 }, { 0, 6, 5, 6 }, { 0, 2, 3, 4 } };
    static final int[][] P_NETHER = { { 0, 1, 0, 6 }, { 0, 5, 1, 0 }, { 0, 3, 1, 0 } };
    static final int[][] P_END = { { 0, 1, 4, 0 }, { 0, 3, 1, 4 }, { 0, 4, 0, 5 } };

    static void ev(int tick, int st, int d1, int d2) {
        if (en >= et.length) return;
        et[en] = tick;
        ek[en] = (st << 16) | (d1 << 8) | d2;
        en++;
    }

    static void note(int ch, int t, int d, int p, int v) {
        if (p < 0 || p > 127 || t >= endTick) return;
        if (d < 12) d = 12;
        if (t + d > endTick) d = endTick - t;
        ev(t, 0x90 | ch, p, v);
        ev(t + d, 0x80 | ch, p, 0);
    }

    // k-esimo grado de la escala (puede ser negativo), raiz = nota MIDI de la tonica
    static int pitch(int root, int[] sc, int k) {
        int q = k / 7, m = k % 7;
        if (m < 0) { m += 7; q--; }
        return root + 12 * q + sc[m];
    }

    static byte[] midi(int ctx, long seed) {
        Random r = new Random(seed);
        en = 0;
        int[] sc = MAJ;
        int[][] prog = P_DAY;
        int root = 48, bars = 24, restP = 22, nMin = 2, nMax = 5, melLo = 7, melHi = 17, vLo = 50, vHi = 72;
        int pMel = 0, pBass = 0, pPad = 89, padV = 26, bassV = 44;
        bpm = 66;
        if (ctx == 1) {
            sc = MIN; prog = P_NIGHT; root = 45; bars = 20; restP = 40; nMin = 2; nMax = 4; melHi = 16; vLo = 44; vHi = 64;
            pPad = 49; padV = 30; bassV = 42; bpm = 52;
        } else if (ctx == 2) {
            sc = PHR; prog = P_NETHER; root = 38; bars = 16; restP = 35; nMin = 1; nMax = 3; melHi = 15; vLo = 46; vHi = 66;
            pMel = 99; pBass = 89; pPad = 94; padV = 34; bassV = 54; bpm = 42;
        } else if (ctx == 3) {
            sc = LYD; prog = P_END; root = 50; bars = 12; restP = 45; nMin = 1; nMax = 3; vLo = 40; vHi = 58;
            pMel = 98; pBass = 89; pPad = 91; padV = 30; bassV = 40; bpm = 38;
        }
        int bt = RES * 4;
        endTick = bars * bt + RES * 2;
        ev(0, 0xC0, pMel, 0); ev(0, 0xC1, pBass, 0); ev(0, 0xC2, pPad, 0);
        ev(0, 0xB0, 7, 100); ev(0, 0xB1, 7, 85); ev(0, 0xB2, 7, 90);
        ev(0, 0xB0, 91, 90); ev(0, 0xB1, 91, 70); ev(0, 0xB2, 91, 100);   // reverb
        int[] pr = prog[r.nextInt(prog.length)];
        for (int b = 0; b < bars; b++) {
            int t = b * bt;
            if (b > 0 && (b & 7) == 0) pr = prog[r.nextInt(prog.length)];
            int cd = pr[(b >> 1) % pr.length];
            if ((b & 1) == 0) {
                note(1, t, 2 * bt - 10, pitch(root, sc, cd - 7), bassV);
                if (r.nextInt(2) == 0) note(1, t + bt, bt - 10, pitch(root, sc, cd - 7 + 4), bassV - 8);
                for (int i = 0; i < 3; i++) note(2, t + i * 6, 2 * bt, pitch(root, sc, cd + 2 * i), padV);
            }
            if (r.nextInt(100) < restP) continue;
            int nn = nMin + r.nextInt(nMax - nMin + 1);
            int[] sl = new int[nn];
            boolean[] taken = new boolean[8];
            for (int i = 0; i < nn; i++) {
                int s;
                do { s = r.nextInt(8); } while (taken[s]);
                taken[s] = true;
            }
            int c = 0;
            for (int s = 0; s < 8; s++) if (taken[s]) sl[c++] = s;
            int tgt = (cd + 2 * r.nextInt(3)) % 7;
            int k = melLo + r.nextInt(melHi - melLo + 1);
            k -= (((k - tgt) % 7) + 7) % 7;
            if (k < melLo) k += 7;
            for (int i = 0; i < nn; i++) {
                int gap = ((i + 1 < nn) ? sl[i + 1] : 8) - sl[i];
                int d = gap * (RES / 2) + (RES / 2) * r.nextInt(5);
                note(0, t + sl[i] * (RES / 2), d, pitch(root, sc, k), vLo + r.nextInt(vHi - vLo + 1));
                int st = r.nextInt(5) - 2;
                if (st == 0) st = (r.nextInt(2) == 0) ? 1 : -1;
                k += st;
                if (k < melLo) k = melLo + 1;
                if (k > melHi) k = melHi - 1;
            }
        }
        // orden por tiempo (estable; los fin de nota van antes que los inicios del mismo tick)
        for (int i = 1; i < en; i++) {
            int ti = et[i], ki = ek[i];
            int key = ti * 2 + (((ki >> 16) & 0xF0) == 0x90 ? 1 : 0);
            int j = i - 1;
            while (j >= 0 && (et[j] * 2 + (((ek[j] >> 16) & 0xF0) == 0x90 ? 1 : 0)) > key) { et[j + 1] = et[j]; ek[j + 1] = ek[j]; j--; }
            et[j + 1] = ti; ek[j + 1] = ki;
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int us = 60000000 / bpm;
        body.write(0); body.write(0xFF); body.write(0x51); body.write(3);
        body.write((us >> 16) & 0xFF); body.write((us >> 8) & 0xFF); body.write(us & 0xFF);
        int last = 0;
        for (int i = 0; i < en; i++) {
            vlq(body, et[i] - last);
            last = et[i];
            int st = (ek[i] >> 16) & 0xFF;
            body.write(st);
            body.write((ek[i] >> 8) & 0xFF);
            if ((st & 0xF0) != 0xC0) body.write(ek[i] & 0xFF);
        }
        vlq(body, endTick > last ? endTick - last : 0);
        body.write(0xFF); body.write(0x2F); body.write(0);
        byte[] tr = body.toByteArray();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write('M'); o.write('T'); o.write('h'); o.write('d');
        o.write(0); o.write(0); o.write(0); o.write(6);
        o.write(0); o.write(0); o.write(0); o.write(1);
        o.write(0); o.write(RES);
        o.write('M'); o.write('T'); o.write('r'); o.write('k');
        o.write((tr.length >> 24) & 0xFF); o.write((tr.length >> 16) & 0xFF); o.write((tr.length >> 8) & 0xFF); o.write(tr.length & 0xFF);
        o.write(tr, 0, tr.length);
        lastMs = (long) endTick * us / RES / 1000;
        return o.toByteArray();
    }

    static void vlq(ByteArrayOutputStream o, int v) {
        if (v < 0) v = 0;
        int[] tmp = new int[5];
        int n = 0;
        tmp[n++] = v & 0x7F;
        v >>= 7;
        while (v > 0) { tmp[n++] = (v & 0x7F) | 0x80; v >>= 7; }
        while (n > 0) o.write(tmp[--n]);
    }

    // respaldo sin MIDI: la melodia (canal 0) de la ultima composicion como secuencia de tonos
    static byte[] tone() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(ToneControl.VERSION); o.write(1);
        o.write(ToneControl.TEMPO); o.write(Math.max(5, bpm / 4));
        o.write(ToneControl.RESOLUTION); o.write(64);
        int[] on = new int[en], pt = new int[en];
        int n = 0;
        for (int i = 0; i < en; i++) {
            int st = (ek[i] >> 16) & 0xFF;
            if (st == 0x90 && ((ek[i] & 0xFF) > 0)) { on[n] = et[i]; pt[n] = (ek[i] >> 8) & 0xFF; n++; }
        }
        int t = 0;
        for (int i = 0; i < n; i++) {
            if (on[i] > t) { silence(o, (on[i] - t) * 64 / RES); }
            int nx = (i + 1 < n) ? on[i + 1] : endTick;
            int d = (nx - on[i]) * 64 / RES;
            if (d < 1) d = 1;
            int nd = Math.min(d, 100);
            o.write(pt[i]); o.write(nd);
            if (d > nd) silence(o, d - nd);
            t = nx;
        }
        return o.toByteArray();
    }

    static void silence(ByteArrayOutputStream o, int d) {
        while (d > 0) { int x = Math.min(d, 127); o.write(ToneControl.SILENCE); o.write(x); d -= x; }
    }
}
