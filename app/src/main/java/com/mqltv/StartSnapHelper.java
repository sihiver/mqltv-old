package com.mqltv;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.OrientationHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SnapHelper;

/**
 * Snap the closest child to the start (left) edge.
 * Keeps cards fully visible (no half-cut) on TV.
 */
public class StartSnapHelper extends SnapHelper {

    @Nullable
    private OrientationHelper horizontalHelper;

    @Override
    public int[] calculateDistanceToFinalSnap(@NonNull RecyclerView.LayoutManager layoutManager, @NonNull View targetView) {
        int[] out = new int[] {0, 0};
        if (layoutManager.canScrollHorizontally()) {
            out[0] = distanceToStart(targetView, getHorizontalHelper(layoutManager));
        }
        return out;
    }

    @Nullable
    @Override
    public View findSnapView(RecyclerView.LayoutManager layoutManager) {
        if (!(layoutManager instanceof RecyclerView.SmoothScroller.ScrollVectorProvider)) {
            return null;
        }
        if (!layoutManager.canScrollHorizontally()) return null;

        OrientationHelper helper = getHorizontalHelper(layoutManager);

        int childCount = layoutManager.getChildCount();
        if (childCount == 0) return null;

        View closest = null;
        int closestDistance = Integer.MAX_VALUE;

        for (int i = 0; i < childCount; i++) {
            View child = layoutManager.getChildAt(i);
            if (child == null) continue;
            int distance = Math.abs(distanceToStart(child, helper));
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = child;
            }
        }
        return closest;
    }

    @Override
    public int findTargetSnapPosition(RecyclerView.LayoutManager layoutManager, int velocityX, int velocityY) {
        // Keep default fling behavior; we only correct final alignment to start.
        return RecyclerView.NO_POSITION;
    }

    private int distanceToStart(View targetView, OrientationHelper helper) {
        return helper.getDecoratedStart(targetView) - helper.getStartAfterPadding();
    }

    private OrientationHelper getHorizontalHelper(RecyclerView.LayoutManager layoutManager) {
        if (horizontalHelper == null) {
            horizontalHelper = OrientationHelper.createHorizontalHelper(layoutManager);
        }
        return horizontalHelper;
    }
}
