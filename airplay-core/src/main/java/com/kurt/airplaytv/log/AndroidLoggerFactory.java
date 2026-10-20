package com.kurt.airplaytv.log;

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class AndroidLoggerFactory implements ILoggerFactory {

    private final Map<String, Logger> cache = new ConcurrentHashMap<>();

    @Override
    public Logger getLogger(String name) {
        return cache.computeIfAbsent(name, AndroidLogger::new);
    }
}
