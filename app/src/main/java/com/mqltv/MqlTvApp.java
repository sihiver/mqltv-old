package com.mqltv;

import androidx.multidex.MultiDexApplication;

public class MqlTvApp extends MultiDexApplication {
	@Override
	public void onCreate() {
		super.onCreate();
		NetworkClient.init(this);
	}
}
