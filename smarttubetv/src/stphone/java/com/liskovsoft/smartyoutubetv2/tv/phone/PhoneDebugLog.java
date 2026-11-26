package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayDeque;

/**
 * Settings › Share debug log: the app's own recent log (the player, the mini player, the floating window), sent
 * through the share sheet. An app reads only its own log lines; no permission needed.
 */
final class PhoneDebugLog {
    private static final int MAX_CHARS = 24_000; // the newest part: short enough to paste into a chat in one go

    private PhoneDebugLog() {
    }

    private static final String[] TAGS = {
            "PhonePip:V", "PhoneCast:V", "PhoneLiveChat:V", "LiveChatWriter:V", "CastV2Session:V", "CastV2Channel:V", "CastProxyServer:V", "MpdRewriter:V", "PhonePlayback:V", "PhoneMiniPlayer:V", "PhoneShorts:V", "PhoneLyrics:V", "PhoneNewVideos:V", "PhoneUpdater:V", "ViewManager:V",
            "PlaybackActivity:V", "PlaybackFragment:V", "PlaybackPresenter:V", "ExoPlayerImpl:V",
            "ExoPlayerImplInternal:W", "MediaCodecVideoRenderer:V", "AndroidRuntime:E", "*:S"};
    private static final long FILE_LIMIT = 300_000;
    private static java.io.File sFile;
    private static java.io.File sOlder;

    /**
     * From app start, the app's own log lines are also written to a file (two files of 300 KB at most, the older one
     * replaced): what happened before the app was closed or killed is still there the next time the log is shared.
     */
    static void record(android.content.Context context) {
        if (sFile != null) {
            return;
        }
        sFile = new java.io.File(context.getFilesDir(), "debug_log.txt");
        sOlder = new java.io.File(context.getFilesDir(), "debug_log.1.txt");
        Thread thread = new Thread(() -> {
            Process process = null;
            try {
                String[] command = new String[TAGS.length + 5];
                System.arraycopy(new String[] {"logcat", "-v", "time", "-T", "1"}, 0, command, 0, 5);
                System.arraycopy(TAGS, 0, command, 5, TAGS.length);
                process = Runtime.getRuntime().exec(command);
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                java.io.FileWriter writer = new java.io.FileWriter(sFile, true);
                writer.write("---- app started " + new java.util.Date() + " ----\n");
                writer.flush();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!wanted(line)) {
                        continue;
                    }
                    writer.write(line);
                    writer.write('\n');
                    writer.flush();
                    if (sFile.length() > FILE_LIMIT) {
                        writer.close();
                        sOlder.delete();
                        sFile.renameTo(sOlder);
                        writer = new java.io.FileWriter(sFile, true);
                    }
                }
                writer.close();
            } catch (Exception e) {
                android.util.Log.d("PhonePip", "debug log file: " + e);
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
        }, "debug-log-file");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /** The saved lines, oldest first; empty if nothing was saved */
    private static void readSaved(ArrayDeque<String> lines) {
        for (java.io.File file : new java.io.File[] {sOlder, sFile}) {
            if (file == null || !file.exists()) {
                continue;
            }
            try (BufferedReader reader = new BufferedReader(new java.io.FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.addLast(line);
                }
            } catch (Exception ignored) {
                // what could be read is enough
            }
        }
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
        readSaved(lines);
        if (lines.size() > 20) {
            // The saved file: it goes back before the app's last restart
            for (String line : lines) {
                size += line.length() + 1;
            }
            while (size > MAX_CHARS && !lines.isEmpty()) {
                size -= lines.removeFirst().length() + 1; // keep the newest
            }
            StringBuilder saved = new StringBuilder(header);
            for (String line : lines) {
                saved.append(line).append('\n');
            }
            return saved.toString();
        }
        lines.clear();
        size = 0;
        Process process = null;
        try {
            // Only the app's own lines (phones like ColorOS write thousands of system lines a second): the whole buffer,
            // so earlier runs of the app (before a restart) are in it too
            String[] command = new String[TAGS.length + 4];
            System.arraycopy(new String[] {"logcat", "-d", "-v", "time"}, 0, command, 0, 4);
            System.arraycopy(TAGS, 0, command, 4, TAGS.length);
            process = Runtime.getRuntime().exec(command);
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
        // Leave out the lines that repeat many times and say little (they pushed the useful ones out of the log)
        return !line.startsWith("--------- beginning of") && !line.contains("Continue row")
                && !line.contains("player window off") && !line.contains("player window armed");
    }
}
