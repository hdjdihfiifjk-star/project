package com.enteghalyar.local;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import fi.iki.elonen.NanoHTTPD;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private LocalServer server;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        TextView urlView = findViewById(R.id.url);
        TextView status = findViewById(R.id.status);
        ImageView qr = findViewById(R.id.qr);

        String ip = getLocalIpv4();
        String url = "http://" + ip + ":8080/join";

        try {
            qr.setImageBitmap(makeQr(url, 700));
            urlView.setText(url);

            // FIX: read web files from Android assets.
            server = new LocalServer(8080, getExternalFilesDir(null), getAssets());
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);

            status.setText("● آماده است — گوشی دوم QR را اسکن کند");
        } catch (Exception e) {
            status.setText("خطا در اجرای سرویس محلی: " + e.getClass().getSimpleName());
        }
    }

    private Bitmap makeQr(String text, int size) throws Exception {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.MARGIN, 1);

        BitMatrix matrix = new MultiFormatWriter().encode(
                text, BarcodeFormat.QR_CODE, size, size, hints
        );

        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }

        return bitmap;
    }

    private String getLocalIpv4() {
        try {
            for (NetworkInterface ni :
                    Collections.list(NetworkInterface.getNetworkInterfaces())) {

                for (InetAddress address :
                        Collections.list(ni.getInetAddresses())) {

                    String ip = address.getHostAddress();

                    if (!address.isLoopbackAddress()
                            && ip != null
                            && ip.indexOf(':') < 0) {
                        return ip;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return "192.168.43.1";
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }

    static class LocalServer extends NanoHTTPD {
        private final File root;
        private final android.content.res.AssetManager assets;

        LocalServer(int port, File root,
                    android.content.res.AssetManager assets) {
            super(port);
            this.root = root;
            this.assets = assets;

            if (root != null && !root.exists()) {
                root.mkdirs();
            }
        }

        @Override
        public Response serve(IHTTPSession session) {
            try {
                String path = session.getUri();

                if ("/join".equals(path) || "/".equals(path)) {
                    return html();
                }

                if ("/api/upload".equals(path)
                        && Method.POST.equals(session.getMethod())) {

                    Map<String, String> headers = session.getHeaders();
                    String rawName = headers.get("x-filename");

                    String name = (rawName == null || rawName.trim().isEmpty())
                            ? "file-" + System.currentTimeMillis()
                            : URLDecoder.decode(rawName, "UTF-8");

                    name = new File(name).getName()
                            .replaceAll("[\\/:*?"<>|]", "_");

                    File out = new File(
                            root,
                            System.currentTimeMillis() + "_" + name
                    );

                    InputStream input = session.getInputStream();
                    String contentLength = headers.get("content-length");

                    long expected = 0;
                    try {
                        if (contentLength != null) {
                            expected = Long.parseLong(contentLength);
                        }
                    } catch (Exception ignored) {
                    }

                    try (FileOutputStream output = new FileOutputStream(out)) {
                        byte[] buffer = new byte[64 * 1024];
                        int count;
                        long total = 0;

                        while ((count = input.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                            total += count;

                            if (expected > 0 && total >= expected) break;
                        }
                    }

                    return newFixedLengthResponse(
                            Response.Status.OK,
                            "application/json; charset=utf-8",
                            "{"ok":true,"name":""
                                    + escapeJson(name)
                                    + "","size":"
                                    + out.length()
                                    + "}"
                    );
                }

                return newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        "text/plain; charset=utf-8",
                        "Not Found"
                );

            } catch (Exception e) {
                return newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR,
                        "text/plain; charset=utf-8",
                        "Server error"
                );
            }
        }

        private Response html() {
            try {
                // IMPORTANT:
                // app/src/main/assets/web/index.html
                InputStream input = assets.open("web/index.html");

                byte[] buffer = new byte[8192];
                StringBuilder html = new StringBuilder();
                int count;

                while ((count = input.read(buffer)) != -1) {
                    html.append(new String(buffer, 0, count, "UTF-8"));
                }

                input.close();

                return newFixedLengthResponse(
                        Response.Status.OK,
                        "text/html; charset=utf-8",
                        html.toString()
                );

            } catch (Exception e) {
                return newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR,
                        "text/plain; charset=utf-8",
                        "web missing"
                );
            }
        }

        private static String escapeJson(String text) {
            if (text == null) return "";

            return text
                    .replace("\\", "\\\\")
                    .replace(""", "\"");
        }
    }
}
