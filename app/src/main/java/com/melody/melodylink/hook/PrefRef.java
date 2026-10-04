package com.melody.melodylink.hook;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection-only handle to a host {@code androidx.preference} instance.
 *
 * <p>Melody ships androidx.preference, but R8-minified: {@code PreferenceFragmentCompat}
 * becomes {@code androidx.preference.g}. Referencing any of those types as compile-time
 * symbols makes the module APK fail to build ("package androidx.preference does not
 * exist", 0.5.5 CI) and would throw {@code NoClassDefFoundError} at runtime if it did build.
 * Everything here stays dynamic.
 *
 * <p>Design follows Andrea-lyz/MelodyCodecTweaker's {@code PrefRef}, which encodes two hard-won
 * lessons this module previously got wrong:
 *
 * <ol>
 *   <li><b>Prefer the {@code (Context, AttributeSet)} constructor.</b> A programmatically
 *       created COUI category built with only {@code (Context)} never resolves
 *       {@code preferenceCategoryStyle} from the host theme, so it paints its title with the
 *       framework default — white on a light card. The row is present and "successfully
 *       injected" but reads as a blank card. That was the real cause of the blank earbud
 *       detail page, not a crash (the crash buffer only ever showed an unrelated
 *       {@code com.oplus.gesture} system bug).</li>
 *   <li><b>Resolve setters by name, then fall back to signature — and never guess.</b> A pure
 *       name lookup fails <em>silently</em> after an R8 rename: no exception, the value simply
 *       never lands. The fallback re-discovers the setter by parameter type only when exactly
 *       one candidate exists on the whole hierarchy; two or more candidates and we do nothing,
 *       because writing into the wrong setter is worse than the no-op.</li>
 * </ol>
 */
final class PrefRef {

    private PrefRef() {
    }

    // ------------------------------------------------------------- lifecycle

    static Class<?> load(ClassLoader cl, String className) {
        if (cl == null || className == null) return null;
        try {
            return Class.forName(className, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    static Object newInstance(Class<?> cls, Context context) {
        if (cls == null || context == null) return null;
        try {
            Constructor<?> ctor = cls.getConstructor(Context.class);
            return ctor.newInstance(context);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Builds a host preference so it inherits the host theme.
     *
     * <p>Without the {@code AttributeSet} overload, COUI / androidx cannot read
     * {@code R.attr.preferenceCategoryStyle} and the widget paints with framework defaults —
     * the injected Bose cards rendered as blank white-on-white. Falls back to the single-arg
     * constructor when the host class does not declare the two-arg form.
     */
    static Object newInstanceWithAttrs(Class<?> cls, Context context) {
        if (cls == null || context == null) return null;
        try {
            Constructor<?> ctor = cls.getConstructor(Context.class, android.util.AttributeSet.class);
            return ctor.newInstance(context, null);
        } catch (Throwable ignored) {
        }
        return newInstance(cls, context);
    }

    /**
     * Creates a host preference by class name, trying the theming constructor first.
     * Returns null when the class is absent in this Melody build.
     */
    static Object create(ClassLoader cl, String className, Context context) {
        return newInstanceWithAttrs(load(cl, className), context);
    }

    // --------------------------------------------------------------- setters

    /**
     * {@code setKey} takes a {@code String}, not a {@code CharSequence}. Matching against
     * {@code CharSequence.class} silently no-ops, leaving {@code mKey == null}, and the panel
     * then throws "Key cannot be null" the moment anything looks the preference up by key.
     */
    static void setKey(Object pref, String key) {
        invokeSetter(pref, "setKey", String.class, key);
    }

    static void setTitle(Object pref, CharSequence title) {
        invokeSetter(pref, "setTitle", CharSequence.class, title);
    }

    static CharSequence getTitle(Object pref) {
        Object r = invoke(pref, "getTitle", new Class[0], new Object[0]);
        return r instanceof CharSequence ? (CharSequence) r : null;
    }

    static void setSummary(Object pref, CharSequence summary) {
        invokeSetter(pref, "setSummary", CharSequence.class, summary);
    }

    static void setOrder(Object pref, int order) {
        invokeSetter(pref, "setOrder", int.class, order);
    }

    static void setVisible(Object pref, boolean visible) {
        invokeSetter(pref, "setVisible", boolean.class, visible);
    }

    static boolean isVisible(Object pref) {
        Object r = invoke(pref, "isVisible", new Class[0], new Object[0]);
        return !(r instanceof Boolean) || (Boolean) r;
    }

    static void setPersistent(Object pref, boolean persistent) {
        invokeSetter(pref, "setPersistent", boolean.class, persistent);
    }

    static void setEnabled(Object pref, boolean enabled) {
        invokeSetter(pref, "setEnabled", boolean.class, enabled);
    }

    static void setChecked(Object pref, boolean checked) {
        invokeSetter(pref, "setChecked", boolean.class, checked);
    }

    // --------------------------------------------------------------- getters

    static int getOrder(Object pref) {
        Object r = invoke(pref, "getOrder", new Class[0], new Object[0]);
        return r instanceof Integer ? (Integer) r : -1;
    }

    static String getKey(Object pref) {
        Object r = invoke(pref, "getKey", new Class[0], new Object[0]);
        return r == null ? null : r.toString();
    }

    static Object getParent(Object pref) {
        return invoke(pref, "getParent", new Class[0], new Object[0]);
    }

    static Object invokeNoArg(Object target, String name) {
        return invoke(target, name, new Class[0], new Object[0]);
    }

    static Object invoke1Arg(Object target, String name, Class<?> paramType, Object arg) {
        return invoke(target, name, new Class[]{paramType}, new Object[]{arg});
    }

    // ------------------------------------------------------------------ tree

    static Object getPreferenceScreen(Object fragment) {
        if (fragment == null) return null;
        Object screen = invokeNoArg(fragment, "getPreferenceScreen");
        if (screen != null) return screen;
        Object manager = invokeNoArg(fragment, "getPreferenceManager");
        if (manager != null) {
            Object via = invokeNoArg(manager, "getPreferenceScreen");
            if (via != null) return via;
        }
        return findPreferenceScreenInFields(fragment, 3);
    }

    /** R8 preserves type names, so a field scan beats a renamed getter. */
    private static Object findPreferenceScreenInFields(Object owner, int depth) {
        if (owner == null || depth <= 0) return null;
        for (Class<?> cls = owner.getClass(); cls != null && cls != Object.class;
                cls = cls.getSuperclass()) {
            for (Field f : cls.getDeclaredFields()) {
                if (!f.getType().getName().equals("androidx.preference.PreferenceScreen")) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(owner);
                    if (value != null) return value;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    static Object findPreference(Object container, CharSequence key) {
        if (container == null || key == null) return null;
        Object r = invoke(container, "findPreference",
                new Class[]{CharSequence.class}, new Object[]{key});
        if (r != null) return r;
        Class<?> cls = container.getClass();
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                Class<?> p = m.getParameterTypes()[0];
                if (p != CharSequence.class && p != String.class) continue;
                if (m.getReturnType() == void.class || m.getReturnType().isPrimitive()) continue;
                try {
                    m.setAccessible(true);
                    Object v = m.invoke(container, key);
                    if (v != null) return v;
                } catch (Throwable ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /**
     * Depth-first search for a key anywhere under a group.
     *
     * <p><b>Why the extra key check.</b> 0.5.48 evidence: {@code bose.inject.inline ok=true}
     * was logged while {@code bose.injected} never was, and {@code parent} reported
     * {@code COUIPreferenceCategory/0} — a group with zero children. The caller treats a
     * non-null return as "we already injected", so a false positive there permanently
     * short-circuits the whole install path. COUI's own {@code findPreference} has
     * "most recently added" fallback semantics, so a hit does not prove the key matches.
     * Therefore the found object's own key is verified before it is accepted.
     */
    static Object findPreferenceRecursive(Object container, String key) {
        if (container == null || key == null) return null;
        Object direct = findPreference(container, key);
        if (direct != null && keyMatches(direct, key)) return direct;
        int count = getPreferenceCount(container);
        for (int i = 0; i < count; i++) {
            Object child = getPreference(container, i);
            if (child == null) continue;
            Object hit = findPreferenceRecursive(child, key);
            if (hit != null) return hit;
        }
        return null;
    }

    /** True only when {@code pref}'s own key is exactly {@code key}. */
    private static boolean keyMatches(Object pref, String key) {
        try {
            Object r = invoke(pref, "getKey", new Class[0], new Object[0]);
            if (r instanceof String) return key.equals(r);
        } catch (Throwable ignored) {
        }
        return false;
    }

    static int getPreferenceCount(Object container) {
        Object r = invokeNoArg(container, "getPreferenceCount");
        if (r instanceof Integer) return (Integer) r;
        java.util.List<?> children = getChildrenList(container);
        return children == null ? 0 : children.size();
    }

    static Object getPreference(Object container, int index) {
        Object r = invoke(container, "getPreference", new Class[]{int.class}, new Object[]{index});
        if (r != null) return r;
        java.util.List<?> list = getChildrenList(container);
        if (list != null && index >= 0 && index < list.size()) return list.get(index);
        return null;
    }

    /**
     * The {@code List<Preference>} a PreferenceGroup keeps its children in.
     *
     * <p>0.5.48: the old version skipped empty lists outright ({@code if (list.isEmpty())
     * continue}), so a freshly created, genuinely empty group — exactly the state the
     * detail page was in — reported "no children list" instead of "zero children", and the
     * post-insert read-back check could not confirm anything. A declared
     * {@code List<Preference>} field is now accepted on type alone (with an optional
     * element check when it happens to be non-empty), so an empty group still resolves.
     */
    private static java.util.List<?> getChildrenList(Object container) {
        if (container == null) return null;
        for (Class<?> cls = container.getClass(); cls != null && cls != Object.class;
                cls = cls.getSuperclass()) {
            for (Field f : cls.getDeclaredFields()) {
                if (!java.util.List.class.equals(f.getType())) continue;
                java.util.List<?> list = null;
                try {
                    f.setAccessible(true);
                    Object v = f.get(container);
                    if (v instanceof java.util.List) list = (java.util.List<?>) v;
                } catch (Throwable ignored) {
                }
                if (list == null) continue;
                // An empty list is accepted: it is the natural state of a group we are about
                // to inject into, and there is no element to type-check.
                if (list.isEmpty()) return list;
                Object first = list.get(0);
                if (first == null) continue;
                // A Preference child exposes getKey; anything else is some other list.
                try {
                    first.getClass().getMethod("getKey");
                    return list;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /**
     * Adds a child preference to a group.
     *
     * <p><b>Why this is not a simple signature scan.</b> On Melody 17.6.3
     * {@code androidx.preference.PreferenceGroup} declares THREE methods that take a single
     * {@code Preference} (verified in smali):
     * <pre>
     *   .method public final f(Landroidx/preference/Preference;)V   // addPreference
     *   .method public final j(Landroidx/preference/Preference;)Z
     *   .method public final k(Landroidx/preference/Preference;)Z
     * </pre>
     * Every COUI category inherits all three. The previous implementation bailed out with
     * "ambiguous, do not guess" as soon as it saw a second candidate, so
     * {@code addPreference} returned {@code false} for every container on every run — which
     * is why the detail page stayed blank from 0.4.x all the way to 0.5.48 while dozens of
     * unrelated probes were added.
     *
     * <p>Resolution is therefore by <em>return type</em>: the real {@code addPreference}
     * returns {@code void}. A boolean-returning overload is a query ("is this already a
     * child?"), never an insertion, so it is only used as a last-resort fallback.
     *
     * <p>Every attempt is verified by reading the child list back, because R8 may also
     * rename {@code addPreference} itself in other host builds.
     */
    static boolean addPreference(Object container, Object pref) {
        if (container == null || pref == null) return false;
        ClassLoader cl = container.getClass().getClassLoader();
        Method voidCandidate = null;
        java.util.List<Method> booleanCandidates = new java.util.ArrayList<>();
        for (Class<?> cls = container.getClass(); cls != null && cls != Object.class;
                cls = cls.getSuperclass()) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                String pn = m.getParameterTypes()[0].getName();
                if (!pn.equals("androidx.preference.Preference")
                        && !isPreferenceSubclass(cl, pn)) continue;
                if (m.getReturnType() == void.class) {
                    if (voidCandidate == null) voidCandidate = m;
                } else if (m.getReturnType() == boolean.class
                        || m.getReturnType() == Boolean.class) {
                    booleanCandidates.add(m);
                }
            }
        }
        // Preferred: the void-returning insert. Fall back to a boolean overload only when
        // no void one exists anywhere in the hierarchy.
        if (voidCandidate != null && tryAdd(container, pref, voidCandidate)) return true;
        for (Method candidate : booleanCandidates) {
            if (tryAdd(container, pref, candidate) && containsChild(container, pref)) return true;
        }
        // Last resort: a renamed insert that returns something non-boolean.
        for (Class<?> cls = container.getClass(); cls != null && cls != Object.class;
                cls = cls.getSuperclass()) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (m.getReturnType() == void.class || m.getReturnType().isPrimitive()) continue;
                String pn = m.getParameterTypes()[0].getName();
                if (!pn.equals("androidx.preference.Preference")
                        && !isPreferenceSubclass(cl, pn)) continue;
                if (tryAdd(container, pref, m) && containsChild(container, pref)) return true;
            }
        }
        return false;
    }

    private static boolean tryAdd(Object container, Object pref, Method target) {
        try {
            target.setAccessible(true);
            target.invoke(container, pref);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Read-back check: did the child actually land in the group's list? */
    private static boolean containsChild(Object container, Object pref) {
        try {
            int count = getPreferenceCount(container);
            for (int i = 0; i < count; i++) {
                if (getPreference(container, i) == pref) return true;
            }
            java.util.List<?> children = getChildrenList(container);
            if (children != null && children.contains(pref)) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isPreferenceSubclass(ClassLoader cl, String className) {
        try {
            Class<?> base = Class.forName("androidx.preference.Preference", false, cl);
            Class<?> type = Class.forName(className, false, cl);
            return base.isAssignableFrom(type);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Makes room for an injected block by pushing every later sibling down {@code delta}.
     *
     * <p>Without this the injected rows collide with the host's own order values, and COUI —
     * which groups consecutive orders into one rounded card — merges or drops rows. That is
     * what produced the "items stuck together" report and, together with the theming
     * constructor bug, the blank page.
     */
    static void shiftPreferenceOrders(Object container, int threshold, int delta) {
        int count = getPreferenceCount(container);
        for (int i = 0; i < count; i++) {
            Object pref = getPreference(container, i);
            if (pref == null) continue;
            int order = getOrder(pref);
            if (order >= threshold) setOrder(pref, order + delta);
        }
    }

    // -------------------------------------------------------------- dispatch

    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    static void invokeSetter(Object target, String name, Class<?> paramType, Object value) {
        if (target == null) return;
        Method byName = findMethod(target.getClass(), name, new Class[]{paramType});
        if (byName != null) {
            try {
                byName.setAccessible(true);
                byName.invoke(target, value);
                return;
            } catch (Throwable ignored) {
            }
        }
        Method bySig = findUniqueVoidSetter(target.getClass(), paramType);
        if (bySig == null) {
            MLog.w("PrefRef." + name + " unresolved on " + target.getClass().getName());
            return;
        }
        try {
            bySig.setAccessible(true);
            bySig.invoke(target, value);
            MLog.event("prefref.setter.sigfallback",
                    "logical", name,
                    "resolved", bySig.getName(),
                    "type", paramType.getSimpleName());
        } catch (Throwable ignored) {
        }
    }

    /**
     * The single 1-arg {@code void} method anywhere on the hierarchy accepting
     * {@code paramType}, or null when absent or ambiguous. String and CharSequence count as
     * interchangeable because androidx mixes them across setKey / setTitle.
     */
    private static Method findUniqueVoidSetter(Class<?> startCls, Class<?> paramType) {
        boolean textType = paramType == String.class || paramType == CharSequence.class;
        Method match = null;
        for (Class<?> cls = startCls; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (m.getReturnType() != void.class) continue;
                if (m.isSynthetic() || m.isBridge()) continue;
                Class<?> p = m.getParameterTypes()[0];
                boolean accepts = textType
                        ? (p == String.class || p == CharSequence.class)
                        : p == paramType;
                if (!accepts) continue;
                if (match != null && !match.getName().equals(m.getName())) return null;
                match = m;
            }
        }
        return match;
    }

    private static Object invoke(Object target, String name, Class<?>[] paramTypes, Object[] args) {
        if (target == null) return null;
        Method m = findMethod(target.getClass(), name, paramTypes);
        if (m == null) return null;
        try {
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethod(Class<?> startCls, String name, Class<?>[] paramTypes) {
        String cacheKey = startCls.getName() + '#' + name + '#' + paramTypes.length;
        Method cached = METHOD_CACHE.get(cacheKey);
        if (cached != null) return cached;
        for (Class<?> cls = startCls; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            try {
                Method m = cls.getDeclaredMethod(name, paramTypes);
                METHOD_CACHE.put(cacheKey, m);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    /** Every declared method on the whole hierarchy — the pre-PrefRef helper kept for the
     *  non-Preference call sites (COUI builders, dynamic proxies) that still need it. */
    static Method[] allMethods(Class<?> startCls) {
        java.util.LinkedHashSet<Method> out = new java.util.LinkedHashSet<>();
        for (Class<?> cls = startCls; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.isSynthetic() && !m.isBridge()) out.add(m);
            }
        }
        return out.toArray(new Method[0]);
    }
}
