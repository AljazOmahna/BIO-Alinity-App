package si.kclj.bioalinity;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        webView.loadUrl("file:///android_asset/bio_alinity.html");
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

        @JavascriptInterface
        public String getDeviceInfo() {
            return "{\"model\":\"" + Build.MODEL + "\"," +
                    "\"manufacturer\":\"" + Build.MANUFACTURER + "\"," +
                    "\"sdk\":" + Build.VERSION.SDK_INT + "," +
                    "\"release\":\"" + Build.VERSION.RELEASE + "\"}";
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
