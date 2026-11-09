package com.eta.atb;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.error.HookFailedError;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Installs one app's rules into one target process.
 *
 * Two kinds of rules:
 *   silence - make matching void methods return immediately (static void rules
 *             with no method list silence every static void method).
 *   sink    - intercept a telemetry exit, read appId/event arguments, and drop
 *             the call only when both match.
 */
final class RuleEngine {

    private final XposedModule module;
    private final ClassLoader classLoader;
    private final String pkg;
    private final RuleConfig.AppRule rule;
    private final Logger log;
    private final Set<String> hooked = new HashSet<>();

    RuleEngine(XposedModule module, ClassLoader classLoader, String pkg,
               RuleConfig.AppRule rule, Logger log) {
        this.module = module;
        this.classLoader = classLoader;
        this.pkg = pkg;
        this.rule = rule;
        this.log = log;
    }

    void apply() {
        int silenceOk = 0;
        int sinkOk = 0;
        int requiredFail = 0;
        int optionalMiss = 0;

        for (RuleConfig.SilenceRule r : rule.silence) {
            int[] result = applySilence(r);
            silenceOk += result[0];
            if (result[1] != 0) requiredFail += result[1];
            if (result[2] != 0) optionalMiss += result[2];
        }

        for (RuleConfig.SinkRule r : rule.sinks) {
            int[] result = applySink(r);
            sinkOk += result[0];
            if (result[1] != 0) requiredFail += result[1];
            if (result[2] != 0) optionalMiss += result[2];
        }

        int total = silenceOk + sinkOk;
        String status = total == 0 ? "INACTIVE" : (requiredFail > 0 ? "PARTIAL" : "ACTIVE");
        log.info("summary pkg=" + pkg
                + " silence=" + silenceOk
                + " sink=" + sinkOk
                + " optionalMiss=" + optionalMiss
                + " status=" + status);
        if (total == 0) {
            log.error("NO HOOK INSTALLED for " + pkg
                    + " — the rules do not match this build; check class/method names");
        }
    }

    /** @return {installed, requiredFail, optionalMiss} */
    private int[] applySilence(RuleConfig.SilenceRule r) {
        Class<?> c;
        try {
            c = Class.forName(r.className, false, classLoader);
        } catch (Throwable t) {
            if (r.optional) {
                log.info("optional class absent: " + r.className);
                return new int[]{0, 0, 1};
            }
            log.error("class not found: " + r.className + " : " + t);
            return new int[]{0, 1, 0};
        }

        int installed = 0;
        if (r.staticVoid) {
            try {
                for (Method m : c.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getReturnType() != void.class) continue;
                    String name = m.getName();
                    if ("<clinit>".equals(name) || "<init>".equals(name)) continue;
                    if (r.exclude.contains(name.toLowerCase(Locale.ROOT))) continue;
                    if (installSilence(m, null)) installed++;
                }
            } catch (Throwable t) {
                log.warn("cannot enumerate " + r.className + " : " + t);
            }
        } else {
            for (RuleConfig.MethodSpec spec : r.methods) {
                List<Method> found = findMethods(c, spec, false);
                if (found.isEmpty()) {
                    log.info("silence target absent: " + shortName(r.className) + "#" + describe(spec));
                    continue;
                }
                for (Method m : found) {
                    if (m.getReturnType() != void.class && spec.returnValue == null) {
                        log.warn("skip non-void silence target without returnValue " + label(m));
                        continue;
                    }
                    if (installSilence(m, spec.returnValue)) installed++;
                }
            }
        }

