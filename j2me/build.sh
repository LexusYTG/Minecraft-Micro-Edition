#!/data/data/com.termux/files/usr/bin/bash

set -u

# --- Configuración de rutas ---
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
TOOLS_DIR="$ROOT_DIR/j2me-builder-files"
WORK_DIR="$ROOT_DIR/working-tree"
BUILD_DIR="$WORK_DIR/build"
CLASSES_DIR="$BUILD_DIR/classes"
LIB_DIR="$TOOLS_DIR/lib"
PROGUARD_JAR="$TOOLS_DIR/proguard.jar"

MIDLET_NAME="Minecraft2D"
RAW_JAR="$BUILD_DIR/hello-raw.jar"
FINAL_JAR="$BUILD_DIR/hello-final.jar"

cd "$ROOT_DIR" || { echo "ERROR: no existe $ROOT_DIR"; exit 1; }

echo "============================================"
echo " BUILD J2ME - Minecraft Micro Edition"
echo "============================================"

# --- [1/5] Verificar herramientas ---
echo "[1/5] Verificando herramientas..."
for cmd in javac jar java; do
    if ! command -v "$cmd" > /dev/null 2>&1; then
        echo "ERROR: falta '$cmd'. Instalalo con pkg install."
        exit 1
    fi
done
echo "  OK: javac, jar, java"

# --- [2/5] Verificar dependencias ---
echo "[2/5] Verificando stubs y ProGuard..."
if [ ! -s "$LIB_DIR/cldcapi11.jar" ] || [ ! -s "$LIB_DIR/midpapi20.jar" ]; then
    echo "ERROR: faltan los stubs en $LIB_DIR"
    exit 1
fi
if [ ! -s "$PROGUARD_JAR" ]; then
    echo "ERROR: falta $PROGUARD_JAR"
    exit 1
fi
echo "  OK: stubs + proguard.jar presentes"

# --- [3/5] Compilar ---
echo "[3/5] Compilando fuentes Java..."
rm -rf "$BUILD_DIR"
mkdir -p "$CLASSES_DIR"
javac -source 8 -target 8 \
    -bootclasspath "$LIB_DIR/cldcapi11.jar:$LIB_DIR/midpapi20.jar" \
    -d "$CLASSES_DIR" \
    "$WORK_DIR/src"/*.java 2>&1
JAVAC_STATUS=$?
if [ $JAVAC_STATUS -ne 0 ]; then
    echo "ERROR: javac falló (código $JAVAC_STATUS). Abortando."
    exit 1
fi
if [ ! -f "$CLASSES_DIR/$MIDLET_NAME.class" ]; then
    echo "ERROR: no se generó $MIDLET_NAME.class. Abortando."
    exit 1
fi
echo "  OK: clases compiladas en $CLASSES_DIR"

# --- [4/5] Empaquetar JAR crudo ---
echo "[4/5] Empaquetando JAR crudo..."
cp -r "$WORK_DIR/resources"/* "$CLASSES_DIR/"
( cd "$CLASSES_DIR" && jar cfm "$RAW_JAR" META-INF/MANIFEST.MF . ) 2>&1
if [ ! -f "$RAW_JAR" ]; then
    echo "ERROR: no se generó $RAW_JAR. Abortando."
    exit 1
fi
echo "  OK: $RAW_JAR"

# --- [5/5] Preverificar con ProGuard ---
echo "[5/5] Preverificando con ProGuard..."
cat > "$BUILD_DIR/proguard.pro" << PROEOF
-injars $RAW_JAR
-outjars $FINAL_JAR

-libraryjars $LIB_DIR/cldcapi11.jar
-libraryjars $LIB_DIR/midpapi20.jar

-dontshrink
-dontoptimize
-dontobfuscate
-microedition
-dontwarn
PROEOF

java -jar "$PROGUARD_JAR" @"$BUILD_DIR/proguard.pro" 2>&1
PG_STATUS=$?
if [ $PG_STATUS -ne 0 ] || [ ! -f "$FINAL_JAR" ]; then
    echo "ERROR: ProGuard falló (código $PG_STATUS)."
    exit 1
fi
echo "  OK: $FINAL_JAR"

echo ""
echo "============================================"
echo " BUILD COMPLETO"
echo " JAR final: $FINAL_JAR"
echo "============================================"
