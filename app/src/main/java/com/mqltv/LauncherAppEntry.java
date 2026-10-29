package com.mqltv;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

public final class LauncherAppEntry {
    public final String label;
    public final Drawable icon;
    public final Drawable banner;
    public final ComponentName component;
    public final boolean isAddButton;
    public final boolean isLiveTv;
    public final String customSubtitle;

    public LauncherAppEntry(String label, Drawable icon, Drawable banner, ComponentName component, boolean isAddButton) {
        this(label, icon, banner, component, isAddButton, false, null);
    }

    public LauncherAppEntry(String label, Drawable icon, Drawable banner, ComponentName component, boolean isAddButton, boolean isLiveTv, String customSubtitle) {
        this.label = label;
        this.icon = icon;
        this.banner = banner;
        this.component = component;
        this.isAddButton = isAddButton;
        this.isLiveTv = isLiveTv;
        this.customSubtitle = customSubtitle;
    }

    public static LauncherAppEntry createLiveTvEntry(android.content.Context context, String subtitle) {
        Drawable icon = null;
        try {
            icon = androidx.core.content.ContextCompat.getDrawable(context, R.mipmap.ic_launcher);
        } catch (Exception ignored) {
        }
        return new LauncherAppEntry("Live TV", icon, null, null, false, true, subtitle != null ? subtitle : "+5000 Saluran");
    }

    public static LauncherAppEntry fromResolveInfo(ResolveInfo ri, android.content.pm.PackageManager pm) {
        String label = ri.loadLabel(pm) != null ? ri.loadLabel(pm).toString() : "App";
        ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);

        Drawable icon = null;
        android.content.res.Resources appRes = null;
        try {
            if (ri.activityInfo != null && ri.activityInfo.applicationInfo != null) {
                appRes = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
            }
        } catch (Exception ignored) {
        }

        try {
            int iconRes = ri.activityInfo != null ? ri.activityInfo.getIconResource() : 0;
            if (iconRes != 0) {
                if (appRes != null) {
                    try {
                        icon = appRes.getDrawableForDensity(iconRes, android.util.DisplayMetrics.DENSITY_XXHIGH);
                    } catch (Throwable ignored) {
                    }
                }
                if (icon == null) {
                    icon = pm.getDrawable(ri.activityInfo.packageName, iconRes, ri.activityInfo.applicationInfo);
                }
            }
        } catch (Exception ignored) {
        }

        if (icon == null) {
            try {
                ApplicationInfo ai = ri.activityInfo != null ? ri.activityInfo.applicationInfo : null;
                if (ai != null && ai.icon != 0) {
                    if (appRes != null) {
                        try {
                            icon = appRes.getDrawableForDensity(ai.icon, android.util.DisplayMetrics.DENSITY_XXHIGH);
                        } catch (Throwable ignored) {
                        }
                    }
                    if (icon == null) {
                        icon = pm.getApplicationIcon(ai);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // Final fallback: ResolveInfo may still provide a generic icon.
        if (icon == null) {
            try {
                icon = ri.loadIcon(pm);
            } catch (Exception ignored) {
                icon = null;
            }
        }

        Drawable banner = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            try {
                if (ri.activityInfo != null) {
                    banner = ri.activityInfo.loadBanner(pm);
                }
                if (banner == null && ri.activityInfo != null && ri.activityInfo.applicationInfo != null) {
                    banner = pm.getApplicationBanner(ri.activityInfo.applicationInfo);
                }
            } catch (Exception ignored) {
            }
        }

        if (banner == null && appRes != null && ri.activityInfo != null) {
            banner = loadFallbackBanner(appRes, ri.activityInfo.packageName);
        }

        if (banner != null && appRes != null && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
            banner = toRoundedBanner(appRes, banner);
        }

        return new LauncherAppEntry(label, icon, banner, cn, false);
    }

    private static Drawable toRoundedBanner(android.content.res.Resources res, Drawable src) {
        try {
            int w = 320;
            int h = 180;
            android.graphics.Bitmap raw = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas rawCanvas = new android.graphics.Canvas(raw);
            src.setBounds(0, 0, w, h);
            src.draw(rawCanvas);

            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(out);
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
            p.setShader(new android.graphics.BitmapShader(raw, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
            android.graphics.RectF rect = new android.graphics.RectF(0f, 0f, w, h);
            float r = 18f;
            c.drawRoundRect(rect, r, r, p);

            android.graphics.Paint border = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            border.setStyle(android.graphics.Paint.Style.STROKE);
            border.setStrokeWidth(1.5f);
            border.setColor(0x26FFFFFF);
            android.graphics.RectF borderRect = new android.graphics.RectF(0.75f, 0.75f, w - 0.75f, h - 0.75f);
            c.drawRoundRect(borderRect, r, r, border);

            raw.recycle();
            return new android.graphics.drawable.BitmapDrawable(res, out);
        } catch (Throwable ignored) {
            return src;
        }
    }

    @Nullable
    private static Drawable loadFallbackBanner(android.content.res.Resources appRes, String pkg) {
        try {
            android.content.res.XmlResourceParser parser = appRes.getAssets().openXmlResourceParser("AndroidManifest.xml");
            int bannerRes = 0;
            int eventType;
            while ((eventType = parser.next()) != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("application".equals(tag) || "activity".equals(tag) || "activity-alias".equals(tag)) {
                        for (int i = 0; i < parser.getAttributeCount(); i++) {
                            if (parser.getAttributeNameResource(i) == 0x010103f2 || "banner".equals(parser.getAttributeName(i))) {
                                int resId = parser.getAttributeResourceValue(i, 0);
                                if (resId != 0) {
                                    bannerRes = resId;
                                    if (!"application".equals(tag)) break;
                                }
                            }
                        }
                    }
                }
            }
            parser.close();
            if (bannerRes != 0) {
                Drawable d = appRes.getDrawable(bannerRes);
                if (d != null) return d;
            }
        } catch (Throwable ignored) {
        }

        String[] names = {"tv_banner", "banner", "ic_banner", "app_banner"};
        String[] types = {"drawable", "mipmap"};
        for (String type : types) {
            for (String name : names) {
                try {
                    int id = appRes.getIdentifier(name, type, pkg);
                    if (id != 0) {
                        Drawable d = appRes.getDrawable(id);
                        if (d != null && d.getIntrinsicWidth() > d.getIntrinsicHeight() * 1.2f) {
                            return d;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
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
