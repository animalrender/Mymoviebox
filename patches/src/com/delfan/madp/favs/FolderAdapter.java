package com.delfan.madp.favs;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;

public class FolderAdapter extends RecyclerView.Adapter<FolderAdapter.VH> {
    public interface Listener {
        void onFolderClick(FavFolder folder);
        void onFolderEdit(FavFolder folder);
        void onFolderDelete(FavFolder folder);
    }

    private final Context ctx;
    private final ArrayList<FavFolder> data = new ArrayList<FavFolder>();
    private final Listener listener;

    public FolderAdapter(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    public void setFolders(ArrayList<FavFolder> items) {
        data.clear();
        if (items != null) data.addAll(items);
        notifyDataSetChanged();
    }

    @Override
    public VH onCreateViewHolder(ViewGroup parent, int viewType) {
        int layoutId = Res.layout(ctx, "item_fav_folder");
        View v = LayoutInflater.from(ctx).inflate(layoutId, parent, false);
        return new VH(v, ctx);
    }

    @Override
    public void onBindViewHolder(VH h, int position) {
        final FavFolder folder = data.get(position);
        int count = OfflineFavStore.getFolderCount(ctx, folder.id);
        if (h.txtName != null) h.txtName.setText(folder.name);
        if (h.txtCount != null) h.txtCount.setText(Res.toPersianDigits(count) + " عنوان");

        int folderTint = Res.isNightMode() ? 0xFF64B5F6 : Res.getColor(ctx, "BtnPopLogin", 0xFF0277BD);
        int editTint = Res.getColor(ctx, "TxtcolorHome2", Res.isNightMode() ? 0xFFD1D0D0 : 0xFF636363);
        int delTint = Res.getColor(ctx, "BtnPopLoginNo", 0xFFEF574B);

        if (h.imgIcon != null) h.imgIcon.setColorFilter(folderTint, PorterDuff.Mode.SRC_IN);
        if (h.btnEdit != null) h.btnEdit.setColorFilter(editTint, PorterDuff.Mode.SRC_IN);
        if (h.btnDelete != null) h.btnDelete.setColorFilter(delTint, PorterDuff.Mode.SRC_IN);

        View.OnClickListener openClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onFolderClick(folder);
            }
        };
        if (h.root != null) {
            h.root.setOnClickListener(openClick);
        } else {
            h.itemView.setOnClickListener(openClick);
        }
        if (h.btnEdit != null) {
            h.btnEdit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) listener.onFolderEdit(folder);
                }
            });
        }
        if (h.btnDelete != null) {
            h.btnDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) listener.onFolderDelete(folder);
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    public static class VH extends RecyclerView.ViewHolder {
        final View root;
        final ImageView imgIcon;
        final ImageView btnEdit;
        final ImageView btnDelete;
        final TextView txtName;
        final TextView txtCount;

        public VH(View itemView, Context ctx) {
            super(itemView);
            root = itemView.findViewById(Res.id(ctx, "RelMain_FavFolder"));
            imgIcon = (ImageView) itemView.findViewById(Res.id(ctx, "ImgIcon_FavFolder"));
            btnEdit = (ImageView) itemView.findViewById(Res.id(ctx, "BtnEdit_FavFolder"));
            btnDelete = (ImageView) itemView.findViewById(Res.id(ctx, "BtnDelete_FavFolder"));
            txtName = (TextView) itemView.findViewById(Res.id(ctx, "TxtName_FavFolder"));
            txtCount = (TextView) itemView.findViewById(Res.id(ctx, "TxtCount_FavFolder"));
        }
    }
}
