package com.kurt.airplaytv.log;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * Minimal SLF4J 2.0 provider that forwards to android.util.Log.
 * Keeps the ported java-airplay sources logging without a logging framework.
 */
public final class AndroidSlf4jProvider implements SLF4JServiceProvider {

    public static final String REQUESTED_API_VERSION = "2.0";

    private final AndroidLoggerFactory loggerFactory = new AndroidLoggerFactory();
    private final BasicMarkerFactory markerFactory = new BasicMarkerFactory();
    private final BasicMDCAdapter mdcAdapter = new BasicMDCAdapter();

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        return REQUESTED_API_VERSION;
    }

    @Override
    public void initialize() {
        // no-op
    }
}
