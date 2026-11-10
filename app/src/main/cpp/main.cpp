// WOlfhound3D — Android entry point.
//
// SDL2 on Android looks up the exported symbol SDL_main in the main
// library and calls it from SDLActivity.nativeRunMain().
//
// Where the game data lives (the same rule is used in MainActivity.java):
//   1. Android/data/com.wolfhound.canavar/files/   (user copied files there)
//   2. the app's private storage (files/), where MainActivity copies the
//      data bundled in assets/ on first start
// The first one that contains vswap.* is used. We chdir() into it, so the
// engine finds the data files, then run the Wolf4SDL engine
// (renamed wolf4sdl_main).

#include "SDL.h"
#include <unistd.h>
#include <stdlib.h>

#ifdef __ANDROID__
#include <dirent.h>
#include <strings.h>

// true if dir contains a file named vswap.* (any letter case)
static bool dirHasGameData(const char *dir)
{
    DIR *d = opendir(dir);
    if (!d)
        return false;

    bool found = false;
    while (struct dirent *e = readdir(d))
    {
        if (strncasecmp(e->d_name, "vswap.", 6) == 0)
        {
            found = true;
            break;
        }
    }
    closedir(d);
    return found;
}
#endif

int wolf4sdl_main(int argc, char *argv[]);

static int run_engine(int argc, char *argv[])
{
#ifdef __ANDROID__
    // Landscape only (both directions). Must be set before the window is created.
    SDL_SetHint(SDL_HINT_ORIENTATIONS, "LandscapeLeft LandscapeRight");
    // Finger touches must not turn into mouse clicks (= firing) in the engine.
    SDL_SetHint(SDL_HINT_TOUCH_MOUSE_EVENTS, "0");
    // SDL exposes the phone's accelerometer as a joystick by default, so tilting
    // the phone moved the menu cursor / the player. Switch that off.
    SDL_SetHint(SDL_HINT_ACCELEROMETER_AS_JOYSTICK, "0");

    const char *extPath = SDL_AndroidGetExternalStoragePath();
    const char *intPath = SDL_AndroidGetInternalStoragePath();
    const char *dir = NULL;

    if (extPath && *extPath && dirHasGameData(extPath))
        dir = extPath;                      // data placed by the user
    else if (intPath && *intPath)
        dir = intPath;                      // data copied from assets
    else if (extPath && *extPath)
        dir = extPath;

    if (dir)
    {
        chdir(dir);
        // Wolf4SDL stores config and saves in $HOME/.wolf4sdl, and HOME is
        // normally not set on Android (the engine would quit with an error).
        setenv("HOME", dir, 1);
    }
#endif
    return wolf4sdl_main(argc, argv);
}

#ifdef __ANDROID__

#include <jni.h>

// Called from the UI thread (TouchControls). The engine reads its input on
// another thread, so we must NOT touch engine data here: SDL_PushEvent is
// thread-safe, the engine picks the event up in IN_ProcessEvents().
extern "C" JNIEXPORT void JNICALL
Java_com_wolfhound_canavar_MainActivity_nativeKey(JNIEnv *env, jclass clazz,
                                                  jint keysym, jboolean down)
{
    SDL_Event e;
    SDL_zero(e);
    e.type = down ? SDL_KEYDOWN : SDL_KEYUP;
    e.key.state = down ? SDL_PRESSED : SDL_RELEASED;
    e.key.repeat = 0;
    e.key.keysym.sym = (SDL_Keycode)keysym;
    e.key.keysym.scancode = SDL_GetScancodeFromKey((SDL_Keycode)keysym);
    e.key.keysym.mod = KMOD_NONE;
    SDL_PushEvent(&e);
}

// Implemented in wolf4sdl/wl_play.cpp
void Touch_AddLook(int dx);
void Touch_SetStrafe(int dir);
bool Touch_InGameplay(void);

// Right-side swipe: horizontal movement, already scaled (positive = turn right).
extern "C" JNIEXPORT void JNICALL
Java_com_wolfhound_canavar_MainActivity_nativeLook(JNIEnv *env, jclass clazz, jint dx)
{
    Touch_AddLook((int)dx);
}

// Stick left/right in gameplay: -1 strafe left, 0 none, +1 strafe right.
extern "C" JNIEXPORT void JNICALL
Java_com_wolfhound_canavar_MainActivity_nativeStrafe(JNIEnv *env, jclass clazz, jint dir)
{
    Touch_SetStrafe((int)dir);
}

// true while a level is being played (not in menus / intermissions).
extern "C" JNIEXPORT jboolean JNICALL
Java_com_wolfhound_canavar_MainActivity_nativeInGameplay(JNIEnv *env, jclass clazz)
{
    return Touch_InGameplay() ? JNI_TRUE : JNI_FALSE;
}

// SDL_main.h (included via SDL.h on Android) #defines main -> SDL_main,
// so defining SDL_main here provides the entry point SDL expects.
extern "C" int SDL_main(int argc, char *argv[])
{
    return run_engine(argc, argv);
}

#else

// Host/desktop fallback (used for compile testing)
extern "C" int main(int argc, char *argv[])
{
    return run_engine(argc, argv);
}

#endif