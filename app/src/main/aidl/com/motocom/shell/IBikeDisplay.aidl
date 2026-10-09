package com.motocom.shell;

import android.view.Surface;
import com.motocom.shell.IShellLog;

/**
 * What the app asks of the part of itself that ADB runs as "adb shell" (DisplayService).
 */
interface IBikeDisplay {
    /** Ends the privileged process. */
    void destroy() = 16777114;

    /** Where to tell what happens over there, for the bike's record. */
    void setLog(IShellLog log) = 1;

    /**
     * The screen for the bike, painting into [surface], made the first time and handed the new
     * surface after that. Returns its display id, or -1 if it could not be made.
     */
    int show(in Surface surface, int width, int height, int dpi) = 2;

    /** The encoder is going: the screen keeps its apps but paints nowhere for now. */
    void detachSurface() = 3;

    /**
     * Takes [packageName] to [displayId]: the bike's screen, or 0 for the phone's. Moves it if it
     * is already open, starts [component] if not. Says what happened.
     */
    String bring(String packageName, String component, int displayId) = 4;

    /** Done with the bike: the screen goes, and whatever was on it comes back to the phone. */
    void release() = 5;

    /** The bike's screen id, or -1 if there is none right now. */
    int displayId() = 6;
}
