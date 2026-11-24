package androidx.recyclerview.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

public class RecyclerView extends ViewGroup {
    public RecyclerView(Context ctx) { super(ctx); }
    public RecyclerView(Context ctx, AttributeSet attrs) { super(ctx, attrs); }
    protected void onLayout(boolean changed, int l, int t, int r, int b) {}

    public void setLayoutManager(LayoutManager lm) {}
    public void setAdapter(Adapter adapter) {}

    public static abstract class LayoutManager {}

    public static class ViewHolder {
        public final View itemView;
        public ViewHolder(View itemView) { this.itemView = itemView; }
    }

    public static abstract class Adapter<VH extends ViewHolder> {
        public abstract VH onCreateViewHolder(ViewGroup parent, int viewType);
        public abstract void onBindViewHolder(VH holder, int position);
        public abstract int getItemCount();
        public final void notifyDataSetChanged() {}
    }
}
