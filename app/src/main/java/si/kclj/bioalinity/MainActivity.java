package si.kclj.bioalinity;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.microsoft.identity.client.AuthenticationCallback;
import com.microsoft.identity.client.IAccount;
import com.microsoft.identity.client.IAuthenticationResult;
import com.microsoft.identity.client.IPublicClientApplication;
import com.microsoft.identity.client.ISingleAccountPublicClientApplication;
import com.microsoft.identity.client.PublicClientApplication;
import com.microsoft.identity.client.SilentAuthenticationCallback;
import com.microsoft.identity.client.exception.MsalException;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PICK_REPORT = 1001;

    private WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean pageReady = false;
    private String pendingSharedName = null;
    private String pendingSharedJson = null;

    // ---- OneDrive / Microsoft Graph (MSAL) ----
    private ISingleAccountPublicClientApplication msalApp;
    private volatile IAccount msalAccount;
    private volatile String msalAccountName = "";
    private volatile String msalLastSync = "";
    private final OkHttpClient http = new OkHttpClient();
    private static final String[] MS_SCOPES = {
            "https://graph.microsoft.com/Files.ReadWrite",
            "https://graph.microsoft.com/User.Read"
    };
    private static final String MS_AUTHORITY = "https://login.microsoftonline.com/common";
    // Mapa v OneDrive, kamor se shranjuje baza (uporabnikova osebna mapa "DigiLab").
    private static final String MS_FOLDER = "DigiLab";

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

        initMsal();
    }

    // =========================================================
    // MSAL — inicializacija enojnega računa (OneDrive / Graph)
    // =========================================================
    private void initMsal() {
        PublicClientApplication.createSingleAccountPublicClientApplication(
            getApplicationContext(), R.raw.auth_config,
            new IPublicClientApplication.ISingleAccountApplicationCreatedListener() {
                @Override public void onCreated(ISingleAccountPublicClientApplication application) {
                    msalApp = application;
                    loadMsalAccount();
                }
                @Override public void onError(MsalException exception) {
                    android.util.Log.e("MSAL", "init error", exception);
                }
            });
    }

    private void loadMsalAccount() {
        if (msalApp == null) return;
        msalApp.getCurrentAccountAsync(new ISingleAccountPublicClientApplication.CurrentAccountCallback() {
            @Override public void onAccountLoaded(@Nullable IAccount activeAccount) {
                msalAccount = activeAccount;
                msalAccountName = activeAccount != null ? activeAccount.getUsername() : "";
            }
            @Override public void onAccountChanged(@Nullable IAccount prior, @Nullable IAccount current) {
                msalAccount = current;
                msalAccountName = current != null ? current.getUsername() : "";
            }
            @Override public void onError(@NonNull MsalException exception) {
                android.util.Log.e("MSAL", "getCurrentAccount", exception);
            }
        });
    }

    // Pridobi žeton tiho in nato izvede Graph operacijo; ob potrebi po prijavi javi napako.
    private void msAcquireToken(final String key, final TokenAction action) {
        if (msalApp == null) {
            action.onToken(null, "MSAL ni pripravljen");
            return;
        }
        msalApp.acquireTokenSilentAsync(MS_SCOPES, MS_AUTHORITY, new SilentAuthenticationCallback() {
            @Override public void onSuccess(IAuthenticationResult authenticationResult) {
                action.onToken(authenticationResult.getAccessToken(), null);
            }
            @Override public void onError(MsalException exception) {
                action.onToken(null, "Potrebna ponovna prijava: " + exception.getMessage());
            }
        });
    }

    private interface TokenAction { void onToken(String token, String error); }

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
    // Servisna poročila — izbira prek SAF, kopija v ServisnaPorocila
    // =========================================================
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_REPORT) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            String name = queryName(uri);
            if (name == null || name.isEmpty()) name = "porocilo_" + System.currentTimeMillis() + ".pdf";
            byte[] bytes;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new Exception("Ni mogoče odpreti datoteke");
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
                bytes = bos.toByteArray();
            }
            if (bytes.length > 10 * 1024 * 1024) {
                callJs("if(typeof onReportPickError==='function')onReportPickError('Datoteka je prevelika (max 10 MB)')");
                return;
            }
            File dir = getExternalFilesDir("ServisnaPorocila");
            if (dir == null) dir = new File(getFilesDir(), "ServisnaPorocila");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, name);
            // pri obstoječem imenu dodaj časovni žig, da ne prepišemo drugega poročila
            if (f.exists()) {
                int dot = name.lastIndexOf('.');
                String base = dot > 0 ? name.substring(0, dot) : name;
                String ext = dot > 0 ? name.substring(dot) : "";
                f = new File(dir, base + "_" + System.currentTimeMillis() + ext);
            }
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(bytes);
            }
            String b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            callJs("if(typeof onReportPicked==='function')onReportPicked("
                    + jsStr(f.getName()) + "," + jsStr(f.getAbsolutePath()) + "," + jsStr(b64) + ")");
        } catch (Exception e) {
            callJs("if(typeof onReportPickError==='function')onReportPickError(" + jsStr("Napaka: " + e.getMessage()) + ")");
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

        // ---- Servisna poročila (priponke) ----
        @JavascriptInterface
        public void pickReportFile() {
            mainHandler.post(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    i.putExtra(Intent.EXTRA_MIME_TYPES,
                            new String[]{"application/pdf", "image/*", "application/json"});
                    startActivityForResult(i, REQ_PICK_REPORT);
                } catch (Exception e) {
                    callJs("if(typeof onReportPickError==='function')onReportPickError(" + jsStr(e.getMessage()) + ")");
                }
            });
        }

        @JavascriptInterface
        public void openReportFile(String path) {
            mainHandler.post(() -> {
                try {
                    File f = new File(path);
                    if (!f.exists()) {
                        callJs("if(typeof onReportPickError==='function')onReportPickError('Datoteka ne obstaja več na napravi')");
                        return;
                    }
                    Uri uri = FileProvider.getUriForFile(MainActivity.this,
                            getPackageName() + ".fileprovider", f);
                    String n = f.getName().toLowerCase();
                    String mime = n.endsWith(".pdf") ? "application/pdf"
                            : (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")) ? "image/*"
                            : n.endsWith(".json") ? "application/json" : "*/*";
                    Intent view = new Intent(Intent.ACTION_VIEW);
                    view.setDataAndType(uri, mime);
                    view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(view, f.getName()));
                } catch (Exception e) {
                    callJs("if(typeof onReportPickError==='function')onReportPickError(" + jsStr("Ni mogoče odpreti: " + e.getMessage()) + ")");
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

        // ---- OneDrive / Microsoft Graph (MSAL + Graph REST) ----
        // Sinhronizacija prek osebnega OneDrive (mapa DigiLab).
        // Žetoni se pridobivajo prek MSAL (single account, audience multitenant+personal).

        @JavascriptInterface
        public boolean msIsSignedIn() {
            return msalAccount != null;
        }

        @JavascriptInterface
        public String msGetAccount() {
            return msalAccountName == null ? "" : msalAccountName;
        }

        @JavascriptInterface
        public String msGetLastSync() {
            return msalLastSync == null ? "" : msalLastSync;
        }

        @JavascriptInterface
        public void msSignIn() {
            mainHandler.post(() -> {
                if (msalApp == null) { callJs("onMsError('MSAL ni pripravljen')"); return; }
                AuthenticationCallback cb = new AuthenticationCallback() {
                    @Override public void onSuccess(IAuthenticationResult result) {
                        msalAccount = result.getAccount();
                        msalAccountName = msalAccount != null ? msalAccount.getUsername() : "";
                        callJs("onMsSignedIn(" + jsStr(msalAccountName) + ")");
                    }
                    @Override public void onError(MsalException exception) {
                        callJs("onMsError(" + jsStr("Prijava: " + exception.getMessage()) + ")");
                    }
                    @Override public void onCancel() {
                        callJs("onMsError('Prijava preklicana')");
                    }
                };
                try {
                    if (msalAccount != null) {
                        msalApp.signInAgain(MainActivity.this, MS_SCOPES, null, cb);
                    } else {
                        msalApp.signIn(MainActivity.this, null, MS_SCOPES, cb);
                    }
                } catch (Exception e) {
                    callJs("onMsError(" + jsStr("Prijava ni mogoča: " + e.getMessage()) + ")");
                }
            });
        }

        @JavascriptInterface
        public void msCancelSignIn() {
            // no-op (MSAL interaktivni tok prekine uporabnik v brskalniku)
        }

        @JavascriptInterface
        public void msSignOut() {
            mainHandler.post(() -> {
                if (msalApp == null) { callJs("onMsSignedOut()"); return; }
                msalApp.signOut(new ISingleAccountPublicClientApplication.SignOutCallback() {
                    @Override public void onSignOut() {
                        msalAccount = null; msalAccountName = "";
                        callJs("onMsSignedOut()");
                    }
                    @Override public void onError(@NonNull MsalException exception) {
                        callJs("onMsError(" + jsStr("Odjava: " + exception.getMessage()) + ")");
                    }
                });
            });
        }

        @JavascriptInterface
        public void msUpload(String key, String json) {
            final String k = key, j = json;
            mainHandler.post(() -> msAcquireToken(k, (token, err) -> {
                if (token == null) { callJs("onMsUploadDone(false," + jsStr(err) + ")"); return; }
                graphPut(k, j, token);
            }));
        }

        @JavascriptInterface
        public void msDownload(String key) {
            final String k = key;
            mainHandler.post(() -> msAcquireToken(k, (token, err) -> {
                if (token == null) { callJs("onMsDownloadDone(" + jsStr(k) + ",null," + jsStr(err) + ")"); return; }
                graphGet(k, token);
            }));
        }

        // Sestavi PDF iz HTML-ja, shrani lokalno (Porocila/<leto>) in naloži v OneDrive DigiLab/<subfolder>/<filename>.
        @JavascriptInterface
        public void generateAndUploadReport(String subfolder, String filename, String html) {
            final String sf = subfolder, fn = filename, h = html;
            mainHandler.post(() -> renderReportToPdf(sf, fn, h));
        }
    }

    // =========================================================
    // Graph REST — PUT/GET datoteke v mapi DigiLab uporabnikovega OneDrive
    // =========================================================
    private String graphContentUrl(String key) {
        return "https://graph.microsoft.com/v1.0/me/drive/root:/" + MS_FOLDER + "/" + key + ".json:/content";
    }

    private void graphPut(final String key, String json, String token) {
        RequestBody body = RequestBody.create(
                json == null ? "" : json,
                MediaType.parse("application/json; charset=utf-8"));
        Request req = new Request.Builder()
                .url(graphContentUrl(key))
                .header("Authorization", "Bearer " + token)
                .put(body)
                .build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callJs("onMsUploadDone(false," + jsStr("Napaka mreže: " + e.getMessage()) + ")");
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response resp) {
                int code = resp.code();
                resp.close();
                if (code >= 200 && code < 300) {
                    msalLastSync = new java.util.Date().toString();
                    callJs("onMsUploadDone(true," + jsStr("Naloženo v " + MS_FOLDER + "/" + key + ".json") + ")");
                } else {
                    callJs("onMsUploadDone(false," + jsStr("Graph napaka " + code) + ")");
                }
            }
        });
    }

    private void graphGet(final String key, String token) {
        Request req = new Request.Builder()
                .url(graphContentUrl(key))
                .header("Authorization", "Bearer " + token)
                .get()
                .build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callJs("onMsDownloadDone(" + jsStr(key) + ",null," + jsStr("Napaka mreže: " + e.getMessage()) + ")");
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response resp) {
                int code = resp.code();
                String bodyStr = null;
                try {
                    ResponseBody rb = resp.body();
                    if (rb != null) bodyStr = rb.string();
                } catch (IOException e) {
                    bodyStr = null;
                } finally {
                    resp.close();
                }
                if (code == 404) {
                    callJs("onMsDownloadDone(" + jsStr(key) + ",null," + jsStr("Datoteke še ni v " + MS_FOLDER) + ")");
                } else if (code >= 200 && code < 300 && bodyStr != null) {
                    msalLastSync = new java.util.Date().toString();
                    callJs("onMsDownloadDone(" + jsStr(key) + "," + jsStr(bodyStr) + ",'ok')");
                } else {
                    callJs("onMsDownloadDone(" + jsStr(key) + ",null," + jsStr("Graph napaka " + code) + ")");
                }
            }
        });
    }

    // =========================================================
    // Poročila — HTML → PDF (offscreen WebView) → lokalno + OneDrive
    // =========================================================
    private WebView reportWebView; // referenca, da se WebView ne sprosti med async izrisom

    private void reportDone(String filename, boolean ok, String msg, String localPath) {
        callJs("if(typeof onReportGenerated==='function')onReportGenerated("
                + jsStr(filename) + "," + (ok ? "true" : "false") + "," + jsStr(msg) + "," + jsStr(localPath) + ")");
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void renderReportToPdf(final String subfolder, final String filename, String html) {
        try {
            final WebView wv = new WebView(this);
            wv.getSettings().setJavaScriptEnabled(false);
            reportWebView = wv;
            wv.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) {
                    // počakaj kratek hip, da se postavitev ustali, nato izriši v PDF
                    mainHandler.postDelayed(() -> writeWebViewPdf(view, subfolder, filename), 300);
                }
            });
            wv.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
        } catch (Exception e) {
            reportWebView = null;
            reportDone(filename, false, "Napaka izrisa: " + e.getMessage(), null);
        }
    }

    private void writeWebViewPdf(WebView view, final String subfolder, final String filename) {
        try {
            PrintAttributes attrs = new PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
                    .setResolution(new PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build();
            final PrintDocumentAdapter adapter = view.createPrintDocumentAdapter("report");
            File dir = getExternalFilesDir(subfolder);
            if (dir == null) dir = new File(getFilesDir(), subfolder);
            if (!dir.exists()) dir.mkdirs();
            final File outFile = new File(dir, filename);
            final ParcelFileDescriptor pfd = ParcelFileDescriptor.open(outFile,
                    ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE
                            | ParcelFileDescriptor.MODE_TRUNCATE);

            adapter.onStart();
            adapter.onLayout(null, attrs, new CancellationSignal(),
                new PrintDocumentAdapter.LayoutResultCallback() {
                    @Override public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                        adapter.onWrite(new PageRange[]{PageRange.ALL_PAGES}, pfd, new CancellationSignal(),
                            new PrintDocumentAdapter.WriteResultCallback() {
                                @Override public void onWriteFinished(PageRange[] pages) {
                                    try { adapter.onFinish(); } catch (Exception ignored) {}
                                    try { pfd.close(); } catch (Exception ignored) {}
                                    reportWebView = null;
                                    uploadReportPdf(subfolder, filename, outFile);
                                }
                                @Override public void onWriteFailed(CharSequence error) {
                                    try { pfd.close(); } catch (Exception ignored) {}
                                    reportWebView = null;
                                    reportDone(filename, false, "PDF zapis ni uspel: " + error, null);
                                }
                            });
                    }
                    @Override public void onLayoutFailed(CharSequence error) {
                        try { pfd.close(); } catch (Exception ignored) {}
                        reportWebView = null;
                        reportDone(filename, false, "Postavitev ni uspela: " + error, null);
                    }
                }, null);
        } catch (Exception e) {
            reportWebView = null;
            reportDone(filename, false, "Napaka PDF: " + e.getMessage(), null);
        }
    }

    private void uploadReportPdf(final String subfolder, final String filename, final File file) {
        final String localPath = file.getAbsolutePath();
        final byte[] bytes;
        try {
            bytes = readFileBytes(file);
        } catch (Exception e) {
            reportDone(filename, false, "Branje PDF: " + e.getMessage(), localPath);
            return;
        }
        mainHandler.post(() -> msAcquireToken("report", (token, err) -> {
            if (token == null) {
                // PDF je shranjen lokalno, a brez OneDrive (ni prijave/seje)
                reportDone(filename, false, "Shranjeno lokalno; OneDrive: " + err, localPath);
                return;
            }
            graphPutBytes(subfolder + "/" + filename, bytes, "application/pdf", token, filename, localPath);
        }));
    }

    private byte[] readFileBytes(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            return bos.toByteArray();
        }
    }

    // Graph content URL za poljubno pot pod DigiLab (vsak segment URL-kodiran).
    private String graphPathUrl(String relPath) {
        StringBuilder sb = new StringBuilder();
        sb.append(Uri.encode(MS_FOLDER));
        for (String seg : relPath.split("/")) {
            if (seg.isEmpty()) continue;
            sb.append("/").append(Uri.encode(seg));
        }
        return "https://graph.microsoft.com/v1.0/me/drive/root:/" + sb + ":/content";
    }

    private void graphPutBytes(final String relPath, byte[] bytes, String contentType,
                               String token, final String filename, final String localPath) {
        RequestBody body = RequestBody.create(bytes, MediaType.parse(contentType));
        Request req = new Request.Builder()
                .url(graphPathUrl(relPath))
                .header("Authorization", "Bearer " + token)
                .put(body)
                .build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                reportDone(filename, false, "Shranjeno lokalno; mreža: " + e.getMessage(), localPath);
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response resp) {
                int code = resp.code();
                resp.close();
                if (code >= 200 && code < 300) {
                    msalLastSync = new java.util.Date().toString();
                    reportDone(filename, true, "Naloženo: " + MS_FOLDER + "/" + relPath, localPath);
                } else {
                    reportDone(filename, false, "Shranjeno lokalno; Graph napaka " + code, localPath);
                }
            }
        });
    }
}
