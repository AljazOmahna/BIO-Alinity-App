package si.kclj.bioalinity;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Canvas;
import android.graphics.pdf.PdfDocument;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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

import android.content.ContentValues;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
    private static final int REQ_PICK_QCVAL = 1002;

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
        // Mora biti klicano pred ustvarjanjem kateregakoli WebView-a: omogoči, da
        // view.draw() izriše CELOTEN dokument (ne le vidnega pasu) — nujno za
        // pravilen večstranski izris poročil v PDF.
        WebView.enableSlowWholeDocumentDraw();
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
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_PICK_QCVAL) { handleQcValPick(uri); return; }
        if (requestCode != REQ_PICK_REPORT) return;
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

        @JavascriptInterface
        public void msListFolder(String folder) {
            final String f = folder;
            mainHandler.post(() -> msAcquireToken("folder_" + f, (token, err) -> {
                if (token == null) { callJs("onMsFolderListed(null," + jsStr(err) + ")"); return; }
                graphListFolder(f, token);
            }));
        }

        @JavascriptInterface
        public void msDownloadText(String path) {
            final String p = path;
            mainHandler.post(() -> msAcquireToken("text_" + p, (token, err) -> {
                if (token == null) { callJs("onMsTextFile(null," + jsStr(err) + ")"); return; }
                graphGetText(p, token);
            }));
        }

        // Naloži besedilno vsebino (npr. CSV) v DigiLab/<relPath> v OneDriveju.
        @JavascriptInterface
        public void msUploadRaw(String relPath, String content, String mimeType) {
            final String rp = relPath, ct = mimeType != null && !mimeType.isEmpty() ? mimeType : "text/plain; charset=utf-8";
            final byte[] bytes = content == null ? new byte[0] : content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            mainHandler.post(() -> msAcquireToken("raw_" + rp, (token, err) -> {
                if (token == null) { callJs("onMsUploadRawDone(false," + jsStr(err) + ")"); return; }
                graphUploadRawBytes(rp, bytes, ct, token);
            }));
        }

        // Sestavi PDF iz HTML-ja, shrani lokalno (Porocila/<leto>) in naloži v OneDrive DigiLab/<subfolder>/<filename>.
        @JavascriptInterface
        public void generateAndUploadReport(String subfolder, String filename, String html) {
            final String sf = subfolder, fn = filename, h = html;
            mainHandler.post(() -> renderReportToPdf(sf, fn, h));
        }

        // ---- QC vrednosti: ročni uvoz lota (ZIP/XML/PDF) ----
        @JavascriptInterface
        public void pickQcValuesFile() {
            mainHandler.post(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    i.putExtra(Intent.EXTRA_MIME_TYPES,
                            new String[]{"application/zip", "application/xml", "text/xml", "application/pdf"});
                    startActivityForResult(i, REQ_PICK_QCVAL);
                } catch (Exception e) {
                    callJs("if(typeof onQcValuesPickError==='function')onQcValuesPickError(" + jsStr(e.getMessage()) + ")");
                }
            });
        }

        // ---- QC vrednosti: samodejni prenos XML za lot iz OneDrive ----
        @JavascriptInterface
        public void qcFetchXmlForLot(String lot) {
            final String l = lot;
            mainHandler.post(() -> qcFetchXml(l));
        }

        // ---- QC XML: shrani v javne Prenose (Downloads) + OneDrive arhiv ----
        @JavascriptInterface
        public void saveQcXmlToDownloads(String filename, String xml, String lot) {
            final String fn = filename;
            final String lotKey = (lot != null && !lot.isEmpty()) ? lot : stripExt(fn);
            final byte[] bytes = xml == null ? new byte[0] : xml.getBytes(StandardCharsets.UTF_8);
            new Thread(() -> {
                try {
                    String savedPath = _saveXmlToDownloads(fn, bytes);
                    callJs("if(typeof onQcXmlSaved==='function')onQcXmlSaved(" + jsStr(savedPath) + ",true," + jsStr("ok") + ")");
                    msAcquireToken("qcxml", (token, err) -> {
                        if (token != null) graphUploadGeneric("QC_vrednosti/" + lotKey + "/" + fn, bytes, "application/xml", token, fn);
                    });
                } catch (Exception e) {
                    callJs("if(typeof onQcXmlSaved==='function')onQcXmlSaved(null,false," + jsStr(e.getMessage()) + ")");
                }
            }).start();
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

    private void graphUploadRawBytes(final String relPath, byte[] bytes, String contentType, String token) {
        RequestBody body = RequestBody.create(bytes, MediaType.parse(contentType));
        Request req = new Request.Builder()
                .url(graphPathUrl(relPath))
                .header("Authorization", "Bearer " + token)
                .put(body)
                .build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callJs("onMsUploadRawDone(false," + jsStr("Napaka: " + e.getMessage()) + ")");
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response resp) {
                int code = resp.code(); resp.close();
                callJs("onMsUploadRawDone(" + (code >= 200 && code < 300 ? "true" : "false") + ",'ok')");
            }
        });
    }

    private void graphListFolder(final String folder, String token) {
        StringBuilder sb = new StringBuilder();
        sb.append(Uri.encode(MS_FOLDER));
        for (String seg : folder.split("/")) {
            if (!seg.isEmpty()) sb.append("/").append(Uri.encode(seg));
        }
        String url = "https://graph.microsoft.com/v1.0/me/drive/root:/"
                + sb + ":/children?$select=name,id,lastModifiedDateTime&$top=200";
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + token)
                .get()
                .build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callJs("onMsFolderListed(null," + jsStr("Napaka mreže: " + e.getMessage()) + ")");
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response resp) {
                int code = resp.code();
                String body = null;
                try { ResponseBody rb = resp.body(); if (rb != null) body = rb.string(); } catch (IOException ignored) {} finally { resp.close(); }
                if (code >= 200 && code < 300 && body != null) {
                    callJs("onMsFolderListed(" + jsStr(body) + ",'ok')");
                } else {
                    callJs("onMsFolderListed(null," + jsStr("Graph napaka " + code) + ")");
                }
            }
        });
    }

    private void graphGetText(final String relPath, String token) {
        Request req = new Request.Builder()
                .url(graphPathUrl(relPath))
                .header("Authorization", "Bearer " + token)
                .get()
                .build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callJs("onMsTextFile(null," + jsStr("Napaka mreže: " + e.getMessage()) + ")");
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response resp) {
                int code = resp.code();
                String body = null;
                try { ResponseBody rb = resp.body(); if (rb != null) body = rb.string(); } catch (IOException ignored) {} finally { resp.close(); }
                if (code >= 200 && code < 300 && body != null) {
                    callJs("onMsTextFile(" + jsStr(body) + ",'ok')");
                } else {
                    callJs("onMsTextFile(null," + jsStr("Graph napaka " + code) + ")");
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

    private FrameLayout reportContainer; // vsebnik, da WebView ostane pritrjen med async tiskanjem

    private void cleanupReportView() {
        try {
            if (reportContainer != null && reportContainer.getParent() != null)
                ((ViewGroup) reportContainer.getParent()).removeView(reportContainer);
        } catch (Exception ignored) {}
        reportContainer = null;
        reportWebView = null;
    }

    // A4 landscape: 842×595 PostScript pt. Izris pri 2× za kakovost.
    private static final int PDF_W = 842, PDF_H = 595;
    private static final int RVW = PDF_W * 2, RVH = PDF_H * 2; // velikost ene strani v px
    // Vsebinska višina na stran = 561pt (CSS @page size), tj. 1122px pri 2×. Spodnji odmik 34pt izhaja
    // iz razlike RVH-SLICE_H=68px (=34pt×2). CSS page-break-inside:avoid deluje znotraj tega modela.
    private static final int SLICE_H = 561 * 2; // = 1122 px — mora se ujemati z @page{size:842pt 561pt}

    @SuppressLint("SetJavaScriptEnabled")
    private void renderReportToPdf(final String subfolder, final String filename, String html) {
        try {
            final WebView wv = new WebView(this);
            wv.getSettings().setJavaScriptEnabled(false);
            wv.setVerticalScrollBarEnabled(false);
            wv.setHorizontalScrollBarEnabled(false);
            wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            reportWebView = wv;

            // WebView pritrjen na window hierarchy, da se vsebina naloži in izriše.
            final FrameLayout container = new FrameLayout(this);
            container.setAlpha(0f);
            reportContainer = container;
            FrameLayout decor = (FrameLayout) getWindow().getDecorView();
            decor.addView(container, new FrameLayout.LayoutParams(RVW, RVH));
            container.addView(wv, new FrameLayout.LayoutParams(RVW, RVH));

            wv.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) {
                    // počakaj kratek hip, da se postavitev ustali, nato izriši v PDF
                    mainHandler.postDelayed(() -> writeWebViewPdf(view, subfolder, filename), 500);
                }
            });
            wv.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
        } catch (Exception e) {
            cleanupReportView();
            reportDone(filename, false, "Napaka izrisa: " + e.getMessage(), null);
        }
    }

    // Večstranski PDF: WebView postavimo na celotno višino vsebine in ga izrišemo
    // po pasovih (A4 landscape). enableSlowWholeDocumentDraw() (v onCreate) poskrbi,
    // da view.draw() izriše CELOTEN dokument, ne le vidnega pasu.
    private void writeWebViewPdf(WebView view, final String subfolder, final String filename) {
        try {
            // Izmeri pravo višino vsebine pri širini RVW (UNSPECIFIED višina).
            int specW = View.MeasureSpec.makeMeasureSpec(RVW, View.MeasureSpec.EXACTLY);
            int specH = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            view.measure(specW, specH);
            int cH = view.getMeasuredHeight();
            if (cH <= 0) {
                float scale = view.getScale() > 0 ? view.getScale() : 1f;
                cH = (int) Math.ceil(view.getContentHeight() * scale);
            }
            if (cH <= 0) cH = RVH;
            view.layout(0, 0, RVW, cH);

            int pages = Math.max(1, (int) Math.ceil((double) cH / SLICE_H));
            pages = Math.min(pages, 60); // varovalka

            PdfDocument doc = new PdfDocument();
            for (int i = 0; i < pages; i++) {
                PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(PDF_W, PDF_H, i + 1).create();
                PdfDocument.Page pg = doc.startPage(info);
                Canvas canvas = pg.getCanvas();
                canvas.scale(0.5f, 0.5f);              // RVW×RVH px → PDF_W×PDF_H pt
                canvas.translate(0, -(float) (i * SLICE_H));
                view.draw(canvas);                     // izriše celoten dokument (slow whole doc)
                doc.finishPage(pg);
            }

            File dir = getExternalFilesDir(subfolder);
            if (dir == null) dir = new File(getFilesDir(), subfolder);
            if (!dir.exists()) dir.mkdirs();
            File outFile = new File(dir, filename);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                doc.writeTo(fos);
            }
            doc.close();

            cleanupReportView();
            uploadReportPdf(subfolder, filename, outFile);
        } catch (Exception e) {
            cleanupReportView();
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

    // =========================================================
    // QC vrednosti — ročni uvoz (ZIP/XML/PDF) + OneDrive arhiv + samodejni prenos
    // =========================================================
    private static final Pattern QC_LOT_RE = Pattern.compile("LotNumber\\s*=\\s*\"([^\"]+)\"");

    private void handleQcValPick(Uri uri) {
        try {
            String name = queryName(uri);
            if (name == null || name.isEmpty()) name = "qc_" + System.currentTimeMillis();
            byte[] raw = readUriBytes(uri);
            byte[] xmlBytes = null, pdfBytes = null;
            String xmlName = null, pdfName = null;
            String low = name.toLowerCase();
            if (low.endsWith(".zip")) {
                ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(raw));
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    String enl = e.getName().toLowerCase();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192]; int r;
                    while ((r = zin.read(buf)) != -1) bos.write(buf, 0, r);
                    byte[] eb = bos.toByteArray();
                    if (enl.endsWith(".xml")) { xmlBytes = eb; xmlName = baseName(e.getName()); }
                    else if (enl.endsWith(".pdf")) { pdfBytes = eb; pdfName = baseName(e.getName()); }
                    zin.closeEntry();
                }
                zin.close();
            } else if (low.endsWith(".xml")) { xmlBytes = raw; xmlName = name; }
            else if (low.endsWith(".pdf")) { pdfBytes = raw; pdfName = name; }

            String xmlText = (xmlBytes != null) ? new String(xmlBytes, "UTF-8") : null;
            String lot = extractLot(xmlText);
            if (lot == null || lot.isEmpty()) lot = stripExt(name);

            File dir = getExternalFilesDir("QC_vrednosti/" + lot);
            if (dir == null) dir = new File(getFilesDir(), "QC_vrednosti/" + lot);
            if (!dir.exists()) dir.mkdirs();
            String localPdf = null;
            if (xmlBytes != null) writeFile(new File(dir, lot + ".xml"), xmlBytes);
            if (pdfBytes != null) {
                File pf = new File(dir, pdfName != null ? pdfName : lot + ".pdf");
                writeFile(pf, pdfBytes);
                localPdf = pf.getAbsolutePath();
            }

            if (xmlText != null) {
                callJs("if(typeof onQcValuesPicked==='function')onQcValuesPicked("
                        + jsStr(xmlText) + "," + jsStr(lot) + "," + jsStr(localPdf) + ")");
            } else {
                callJs("if(typeof onQcValuesPickError==='function')onQcValuesPickError('Ni XML v datoteki')");
            }

            // OneDrive arhiv (best-effort): xml pod <lot>.xml (determinističen) + pdf
            final String flot = lot;
            final byte[] fxml = xmlBytes, fpdf = pdfBytes;
            final String fpdfName = pdfName;
            mainHandler.post(() -> msAcquireToken("qcval", (token, err) -> {
                if (token == null) {
                    callJs("if(typeof onQcUploadDone==='function')onQcUploadDone(" + jsStr(flot) + ",false," + jsStr("OneDrive: " + err) + ")");
                    return;
                }
                if (fxml != null) graphUploadGeneric("QC_vrednosti/" + flot + "/" + flot + ".xml", fxml, "application/xml", token, flot + ".xml");
                if (fpdf != null) graphUploadGeneric("QC_vrednosti/" + flot + "/" + (fpdfName != null ? fpdfName : flot + ".pdf"), fpdf, "application/pdf", token, fpdfName != null ? fpdfName : flot + ".pdf");
            }));
        } catch (Exception e) {
            callJs("if(typeof onQcValuesPickError==='function')onQcValuesPickError(" + jsStr("Napaka: " + e.getMessage()) + ")");
        }
    }

    private void graphUploadGeneric(final String relPath, byte[] bytes, String contentType, String token, final String label) {
        RequestBody body = RequestBody.create(bytes, MediaType.parse(contentType));
        Request req = new Request.Builder().url(graphPathUrl(relPath))
                .header("Authorization", "Bearer " + token).put(body).build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callJs("if(typeof onQcUploadDone==='function')onQcUploadDone(" + jsStr(label) + ",false," + jsStr("mreža: " + e.getMessage()) + ")");
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response resp) {
                int code = resp.code(); resp.close();
                boolean ok = code >= 200 && code < 300;
                if (ok) msalLastSync = new java.util.Date().toString();
                callJs("if(typeof onQcUploadDone==='function')onQcUploadDone(" + jsStr(label) + "," + (ok ? "true" : "false") + "," + jsStr(ok ? "ok" : ("Graph " + code)) + ")");
            }
        });
    }

    // Samodejni prenos XML za lot iz OneDrive (determinističen <lot>.xml)
    private void qcFetchXml(final String lot) {
        if (lot == null || lot.isEmpty()) return;
        msAcquireToken("qcfetch", (token, err) -> {
            if (token == null) { callJs("if(typeof onQcXmlFetched==='function')onQcXmlFetched(" + jsStr(lot) + ",null," + jsStr(err) + ")"); return; }
            Request req = new Request.Builder()
                    .url(graphPathUrl("QC_vrednosti/" + lot + "/" + lot + ".xml"))
                    .header("Authorization", "Bearer " + token).get().build();
            http.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    callJs("if(typeof onQcXmlFetched==='function')onQcXmlFetched(" + jsStr(lot) + ",null," + jsStr("mreža: " + e.getMessage()) + ")");
                }
                @Override public void onResponse(@NonNull Call call, @NonNull Response resp) {
                    int code = resp.code();
                    String b = null;
                    try { ResponseBody rb = resp.body(); if (rb != null) b = rb.string(); } catch (IOException ex) { b = null; } finally { resp.close(); }
                    if (code >= 200 && code < 300 && b != null) {
                        callJs("if(typeof onQcXmlFetched==='function')onQcXmlFetched(" + jsStr(lot) + "," + jsStr(b) + ",'ok')");
                    } else {
                        callJs("if(typeof onQcXmlFetched==='function')onQcXmlFetched(" + jsStr(lot) + ",null," + jsStr(code == 404 ? "ni v OneDrive" : ("Graph " + code)) + ")");
                    }
                }
            });
        });
    }

    private byte[] readUriBytes(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("Ni mogoče odpreti datoteke");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            return bos.toByteArray();
        }
    }
    private void writeFile(File f, byte[] bytes) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(bytes); }
    }
    private String baseName(String p) {
        if (p == null) return null;
        int s = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return s >= 0 ? p.substring(s + 1) : p;
    }
    private String stripExt(String n) {
        if (n == null) return "";
        int d = n.lastIndexOf('.');
        return d > 0 ? n.substring(0, d) : n;
    }
    private String extractLot(String xmlText) {
        if (xmlText == null) return null;
        Matcher m = QC_LOT_RE.matcher(xmlText);
        return m.find() ? m.group(1).trim() : null;
    }

    private String _saveXmlToDownloads(String filename, byte[] bytes) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            cv.put(MediaStore.Downloads.MIME_TYPE, "application/xml");
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            android.net.Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("MediaStore.Downloads: insert vrnil null");
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new Exception("Ni mogoče odpreti izhodnega toka");
                os.write(bytes);
            }
            cv.clear();
            cv.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, cv, null, null);
            return uri.toString();
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, filename);
            writeFile(f, bytes);
            return f.getAbsolutePath();
        }
    }
}
