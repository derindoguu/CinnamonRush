package bakery;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Cinnamon Rush web server. Serves the browser client from the web folder and exposes
 * a tiny JSON API: GET /api/state and POST /api/action. One game per browser session.
 *
 * Usage: java -cp out bakery.Main [port] [webDir]
 */
public final class Main {
    private static final Map<String, Game> GAMES = new ConcurrentHashMap<>();
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8", "svg", "image/svg+xml", "png", "image/png",
            "ico", "image/x-icon");

    interface Body { String get() throws IOException; }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path webRoot = Path.of(args.length > 1 ? args[1] : "web").toAbsolutePath().normalize();

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/api/state", ex -> json(ex, () -> {
            Game g = game(ex);
            g.tick();
            return Json.write(g.state());
        }));
        server.createContext("/api/action", ex -> json(ex, () -> {
            Game g = game(ex);
            Map<String, String> params = parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Game.Result r = g.act(params.getOrDefault("type", ""), params);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", r.ok());
            out.put("msg", r.msg());
            out.put("state", g.state());
            return Json.write(out);
        }));
        server.createContext("/", ex -> serveStatic(ex, webRoot));
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Cinnamon Rush is open at http://localhost:" + port + "  (Ctrl+C to close the bakery)");
    }

    private static Game game(HttpExchange ex) {
        String sid = null;
        for (String header : ex.getRequestHeaders().getOrDefault("Cookie", java.util.List.of())) {
            for (String part : header.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals("sid")) sid = kv[1];
            }
        }
        if (sid == null || !GAMES.containsKey(sid)) {
            sid = UUID.randomUUID().toString();
            ex.getResponseHeaders().add("Set-Cookie", "sid=" + sid + "; Path=/; SameSite=Strict; HttpOnly");
        }
        return GAMES.computeIfAbsent(sid, k -> new Game());
    }

    private static void json(HttpExchange ex, Body body) throws IOException {
        try (ex) {
            String out;
            int code = 200;
            try {
                out = body.get();
            } catch (RuntimeException e) {
                e.printStackTrace();
                code = 500;
                out = "{\"ok\":false,\"msg\":\"Server error\"}";
            }
            send(ex, code, "application/json; charset=utf-8", out.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void serveStatic(HttpExchange ex, Path webRoot) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            Path file = webRoot.resolve(path.substring(1)).normalize();
            if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {
                send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String name = file.getFileName().toString();
            String ext = name.substring(name.lastIndexOf('.') + 1);
            send(ex, 200, TYPES.getOrDefault(ext, "application/octet-stream"), Files.readAllBytes(file));
        }
    }

    private static void send(HttpExchange ex, int code, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> out = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            String[] kv = pair.split("=", 2);
            out.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return out;
    }
}
