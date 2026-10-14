package org.libsdl.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The settings file: key=value lines in the app's folder, the one the engine
 * reads and writes as well (JSRF_SETTINGS_FILE, port/android/jsrf_android.c;
 * nv2a_gl.c keeps "widescreen" there, the engine starts at "scale"). Only the
 * key being set is rewritten.
 */
final class Settings {
    private Settings() {}

    static File file(Context c) {
        return new File(c.getExternalFilesDir(null), "settings");
    }

    static int get(Context c, String key, int def) {
        for (String line : read(file(c))) {
            if (line.startsWith(key + "=")) {
                try {
                    return Integer.parseInt(line.substring(key.length() + 1).trim());
                } catch (NumberFormatException e) {
                    return def;
                }
            }
        }
        return def;
    }

    static synchronized void set(Context c, String key, int value) {
        File f = file(c);
        List<String> out = new ArrayList<>();
        boolean done = false;
        for (String line : read(f)) {
            if (line.startsWith(key + "=")) {
                if (!done) out.add(key + "=" + value);
                done = true;
            } else {
                out.add(line);
            }
        }
        if (!done) out.add(key + "=" + value);
        File tmp = new File(f.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            for (String line : out) w.write(line + "\n");
        } catch (Exception e) {
            return;
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            tmp.renameTo(f);
        }
    }

    private static List<String> read(File f) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        } catch (Exception e) {
            /* no file yet: no settings */
        }
        return lines;
    }
}
