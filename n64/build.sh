#!/data/data/com.termux/files/usr/bin/bash

PROJECT_DIR="$HOME/MME"
WORKING_TREE="$PROJECT_DIR/n64/working-tree"
BUILD_DIR="$WORKING_TREE/build"
SRC_DIR="$WORKING_TREE/src"
LOG_FILE="$BUILD_DIR/logs.txt"

mkdir -p "$BUILD_DIR"

echo "🚀 Iniciando compilación para N64..."
echo "📂 Directorio de origen: $SRC_DIR"

# Entrar a Ubuntu y compilar
proot-distro login ubuntu -- bash -c "
    # Configurar el entorno de libdragon
    export N64_INST=/opt/libdragon
    export PATH=\$PATH:\$N64_INST/bin
    
    cd $SRC_DIR || exit 1
    
    make clean > /dev/null 2>&1
    make > $LOG_FILE 2>&1
    
    # Mover la ROM final (.z64) a la carpeta build si se generó
    ROM=\$(find . -maxdepth 1 -name '*.z64' | head -n 1)
    if [ -n \"\$ROM\" ]; then
        mv \"\$ROM\" $BUILD_DIR/
    fi
    
    chmod -R 777 $BUILD_DIR
"

echo "✅ Compilación finalizada."
echo "📄 --- LOG DE COMPILACIÓN ---"
if [ -f "$LOG_FILE" ]; then
    cat "$LOG_FILE"
else
    echo "❌ Error: No se generó el archivo de log."
fi
echo "-----------------------------"
