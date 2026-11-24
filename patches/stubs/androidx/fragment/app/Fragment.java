package androidx.fragment.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

public class Fragment {
    public Context getContext() { return null; }
    public Activity getActivity() { return null; }
    public Context requireContext() { return null; }
    public Resources getResources() { return null; }
    public void startActivity(Intent intent) {}
    public void startActivityForResult(Intent intent, int requestCode) {}
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) { return null; }
    public void onResume() {}
    public void onHiddenChanged(boolean hidden) {}
    public void onActivityResult(int requestCode, int resultCode, Intent data) {}
    public void onDestroyView() {}
}
