package com.gtamine.mobile.fragments;

import com.gtamine.mobile.modloaders.FabriclikeUtils;
import com.gtamine.mobile.modloaders.ModloaderListenerProxy;

public class FabricInstallFragment extends FabriclikeInstallFragment {

    public static final String TAG = "FabricInstallFragment";

    public FabricInstallFragment() {
        super(FabriclikeUtils.FABRIC_UTILS, TAG);
    }
}
