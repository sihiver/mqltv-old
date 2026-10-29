package com.mqltv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;

final class SystemSettings {
    private SystemSettings() {}

    private static final String[] FALLBACK_COMPONENTS = {
            "com.xiaomi.mitv.settings/com.xiaomi.mitv.settings.entry.MainActivity",
            "com.tcl.settings/com.tcl.settings.ui.MainActivity",
            "com.amazon.tv.launcher/com.amazon.tv.launcher.ui.MainSettingsActivity"
    };

    private static final ComponentName AOSP_TV_SETTINGS =
            new ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings");

    static Intent intent(Context context, String action) {
        PackageManager pm = context.getPackageManager();
        Intent standard = new Intent(action);
        ResolveInfo resolved = pm.resolveActivity(standard, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolved != null && resolved.activityInfo != null && !resolved.activityInfo.name.contains("CTSDummy")) {
            return standard;
        }
        Intent vendor = vendorIntent(context);
        if (vendor != null) return vendor;

        Intent aosp = new Intent().setComponent(AOSP_TV_SETTINGS);
        try {
            pm.getPackageInfo(AOSP_TV_SETTINGS.getPackageName(), 0);
            return aosp;
        } catch (PackageManager.NameNotFoundException e) {
            return new Intent(Settings.ACTION_SETTINGS);
        }
    }

    static Intent vendorIntent(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String value : FALLBACK_COMPONENTS) {
            ComponentName component = ComponentName.unflattenFromString(value);
            if (component == null) continue;
            try {
                pm.getPackageInfo(component.getPackageName(), 0);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            return new Intent().setComponent(component);
        }
        return null;
    }

    static Intent appInfo(Context context, String pkg) {
        Intent info = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
        return context.getPackageManager().resolveActivity(info, PackageManager.MATCH_DEFAULT_ONLY) != null ? info : null;
    }
}

