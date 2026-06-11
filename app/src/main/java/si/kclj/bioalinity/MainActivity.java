package si.kclj.bioalinity;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean pageReady = false;
    private String pendingSharedName = null;
    private String pendingSharedJson = null;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ws.setSafeBrowsingEnabled(false);
        }

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                flushPendingShared();
            }
        });
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        webView.loadUrl("file:///android_asset/bio_alinity.html");

        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private void callJs(final String js) {
        mainHandler.post(() -> webView.evaluateJavascript(js, null));
    }

    private static String jsStr(String s) {
        return s == null ? "null" : JSONObject.quote(s);
    }

    // =========================================================
    // Quick Share — receive a shared/opened .json file and import
    // =========================================================
    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        }
        if (uri == null) return;
        final String name = queryName(uri);
        final String json = readUri(uri);
        if (json == null || json.isEmpty()) return;
        pendingSharedName = name;
        pendingSharedJson = json;
        if (pageReady) flushPendingShared();
    }

    private void flushPendingShared() {
        if (pendingSharedJson == null) return;
        final String n = pendingSharedName, j = pendingSharedJson;
        pendingSharedName = null; pendingSharedJson = null;
        callJs("if(typeof onSharedImport==='function')onSharedImport(" + jsStr(n) + "," + jsStr(j) + ")");
    }

    private String queryName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        String s = uri.getLastPathSegment();
        return s != null ? s : "";
    }

    private String readUri(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================
    // AndroidBridge — JS interface (tablet subset, no RFID/DW)
    // =========================================================
    public class AndroidBridge {

        @JavascriptInterface
        public void showToast(String msg) {
            mainHandler.post(() ->
                android.widget.Toast.makeText(MainActivity.this, msg,
                    android.widget.Toast.LENGTH_SHORT).show()
            );
        }

        // ---- Quick Share (Nearby) — share a JSON file via system chooser ----
        @JavascriptInterface
        public void quickShare(String filename, String json) {
            mainHandler.post(() -> {
                try {
                    String name = (filename == null || filename.isEmpty()) ? "bioalinity.json" : filename;
                    File dir = new File(getCacheDir(), "share");
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, name);
                    try (FileOutputStream fos = new FileOutputStream(f);
                         OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8")) {
                        w.write(json == null ? "" : json);
                    }
                    Uri uri = FileProvider.getUriForFile(MainActivity.this,
                            getPackageName() + ".fileprovider", f);
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("application/json");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.putExtra(Intent.EXTRA_TITLE, name);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    Intent chooser = Intent.createChooser(send, "Deli prek Quick Share");
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(chooser);
                } catch (Exception e) {
                    callJs("if(typeof onQuickShareError==='function')onQuickShareError(" + jsStr(e.getMessage()) + ")");
                }
            });
        }

        @JavascriptInterface
        public String getDeviceInfo() {
            String verName = "?";
            long verCode = 0;
            try {
                android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
                verName = pi.versionName;
                verCode = (Build.VERSION.SDK_INT >= 28) ? pi.getLongVersionCode() : pi.versionCode;
            } catch (Exception ignored) {}
            return "{\"model\":\"" + Build.MODEL + "\"," +
                    "\"manufacturer\":\"" + Build.MANUFACTURER + "\"," +
                    "\"sdk\":" + Build.VERSION.SDK_INT + "," +
                    "\"release\":\"" + Build.VERSION.RELEASE + "\"," +
                    "\"appVersion\":" + JSONObject.quote(verName) + "," +
                    "\"appBuild\":" + verCode + "}";
        }

        // ---- OneDrive / Microsoft Graph (placeholder) ----
        // Implemented as stubs until IT UKCL approves Azure app registration.
        // When Azure client_id is available, implement MSAL OAuth2 PKCE flow here.

        @JavascriptInterface
        public boolean msIsSignedIn() {
            return false;
        }

        @JavascriptInterface
        public String msGetAccount() {
            return "";
        }

        @JavascriptInterface
        public String msGetLastSync() {
            return "";
        }

        @JavascriptInterface
        public void msSignIn() {
            mainHandler.post(() -> callJs("onMsError('OneDrive: Čaka na Azure app registracijo IT UKCL')"));
        }

        @JavascriptInterface
        public void msCancelSignIn() {
            // no-op
        }

        @JavascriptInterface
        public void msSignOut() {
            mainHandler.post(() -> callJs("onMsSignedOut()"));
        }

        @JavascriptInterface
        public void msUpload(String key, String json) {
            // stub — notify JS that upload is not yet available
            mainHandler.post(() -> callJs("onMsUploadDone(false,'OneDrive sync ni aktiven')"));
        }

        @JavascriptInterface
        public void msDownload(String key) {
            // stub — notify JS that download is not yet available
            mainHandler.post(() -> callJs("onMsDownloadDone('" + key + "',null,'OneDrive sync ni aktiven')"));
        }
    }
}
