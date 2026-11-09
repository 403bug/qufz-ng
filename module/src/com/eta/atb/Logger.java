package com.eta.atb;

import android.util.Log;

import io.github.libxposed.api.XposedModule;

/** Logs to both android.util.Log and the LSPosed module log. */
final class Logger {

    private final XposedModule module;

    Logger(XposedModule module) {
        this.module = module;
    }

    void info(String s) {
        Log.i(MainHook.TAG, s);
        mirror(Log.INFO, s);
    }

    void warn(String s) {
        Log.w(MainHook.TAG, s);
        mirror(Log.WARN, s);
    }

    void error(String s) {
        Log.e(MainHook.TAG, s);
        mirror(Log.ERROR, s);
    }

    private void mirror(int priority, String s) {
        try {
            module.log(priority, MainHook.TAG, s);
        } catch (Throwable ignored) {
            // logging must never break the host process
        }
    }
}
