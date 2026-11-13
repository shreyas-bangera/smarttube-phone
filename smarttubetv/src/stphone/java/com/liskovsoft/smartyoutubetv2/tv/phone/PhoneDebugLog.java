package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayDeque;

/**
 * Settings › Share debug log: the app's own recent log (the player, the mini player, the floating window), sent
 * through the share sheet. An app reads only its own log lines; no permission needed.
 */
final class PhoneDebugLog {
    private static final int MAX_CHARS = 90_000; // well within what a share intent carries

    private PhoneDebugLog() {
    }

    static void share(Activity activity) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String log = collect();
            main.post(() -> {
                if (activity.isFinishing()) {
                    return;
                }
                Intent send = new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "StreamTube debug log")
                        .putExtra(Intent.EXTRA_TEXT, log);
                try {
                    activity.startActivity(Intent.createChooser(send, activity.getString(R.string.phone_debug_log)));
                } catch (RuntimeException e) {
                    Toast.makeText(activity, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
                }
            });
        }, "debug-log").start();
    }

    private static String collect() {
        StringBuilder header = new StringBuilder()
                .append("StreamTube ").append(BuildConfig.VERSION_NAME)
                .append(" | ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" | Android ").append(Build.VERSION.RELEASE).append(" (").append(Build.VERSION.SDK_INT).append(")\n\n");
        ArrayDeque<String> lines = new ArrayDeque<>();
        int size = 0;
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[] {"logcat", "-d", "-v", "time", "-t", "6000"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!wanted(line)) {
                    continue;
                }
                lines.addLast(line);
                size += line.length() + 1;
                while (size > MAX_CHARS && !lines.isEmpty()) {
                    size -= lines.removeFirst().length() + 1; // keep the newest
                }
            }
        } catch (Exception e) {
            header.append("log not readable: ").append(e).append('\n');
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        StringBuilder out = new StringBuilder(header);
        for (String line : lines) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static boolean wanted(String line) {
        return line.contains("Phone") || line.contains("AndroidRuntime") || line.contains("ExoPlayer")
                || line.contains("PlaybackActivity") || line.contains("PlaybackFragment") || line.contains("MediaCodec")
                || line.contains("ViewManager") || line.contains("Exception");
    }
}
