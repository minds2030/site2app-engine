package mgks.os.swv;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.navigation.NavigationView;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Matcher;

import mgks.os.swv.plugins.QRScannerPlugin;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    private static final String TAG = "MainActivity";
    private boolean isPageLoaded = false;
    static Functions fns = new Functions();
    private FileProcessing fileProcessing;
    private LinearLayout adContainer;
    private PermissionManager permissionManager;
    private ActivityResultLauncher<Intent> fileUploadLauncher;
    private ActivityResultLauncher<ScanOptions> qrScannerLauncher;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        SWVContext.getPluginManager().onActivityResult(requestCode, resultCode, intent);
    }

    @SuppressLint({"SetJavaScriptEnabled", "WrongViewCast", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (SWVContext.ASWP_BLOCK_SCREENSHOTS) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        super.onCreate(savedInstanceState);

        final SplashScreen splashScreen = androidx.core.splashscreen.SplashScreen.installSplashScreen(this);
        final View content = findViewById(android.R.id.content);
        
        if (SWVContext.ASWP_EXTEND_SPLASH) {
            content.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (isPageLoaded) {
                        content.getViewTreeObserver().removeOnPreDrawListener(this);
                        return true;
                    } else {
                        return false;
                    }
                }
            });
        }

        permissionManager = new PermissionManager(this);
        fileUploadLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            Uri[] results = null;
            if (result.getResultCode() == Activity.RESULT_OK) {
                if (null != SWVContext.asw_file_path) {
                    Intent data = result.getData();
                    if (data != null && (data.getDataString() != null || data.getClipData() != null)) {
                        ClipData clipData = data.getClipData();
                        if (clipData != null) {
                            results = new Uri[clipData.getItemCount()];
                            for (int i = 0; i < clipData.getItemCount(); i++) {
                                results[i] = clipData.getItemAt(i).getUri();
                            }
                        } else {
                            results = new Uri[]{Uri.parse(data.getDataString())};
                        }
                    }
                }
            }
            if (SWVContext.asw_file_path != null) {
                SWVContext.asw_file_path.onReceiveValue(results);
                SWVContext.asw_file_path = null;
            }
        });

        qrScannerLauncher = registerForActivityResult(new ScanContract(), result -> {
            PluginInterface plugin = SWVContext.getPluginManager().getPluginInstance("QRScannerPlugin");
            if (plugin instanceof QRScannerPlugin) {
                ((QRScannerPlugin) plugin).handleScanResult(result);
            }
        });

        SWVContext.setAppContext(getApplicationContext());
        fileProcessing = new FileProcessing(this, fileUploadLauncher);
        
        // إجبار التطبيق على استخدام الواجهة البسيطة بدون قوائم
        setContentView(R.layout.activity_main);
        setupLayout();
        initializeWebView();

        SWVContext.loadPlugins(this);
        SWVContext.init(this, SWVContext.asw_view, fns);

        if (savedInstanceState == null) {
            setupFeatures();
            handleIncomingIntents();
        }

        ViewCompat.setOnApplyWindowInsetsListener(content, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return windowInsets;
        });
    }

    private void setupLayout() {
        // تم تعطيل كل أكواد الـ Drawer و Toolbar لضمان نظافة الشاشة
        SWVContext.asw_view = findViewById(R.id.msw_view);
        adContainer = findViewById(R.id.msw_ad_container);
        SWVContext.print_view = findViewById(R.id.print_view);
        
        if(adContainer != null) adContainer.setVisibility(View.GONE);
    }

    private void initializeWebView() {
        SWVContext.init(this, SWVContext.asw_view, fns);
        WebSettings webSettings = SWVContext.asw_view.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(false);
        webSettings.setDisplayZoomControls(false);
        webSettings.setDomStorageEnabled(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        SWVContext.asw_view.setWebViewClient(new WebViewCallback());
        SWVContext.asw_view.setWebChromeClient(createWebChromeClient());
        SWVContext.asw_view.setBackgroundColor(Color.BLACK); // خلفية سوداء لمنع البياض
        SWVContext.asw_view.addJavascriptInterface(new WebAppInterface(), "AndroidInterface");
        
        setupDownloadListener();
    }

    private void setupDownloadListener() {
        SWVContext.asw_view.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setMimeType(mimeType);
            String cookies = CookieManager.getInstance().getCookie(url);
            request.addRequestHeader("cookie", cookies);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimeType));
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm != null) dm.enqueue(request);
            Toast.makeText(this, "جاري التحميل...", Toast.LENGTH_LONG).show();
        });
    }

    private WebChromeClient createWebChromeClient() {
        return new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                if (SWVContext.asw_progress == null) SWVContext.asw_progress = findViewById(R.id.msw_progress);
                if (SWVContext.asw_progress != null) {
                    SWVContext.asw_progress.setVisibility(p == 100 ? View.GONE : View.VISIBLE);
                    SWVContext.asw_progress.setProgress(p);
                }
                if (p > 80) isPageLoaded = true;
            }
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                return fileProcessing.onShowFileChooser(webView, filePathCallback, fileChooserParams);
            }
        };
    }

    private void setupFeatures() {
        setupSwipeRefresh();
        permissionManager.requestInitialPermissions();
    }

    private void setupSwipeRefresh() {
        final SwipeRefreshLayout pullRefresh = findViewById(R.id.pullfresh);
        if (pullRefresh != null) {
            pullRefresh.setOnRefreshListener(() -> {
                SWVContext.asw_view.reload();
                pullRefresh.setRefreshing(false);
            });
        }
    }

    private void handleIncomingIntents() {
        String url = SWVContext.ASWV_URL;
        if (getIntent().getData() != null) url = getIntent().getDataString();
        SWVContext.asw_view.loadUrl(url);
    }

    @Override public boolean onNavigationItemSelected(@NonNull MenuItem item) { return true; }
    @Override public boolean onCreateOptionsMenu(Menu menu) { return true; }
    @Override public boolean onOptionsItemSelected(MenuItem item) { return super.onOptionsItemSelected(item); }

    public class WebAppInterface {
        @JavascriptInterface
        public void setNativeTheme(String theme) {
            runOnUiThread(() -> {
                if ("dark".equals(theme)) AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                else AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            });
        }
    }

    public class WebViewCallback extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
        }
        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            isPageLoaded = true;
            findViewById(R.id.msw_view).setVisibility(View.VISIBLE);
        }
    }
}
