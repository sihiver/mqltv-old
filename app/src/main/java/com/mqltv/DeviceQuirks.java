package com.mqltv;

import android.os.Build;

import java.util.Locale;

public final class DeviceQuirks {
    private DeviceQuirks() {}

    public static boolean isHuaweiEc6108v9() {
        String model = Build.MODEL;
        String manufacturer = Build.MANUFACTURER;

        if (model == null) return false;

        String m = model.toUpperCase(Locale.US);
        boolean modelMatch = m.contains("EC6108V9") || m.equals("EC6108") || m.contains("EC6108");
        if (!modelMatch) return false;

        if (manufacturer == null) return true;
        return manufacturer.toLowerCase(Locale.US).contains("huawei");
    }

    public static boolean isZteB760H() {
        final String manufacturer = android.os.Build.MANUFACTURER;
        final String model = android.os.Build.MODEL;
        final String device = android.os.Build.DEVICE;

        final String m = manufacturer != null ? manufacturer.toUpperCase(java.util.Locale.US) : "";
        final String mo = model != null ? model.toUpperCase(java.util.Locale.US) : "";
        final String d = device != null ? device.toUpperCase(java.util.Locale.US) : "";

        if (!m.contains("ZTE")) return false;
        return mo.contains("B760") || d.contains("B760");
    }
}
