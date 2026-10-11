package com.factorio;

import android.app.Application;

import com.factorio.core.PackagePath;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // package-independent paths (see PackagePath): must run before any archive is extracted
        PackagePath.init(this);
    }
}
