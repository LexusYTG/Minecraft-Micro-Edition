CXX = gpp
CXXFLAGS = -O2 -march=i486 -mno-80387 -fno-rtti -w
OBJS = main.o osk.o gfx.o game_world.o game_logic.o game_render.o

mc.exe: $(OBJS)
	$(CXX) -o mc.exe $(OBJS)

%.o: %.cpp game.h gfx.h fixed.h
	$(CXX) $(CXXFLAGS) -c $< -o $@

clean:
	rm -f *.o mc.exe
