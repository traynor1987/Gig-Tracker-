package site.chatgpt.traynor1987.gigtracker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity implements NativeLocationBus.Listener {
    private static final String TRUSTED_ORIGIN = "https://gig-tracker.traynor1987.chatgpt.site";
    private static final String TRUSTED_HOST = "gig-tracker.traynor1987.chatgpt.site";
    private static final int LOCATION_PERMISSION_REQUEST = 4119;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 4120;
    private static final int FILE_CHOOSER_REQUEST = 4121;
    private static final int SAVE_FILE_REQUEST = 4122;
    private static final int BACKGROUND_LOCATION_PERMISSION_REQUEST = 4123;
    private static final int MAX_EXPORTED_FILE_CHARS = 20_000_000;
    private static final String PERMISSION_PREFS = "gig_tracker_native_permissions";
    private static final String KEY_ALWAYS_LOCATION_EXPLAINED = "always_location_explained";

    private WebView webView;
    private GpsSampleQueue sampleQueue;
    private String pendingCommand;
    private String pendingSessionId;
    private boolean pageReady;
    private boolean flushing;
    private ValueCallback<Uri[]> fileChooserCallback;
    private PendingSave pendingSave;
    private boolean pendingAlwaysLocationRequest;
    private final Queue<JSONObject> flushQueue = new ArrayDeque<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sampleQueue = new GpsSampleQueue(this);
        webView = new WebView(this);
        configureWebView();
        setContentView(webView);
        boolean restored = savedInstanceState != null && webView.restoreState(savedInstanceState) != null;
        if (!restored) webView.loadUrl(TRUSTED_ORIGIN);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        WebView.setWebContentsDebuggingEnabled(true);
        webView.addJavascriptInterface(new GpsBridge(), "GigTrackerAndroid");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST);
                    return true;
                } catch (RuntimeException error) {
                    fileChooserCallback = null;
                    return false;
                }
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isTrusted(uri)) return false;
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = isTrusted(Uri.parse(url));
                if (!pageReady) return;
                injectNativeProvider();
                flushPendingSamples();
            }
        });
    }

    private void injectNativeProvider() {
        String script = "(function(){" +
            "window.GigTrackerNativeGpsProvider={available:true," +
            "start:function(id){GigTrackerAndroid.start(String(id));}," +
            "pause:function(id){GigTrackerAndroid.pause(String(id));}," +
            "resume:function(id){GigTrackerAndroid.resume(String(id));}," +
            "stop:function(id){GigTrackerAndroid.stop(String(id));}," +
            "requestAlwaysLocation:function(){GigTrackerAndroid.requestAlwaysLocation();}," +
            "permissionStatus:function(){return String(GigTrackerAndroid.getLocationPermissionStatus());}};" +
            "window.GigTrackerNativeFileProvider={available:true," +
            "save:function(name,content,type){GigTrackerAndroid.saveFile(String(name),String(type),String(content));}};" +
            "window.dispatchEvent(new Event('gigtracker-native-ready'));" +
            "return true;})()";
        webView.evaluateJavascript(script, null);
    }

    private final class GpsBridge {
        @JavascriptInterface
        public void start(String sessionId) {
            runOnUiThread(() -> beginNativeCommand(GigLocationService.ACTION_START, sessionId));
        }

        @JavascriptInterface
        public void pause(String sessionId) {
            runOnUiThread(() -> sendServiceCommand(GigLocationService.ACTION_PAUSE, sessionId));
        }

        @JavascriptInterface
        public void resume(String sessionId) {
            runOnUiThread(() -> beginNativeCommand(GigLocationService.ACTION_RESUME, sessionId));
        }

        @JavascriptInterface
        public void stop(String sessionId) {
            runOnUiThread(() -> sendServiceCommand(GigLocationService.ACTION_STOP, sessionId));
        }

        @JavascriptInterface
        public void flush() {
            runOnUiThread(MainActivity.this::flushPendingSamples);
        }

        @JavascriptInterface
        public void requestAlwaysLocation() {
            runOnUiThread(() -> requestAlwaysLocationAccess(false));
        }

        @JavascriptInterface
        public String getLocationPermissionStatus() {
            return locationPermissionStatus();
        }

        @JavascriptInterface
        public void saveFile(String filename, String mimeType, String content) {
            runOnUiThread(() -> beginFileSave(filename, mimeType, content));
        }
    }

    private void beginNativeCommand(String command, String sessionId) {
        String clean = cleanSessionId(sessionId);
        if (clean == null) return;
        pendingCommand = command;
        pendingSessionId = clean;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION_REQUEST);
            return;
        }
        requestNotificationOrStart();
    }

    private void requestNotificationOrStart() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
            return;
        }
        runPendingCommand();
    }

    private void runPendingCommand() {
        String command = pendingCommand;
        String sessionId = pendingSessionId;
        pendingCommand = null;
        pendingSessionId = null;
        if (command != null && sessionId != null) {
            sendServiceCommand(command, sessionId);
            if (GigLocationService.ACTION_START.equals(command) || GigLocationService.ACTION_RESUME.equals(command)) {
                requestAlwaysLocationAccess(true);
            }
        }
    }

    private void requestAlwaysLocationAccess(boolean automaticOffer) {
        if (Build.VERSION.SDK_INT < 29) {
            notifyPermissionStatus();
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingAlwaysLocationRequest = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION_REQUEST);
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            notifyPermissionStatus();
            if (!automaticOffer) Toast.makeText(this, "Location is already allowed all the time.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (automaticOffer && getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE).getBoolean(KEY_ALWAYS_LOCATION_EXPLAINED, false)) return;

        String systemLabel = Build.VERSION.SDK_INT >= 30
            ? getPackageManager().getBackgroundPermissionOptionLabel().toString()
            : "Allow all the time";
        new AlertDialog.Builder(this)
            .setTitle("Keep GPS tracking during a run")
            .setMessage("Choose “" + systemLabel + "” for reliable mileage while the screen is off, the phone is locked, or another app is open. Gig Tracker still records only during an active session, pauses on breaks, and stops at End Session.")
            .setNegativeButton("Not now", (dialog, which) -> markAlwaysLocationExplained())
            .setPositiveButton(Build.VERSION.SDK_INT >= 30 ? "Open settings" : "Continue", (dialog, which) -> {
                markAlwaysLocationExplained();
                if (Build.VERSION.SDK_INT == 29) {
                    requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, BACKGROUND_LOCATION_PERMISSION_REQUEST);
                } else {
                    Intent settingsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:" + getPackageName()));
                    startActivity(settingsIntent);
                    Toast.makeText(this, "Open Permissions → Location → “" + systemLabel + "”.", Toast.LENGTH_LONG).show();
                }
            })
            .show();
    }

    private void markAlwaysLocationExplained() {
        getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ALWAYS_LOCATION_EXPLAINED, true).apply();
    }

    private String locationPermissionStatus() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return "denied";
        if (Build.VERSION.SDK_INT < 29 || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) return "all_the_time";
        return "while_in_use";
    }

    private void notifyPermissionStatus() {
        if (!pageReady || webView == null) return;
        String status = locationPermissionStatus();
        String script = "window.dispatchEvent(new CustomEvent('gigtracker-native-permission-changed',{detail:" + JSONObject.quote(status) + "}));";
        webView.evaluateJavascript(script, null);
    }

    private void sendServiceCommand(String action, String sessionId) {
        String clean = cleanSessionId(sessionId);
        Intent intent = new Intent(this, GigLocationService.class).setAction(action);
        if (clean != null) intent.putExtra(GigLocationService.EXTRA_SESSION_ID, clean);
        if (GigLocationService.ACTION_START.equals(action) || GigLocationService.ACTION_RESUME.equals(action)) startForegroundService(intent);
        else startService(intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                if (pendingCommand != null) requestNotificationOrStart();
                if (pendingAlwaysLocationRequest) {
                    pendingAlwaysLocationRequest = false;
                    requestAlwaysLocationAccess(false);
                }
                notifyPermissionStatus();
            }
            else {
                pendingCommand = null;
                pendingSessionId = null;
                pendingAlwaysLocationRequest = false;
                Toast.makeText(this, "Precise location is required for native GPS. The session can still run with manual mileage.", Toast.LENGTH_LONG).show();
                notifyPermissionStatus();
            }
        } else if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notifications are off. Android may hide the ongoing GPS notification, but tracking will still start.", Toast.LENGTH_LONG).show();
            }
            runPendingCommand();
        } else if (requestCode == BACKGROUND_LOCATION_PERMISSION_REQUEST) {
            if (locationPermissionStatus().equals("all_the_time")) Toast.makeText(this, "Location is now allowed all the time for active runs.", Toast.LENGTH_LONG).show();
            else Toast.makeText(this, "Background location was not enabled. You can try again in Gig Tracker Settings.", Toast.LENGTH_LONG).show();
            notifyPermissionStatus();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        NativeLocationBus.setListener(this);
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
            webView.postDelayed(this::flushPendingSamples, 350L);
            webView.postDelayed(this::notifyPermissionStatus, 500L);
        }
    }

    @Override
    protected void onPause() {
        NativeLocationBus.setListener(null);
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        NativeLocationBus.setListener(null);
        if (webView != null) webView.destroy();
        if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
        fileChooserCallback = null;
        pendingSave = null;
        super.onDestroy();
    }

    private void beginFileSave(String filename, String mimeType, String content) {
        if (!pageReady || content == null || content.length() > MAX_EXPORTED_FILE_CHARS) {
            Toast.makeText(this, "That export is too large for the debug wrapper.", Toast.LENGTH_LONG).show();
            return;
        }
        String safeName = filename == null ? "gig-tracker-export.txt" : filename.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeName.isEmpty()) safeName = "gig-tracker-export.txt";
        pendingSave = new PendingSave(safeName, mimeType == null || mimeType.isEmpty() ? "text/plain" : mimeType, content);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(pendingSave.mimeType)
            .putExtra(Intent.EXTRA_TITLE, pendingSave.filename);
        startActivityForResult(intent, SAVE_FILE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST) {
            ValueCallback<Uri[]> callback = fileChooserCallback;
            fileChooserCallback = null;
            if (callback != null) callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            return;
        }
        if (requestCode == SAVE_FILE_REQUEST) {
            PendingSave save = pendingSave;
            pendingSave = null;
            if (save == null || resultCode != RESULT_OK || data == null || data.getData() == null) return;
            try (java.io.OutputStream output = getContentResolver().openOutputStream(data.getData(), "w")) {
                if (output == null) throw new java.io.IOException("No output stream");
                output.write(save.content.getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, "Gig Tracker export saved.", Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                Toast.makeText(this, "Android could not save that export.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private static final class PendingSave {
        final String filename;
        final String mimeType;
        final String content;

        PendingSave(String filename, String mimeType, String content) {
            this.filename = filename;
            this.mimeType = mimeType;
            this.content = content;
        }
    }

    @Override
    public void onPendingSamples() {
        runOnUiThread(this::flushPendingSamples);
    }

    private void flushPendingSamples() {
        if (!pageReady || flushing || webView == null) return;
        List<JSONObject> pending = sampleQueue.pending();
        if (pending.isEmpty()) return;
        flushQueue.clear();
        flushQueue.addAll(pending);
        flushing = true;
        deliverNextSample();
    }

    private void deliverNextSample() {
        JSONObject envelope = flushQueue.poll();
        if (envelope == null) {
            flushing = false;
            return;
        }
        String sampleId = envelope.optString("id");
        String sessionId = envelope.optString("sessionId");
        JSONObject sample = envelope.optJSONObject("sample");
        if (sampleId.isEmpty() || sessionId.isEmpty() || sample == null) {
            sampleQueue.acknowledge(sampleId);
            deliverNextSample();
            return;
        }
        String script = "(function(){try{" +
            "if(!window.GigTrackerGpsIngress||typeof window.GigTrackerGpsIngress.ingest!=='function')return false;" +
            "return window.GigTrackerGpsIngress.ingest(" + sample + "," + JSONObject.quote(sessionId) + ")===true;" +
            "}catch(e){return false;}})()";
        webView.evaluateJavascript(script, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String result) {
                if ("true".equals(result)) sampleQueue.acknowledge(sampleId);
                deliverNextSample();
            }
        });
    }

    private static boolean isTrusted(Uri uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && TRUSTED_HOST.equalsIgnoreCase(uri.getHost());
    }

    private static String cleanSessionId(String value) {
        if (value == null) return null;
        String clean = value.trim();
        return clean.isEmpty() || clean.length() > 128 ? null : clean;
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