        if (installed > 0) {
            log.info("silence " + r.className + " -> " + installed + " method(s)");
            return new int[]{installed, 0, 0};
        }
        if (r.optional) {
            log.info("optional silence rule matched 0 methods: " + r.className);
            return new int[]{0, 0, 1};
        }
        log.error("silence rule matched 0 methods: " + r.className);
        return new int[]{0, 1, 0};
    }

    /** @return {installed, requiredFail, optionalMiss} */
    private int[] applySink(RuleConfig.SinkRule r) {
        if (r.eventIndex < 0 || r.appIdIndex < -1) {
            log.error("sink rule has invalid appIdIndex/eventIndex: " + r.className);
            return new int[]{0, r.optional ? 0 : 1, r.optional ? 1 : 0};
        }
        if (r.eventPrefixes.isEmpty() && r.eventExact.isEmpty() && !r.blockAll) {
            log.error("sink rule has no event filter and blockAll=false, skipped: " + r.className);
            return new int[]{0, r.optional ? 0 : 1, r.optional ? 1 : 0};
        }

        Class<?> c;
        try {
            c = Class.forName(r.className, false, classLoader);
        } catch (Throwable t) {
            if (r.optional) {
                log.info("optional sink class absent: " + r.className);
                return new int[]{0, 0, 1};
            }
            log.error("sink class not found: " + r.className + " : " + t);
            return new int[]{0, 1, 0};
        }

        int installed = 0;
        for (RuleConfig.MethodSpec spec : r.methods) {
            List<Method> found = findMethods(c, spec, true);
            if (found.isEmpty()) {
                log.info("sink target absent: " + shortName(r.className) + "#" + describe(spec));
                continue;
            }
            for (Method m : found) {
                if (m.getReturnType() != void.class) {
                    log.warn("skip non-void sink target " + label(m));
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length <= r.eventIndex || ps[r.eventIndex] != String.class) {
                    log.warn("skip sink without String event arg at index " + r.eventIndex + " : " + label(m));
                    continue;
                }
                if (r.appIdIndex >= 0) {
                    if (ps.length <= r.appIdIndex || ps[r.appIdIndex] != String.class) {
                        log.warn("skip sink without String appId arg at index " + r.appIdIndex + " : " + label(m));
                        continue;
                    }
                }
                if (installSink(m, r)) installed++;
            }
        }

        if (installed > 0) {
            log.info("sink " + r.className + " -> " + installed + " method(s)");
            return new int[]{installed, 0, 0};
        }
        if (r.optional) {
            log.info("optional sink rule matched 0 methods: " + r.className);
            return new int[]{0, 0, 1};
        }
        log.error("sink rule matched 0 methods: " + r.className);
        return new int[]{0, 1, 0};
    }

    private boolean installSilence(final Method m, final Object returnValue) {
        if (!hooked.add(key(m))) return false;
        try {
            m.setAccessible(true);
        } catch (Throwable ignored) {
        }

        final Object value = m.getReturnType() == void.class ? null : coerce(returnValue, m.getReturnType());
        final String label = label(m);
        try {
            module.hook(m).intercept(new XposedInterface.Hooker() {
                private final AtomicInteger hits = new AtomicInteger();

                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (MainHook.isDisabled()) return chain.proceed();
                    int n = hits.incrementAndGet();
                    if (n <= 3 || n % 100 == 0) {
                        log.info("hit silence " + label + " n=" + n);
                    }
                    return value;
                }
            });
            log.info("hooked silence " + label);
            return true;
        } catch (HookFailedError e) {
            log.warn("hook failed " + label + " : " + e);
        } catch (Throwable t) {
            log.warn("hook error " + label + " : " + t);
        }
        return false;
    }

    /** Coerce a rule's configured returnValue to the hooked method's return type. */
    private static Object coerce(Object v, Class<?> type) {
        if (type == void.class) return null;
        if (type == boolean.class || type == Boolean.class) {
            if (v instanceof Boolean) return v;
            if (v == null) return Boolean.FALSE;
            return Boolean.parseBoolean(String.valueOf(v));
        }
        if (type == int.class || type == Integer.class) {
            if (v instanceof Number) return ((Number) v).intValue();
            try { return v == null ? 0 : Integer.parseInt(String.valueOf(v)); } catch (Throwable t) { return 0; }
        }
        if (type == long.class || type == Long.class) {
            if (v instanceof Number) return ((Number) v).longValue();
            try { return v == null ? 0L : Long.parseLong(String.valueOf(v)); } catch (Throwable t) { return 0L; }
        }
        if (type == float.class || type == Float.class) {
            if (v instanceof Number) return ((Number) v).floatValue();
            try { return v == null ? 0f : Float.parseFloat(String.valueOf(v)); } catch (Throwable t) { return 0f; }
        }
        if (type == double.class || type == Double.class) {
            if (v instanceof Number) return ((Number) v).doubleValue();
            try { return v == null ? 0d : Double.parseDouble(String.valueOf(v)); } catch (Throwable t) { return 0d; }
        }
        if (type == short.class || type == Short.class) {
            if (v instanceof Number) return ((Number) v).shortValue();
            return (short) 0;
        }
        if (type == byte.class || type == Byte.class) {
            if (v instanceof Number) return ((Number) v).byteValue();
            return (byte) 0;
        }
        if (type == char.class || type == Character.class) {
            String s = String.valueOf(v);
            return s.isEmpty() ? '\0' : s.charAt(0);
        }
        if (v == null) return null;
        if (type.isInstance(v)) return v;
        if (type == String.class) return String.valueOf(v);
        return null;
    }

    private boolean installSink(final Method m, final RuleConfig.SinkRule r) {
        if (!hooked.add(key(m))) return false;
        try {
            m.setAccessible(true);
        } catch (Throwable ignored) {
        }

        final String label = label(m);
        try {
            module.hook(m).intercept(new XposedInterface.Hooker() {
                private final AtomicInteger hits = new AtomicInteger();

                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (MainHook.isDisabled()) return chain.proceed();

                    Object event;
                    Object appId = null;
                    try {
                        event = chain.getArg(r.eventIndex);
                        if (r.appIdIndex >= 0) appId = chain.getArg(r.appIdIndex);
                    } catch (Throwable t) {
                        return chain.proceed();
                    }
                    if (!(event instanceof String)) return chain.proceed();
                    if (r.appIdIndex >= 0 && !r.appIds.isEmpty()) {
                        if (appId == null || !r.appIds.contains(String.valueOf(appId))) {
                            return chain.proceed();
                        }
                    }

                    String lower = ((String) event).toLowerCase(Locale.ROOT);
                    boolean block = r.blockAll || r.eventExact.contains(lower);
                    if (!block) {
                        for (String prefix : r.eventPrefixes) {
                            if (lower.startsWith(prefix)) {
                                block = true;
                                break;
                            }
                        }
                    }
                    if (!block) return chain.proceed();

                    int n = hits.incrementAndGet();
                    if (n <= 3 || n % 100 == 0) {
                        log.info("blocked event appId=" + appId + " event=" + event
                                + " via " + label + " n=" + n);
                    }
                    return null;
                }
            });
            log.info("hooked sink " + label);
            return true;
        } catch (HookFailedError e) {
            log.warn("sink hook failed " + label + " : " + e);
        } catch (Throwable t) {
            log.warn("sink hook error " + label + " : " + t);
        }
        return false;
    }

    private static List<Method> findMethods(Class<?> c, RuleConfig.MethodSpec spec, boolean superClasses) {
        List<Method> out = new ArrayList<>();
        Class<?> k = c;
        while (k != null && k != Object.class) {
            try {
                for (Method m : k.getDeclaredMethods()) {
                    if (matches(m, spec)) out.add(m);
                }
            } catch (Throwable ignored) {
            }
            if (!superClasses) break;
            k = k.getSuperclass();
        }
        return out;
    }

    private static boolean matches(Method m, RuleConfig.MethodSpec spec) {
        if (!m.getName().equals(spec.name)) return false;
        if (!spec.hasParams) return true;
        Class<?>[] ps = m.getParameterTypes();
        if (ps.length != spec.params.size()) return false;
        for (int i = 0; i < ps.length; i++) {
            if (!ps[i].getName().equals(spec.params.get(i))) return false;
        }
        return true;
    }

    private static String key(Method m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getDeclaringClass().getName()).append('#').append(m.getName()).append('(');
        for (Class<?> p : m.getParameterTypes()) {
            sb.append(p.getName()).append(',');
        }
        return sb.append(')').toString();
    }

    private static String label(Method m) {
        StringBuilder sb = new StringBuilder();
        sb.append(shortName(m.getDeclaringClass().getName()))
                .append('#').append(m.getName()).append('(');
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(shortName(ps[i].getName()));
        }
        return sb.append(')').toString();
    }

    private static String shortName(String name) {
        int i = Math.max(name.lastIndexOf('.'), name.lastIndexOf('$'));
        return i >= 0 ? name.substring(i + 1) : name;
    }

    private static String describe(RuleConfig.MethodSpec spec) {
        if (!spec.hasParams) return spec.name + "(...)";
        return spec.name + "(" + String.join(",", spec.params) + ")";
    }
}
