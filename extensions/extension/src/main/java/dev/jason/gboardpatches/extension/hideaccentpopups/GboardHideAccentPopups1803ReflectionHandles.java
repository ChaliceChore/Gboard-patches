package dev.jason.gboardpatches.extension.hideaccentpopups;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/** Reflection handles for the Gboard 18.0.3 SoftKeyDef / ActionDef metadata model. */
final class GboardHideAccentPopups1803ReflectionHandles {
    private static final String SOFT_KEY_DEF_CLASS =
            "com.google.android.libraries.inputmethod.metadata.SoftKeyDef";
    private static final String ACTION_DEF_CLASS =
            "com.google.android.libraries.inputmethod.metadata.ActionDef";
    private static final String ACTION_TYPE_CLASS = "pmy";
    private static final String ACTION_ENTRY_CLASS = "pnu";
    private static final String ACTION_BUILDER_CLASS = "pmz";
    private static final String METADATA_BUILDER_CLASS = "ppo";

    private final Class<?> softKeyDefClass;
    private final Class<?> actionEntryClass;
    private final Method exactActionLookupMethod;
    private final Field actionEntriesField;
    private final Field popupLabelsField;
    private final Field popupIconsField;
    private final Field entryKeycodeField;
    private final Field entryPayloadField;
    private final Constructor<?> actionBuilderConstructor;
    private final Method copyActionPropertiesMethod;
    private final Field actionBuilderEntriesField;
    private final Field actionBuilderLabelsField;
    private final Field actionBuilderIconsField;
    private final Method buildActionMethod;
    private final Constructor<?> metadataBuilderConstructor;
    private final Method copyMetadataMethod;
    private final Method putActionMethod;
    private final Field metadataBuilderActionsField;
    private final Method buildMetadataMethod;
    private final Object pressActionType;
    private final Object longPressActionType;

    GboardHideAccentPopups1803ReflectionHandles(ClassLoader classLoader) throws Throwable {
        softKeyDefClass = resolve(classLoader, SOFT_KEY_DEF_CLASS);
        Class<?> actionDefClass = resolve(classLoader, ACTION_DEF_CLASS);
        Class<?> actionTypeClass = resolve(classLoader, ACTION_TYPE_CLASS);
        actionEntryClass = resolve(classLoader, ACTION_ENTRY_CLASS);
        Class<?> actionBuilderClass = resolve(classLoader, ACTION_BUILDER_CLASS);
        Class<?> metadataBuilderClass = resolve(classLoader, METADATA_BUILDER_CLASS);

        exactActionLookupMethod = softKeyDefClass.getDeclaredMethod("h", actionTypeClass);
        actionEntriesField = actionDefClass.getDeclaredField("d");
        popupLabelsField = actionDefClass.getDeclaredField("n");
        popupIconsField = actionDefClass.getDeclaredField("o");
        entryKeycodeField = actionEntryClass.getDeclaredField("c");
        entryPayloadField = actionEntryClass.getDeclaredField("e");

        actionBuilderConstructor = actionBuilderClass.getDeclaredConstructor();
        copyActionPropertiesMethod = actionBuilderClass.getDeclaredMethod("j", actionDefClass);
        actionBuilderEntriesField = actionBuilderClass.getDeclaredField("b");
        actionBuilderLabelsField = actionBuilderClass.getDeclaredField("c");
        actionBuilderIconsField = actionBuilderClass.getDeclaredField("d");
        buildActionMethod = actionBuilderClass.getDeclaredMethod("c");

        metadataBuilderConstructor = metadataBuilderClass.getDeclaredConstructor();
        copyMetadataMethod = metadataBuilderClass.getDeclaredMethod("j", softKeyDefClass);
        putActionMethod = metadataBuilderClass.getDeclaredMethod("t", actionDefClass);
        metadataBuilderActionsField = metadataBuilderClass.getDeclaredField("b");
        buildMetadataMethod = metadataBuilderClass.getDeclaredMethod("d");

        if (actionEntriesField.getType().getComponentType() != actionEntryClass
                || actionBuilderEntriesField.getType().getComponentType() != actionEntryClass
                || popupLabelsField.getType() != String[].class
                || popupIconsField.getType() != int[].class
                || entryKeycodeField.getType() != int.class
                || !Map.class.isAssignableFrom(metadataBuilderActionsField.getType())
                || buildActionMethod.getReturnType() != actionDefClass) {
            throw new IllegalStateException("18.0.3 SoftKeyDef/ActionDef shape drift");
        }

        AccessibleObject.setAccessible(new AccessibleObject[] {
                exactActionLookupMethod,
                actionEntriesField,
                popupLabelsField,
                popupIconsField,
                entryKeycodeField,
                entryPayloadField,
                actionBuilderConstructor,
                copyActionPropertiesMethod,
                actionBuilderEntriesField,
                actionBuilderLabelsField,
                actionBuilderIconsField,
                buildActionMethod,
                metadataBuilderConstructor,
                copyMetadataMethod,
                putActionMethod,
                metadataBuilderActionsField,
                buildMetadataMethod,
        }, true);

        pressActionType = enumValue(actionTypeClass, "PRESS");
        longPressActionType = enumValue(actionTypeClass, "LONG_PRESS");
    }

