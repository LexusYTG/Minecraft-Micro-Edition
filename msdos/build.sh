#!/data/data/com.termux/files/usr/bin/bash

set -e

# --- 1. Configuración ---
PROJECT_DIR="$HOME/MME"
WORKING_TREE="$PROJECT_DIR/msdos/working-tree"
BUILD_DIR="$WORKING_TREE/build"
SRC_DIR="$WORKING_TREE/src"
FINAL_DIR="$PROJECT_DIR/MMEfinal/dos"
LOG_FILE="$BUILD_DIR/logs.txt"

# --- 2. Preparación ---
mkdir -p "$BUILD_DIR" "$FINAL_DIR"
echo "============================================"
echo " 🎮 COMPILANDO PARA MS-DOS (i386/i486)"
echo "============================================"

# --- 3. Compilación ---
echo "[1/2] Compilando código fuente..."
if proot-distro login ubuntu -- bash -c "
    source /usr/local/djgpp/setenv > /dev/null
    cd $SRC_DIR || exit 1
    make clean > /dev/null 2>&1 || true
    make CC=i586-pc-msdosdjgpp-gcc CXX=i586-pc-msdosdjgpp-g++ > $LOG_FILE 2>&1
    if [ -f mc.exe ]; then
        mv mc.exe $FINAL_DIR/mme.exe
        rm -f *.o
    else
        exit 1
    fi
    chmod -R 777 $FINAL_DIR $BUILD_DIR
"; then
    echo "[2/2] Empaquetado completado."
    echo "✅ Éxito: $FINAL_DIR/mme.exe"
else
    echo "❌ ERROR: Falló la compilación para MS-DOS."
    echo "📄 --- LOG DE ERRORES ---"
    cat "$LOG_FILE"
    echo "-------------------------"
    exit 1
fi
