package com.kefi.player;

import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import android.util.Log;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;

public class MainActivity extends BridgeActivity {

    /**
     * Callback for {@link #launchIntentSenderForResult}. A plain boolean (RESULT_OK or not) is
     * enough at this layer: both callers this mechanism exists for — T5's
     * RecoverableSecurityException catch on API 29, and the PendingIntent MediaStore.
     * createDeleteRequest() returns on API 30+ — only ever need to know whether the user
     * approved the system dialog, nothing else about the result Intent.
     */
    public interface IntentSenderResultCallback {
        void onIntentSenderResult(boolean approved);
    }

    // WHY this exists, and why here rather than as a Capacitor plugin helper:
    //
    // T5 needs to launch an android.content.IntentSender (RecoverableSecurityException.
    // userAction.actionIntent.intentSender on API 29; MediaStore.createDeleteRequest(...)'s
    // returned PendingIntent.intentSender on API 30+) and learn whether the user approved it.
    // Capacitor's documented, in-repo mechanism for "launch something, get a result back" —
    // Plugin.startActivityForResult(PluginCall, Intent, callbackName) plus a method annotated
    // @ActivityCallback (see node_modules/@capacitor/android/.../com/getcapacitor/Plugin.java,
    // read in full for this task) — is built on ActivityResultContracts.StartActivityForResult,
    // whose input type is android.content.Intent. An IntentSender is not an Intent — it is a
    // reference to a system-owned pending action — and Plugin.java has no IntentSender-shaped
    // counterpart. No plugin in this codebase has ever awaited an activity result at all, so
    // there was no in-repo precedent to extend either way.
    //
    // The fix: register a SECOND, separate launcher directly on this Activity, using the
    // contract that IS IntentSender-shaped: ActivityResultContracts.StartIntentSenderForResult
    // (input IntentSenderRequest, output ActivityResult) — same AndroidX
    // ActivityResultCaller/registerForActivityResult machinery Capacitor's own Plugin.java
    // already uses for its Intent case, just a different contract. This requires
    // registerForActivityResult() to be callable on MainActivity, which it is: MainActivity
    // extends BridgeActivity extends AppCompatActivity extends FragmentActivity extends
    // ComponentActivity, and ComponentActivity is what implements ActivityResultCaller and
    // provides registerForActivityResult — confirmed by reading the actual resolved sources for
    // this project's pinned versions (androidx.appcompat:appcompat:1.7.1's AppCompatActivity.java,
    // androidx.fragment:fragment:1.8.9's FragmentActivity.java, androidx.activity:activity:1.11.0's
    // ComponentActivity.kt/ActivityResultCaller.kt), not just from documentation. Capacitor's own
    // Bridge.java (registerForActivityResult(), ~line 1002) already calls
    // `context.registerForActivityResult(contract, callback)` directly on that same
    // AppCompatActivity-typed field today for its own @ActivityCallback plumbing, i.e. one layer
    // up this exact inheritance chain — proof this call already works in this app's actual
    // dependency graph, not just in theory. That confirmed the PRIMARY approach cleanly, so the
    // deprecated startIntentSenderForResult()/handleOnActivityResult() fallback was not needed.
    //
    // registerForActivityResult() must be called before the Activity reaches STARTED, and
    // AndroidX's own docs say the standard way to guarantee that is a field initializer — so this
    // is a field, not something set up lazily inside onCreate. Field initializers run during
    // construction, well before the framework ever calls onCreate, so ordering is safe.
    // Only one IntentSender launch is ever in flight at a time (the user is looking at one
    // system dialog); a single field is enough and keeps this mechanism simple. This is
    // deliberately generic infrastructure, not delete-specific — it holds no knowledge of what
    // the IntentSender is for.
    //
    // MUST be declared before intentSenderLauncher below: javac's forward-reference rule for
    // instance field initializers is purely textual/declaration-order, even for a field read
    // only inside a lambda that won't actually run until long after both fields exist. Reading
    // this field from inside intentSenderLauncher's lambda while this declaration came AFTER it
    // in source failed with "illegal forward reference" — caught only by a real
    // compileDebugJavaWithJavac, not by compileDebugKotlin (nothing in Kotlin calls into this
    // file yet) and not by manual reading, since the actual runtime order (field init happens
    // during construction; the lambda body runs later, after a user responds to the system
    // dialog) is exactly backwards from what javac's textual rule cares about.
    private IntentSenderResultCallback pendingIntentSenderCallback;

    private final ActivityResultLauncher<IntentSenderRequest> intentSenderLauncher =
        registerForActivityResult(
            new ActivityResultContracts.StartIntentSenderForResult(),
            (ActivityResult result) -> {
                IntentSenderResultCallback callback = pendingIntentSenderCallback;
                pendingIntentSenderCallback = null;
                if (callback != null) {
                    callback.onIntentSenderResult(result.getResultCode() == RESULT_OK);
                }
            }
        );

    /**
     * Generic entry point for a Capacitor plugin to launch an IntentSender and learn whether the
     * user approved it, reached via {@code (getActivity() as MainActivity)} from Kotlin (mirrors
     * how PlayerPlugin.kt already reaches Activity-level things, e.g. context.startActivity in
     * openAppSettings/setAsRingtone — this is the IntentSender-shaped, result-awaiting sibling of
     * that). Deliberately takes no delete-specific parameters or types; T5's deleteTrack is this
     * mechanism's first consumer, not its only intended one.
     */
    public void launchIntentSenderForResult(IntentSender intentSender, IntentSenderResultCallback callback) {
        pendingIntentSenderCallback = callback;
        intentSenderLauncher.launch(new IntentSenderRequest.Builder(intentSender).build());
    }

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