    boolean isSoftKeyMetadata(Object metadata) {
        return softKeyDefClass.isInstance(metadata);
    }

    String extractPressText(Object metadata) throws Throwable {
        Object[] entries = extractEntries(exactActionLookupMethod.invoke(metadata, pressActionType));
        if (entries.length == 0 || entries[0] == null) {
            return null;
        }
        Object payload = entryPayloadField.get(entries[0]);
        return payload instanceof CharSequence ? payload.toString() : null;
    }

    /**
     * Returns a copy of {@code metadata} without the accented long-press entries, or {@code null}
     * when the key has nothing to hide.
     */
    Object withoutAccentedLongPressEntries(Object metadata, String pressText) throws Throwable {
        Object longPressAction = exactActionLookupMethod.invoke(metadata, longPressActionType);
        Object[] entries = extractEntries(longPressAction);
        int[] keycodes = new int[entries.length];
        Object[] payloads = new Object[entries.length];
        for (int index = 0; index < entries.length; index++) {
            Object entry = entries[index];
            keycodes[index] = entry == null ? 0 : entryKeycodeField.getInt(entry);
            payloads[index] = entry == null ? null : entryPayloadField.get(entry);
        }
        boolean[] keep = GboardHideAccentPopupsPolicy.planKeepMask(pressText, keycodes, payloads);
        if (keep == null) {
            return null;
        }

        int keptCount = 0;
        for (boolean kept : keep) {
            if (kept) {
                keptCount++;
            }
        }
        Object patchedAction = null;
        if (keptCount > 0) {
            patchedAction = buildFilteredAction(longPressAction, entries, keep, keptCount);
            if (patchedAction == null) {
                return null;
            }
        }

        Object metadataBuilder = metadataBuilderConstructor.newInstance();
        copyMetadataMethod.invoke(metadataBuilder, metadata);
        if (patchedAction != null) {
            putActionMethod.invoke(metadataBuilder, patchedAction);
        } else {
            // Nothing but accents: drop LONG_PRESS so the key behaves like one without a popup.
            ((Map<?, ?>) metadataBuilderActionsField.get(metadataBuilder))
                    .remove(longPressActionType);
        }
        return buildMetadataMethod.invoke(metadataBuilder);
    }

    private Object buildFilteredAction(Object action, Object[] entries, boolean[] keep,
            int keptCount) throws Throwable {
        Object actionBuilder = actionBuilderConstructor.newInstance();
        copyActionPropertiesMethod.invoke(actionBuilder, action);

        Object filteredEntries = Array.newInstance(actionEntryClass, keptCount);
        for (int index = 0, target = 0; index < entries.length; index++) {
            if (keep[index]) {
                Array.set(filteredEntries, target++, entries[index]);
            }
        }
        actionBuilderEntriesField.set(actionBuilder, filteredEntries);

        String[] labels = (String[]) popupLabelsField.get(action);
        if (labels != null && labels.length == entries.length) {
            String[] filteredLabels = new String[keptCount];
            for (int index = 0, target = 0; index < entries.length; index++) {
                if (keep[index]) {
                    filteredLabels[target++] = labels[index];
                }
            }
            actionBuilderLabelsField.set(actionBuilder, filteredLabels);
        }

        int[] icons = (int[]) popupIconsField.get(action);
        if (icons != null && icons.length == entries.length) {
            int[] filteredIcons = new int[keptCount];
            for (int index = 0, target = 0; index < entries.length; index++) {
                if (keep[index]) {
                    filteredIcons[target++] = icons[index];
                }
            }
            actionBuilderIconsField.set(actionBuilder, filteredIcons);
        }
        return buildActionMethod.invoke(actionBuilder);
    }

    private Object[] extractEntries(Object action) throws IllegalAccessException {
        Object value = action == null ? null : actionEntriesField.get(action);
        return value instanceof Object[] ? (Object[]) value : new Object[0];
    }

    private static Class<?> resolve(ClassLoader classLoader, String name)
            throws ClassNotFoundException {
        return Class.forName(name, false, classLoader);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumValue(Class<?> enumClass, String name) {
        return Enum.valueOf((Class<? extends Enum>) enumClass.asSubclass(Enum.class), name);
    }
}
