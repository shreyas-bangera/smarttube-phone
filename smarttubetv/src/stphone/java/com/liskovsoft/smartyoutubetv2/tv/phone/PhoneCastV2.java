package com.liskovsoft.smartyoutubetv2.tv.phone;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Google Cast TVs (Google TV, Android TV, Chromecast): asks the TV to open YouTube, then asks that YouTube for its
 * screen id (YouTube's lounge then takes over, as with any TV). This is the small part of Google Cast it takes,
 * spoken directly (a TLS socket to the TV, length-prefixed protobuf messages with JSON in them), so no Google Play
 * services are needed.
 */
final class PhoneCastV2 {
    private static final String TAG = "PhoneCast";
    private static final String YOUTUBE_APP = "233637DE";
    private static final String NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
    private static final String NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";
    private static final String NS_RECEIVER = "urn:x-cast:com.google.cast.receiver";
    private static final String NS_YOUTUBE = "urn:x-cast:com.google.youtube.mdx";
    private static final String SENDER = "sender-streamtube";

    private PhoneCastV2() {
    }

    /** Opens YouTube on the Cast TV and returns its screen id (YouTube's id for the TV, for the lounge) */
    static String openYouTube(SocketFactory wifi, String host, int port) throws Exception {
        SSLContext tls = SSLContext.getInstance("TLS");
        // The TV's certificate is its own (not from an authority): accepted, as every Cast sender does
        tls.init(null, new TrustManager[] {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        Socket plain = wifi != null ? wifi.createSocket(host, port) : new Socket(host, port);
        try (SSLSocket socket = (SSLSocket) tls.getSocketFactory().createSocket(plain, host, port, true)) {
            socket.setSoTimeout(15_000);
            socket.startHandshake();
            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(out, "receiver-0", NS_CONNECTION, "{\"type\":\"CONNECT\"}");
            send(out, "receiver-0", NS_RECEIVER, "{\"type\":\"LAUNCH\",\"appId\":\"" + YOUTUBE_APP + "\",\"requestId\":1}");

            String transport = null;
            long until = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < until) {
                Message message = read(in);
                if (NS_HEARTBEAT.equals(message.namespace) && message.payload.contains("PING")) {
                    send(out, message.source, NS_HEARTBEAT, "{\"type\":\"PONG\"}");
                    continue;
                }
                if (transport == null && NS_RECEIVER.equals(message.namespace)) {
                    transport = youTubeTransport(message.payload);
                    if (transport != null) {
                        // YouTube is open on the TV: talk to it, and ask for its screen id
                        send(out, transport, NS_CONNECTION, "{\"type\":\"CONNECT\"}");
                        send(out, transport, NS_YOUTUBE, "{\"type\":\"getMdxSessionStatus\"}");
                    }
                    continue;
                }
                if (NS_YOUTUBE.equals(message.namespace)) {
                    JSONObject status = new JSONObject(message.payload);
                    JSONObject data = status.optJSONObject("data");
                    String screenId = data != null ? data.optString("screenId", null) : null;
                    if (screenId != null && !screenId.isEmpty()) {
                        Log.d(TAG, "cast device: YouTube open, screen " + screenId);
                        return screenId;
                    }
                }
            }
            throw new IOException("YouTube didn't open on the TV");
        }
    }

    /** The YouTube app's transport id in a receiver status, once it's running */
    private static String youTubeTransport(String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            JSONObject status = json.optJSONObject("status");
            JSONArray apps = status != null ? status.optJSONArray("applications") : null;
            if (apps == null) {
                return null;
            }
            for (int i = 0; i < apps.length(); i++) {
                JSONObject app = apps.getJSONObject(i);
                if (YOUTUBE_APP.equals(app.optString("appId"))) {
                    return app.optString("transportId", null);
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    // region The messages: a 4-byte length, then a CastMessage protobuf

    private static final class Message {
        String source;
        String namespace;
        String payload = "";
    }

    private static void send(OutputStream out, String destination, String namespace, String payload) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        varintField(body, 1, 0); // protocol_version CASTV2_1_0
        stringField(body, 2, SENDER);
        stringField(body, 3, destination);
        stringField(body, 4, namespace);
        varintField(body, 5, 0); // payload_type STRING
        stringField(body, 6, payload);
        byte[] bytes = body.toByteArray();
        out.write(new byte[] {(byte) (bytes.length >>> 24), (byte) (bytes.length >>> 16), (byte) (bytes.length >>> 8), (byte) bytes.length});
        out.write(bytes);
        out.flush();
    }

    private static Message read(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length <= 0 || length > 1 << 20) {
            throw new IOException("bad message");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        Message message = new Message();
        int[] at = {0};
        while (at[0] < bytes.length) {
            long key = varint(bytes, at);
            int field = (int) (key >>> 3);
            int type = (int) (key & 7);
            if (type == 0) {
                varint(bytes, at);
            } else if (type == 2) {
                int size = (int) varint(bytes, at);
                String value = new String(bytes, at[0], size, StandardCharsets.UTF_8);
                at[0] += size;
                if (field == 2) {
                    message.source = value;
                } else if (field == 4) {
                    message.namespace = value;
                } else if (field == 6) {
                    message.payload = value;
                }
            } else {
                break; // nothing else is sent
            }
        }
        return message;
    }

    private static long varint(byte[] bytes, int[] at) {
        long value = 0;
        int shift = 0;
        while (at[0] < bytes.length) {
            int b = bytes[at[0]++] & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        return value;
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static void varintField(ByteArrayOutputStream out, int field, long value) {
        writeVarint(out, (long) field << 3);
        writeVarint(out, value);
    }

    private static void stringField(ByteArrayOutputStream out, int field, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarint(out, ((long) field << 3) | 2);
        writeVarint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    // endregion
}
