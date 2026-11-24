package com.squareup.picasso;

import android.widget.ImageView;

public class RequestCreator {
    public RequestCreator placeholder(int placeholderResId) { return this; }
    public RequestCreator error(int errorResId) { return this; }
    public void into(ImageView target) {}
}
