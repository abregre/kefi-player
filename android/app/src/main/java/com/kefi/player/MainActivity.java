package com.kefi.player;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Plugins that live in the app module are not auto-discovered the way
        // npm-installed ones are, and must be registered before the bridge starts.
        registerPlugin(PlayerPlugin.class);
        registerPlugin(YtdlpPlugin.class);
        super.onCreate(savedInstanceState);
        captureSharedUrl(getIntent());

        // A backgrounded music player spends most of its life with the screen off,
        // which is exactly when Android is most likely to reclaim the WebView's
        // renderer process under memory pressure. That renderer runs in its own
        // separate sandboxed process, but WebView's default behaviour when nothing
        // handles onRenderProcessGone is to kill the WHOLE app process — taking
        // PlayerController/PlaybackService and the music down with it. Returning
        // true tells the framework this is handled; the dead WebView object can't
        // be reused, so recreate() tears down and rebuilds the Activity (and a
        // fresh WebView) while the already-running PlaybackService/MediaSession
        // (a separate process from the renderer) keeps playing underneath it —
        // this is the real rehydration path T8's usePlayer composable is for.
        getBridge().addWebViewListener(new WebViewListener() {
            @Override
            public boolean onRenderProcessGone(WebView webView, RenderProcessGoneDetail detail) {
                Log.w("KefiApp", "renderer gone, recovering");
                recreate();
                return true;
            }
        });
    }

    /** Fired instead of onCreate when the app is already running (launchMode singleTask). */
    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        captureSharedUrl(intent);
    }

    private void captureSharedUrl(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) {
            return;
        }
        SharedUrlHolder.INSTANCE.offer(intent.getStringExtra(Intent.EXTRA_TEXT));
    }
}
