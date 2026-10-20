package com.kurt.airplaytv.log;

import android.util.Log;

import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MarkerIgnoringBase;
import org.slf4j.helpers.MessageFormatter;

/**
 * SLF4J logger backed by android.util.Log. Tag is truncated to 23 chars
 * (required on API &lt; 26). TRACE maps to DEBUG.
 */
final class AndroidLogger extends MarkerIgnoringBase {

    private final String tag;

    AndroidLogger(String name) {
        this.tag = name.length() <= 23 ? name : name.substring(name.length() - 23);
    }

    private void log(int priority, String format, Object... args) {
        // All SLF4J output flows into LogBuffer, which renders it both on
        // the in-app Logs screen and into logcat (tag "AirPlayTV").
        FormattingTuple tuple = MessageFormatter.arrayFormat(format, args);
        LogBuffer.append(priority, tag, tuple.getMessage(), tuple.getThrowable());
    }

    private void log(int priority, String format, Throwable t) {
        LogBuffer.append(priority, tag, format, t);
    }

    @Override public boolean isTraceEnabled() { return Log.isLoggable(tag, Log.DEBUG); }
    @Override public boolean isDebugEnabled() { return Log.isLoggable(tag, Log.DEBUG); }
    @Override public boolean isInfoEnabled() { return Log.isLoggable(tag, Log.INFO); }
    @Override public boolean isWarnEnabled() { return Log.isLoggable(tag, Log.WARN); }
    @Override public boolean isErrorEnabled() { return Log.isLoggable(tag, Log.ERROR); }

    @Override public void trace(String msg) { log(Log.DEBUG, msg); }
    @Override public void trace(String format, Object arg) { log(Log.DEBUG, format, arg); }
    @Override public void trace(String format, Object arg1, Object arg2) { log(Log.DEBUG, format, arg1, arg2); }
    @Override public void trace(String format, Object... arguments) { log(Log.DEBUG, format, arguments); }
    @Override public void trace(String msg, Throwable t) { log(Log.DEBUG, msg, t); }

    @Override public void debug(String msg) { log(Log.DEBUG, msg); }
    @Override public void debug(String format, Object arg) { log(Log.DEBUG, format, arg); }
    @Override public void debug(String format, Object arg1, Object arg2) { log(Log.DEBUG, format, arg1, arg2); }
    @Override public void debug(String format, Object... arguments) { log(Log.DEBUG, format, arguments); }
    @Override public void debug(String msg, Throwable t) { log(Log.DEBUG, msg, t); }

    @Override public void info(String msg) { log(Log.INFO, msg); }
    @Override public void info(String format, Object arg) { log(Log.INFO, format, arg); }
    @Override public void info(String format, Object arg1, Object arg2) { log(Log.INFO, format, arg1, arg2); }
    @Override public void info(String format, Object... arguments) { log(Log.INFO, format, arguments); }
    @Override public void info(String msg, Throwable t) { log(Log.INFO, msg, t); }

    @Override public void warn(String msg) { log(Log.WARN, msg); }
    @Override public void warn(String format, Object arg) { log(Log.WARN, format, arg); }
    @Override public void warn(String format, Object arg1, Object arg2) { log(Log.WARN, format, arg1, arg2); }
    @Override public void warn(String format, Object... arguments) { log(Log.WARN, format, arguments); }
    @Override public void warn(String msg, Throwable t) { log(Log.WARN, msg, t); }

    @Override public void error(String msg) { log(Log.ERROR, msg); }
    @Override public void error(String format, Object arg) { log(Log.ERROR, format, arg); }
    @Override public void error(String format, Object arg1, Object arg2) { log(Log.ERROR, format, arg1, arg2); }
    @Override public void error(String format, Object... arguments) { log(Log.ERROR, format, arguments); }
    @Override public void error(String msg, Throwable t) { log(Log.ERROR, msg, t); }
}
