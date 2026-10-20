package com.kurt.airplaytv.log;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * In-memory ring buffer of recent app log lines, backing the in-app Logs
 * screen. Fed by the SLF4J bridge (all protocol-core logging), key service
 * lifecycle events, and the uncaught-exception handler.
 */
public final class LogBuffer {

    private static final int CAPACITY = 600;
    /** Logcat tag every buffered line is also written under. */
    private static final String LOGBUF_TAG = "AirPlayTV";
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private LogBuffer() {
    }

    public static void append(int priority, String tag, String message) {
        add(priority, tag, message, null);
    }

    public static void append(int priority, String tag, String message, Throwable t) {
        add(priority, tag, message, t);
    }

    private static synchronized void add(int priority, String tag, String message, Throwable t) {
        StringBuilder line = new StringBuilder()
                .append(TIME.format(new Date()))
                .append(' ').append(letter(priority))
                .append('/').append(tag).append(": ").append(message);
        if (t != null) {
            line.append('\n').append(Log.getStackTraceString(t));
        }
        String entry = line.toString();
        while (LINES.size() >= CAPACITY) {
            LINES.pollFirst();
        }
        LINES.addLast(entry);
        // Mirror into logcat under one tag so the in-app Logs screen and
        // `adb logcat` show the same content: `adb logcat -d | grep AirPlayTV`.
        Log.println(priority, LOGBUF_TAG, entry);
    }

    public static synchronized List<String> snapshot() {
        return new ArrayList<>(LINES);
    }

    public static synchronized void clear() {
        LINES.clear();
    }

    private static char letter(int priority) {
        switch (priority) {
            case Log.VERBOSE: return 'V';
            case Log.DEBUG: return 'D';
            case Log.INFO: return 'I';
            case Log.WARN: return 'W';
            case Log.ERROR: return 'E';
            default: return '?';
        }
    }
}
