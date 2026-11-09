package com.eta.atb;

import android.content.SharedPreferences;

/** Maps package names to the boolean switches written by SettingsActivity. */
final class FeatureToggles {

    static final String PREFS = "atb_config";
    static final String PHONE_MANAGER = "phone_manager";
    static final String SECURITY_FAMILY = "security_family";
    static final String DCS = "dcs";
    static final String BROWSER = "browser";
    static final String KILL_SWITCH = "kill_switch";

    private FeatureToggles() {
    }

    static boolean enabled(String pkg, SharedPreferences p) {
        if (p != null && p.getBoolean(KILL_SWITCH, false)) return false;
        String key = key(pkg);
        if (key == null) return true; // custom app from the override JSON
        boolean def = defaultFor(key);
        return p == null ? def : p.getBoolean(key, def);
    }

    static String key(String pkg) {
        if (pkg == null) return null;
        int colon = pkg.indexOf(':');
        if (colon > 0) pkg = pkg.substring(0, colon);
        switch (pkg) {
            case "com.coloros.phonemanager":
                return PHONE_MANAGER;
            case "com.coloros.securityguard":
            case "com.coloros.familyguard":
                return SECURITY_FAMILY;
            case "com.oplus.statistics.rom":
                return DCS;
            case "com.heytap.browser":
                return BROWSER;
            default:
                return null;
        }
    }

    static boolean defaultFor(String key) {
        return PHONE_MANAGER.equals(key);
    }
}