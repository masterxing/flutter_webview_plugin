package com.flutter_webview_plugin;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.provider.MediaStore;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import android.database.Cursor;
import android.provider.OpenableColumns;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.io.File;
import java.util.Date;
import java.io.IOException;
import java.text.SimpleDateFormat;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

import static android.app.Activity.RESULT_OK;

/**
 * Created by lejard_h on 20/12/2017.
 */

class WebviewManager {

    private ValueCallback<Uri> mUploadMessage;
    private ValueCallback<Uri[]> mUploadMessageArray;
    private final static int FILECHOOSER_RESULTCODE = 1;
    private final static int REQUEST_CAMERA = 100;
    private Uri fileUri;
    private Uri videoUri;

    private long getFileSize(Uri fileUri) {
        Cursor returnCursor = context.getContentResolver().query(fileUri, null, null, null, null);
        returnCursor.moveToFirst();
        int sizeIndex = returnCursor.getColumnIndex(OpenableColumns.SIZE);
        return returnCursor.getLong(sizeIndex);
    }

    @TargetApi(7)
    class ResultHandler {
        public boolean handleResult(int requestCode, int resultCode, Intent intent) {
            boolean handled = false;
            if (Build.VERSION.SDK_INT >= 21) {
                if (requestCode == FILECHOOSER_RESULTCODE) {
                    Uri[] results = null;
                    if (resultCode == Activity.RESULT_OK) {
                        if (fileUri != null && getFileSize(fileUri) > 0) {
                            results = new Uri[]{fileUri};
                        } else if (videoUri != null && getFileSize(videoUri) > 0) {
                            results = new Uri[]{videoUri};
                        } else if (intent != null) {
                            results = getSelectedFiles(intent);
                        }
                    }
                    if (results != null && results.length > 0) {
                        Uri uri = results[0];
                        File file = UriUtils.uri2File(activity, uri);
                        if (file!=null){
                            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                            // 打印出图片的大小
                            Log.i("xing", "原始图片大小："+bitmap.getByteCount());
                            //图片尺寸宽高均不小于756不大于2048
                            Bitmap bitmap1 = ImageUtils.compressBySampleSize(bitmap, Math.max(756, bitmap.getWidth()),
                                    Math.min(2048, bitmap.getHeight()));
                            //且大小不超过1M的图片
                            byte[] bytes = ImageUtils.compressByQuality(bitmap1, 1000 * 1000L);
                            Bitmap bitmap2 = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                            Log.i("xing", "压缩后图片大小：" + bitmap2.getByteCount());
                            Uri uri1 = Uri.parse(MediaStore.Images.Media.insertImage(activity.getContentResolver(), bitmap2, null, null));
                            results = new Uri[]{uri1};
                        }
                    }

                    if (mUploadMessageArray != null) {
                        mUploadMessageArray.onReceiveValue(results);
                        mUploadMessageArray = null;
                    }
                    handled = true;
                }

            } else {
                if (requestCode == FILECHOOSER_RESULTCODE) {
                    Uri result = null;
                    if (resultCode == RESULT_OK && intent != null) {
                        result = intent.getData();
                    }
                    if (mUploadMessage != null) {
                        mUploadMessage.onReceiveValue(result);
                        mUploadMessage = null;
                    }
                    handled = true;
                }
            }
            return handled;
        }

