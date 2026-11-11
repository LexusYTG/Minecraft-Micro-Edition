#!/data/data/com.termux/files/usr/bin/bash

set -e

# --- 1. Configuración ---
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
TOOLS_DIR="$ROOT_DIR/j2me-builder-files"
WORK_DIR="$ROOT_DIR/working-tree"
BUILD_DIR="$WORK_DIR/build"
CLASSES_DIR="$BUILD_DIR/classes"
LIB_DIR="$TOOLS_DIR/lib"
PROGUARD_JAR="$TOOLS_DIR/proguard.jar"

FINAL_DIR="$HOME/MME/MMEfinal/j2me"
MIDLET_NAME="Minicraft2D"
RAW_JAR="$BUILD_DIR/Minicraft-Micro-Edition-j2me-raw.jar"
FINAL_JAR="$BUILD_DIR/Minicraft-Micro-Edition-j2me.jar"

# --- 2. Preparación ---
mkdir -p "$BUILD_DIR" "$CLASSES_DIR" "$FINAL_DIR"
echo "============================================"
echo " 🎮 COMPILANDO PARA J2ME"
echo "============================================"

for cmd in javac jar java; do
    command -v "$cmd" >/dev/null 2>&1 || { echo "❌ Falta $cmd"; exit 1; }
done

# --- 3. Compilación ---
echo "[1/2] Compilando y empaquetando..."
javac -source 8 -target 8 \
    -bootclasspath "$LIB_DIR/cldcapi11.jar:$LIB_DIR/midpapi20.jar" \
    -d "$CLASSES_DIR" "$WORK_DIR/src"/*.java

cp -r "$WORK_DIR/resources"/* "$CLASSES_DIR/"
( cd "$CLASSES_DIR" && jar cfm "$RAW_JAR" META-INF/MANIFEST.MF . )

cat > "$BUILD_DIR/proguard.pro" << PROEOF
-injars $RAW_JAR
-outjars $FINAL_JAR
-libraryjars $LIB_DIR/cldcapi11.jar
-libraryjars $LIB_DIR/midpapi20.jar
-dontshrink
-dontoptimize
-dontobfuscate
-microedition
-target 1.3
-dontwarn
PROEOF

# --- 4. Ejecución de ProGuard ---
if java -jar "$PROGUARD_JAR" @"$BUILD_DIR/proguard.pro"; then
    if [ -f "$FINAL_JAR" ]; then
        mv "$FINAL_JAR" "$FINAL_DIR/"
        echo "[2/2] Empaquetado completado."
        echo "✅ Éxito: $FINAL_DIR/$(basename "$FINAL_JAR")"
    else
        echo "❌ ERROR: ProGuard terminó pero no generó el JAR final."
        exit 1
    fi
else
    echo "❌ ERROR: Falló ProGuard."
    exit 1
fi
