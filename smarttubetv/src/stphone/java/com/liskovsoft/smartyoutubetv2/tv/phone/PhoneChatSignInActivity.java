package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Sign in to chat: YouTube only takes live chat messages from a YouTube website session, not from the TV-style
 * sign-in the rest of the app uses. Google's own sign-in page opens here (the browser's, so Google allows it); once
 * YouTube's login cookies are there, they're kept for the chat only, in the app's no-backup storage (never in
 * backups, never sent anywhere but YouTube), and this browser's own copy is wiped.
 */
public class PhoneChatSignInActivity extends Activity {
    private static final String LOGIN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https://www.youtube.com/";
    private static final String FILE = "chat_web_login";

    private WebView mWeb;
    private boolean mDone;

    static void open(Activity activity) {
        activity.startActivity(new Intent(activity, PhoneChatSignInActivity.class));
    }

    // region The kept login

    /** At app start (and after signing in): the chat uses the kept login, if there is one */
    static void apply(Context context) {
        com.liskovsoft.youtubeapi.chat.LiveChatWriter.setWebCookie(read(context));
        String[] page = readPage(context);
        com.liskovsoft.youtubeapi.chat.LiveChatWriter.setWebPageId(page != null && !page[0].isEmpty() ? page[0] : null);
    }

    // The channel that writes (a Google account can have several): its page id ("" its main channel), and picture

    private static final String PAGE_FILE = "chat_web_page";

    /** {pageId, photo}, or null when no channel was chosen yet */
    static String[] readPage(Context context) {
        File file = new File(context.getNoBackupFilesDir(), PAGE_FILE);
        if (!file.exists()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = in.read(bytes);
            String text = read > 0 ? new String(bytes, 0, read, StandardCharsets.UTF_8) : "";
            String[] parts = text.split("\n", -1);
            return new String[] {parts[0], parts.length > 1 ? parts[1] : "", parts.length > 2 ? parts[2] : ""};
        } catch (Exception e) {
            return null;
        }
    }

    static void choosePage(Context context, String pageId, String photo, String name) {
        try (FileOutputStream out = new FileOutputStream(new File(context.getNoBackupFilesDir(), PAGE_FILE))) {
            out.write(((pageId != null ? pageId : "") + "\n" + (photo != null ? photo : "") + "\n" + (name != null ? name : ""))
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            android.util.Log.d("PhoneLiveChat", "chat channel not kept: " + e);
        }
        com.liskovsoft.youtubeapi.chat.LiveChatWriter.setWebPageId(pageId);
    }

    static void signOut(Context context) {
        File file = file(context);
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        File page = new File(context.getNoBackupFilesDir(), PAGE_FILE);
        if (page.exists()) {
            //noinspection ResultOfMethodCallIgnored
            page.delete();
        }
        com.liskovsoft.youtubeapi.chat.LiveChatWriter.setWebPageId(null);
        com.liskovsoft.youtubeapi.chat.LiveChatWriter.setWebCookie(null);
    }

    private static File file(Context context) {
        return new File(context.getNoBackupFilesDir(), FILE);
    }

    private static String read(Context context) {
        File file = file(context);
        if (!file.exists()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = in.read(bytes);
            return read > 0 ? new String(bytes, 0, read, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(Context context, String cookie) {
        try (FileOutputStream out = new FileOutputStream(file(context))) {
            out.write(cookie.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            android.util.Log.d("PhoneLiveChat", "chat login not kept: " + e);
        }
    }

    // endregion

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getResources().getColor(R.color.phone_bg));
        root.setFitsSystemWindows(true);

        // A bar: back, and what this is for
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Math.round(4 * d), Math.round(4 * d), Math.round(16 * d), Math.round(4 * d));
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_phone_back);
        back.setImageTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.phone_text)));
        int pad = Math.round(12 * d);
        back.setPadding(pad, pad, pad, pad);
        back.setBackgroundResource(R.drawable.phone_ripple);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(Math.round(48 * d), Math.round(48 * d)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.phone_chat_sign_in);
        title.setTextColor(getResources().getColor(R.color.phone_text));
        title.setTextSize(18);
        title.setTypeface(PhoneUi.medium(this));
        titles.addView(title);
        TextView sub = new TextView(this);
        sub.setText(R.string.phone_chat_sign_in_why);
        sub.setTextColor(getResources().getColor(R.color.phone_text_2));
        sub.setTextSize(13);
        titles.addView(sub);
        LinearLayout.LayoutParams titlesParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titlesParams.setMarginStart(Math.round(8 * d));
        bar.addView(titles, titlesParams);
        root.addView(bar);

        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.phone_accent)));
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(3 * d)));

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.removeAllCookies(null); // a clean start: only this sign-in

        mWeb = new WebView(this);
        mWeb.setBackgroundColor(Color.WHITE);
        WebSettings settings = mWeb.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // Google refuses sign-in in an app's web view ("; wv" in its user agent): the browser's own
        settings.setUserAgentString(settings.getUserAgentString().replace("; wv", ""));
        cookies.setAcceptThirdPartyCookies(mWeb, true);
        mWeb.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress < 100 ? View.VISIBLE : View.INVISIBLE);
            }
        });
        mWeb.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                check();
                // YouTube's page changes without new page loads: look again a few times
                view.postDelayed(PhoneChatSignInActivity.this::check, 1500);
                view.postDelayed(PhoneChatSignInActivity.this::check, 4000);
            }
        });
        FrameLayout frame = new FrameLayout(this);
        frame.addView(mWeb, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        mWeb.loadUrl(LOGIN_URL);
    }

    /** Signed in once YouTube's cookies hold a session (SAPISID, with SID or LOGIN_INFO) */
    private void check() {
        if (mDone || isFinishing()) {
            return;
        }
        String cookie = CookieManager.getInstance().getCookie("https://www.youtube.com");
        if (cookie == null || !cookie.contains("SAPISID=") || !(cookie.contains("SID=") || cookie.contains("LOGIN_INFO="))) {
            return;
        }
        mDone = true;
        write(this, cookie);
        apply(this);
        // The app's own copy is the only one: the browser forgets this sign-in
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        android.widget.Toast.makeText(this, R.string.phone_chat_signed_in, android.widget.Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onDestroy() {
        if (mWeb != null) {
            mWeb.stopLoading();
            mWeb.destroy();
        }
        super.onDestroy();
    }
}
