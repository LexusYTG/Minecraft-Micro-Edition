#!/data/data/com.termux/files/usr/bin/bash

# Definir rutas base apuntando correctamente a la carpeta de PSP
PROJECT_DIR="$HOME/MME"
WORKING_TREE="$PROJECT_DIR/psp/working-tree"
BUILD_DIR="$WORKING_TREE/build"
SRC_DIR="$WORKING_TREE/src"
LOG_FILE="$BUILD_DIR/logs.txt"

# 1. Asegurar que existan las carpetas de build
mkdir -p "$BUILD_DIR"

echo "🚀 Iniciando compilación para PSP..."
echo "📂 Directorio de origen: $SRC_DIR"

# 2. Entrar a Ubuntu y compilar
proot-distro login ubuntu -- bash -c "
    export PSPDEV=/root/pspdev
    export PATH=\$PATH:\$PSPDEV/bin
    
    cd $SRC_DIR || exit 1
    
    # Limpiar compilaciones anteriores
    make clean > /dev/null 2>&1
    
    # Compilar y mandar la salida al log
    echo 'Compilando código fuente...'
    make > $LOG_FILE 2>&1
    
    # Mover el ejecutable final a la carpeta build si la compilación fue exitosa
    if [ -f EBOOT.PBP ]; then
        mv EBOOT.PBP $BUILD_DIR/
    fi
    
    # Arreglar permisos para que Termux pueda leer/escribir los archivos
    chmod -R 777 $BUILD_DIR
    chmod -R 777 $SRC_DIR
"

# 3. Desloguear y mostrar el log
echo "✅ Compilación finalizada."
echo "📄 --- LOG DE COMPILACIÓN ---"
if [ -f "$LOG_FILE" ]; then
    cat "$LOG_FILE"
else
    echo "❌ Error: No se generó el archivo de log. Revisa si 'make' está instalado en Ubuntu."
fi
echo "-----------------------------"
