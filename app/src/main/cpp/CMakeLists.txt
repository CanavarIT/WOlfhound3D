cmake_minimum_required(VERSION 3.22)
project(Wolfhound3D C CXX)

# ---- SDL2 (static) ----------------------------------------------------------
set(SDL_SHARED OFF CACHE BOOL "" FORCE)
set(SDL_STATIC ON CACHE BOOL "" FORCE)
set(SDL_TEST_LIBRARY_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(SDL2 EXCLUDE_FROM_ALL)

# ---- SDL2_mixer (static, built-in decoders only) -----------------------------
# Все кодеки, требующие системных библиотек, выключены — их нет под NDK:
#   WavPack, Opus, MOD/libxmp, MIDI/FluidSynth, GME.
# Остаётся встроенное: WAV, Ogg (stb_vorbis), MP3 (drmp3), FLAC (dr_flac).
#
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_VENDORED OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_SAMPLES OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_INSTALL OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_CMD OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_WAVPACK OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_OPUS OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_MOD OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_MIDI OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_GME OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_FLAC_LIBFLAC OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_MP3_MPG123 OFF CACHE BOOL "" FORCE)
add_subdirectory(SDL2_mixer EXCLUDE_FROM_ALL)


# ---- Wolf4SDL engine core (static) ------------------------------------------
set(WOLF_SOURCES
    wolf4sdl/opl3.c
    wolf4sdl/states.cpp
    wolf4sdl/layouts_gen.cpp
    wolf4sdl/signon.cpp
    wolf4sdl/id_ca.cpp
    wolf4sdl/id_in.cpp
    wolf4sdl/id_pm.cpp
    wolf4sdl/id_sd.cpp
    wolf4sdl/id_us_1.cpp
    wolf4sdl/id_vh.cpp
    wolf4sdl/id_vl.cpp
    wolf4sdl/wl_act1.cpp
    wolf4sdl/wl_act2.cpp
    wolf4sdl/wl_agent.cpp
    wolf4sdl/wl_debug.cpp
    wolf4sdl/wl_draw.cpp
    wolf4sdl/wl_game.cpp
    wolf4sdl/wl_inter.cpp
    wolf4sdl/wl_main.cpp
    wolf4sdl/wl_menu.cpp
    wolf4sdl/wl_play.cpp
    wolf4sdl/wl_state.cpp
    wolf4sdl/wl_text.cpp
)

add_library(wolfcore STATIC ${WOLF_SOURCES})

target_include_directories(wolfcore PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/wolf4sdl)
target_compile_definitions(wolfcore PRIVATE
    DEBUGKEYS
    ARTSEXTERN
    DEMOSEXTERN
    PLAYDEMOLIKEORIGINAL
)
# touch controls v2 (swipe look, stick strafe, no accelerometer joystick);
# the define only exists to force a rebuild of the native code
target_compile_definitions(wolfcore PRIVATE WOLF_TOUCH_V2=1)
target_link_libraries(wolfcore PUBLIC SDL2::SDL2-static SDL2_mixer)

# ---- Android entry point library --------------------------------------------
add_library(main SHARED main.cpp)
target_include_directories(main PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/wolf4sdl)
target_compile_definitions(main PRIVATE WOLF_TOUCH_V2=1)
target_link_libraries(main PRIVATE wolfcore)
