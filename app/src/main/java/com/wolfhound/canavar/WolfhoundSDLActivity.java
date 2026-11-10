package com.wolfhound.canavar;

import com.wolfhound.canavar.sdl.SDLActivity;

/**
 * WOlfhound3D engine activity.
 * Loads the single native library "main" (Wolf4SDL engine statically
 * linked with SDL2 and SDL2_mixer).
 */
public class WolfhoundSDLActivity extends SDLActivity {
    @Override
    protected String[] getLibraries() {
        return new String[]{
            "main"
        };
    }
}
