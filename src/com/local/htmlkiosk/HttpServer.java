package com.local.htmlkiosk;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简本地 HTTP 服务：
 *  - GET  /                 上传页
 *  - GET  /api/list         书库文件列表(JSON)
 *  - POST /api/delete?path= 删除文件
 *  - POST /upload           上传(X-Filename: percent-encoded 相对路径)
 *  - GET  /view/<path>      原样返回文件，支持 Range(视频拖动)
 *
 * 只依赖标准库，方便在电脑上单测。
 */
public class HttpServer {

    public interface Listener {
        void onFilesChanged();
    }

    private static final long MAX_UPLOAD = 512L * 1024 * 1024;
    private static final int MAX_SCAN = 5000;

    private static final Map<String, String> MIME = new HashMap<String, String>();
    static {
        MIME.put("html", "text/html; charset=utf-8");
        MIME.put("htm", "text/html; charset=utf-8");
        MIME.put("css", "text/css; charset=utf-8");
        MIME.put("js", "application/javascript; charset=utf-8");
        MIME.put("mjs", "application/javascript; charset=utf-8");
        MIME.put("json", "application/json; charset=utf-8");
        MIME.put("xml", "application/xml; charset=utf-8");
        MIME.put("txt", "text/plain; charset=utf-8");
        MIME.put("csv", "text/csv; charset=utf-8");
        MIME.put("svg", "image/svg+xml");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("jpeg", "image/jpeg");
        MIME.put("gif", "image/gif");
        MIME.put("webp", "image/webp");
        MIME.put("avif", "image/avif");
        MIME.put("ico", "image/x-icon");
        MIME.put("mp4", "video/mp4");
        MIME.put("m4v", "video/mp4");
        MIME.put("webm", "video/webm");
        MIME.put("mov", "video/quicktime");
        MIME.put("mp3", "audio/mpeg");
        MIME.put("m4a", "audio/mp4");
        MIME.put("wav", "audio/wav");
        MIME.put("ogg", "audio/ogg");
        MIME.put("woff", "font/woff");
        MIME.put("woff2", "font/woff2");
        MIME.put("ttf", "font/ttf");
        MIME.put("otf", "font/otf");
        MIME.put("wasm", "application/wasm");
        MIME.put("pdf", "application/pdf");
        MIME.put("zip", "application/zip");
    }

    private final File root;
    private final int port;
    private final String uploadHtml;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private volatile Listener listener;
    /** 最近打开的 HTML 所在目录，用来兜底解析生成页里的绝对路径 /assets/x.png */
    private volatile File currentBase;

