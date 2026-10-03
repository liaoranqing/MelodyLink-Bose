package com.melody.melodylink.hook;

/**
 * Reports which hard-coded host class names still exist in the running Melody build.
 *
 * <p>Why this exists: this module targets a specific Melody build, and Melody renames its
 * obfuscated classes between releases. Every "the page is blank" / "the slider disappeared"
 * report so far was really "the class name we anchored on no longer exists, and the hook
 * silently did nothing". The module already logs each failure, but the failures are spread
 * across dozens of hooks and easy to miss.
 *
 * <p>This produces one compact {@code evt=class.audit} line listing every miss, so a single
 * grep after a Melody update tells us exactly what to rename — instead of guessing, which is
 * how 0.4.x and 0.5.x accumulated a stack of confidently-wrong fixes.
 */
final class ClassAudit {

    private ClassAudit() {
    }

    /** Class names this module hooks by literal string. */
    private static final String[] ANCHORS = {
            "com.oplus.melody.ui.component.detail.DetailMainActivity",
            "com.oplus.melody.ui.component.detail.DetailMainViewModel",
            "com.oplus.melody.ui.component.detail.noisereduction.NoiseReductionItem",
            "com.oplus.melody.ui.component.detail.equalizer.EqualizerItem",
            "com.oplus.melody.ui.component.detail.spatialaudio.SpatialAudioItem",
            "com.oplus.melody.ui.widget.MelodyDetailModelView",
            "com.oplus.melody.ui.widget.MelodyPromptVolumeSeekBarPreference",
            "com.oplus.melody.ui.widget.MelodyUiTipsSwitchPreference",
            "com.oplus.melody.common.widget.MelodyCOUIPreferenceCategory",
            "com.oplus.melody.common.widget.MelodyCOUIPreference",
            "com.oplus.melody.onespace.items.OneSpaceNoisePreference",
            "com.oplus.melody.onespace.items.OneSpaceHeaderPreference",
            "com.oplus.melody.onespace.OneSpaceDetailActivity",
            "androidx.preference.Preference",
            "androidx.preference.PreferenceGroup",
            "androidx.preference.PreferenceCategory",
            "androidx.preference.g",
            "com.coui.appcompat.preference.COUIPreferenceCategory",
            "com.coui.appcompat.preference.COUISwitchPreference",
            "com.oplus.melody.btsdk.api.data.DeviceInfo",
            "com.oplus.melody.btsdk.multidevice.HeadsetCoreService",
            "com.oplus.melody.model.repository.earphone.EarphoneDTO",
            "com.oplus.melody.provider.EarphoneControlProvider",
    };

    /**
     * Runs once per process after the hooks are installed. Emits one summary event plus one
     * event per missing class, all greppable via {@code grep 'evt=class.'}.
     */
    static void run(ClassLoader loader) {
        if (loader == null) return;
        int found = 0;
        StringBuilder missing = new StringBuilder();
        for (String name : ANCHORS) {
            boolean present;
            try {
                present = Class.forName(name, false, loader) != null;
            } catch (Throwable t) {
                present = false;
            }
            if (present) {
                found++;
            } else {
                if (missing.length() > 0) missing.append(',');
                missing.append(name);
                MLog.event("class.missing", "name", name);
            }
        }
        MLog.event("class.audit",
                "found", found,
                "total", ANCHORS.length,
                "missing", missing.length() == 0 ? "none" : missing.toString());
    }
}
