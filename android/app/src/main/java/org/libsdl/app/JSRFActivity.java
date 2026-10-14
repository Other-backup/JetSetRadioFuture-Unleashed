package org.libsdl.app;

import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.util.Log;
import android.view.InputDevice;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.Toast;

import java.io.File;

import org.libsdl.app.SDLActivity;

/**
 * The game: SDL's activity, which loads libSDL2.so and libmain.so (the
 * engine) and calls its SDL_main with the folders below
 * (port/android/jsrf_android.c reads them). On top of SDL's surface, the
 * on-screen controls (TouchPadView) while no controller is connected, and
 * the settings button.
 */
public class JSRFActivity extends SDLActivity
        implements TouchPadView.Listener, InputManager.InputDeviceListener {

    /* port/android/jsrf_android.c */
    static native void nativeTouch(int buttons, int analog, int x, int y, boolean stick, boolean shown);
    static native boolean nativeWidescreen();
    static native void nativeSetWidescreen(boolean on);
    static native String nativeVersion();

    private TouchPadView mPad;
    private InputManager mInput;

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "main" };
    }

    @Override
    protected String[] getArguments() {
        File data = getExternalFilesDir(null);
        return new String[] {
            new File(data, "game").getAbsolutePath(),   /* default.xbe and Media/ */
            data.getAbsolutePath(),                      /* Logs/, settings, save/ */
            getCacheDir().getAbsolutePath(),             /* temporary files */
        };
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (mLayout == null) return;        /* SDL could not load the engine: it says so */
        mPad = new TouchPadView(this, this);
        mLayout.addView(mPad, new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mInput = (InputManager) getSystemService(INPUT_SERVICE);
        if (mInput != null) mInput.registerInputDeviceListener(this, null);
        updateControls();
    }

    @Override
    protected void onPause() {
        if (mPad != null) mPad.reset();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (mInput != null) mInput.unregisterInputDeviceListener(this);
        super.onDestroy();
    }

    /** Landscape, either way up. SDL would let a resizable window turn with
     *  the phone, into portrait too. */
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    /* ---- controllers and the on-screen controls ---- */

    @Override public void onInputDeviceAdded(int id)   { updateControls(); }
    @Override public void onInputDeviceRemoved(int id) { updateControls(); }
    @Override public void onInputDeviceChanged(int id) { updateControls(); }

    private static boolean isController(InputDevice d) {
        if (d == null || d.isVirtual()) return false;
        int s = d.getSources();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private boolean touchPref() {
        return Settings.get(this, "touch", 1) != 0;
    }

    /** The on-screen controls while no controller is connected (and the
     *  player has not turned them off). */
    private void updateControls() {
        if (mPad == null) return;
        boolean pad = false;
        for (int id : InputDevice.getDeviceIds())
            if (isController(InputDevice.getDevice(id))) pad = true;
        boolean want = !pad && touchPref();
        mPad.setControls(want);
        Log.i("JSRF", "controls: " + (pad ? "a controller" : want ? "on screen" : "none shown"));
    }

    /* ---- settings ---- */

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    @Override
    public void onSettings() {
        final boolean wide = nativeWidescreen();
        final boolean touch = touchPref();
        final int scale = Settings.get(this, "scale", 2);
        String[] items = {
            "Widescreen: " + (wide ? "on" : "off"),
            "On-screen controls: " + (touch ? "shown" : "hidden"),
            "Resolution: " + scale + "x",
            "Controls",
        };
        mPad.reset();
        dialog().setTitle("JSRF Unleashed " + nativeVersion())
            .setItems(items, (d, which) -> {
                switch (which) {
                case 0:
                    nativeSetWidescreen(!wide);
                    break;
                case 1:
                    Settings.set(this, "touch", touch ? 0 : 1);
                    updateControls();
                    break;
                case 2:
                    pickScale(scale);
                    break;
                default:
                    showControls();
                    break;
                }
            })
            .setNegativeButton("Close", null)
            .show();
    }

    private void pickScale(int now) {
        String[] items = new String[4];
        for (int s = 1; s <= 4; s++)
            items[s - 1] = s + "x (" + 640 * s + " x " + 480 * s + ")" + (s == now ? "  ✓" : "");
        dialog().setTitle("Resolution")
            .setItems(items, (d, which) -> {
                Settings.set(this, "scale", which + 1);
                if (which + 1 != now)
                    Toast.makeText(this, "Used the next time the game starts.", Toast.LENGTH_LONG).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showControls() {
        dialog().setTitle("Controls")
            .setMessage("A controller works as on the console (Xbox, PlayStation, Switch Pro and "
                + "others, over Bluetooth or USB): A jump, R trigger talk and spray, Start, "
                + "Back/Select Back.\n\n"
                + "On screen: put your left thumb anywhere on the left half to steer; A B X Y are "
                + "the pad's, R and L its triggers, the hollow and filled circles White and Black; "
                + "Start and Back are at the top.\n\n"
                + "A keyboard: WASD (by position) moves, arrows the camera, Space jump, E spray, "
                + "Enter Start, Esc Back.\n\n"
                + "A new resolution is used the next time the game starts.")
            .setPositiveButton("OK", null)
            .show();
    }
}
