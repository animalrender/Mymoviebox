package com.delfan.madp.fragments;

import android.content.Context;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.delfan.madp.Adapter.CaAdFilm_DubleList;
import java.util.List;

public class fr_favs extends Fragment {
    public static FrameLayout FrLayoutLogin_Frg5;
    public static RecyclerView RecyclerCinema_Frg5;
    public static RecyclerView RecyclerSeryal_Frg5;
    public static SwipeRefreshLayout Swipe1_Fr5;
    public static SwipeRefreshLayout Swipe2_Fr5;
    public static TextView TxtMsg1_Fr5;
    public static TextView TxtMsg2_Fr5;
    public static CaAdFilm_DubleList adapter_cinema;
    public static CaAdFilm_DubleList adapter_seryal;
    public static List list_cinema;
    public static List list_seryal;

    public static void EmptyList_Frg5() {}
    public static void SetDataFavs(Context ctx) {}
}
