TARGET = Minecraft2D
OBJS = main.o osk.o gfx.o game_world.o game_logic.o game_render.o

CFLAGS = -O2 -G0 -Wall
CXXFLAGS = $(CFLAGS) -std=gnu++11
ASFLAGS = $(CFLAGS)

LIBDIR =
LIBS = -lstdc++ -lpspgum -lpspgu -lpspdebug -lpspdisplay -lpspge -lpspctrl -lpsppower -lpsputility -lm
LDFLAGS =

EXTRA_TARGETS = EBOOT.PBP
PSP_EBOOT_TITLE = Minecraft2D

PSPSDK = $(shell psp-config --pspsdk-path)
include $(PSPSDK)/lib/build.mak
