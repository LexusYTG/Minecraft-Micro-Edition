#!/data/data/com.termux/files/usr/bin/bash

set -e

# --- 1. Configuración ---
PROJECT_DIR="$HOME/MME"
WORKING_TREE="$PROJECT_DIR/n64/working-tree"
BUILD_DIR="$WORKING_TREE/build"
SRC_DIR="$WORKING_TREE/src"
FINAL_DIR="$PROJECT_DIR/MMEfinal/n64"
LOG_FILE="$BUILD_DIR/logs.txt"

# --- 2. Preparación ---
mkdir -p "$BUILD_DIR" "$FINAL_DIR"
echo "============================================"
echo " 🎮 COMPILANDO PARA N64"
echo "============================================"

# --- 3. Compilación ---
echo "[1/2] Compilando código fuente..."
if proot-distro login ubuntu -- bash -c "
    export N64_INST=/opt/libdragon
    export PATH=\$PATH:\$N64_INST/bin
    cd $SRC_DIR || exit 1
    make clean > /dev/null 2>&1
    make > $LOG_FILE 2>&1
    ROM=\$(find . -maxdepth 1 -name '*.z64' | head -n 1)
    if [ -n \"\$ROM\" ]; then
        mv \"\$ROM\" $FINAL_DIR/
    else
        exit 1
    fi
    chmod -R 777 $FINAL_DIR $BUILD_DIR
"; then
    echo "[2/2] Empaquetado completado."
    echo "✅ Éxito: $FINAL_DIR/Minicraft64.z64"
else
    echo "❌ ERROR: Falló la compilación de N64."
    echo "📄 --- LOG DE ERRORES ---"
    cat "$LOG_FILE"
    echo "-------------------------"
    exit 1
fi