        public boolean handlePermissionResult(int requestCode, String[] permissions, int[] grantResults) {
            Log.d("xing", "handlePermissionResult: ");
            if (requestCode == REQUEST_CAMERA) {
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    Intent takePhotoIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    fileUri = getOutputFilename(MediaStore.ACTION_IMAGE_CAPTURE);
                    takePhotoIntent.putExtra(MediaStore.EXTRA_OUTPUT, fileUri);

                    activity.startActivityForResult(takePhotoIntent, FILECHOOSER_RESULTCODE);
                } else {
                    if (mUploadMessageArray != null) {
                        mUploadMessageArray.onReceiveValue(null);
                        mUploadMessageArray = null;
                    }
                }
                return true;
            }
            return false;
        }
    }

    private Uri[] getSelectedFiles(Intent data) {
        // we have one files selected
        if (data.getData() != null) {
            String dataString = data.getDataString();
            if (dataString != null) {
                return new Uri[]{Uri.parse(dataString)};
            }
        }
        // we have multiple files selected
        if (data.getClipData() != null) {
            final int numSelectedFiles = data.getClipData().getItemCount();
            Uri[] result = new Uri[numSelectedFiles];
            for (int i = 0; i < numSelectedFiles; i++) {
                result[i] = data.getClipData().getItemAt(i).getUri();
            }
            return result;
        }
        return null;
    }

    private final Handler platformThreadHandler;
    boolean closed = false;
    WebView webView;
    Activity activity;
    BrowserClient webViewClient;
    ResultHandler resultHandler;
    Context context;
    private boolean ignoreSSLErrors = false;

    WebviewManager(final Activity activity, final Context context, final List<String> channelNames) {
        this.webView = new ObservableWebView(activity);
        this.activity = activity;
        this.context = context;
        this.resultHandler = new ResultHandler();
        this.platformThreadHandler = new Handler(context.getMainLooper());
        webViewClient = new BrowserClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                if (ignoreSSLErrors) {
                    handler.proceed();
                } else {
                    super.onReceivedSslError(view, handler, error);
                }
            }
        };
        webView.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    switch (keyCode) {
                        case KeyEvent.KEYCODE_BACK:
                            Log.d("xing", "onKey: KEYCODE_BACK");
                            FlutterWebviewPlugin.channel.invokeMethod("onBack", null);
//                            if (webView.canGoBack()) {
//                                webView.goBack();
//                            } else {
//
//                            }
                            return true;
                    }
                }

                return false;
            }
        });

        ((ObservableWebView) webView).setOnScrollChangedCallback(new ObservableWebView.OnScrollChangedCallback() {
            public void onScroll(int x, int y, int oldx, int oldy) {
                Map<String, Object> yDirection = new HashMap<>();
                yDirection.put("yDirection", (double) y);
                FlutterWebviewPlugin.channel.invokeMethod("onScrollYChanged", yDirection);
                Map<String, Object> xDirection = new HashMap<>();
                xDirection.put("xDirection", (double) x);
                FlutterWebviewPlugin.channel.invokeMethod("onScrollXChanged", xDirection);
            }
        });

        webView.setWebViewClient(webViewClient);
        webView.setWebChromeClient(new WebChromeClient() {
            //The undocumented magic method override
            //Eclipse will swear at you if you try to put @Override here
            // For Android 3.0+
            public void openFileChooser(ValueCallback<Uri> uploadMsg) {

                mUploadMessage = uploadMsg;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                activity.startActivityForResult(Intent.createChooser(i, "File Chooser"), FILECHOOSER_RESULTCODE);

            }

            // For Android 3.0+
            public void openFileChooser(ValueCallback uploadMsg, String acceptType) {
                mUploadMessage = uploadMsg;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                activity.startActivityForResult(
                        Intent.createChooser(i, "File Browser"),
                        FILECHOOSER_RESULTCODE);
            }

            //For Android 4.1
            public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType, String capture) {
                mUploadMessage = uploadMsg;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                activity.startActivityForResult(Intent.createChooser(i, "File Chooser"), FILECHOOSER_RESULTCODE);

            }

//            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
//            @Override
//            public void onPermissionRequest(PermissionRequest request) {
//                super.onPermissionRequest(request);
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
//                    request.grant(request.getResources());
//                }
//            }

            //For Android 5.0+
            public boolean onShowFileChooser(
                    WebView webView, ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {
                if (mUploadMessageArray != null) {
                    mUploadMessageArray.onReceiveValue(null);
                }
                mUploadMessageArray = filePathCallback;
                fileUri = null;
                videoUri = null;
                Log.d("xing", "onShowFileChooser: ");
                //检查是否有相机权限
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    Log.d("xing", "onShowFileChooser: 申请相机权限");
                    //申请相机权限
                    activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
                } else {
                    Intent takePhotoIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    fileUri = getOutputFilename(MediaStore.ACTION_IMAGE_CAPTURE);
                    takePhotoIntent.putExtra(MediaStore.EXTRA_OUTPUT, fileUri);
                    activity.startActivityForResult(takePhotoIntent, FILECHOOSER_RESULTCODE);
                }
                return true;
            }


            @Override
            public void onProgressChanged(WebView view, int progress) {
                Map<String, Object> args = new HashMap<>();
                args.put("progress", progress / 100.0);
                FlutterWebviewPlugin.channel.invokeMethod("onProgressChanged", args);
            }

            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
        });
        registerJavaScriptChannelNames(channelNames);
    }

    private Uri getOutputFilename(String intentType) {
        String prefix = "";
        String suffix = "";

        if (intentType == MediaStore.ACTION_IMAGE_CAPTURE) {
            prefix = "image-";
            suffix = ".jpg";
        } else if (intentType == MediaStore.ACTION_VIDEO_CAPTURE) {
            prefix = "video-";
            suffix = ".mp4";
        }

        String packageName = context.getPackageName();
        File capturedFile = null;
        try {
            capturedFile = createCapturedFile(prefix, suffix);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return FileProvider.getUriForFile(context, packageName + ".fileprovider", capturedFile);
    }

    private File createCapturedFile(String prefix, String suffix) throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String imageFileName = prefix + "_" + timeStamp;
        File storageDir = context.getExternalFilesDir(null);
        return File.createTempFile(imageFileName, suffix, storageDir);
    }

    private Boolean acceptsImages(String[] types) {
        return isArrayEmpty(types) || arrayContainsString(types, "image");
    }

    private Boolean acceptsVideo(String[] types) {
        return isArrayEmpty(types) || arrayContainsString(types, "video");
    }

    private Boolean arrayContainsString(String[] array, String pattern) {
        for (String content : array) {
            if (content.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private Boolean isArrayEmpty(String[] arr) {
        // when our array returned from getAcceptTypes() has no values set from the
        // webview
        // i.e. <input type="file" />, without any "accept" attr
        // will be an array with one empty string element, afaik
        return arr.length == 0 || (arr.length == 1 && arr[0].length() == 0);
    }

    private String[] getSafeAcceptedTypes(WebChromeClient.FileChooserParams params) {

        // the getAcceptTypes() is available only in api 21+
        // for lower level, we ignore it
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return params.getAcceptTypes();
        }

        final String[] EMPTY = {};
        return EMPTY;
    }

    private void clearCookies() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().removeAllCookies(new ValueCallback<Boolean>() {
                @Override
                public void onReceiveValue(Boolean aBoolean) {

                }
            });
        } else {
            CookieManager.getInstance().removeAllCookie();
        }
    }

    private void clearCache() {
        webView.clearCache(true);
        webView.clearFormData();
    }

    private void registerJavaScriptChannelNames(List<String> channelNames) {
        for (String channelName : channelNames) {
            webView.addJavascriptInterface(
                    new JavaScriptChannel(FlutterWebviewPlugin.channel, channelName, platformThreadHandler), channelName);
        }
    }

    void openUrl(
            boolean withJavascript,
            boolean clearCache,
            boolean hidden,
            boolean clearCookies,
            boolean mediaPlaybackRequiresUserGesture,
            String userAgent,
            String url,
            Map<String, String> headers,
            boolean withZoom,
            boolean displayZoomControls,
            boolean withLocalStorage,
            boolean withOverviewMode,
            boolean scrollBar,
            boolean supportMultipleWindows,
            boolean appCacheEnabled,
            boolean allowFileURLs,
            boolean useWideViewPort,
            String invalidUrlRegex,
            boolean geolocationEnabled,
            boolean debuggingEnabled,
            boolean ignoreSSLErrors
    ) {
        webView.getSettings().setJavaScriptEnabled(withJavascript);
        webView.getSettings().setBuiltInZoomControls(withZoom);
        webView.getSettings().setSupportZoom(withZoom);
        webView.getSettings().setDisplayZoomControls(displayZoomControls);
        webView.getSettings().setDomStorageEnabled(withLocalStorage);
        webView.getSettings().setLoadWithOverviewMode(withOverviewMode);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(supportMultipleWindows);

        webView.getSettings().setSupportMultipleWindows(supportMultipleWindows);

        webView.getSettings().setAppCacheEnabled(appCacheEnabled);

        webView.getSettings().setAllowFileAccessFromFileURLs(allowFileURLs);
        webView.getSettings().setAllowUniversalAccessFromFileURLs(allowFileURLs);

        webView.getSettings().setUseWideViewPort(useWideViewPort);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            webView.getSettings().setMediaPlaybackRequiresUserGesture(mediaPlaybackRequiresUserGesture);
        }

        // Handle debugging
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.setWebContentsDebuggingEnabled(debuggingEnabled);
        }
        //ignore SSL errors
        this.ignoreSSLErrors = ignoreSSLErrors;

        webViewClient.updateInvalidUrlRegex(invalidUrlRegex);

        if (geolocationEnabled) {
            webView.getSettings().setGeolocationEnabled(true);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webView.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        if (clearCache) {
            clearCache();
        }

        if (hidden) {
            webView.setVisibility(View.GONE);
        }

        if (clearCookies) {
            clearCookies();
        }

        if (userAgent != null) {
            webView.getSettings().setUserAgentString(userAgent);
        }

        if (!scrollBar) {
            webView.setVerticalScrollBarEnabled(false);
        }

        if (headers != null) {
            webView.loadUrl(url, headers);
        } else {
            webView.loadUrl(url);
        }
    }

    void reloadUrl(String url) {
        webView.loadUrl(url);
    }

    void reloadUrl(String url, Map<String, String> headers) {
        webView.loadUrl(url, headers);
    }

    void close(MethodCall call, MethodChannel.Result result) {
        if (webView != null) {
            ViewGroup vg = (ViewGroup) (webView.getParent());
            vg.removeView(webView);
        }
        webView = null;
        if (result != null) {
            result.success(null);
        }

        closed = true;
        FlutterWebviewPlugin.channel.invokeMethod("onDestroy", null);
    }

    void close() {
        close(null, null);
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    void eval(MethodCall call, final MethodChannel.Result result) {
        String code = call.argument("code");

        webView.evaluateJavascript(code, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                result.success(value);
            }
        });
    }

    /**
     * Reloads the Webview.
     */
    void reload(MethodCall call, MethodChannel.Result result) {
        if (webView != null) {
            webView.reload();
        }
    }

    /**
     * Navigates back on the Webview.
     */
    void back(MethodCall call, MethodChannel.Result result) {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        }
    }

    /**
     * Navigates forward on the Webview.
     */
    void forward(MethodCall call, MethodChannel.Result result) {
        if (webView != null && webView.canGoForward()) {
            webView.goForward();
        }
    }

    void resize(FrameLayout.LayoutParams params) {
        webView.setLayoutParams(params);
    }

    /**
     * Checks if going back on the Webview is possible.
     */
    boolean canGoBack() {
        return webView.canGoBack();
    }

    /**
     * Checks if going forward on the Webview is possible.
     */
    boolean canGoForward() {
        return webView.canGoForward();
    }

    /**
     * Clears cache
     */
    void cleanCache() {
        webView.clearCache(true);
    }

    void hide(MethodCall call, MethodChannel.Result result) {
        if (webView != null) {
            webView.setVisibility(View.GONE);
        }
    }

    void show(MethodCall call, MethodChannel.Result result) {
        if (webView != null) {
            webView.setVisibility(View.VISIBLE);
        }
    }

    void stopLoading(MethodCall call, MethodChannel.Result result) {
        if (webView != null) {
            webView.stopLoading();
        }
    }
}
