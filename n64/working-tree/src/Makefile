# Minicraft Micro Edition - port a Nintendo 64 (libdragon, rama trunk)
# El script de build exporta N64_INST=/opt/libdragon antes de llamar a make.
BUILD_DIR = build
SOURCE_DIR = .
include $(N64_INST)/include/n64.mk

# n64.mk compila con -Werror; el codigo viene de la version de PSP y puede tener warnings inofensivos
N64_CXXFLAGS += -Wno-error

OBJS = $(BUILD_DIR)/main.o $(BUILD_DIR)/osk.o $(BUILD_DIR)/gfx.o \
       $(BUILD_DIR)/game_world.o $(BUILD_DIR)/game_logic.o $(BUILD_DIR)/game_render.o

all: Minicraft64.z64

Minicraft64.z64: N64_ROM_TITLE = "Minicraft ME"

# Sistema de idiomas: leng/idiomas.txt (el mismo archivo de la version Java) se mete en la ROM como sistema
# de archivos DFS; el juego lo lee como "rom:/idiomas.txt" (si no estuviera, prueba sd:/MC64/leng/idiomas.txt).
$(BUILD_DIR)/Minicraft64.dfs: leng/idiomas.txt
	@mkdir -p $(dir $@)
	@echo "    [DFS] $@"
	$(N64_MKDFS) $@ leng

Minicraft64.z64: $(BUILD_DIR)/Minicraft64.dfs

$(BUILD_DIR)/Minicraft64.elf: $(OBJS)

clean:
	rm -rf $(BUILD_DIR) *.z64

-include $(wildcard $(BUILD_DIR)/*.d)

.PHONY: all clean
