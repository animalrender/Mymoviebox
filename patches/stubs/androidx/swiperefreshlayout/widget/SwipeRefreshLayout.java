package androidx.swiperefreshlayout.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;

public class SwipeRefreshLayout extends ViewGroup {
    public interface OnRefreshListener {
        void onRefresh();
    }
    public SwipeRefreshLayout(Context ctx) { super(ctx); }
    public SwipeRefreshLayout(Context ctx, AttributeSet attrs) { super(ctx, attrs); }
    protected void onLayout(boolean changed, int l, int t, int r, int b) {}
    public void setRefreshing(boolean refreshing) {}
    public void setOnRefreshListener(OnRefreshListener listener) {}
    public void setColorSchemeColors(int... colors) {}
}
