package com.mqltv;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

public final class LauncherAppEntry {
    public final String label;
    public final Drawable icon;
    public final ComponentName component;
    public final boolean isAddButton;

    public LauncherAppEntry(String label, Drawable icon, ComponentName component, boolean isAddButton) {
        this.label = label;
        this.icon = icon;
        this.component = component;
        this.isAddButton = isAddButton;
    }

    public static LauncherAppEntry fromResolveInfo(ResolveInfo ri, android.content.pm.PackageManager pm) {
        String label = ri.loadLabel(pm) != null ? ri.loadLabel(pm).toString() : "App";
        Drawable icon = ri.loadIcon(pm);
        ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
        return new LauncherAppEntry(label, icon, cn, false);
    }

    @Nullable
    public Intent buildLaunchIntent() {
        if (component == null) return null;
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(component);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }
}
