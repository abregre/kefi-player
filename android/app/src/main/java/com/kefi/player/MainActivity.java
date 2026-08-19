package com.kefi.player;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Plugins that live in the app module are not auto-discovered the way
        // npm-installed ones are, and must be registered before the bridge starts.
        registerPlugin(PlayerPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