    public HttpServer(File root, int port, String uploadHtml) {
        this.root = root;
        this.port = port;
        this.uploadHtml = uploadHtml;
        if (!root.exists()) {
            //noinspection ResultOfMethodCallIgnored
            root.mkdirs();
        }
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public int getPort() {
        return port;
    }

    public File getRoot() {
        return root;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port), 64);
        serverSocket = ss;
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "kiosk-http");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        running = false;
        ServerSocket ss = serverSocket;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(socket);
                    }
                });
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(300000);
            InputStream in = new BufferedInputStream(socket.getInputStream(), 65536);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 65536);
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.trim().isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }
            String method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];

            Map<String, String> headers = walkHeaders(in);

            String rawPath = target;
            String rawQuery = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                rawPath = target.substring(0, q);
                rawQuery = target.substring(q + 1);
            }
            String path = percentDecode(rawPath);
            Map<String, String> query = parseQuery(rawQuery);

            route(method, path, query, headers, in, out);
            out.flush();
        } catch (Exception e) {
            // 单个连接失败不影响服务
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void route(String method, String path, Map<String, String> query,
                       Map<String, String> headers, InputStream in, OutputStream out) throws IOException {
        boolean head = "HEAD".equals(method);

        if (head || "GET".equals(method)) {
            if (path.equals("/") || path.equals("/upload") || path.equals("/index.html")) {
                sendText(out, 200, "OK", "text/html; charset=utf-8", uploadHtml, head);
                return;
            }
            if (path.equals("/api/list")) {
                sendText(out, 200, "OK", "application/json; charset=utf-8", listJson(), head);
                return;
            }
            if (path.equals("/favicon.ico")) {
                sendEmpty(out, 204, "No Content");
                return;
            }
            if (path.startsWith("/view/")) {
                serveFile(path.substring("/view/".length()), headers, out, head, true);
                return;
            }
            serveFile(path, headers, out, head, false);
            return;
        }

        if ("POST".equals(method)) {
            if (path.equals("/upload")) {
                handleUpload(headers, query, in, out);
                return;
            }
            if (path.equals("/api/delete")) {
                handleDelete(query, out);
                return;
            }
        }

        sendText(out, 404, "Not Found", "application/json; charset=utf-8",
                "{\"ok\":false,\"error\":\"not found\"}", false);
    }

    // ---------------------------------------------------------------- files

    private void serveFile(String rawRel, Map<String, String> headers, OutputStream out,
                           boolean head, boolean allowBaseFallback) throws IOException {
        String rel = rawRel;
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        File file = null;
        try {
            if (!rel.isEmpty()) file = safeResolve(rel);
        } catch (Exception ignored) {
            file = null;
        }
        if (file == null || !file.isFile()) {
            File base = currentBase;
            if (base != null && allowBaseFallback && !rel.isEmpty()) {
                try {
                    File alt = new File(base, rel);
                    String basePath = base.getCanonicalPath();
                    String altPath = alt.getCanonicalPath();
                    if ((altPath.equals(basePath) || altPath.startsWith(basePath + File.separator)) && alt.isFile()) {
                        file = alt;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (file == null || !file.isFile()) {
            sendText(out, 404, "Not Found", "text/plain; charset=utf-8", "404 not found", head);
            return;
        }

        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html") || name.endsWith(".htm")) {
            currentBase = file.getParentFile();
        }

        long total = file.length();
        long start = 0;
        long end = total - 1;
        boolean partial = false;

        String range = headers.get("range");
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring(6).trim();
            int comma = spec.indexOf(',');
            if (comma >= 0) spec = spec.substring(0, comma);
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                String a = spec.substring(0, dash).trim();
                String b = spec.substring(dash + 1).trim();
                try {
                    if (a.isEmpty() && !b.isEmpty()) {
                        long suffix = Long.parseLong(b);
                        start = Math.max(0, total - suffix);
                        end = total - 1;
                        partial = suffix > 0;
                    } else if (!a.isEmpty()) {
                        start = Long.parseLong(a);
                        end = b.isEmpty() ? total - 1 : Math.min(Long.parseLong(b), total - 1);
                        partial = true;
                    }
                } catch (NumberFormatException ignored) {
                    partial = false;
                }
            }
        }

        if (total == 0) {
            start = 0;
            end = -1;
            partial = false;
        } else if (partial && (start > end || start >= total)) {
            sendText(out, 416, "Range Not Satisfiable", "text/plain; charset=utf-8",
                    "range not satisfiable", head);
            return;
        }

        long length = total == 0 ? 0 : end - start + 1;
        StringBuilder sb = new StringBuilder();
        sb.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        sb.append("Content-Type: ").append(mimeFor(name)).append("\r\n");
        sb.append("Content-Length: ").append(length).append("\r\n");
        sb.append("Accept-Ranges: bytes\r\n");
        if (partial) {
            sb.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n");
        }
        sb.append("Cache-Control: no-store\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));

        if (head || length == 0) {
            return;
        }

        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            raf.seek(start);
            byte[] buf = new byte[65536];
            long remain = length;
            while (remain > 0) {
                int r = raf.read(buf, 0, (int) Math.min(buf.length, remain));
                if (r < 0) break;
                out.write(buf, 0, r);
                remain -= r;
            }
        } finally {
            try {
                raf.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void handleUpload(Map<String, String> headers, Map<String, String> query,
                              InputStream in, OutputStream out) throws IOException {
        String name = headers.get("x-filename");
        if (name == null || name.trim().isEmpty()) {
            name = query.get("name");
        }
        if (name == null || name.trim().isEmpty()) {
            sendText(out, 400, "Bad Request", "application/json; charset=utf-8",
                    "{\"ok\":false,\"error\":\"missing X-Filename\"}", false);
            return;
        }

        String rel = percentDecode(name).replace('\\', '/');
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }

        File dest;
        try {
            dest = safeResolve(rel);
        } catch (Exception e) {
            sendText(out, 400, "Bad Request", "application/json; charset=utf-8",
                    "{\"ok\":false,\"error\":\"invalid path\"}", false);
            return;
        }
        if (dest.isDirectory()) {
            sendText(out, 400, "Bad Request", "application/json; charset=utf-8",
                    "{\"ok\":false,\"error\":\"path is a directory\"}", false);
            return;
        }

        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            sendText(out, 500, "Server Error", "application/json; charset=utf-8",
                    "{\"ok\":false,\"error\":\"mkdir failed\"}", false);
            return;
        }

        long declared = parseLong(headers.get("content-length"), -1);
        if (declared > MAX_UPLOAD) {
            sendText(out, 413, "Payload Too Large", "application/json; charset=utf-8",
                    "{\"ok\":false,\"error\":\"file too large\"}", false);
            return;
        }

        File tmp = new File(parent, ".upload-" + System.nanoTime() + ".tmp");
        long written = 0;
        try {
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[65536];
                if (declared >= 0) {
                    long remain = declared;
                    while (remain > 0) {
                        int r = in.read(buf, 0, (int) Math.min(buf.length, remain));
                        if (r < 0) break;
                        fos.write(buf, 0, r);
                        remain -= r;
                        written += r;
                    }
                } else {
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        fos.write(buf, 0, r);
                        written += r;
                        if (written > MAX_UPLOAD) break;
                    }
                }
            } finally {
                fos.flush();
                fos.close();
            }

            if (declared >= 0 && written != declared) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                sendText(out, 400, "Bad Request", "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"incomplete body\"}", false);
                return;
            }

            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }

        Listener l = listener;
        if (l != null) l.onFilesChanged();

        sendText(out, 200, "OK", "application/json; charset=utf-8",
                "{\"ok\":true,\"path\":" + jsonQuote(rel) + ",\"size\":" + written + "}", false);
    }

    private void handleDelete(Map<String, String> query, OutputStream out) throws IOException {
        String p = query.get("path");
        if (p == null || p.trim().isEmpty()) {
            sendText(out, 400, "Bad Request", "application/json; charset=utf-8",
                    "{\"ok\":false,\"error\":\"missing path\"}", false);
            return;
        }
        File f;
        try {
            f = safeResolve(p);
        } catch (Exception e) {
            sendText(out, 400, "Bad Request", "application/json; charset=utf-8",
                    "{\"ok\":false,\"error\":\"invalid path\"}", false);
            return;
        }
        if (!f.isFile()) {
            sendText(out, 404, "Not Found", "application/json; charset=utf-8",
                    "{\"ok\":false,\"error\":\"not found\"}", false);
            return;
        }
        boolean ok = f.delete();
        if (ok) {
            Listener l = listener;
            if (l != null) l.onFilesChanged();
        }
        sendText(out, ok ? 200 : 500, ok ? "OK" : "Server Error",
                "application/json; charset=utf-8",
                "{\"ok\":" + ok + "}", false);
    }

    private String listJson() {
        List<File> files = new ArrayList<File>();
        collect(root, files, 0);
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"root\":").append(jsonQuote(root.getAbsolutePath()));
        sb.append(",\"files\":[");
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"path\":").append(jsonQuote(relPath(f)));
            sb.append(",\"size\":").append(f.length());
            sb.append(",\"mtime\":").append(f.lastModified()).append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private void collect(File dir, List<File> out, int depth) {
        if (depth > 8 || out.size() >= MAX_SCAN) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (out.size() >= MAX_SCAN) return;
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                collect(f, out, depth + 1);
            } else if (isHtml(n)) {
                out.add(f);
            }
        }
    }

    private String relPath(File f) {
        String base = root.getAbsolutePath();
        String abs = f.getAbsolutePath();
        if (abs.startsWith(base + File.separator)) {
            return abs.substring(base.length() + 1).replace(File.separatorChar, '/');
        }
        return f.getName();
    }

    // ---------------------------------------------------------------- utils

    private File safeResolve(String rel) throws IOException {
        String cleaned = rel.replace('\\', '/');
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        if (cleaned.isEmpty()) throw new IOException("empty path");
        for (String seg : cleaned.split("/")) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..") || seg.startsWith(".")) {
                throw new IOException("invalid segment");
            }
        }
        File f = new File(root, cleaned);
        String rootPath = root.getCanonicalPath();
        String filePath = f.getCanonicalPath();
        if (!filePath.equals(rootPath) && !filePath.startsWith(rootPath + File.separator)) {
            throw new IOException("outside root");
        }
        return f;
    }

    private static boolean isHtml(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".html") || n.endsWith(".htm");
    }

    private static String mimeFor(String lowerName) {
        int dot = lowerName.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = lowerName.substring(dot + 1);
        String m = MIME.get(ext);
        return m != null ? m : "application/octet-stream";
    }

    private static Map<String, String> walkHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new HashMap<String, String>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int idx = line.indexOf(':');
            if (idx > 0) {
                String k = line.substring(0, idx).trim().toLowerCase(Locale.ROOT);
                String v = line.substring(idx + 1).trim();
                headers.put(k, v);
            }
        }
        return headers;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') break;
            if (c == '\r') continue;
            bos.write(c);
            if (bos.size() > 65536) break;
        }
        if (!any) return null;
        return new String(bos.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new HashMap<String, String>();
        if (raw == null || raw.isEmpty()) return map;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            map.put(percentDecode(k), percentDecode(v));
        }
        return map;
    }

    private static String percentDecode(String s) {
        if (s == null || s.indexOf('%') < 0) return s == null ? "" : s;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    bos.write((hi << 4) | lo);
                    i += 3;
                    continue;
                }
            }
            int cp = s.codePointAt(i);
            byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            bos.write(bytes, 0, bytes.length);
            i += Character.charCount(cp);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String jsonQuote(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static long parseLong(String s, long def) {
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void sendText(OutputStream out, int code, String reason, String contentType,
                                 String body, boolean head) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        sb.append("Content-Length: ").append(bytes.length).append("\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (!head) out.write(bytes);
    }

    private static void sendEmpty(OutputStream out, int code, String reason) throws IOException {
        String head = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
    }
}