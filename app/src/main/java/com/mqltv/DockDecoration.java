package com.mqltv;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Draws the CleanYourTV / tvOS style translucent dock tray behind the first row of apps
 * and gives the dock subtle vertical breathing room.
 */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public DockDecoration(int dockSize, float density) {
        this.dockSize = dockSize;
        this.density = density;
        band.setColor(0x8014161C); // Translucent dark glass tray
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.2f * density);
        edge.setColor(0x2EFFFFFF); // Subtle crisp border
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos == RecyclerView.NO_POSITION) return;

        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        if (adapter instanceof LauncherAppsAdapter) {
            LauncherAppsAdapter la = (LauncherAppsAdapter) adapter;
            if (la.isLibraryAt(pos)) {
                outRect.set(0, (int) (16 * density), 0, (int) (24 * density));
                return;
            }
        }

        int spanCount = dockSize; // 4 columns
        int column = pos % spanCount;
        int spacing = Math.round(20 * density);

        // Column 0 left = 0 (aligns with left edge / clock), column 3 right = 0 (aligns with right edge)
        outRect.left = column * spacing / spanCount;
        outRect.right = spacing - (column + 1) * spacing / spanCount;

        if (pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (14 * density);
        } else {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (8 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            float l = child.getLeft();
            float t = child.getTop() + child.getTranslationY();
            float r = child.getRight();
            float b = child.getBottom() + child.getTranslationY();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;

        float padX = 4 * density;
        float padY = 4 * density;
        rect.inset(-padX, -padY);
        float radius = 20 * density;

        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, edge);
    }
}
