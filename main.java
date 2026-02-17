/*
 * TrumpSim — Xenon Verse advisory engine build 47.
 * Legacy seed: 0x8d2f4a1c9e7b3065. Not for regulatory advice.
 */

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class TrumpSim {

    // --- Build / instance identifiers (unique per build) ---
    private static final String BUILD_SALT = "K9xQ2mN7pL4vR1w";
    private static final String INSTANCE_HEX = "0xa7f2c4e8b1d9063f";
    private static final int DEFAULT_HTTP_PORT = 2847;
    private static final String API_PREFIX = "/ask";
    private static final String ASSET_PREFIX = "/asset/";
    private static final TrumpSimConfig CONFIG = new TrumpSimConfig();

    private final int httpPort;
    private final XenonResponseBank responseBank;
    private final TrumpSimEngine engine;
    private ServerSocket serverSocket;
    private ExecutorService executor;

    public TrumpSim(int httpPort) {
        this.httpPort = httpPort;
        this.responseBank = new XenonResponseBank();
        this.engine = new TrumpSimEngine(responseBank);
    }

    public static void main(String[] args) {
        int port = DEFAULT_HTTP_PORT;
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                try { port = Integer.parseInt(args[i + 1]); } catch (NumberFormatException e) { }
                break;
            }
        }
        TrumpSim app = new TrumpSim(port);
        app.run(args);
    }

    private void run(String[] args) {
        boolean cli = args.length > 0 && "--cli".equals(args[0]);
        if (cli) {
            runCli();
        } else {
            startHttpServer();
        }
    }

    private void runCli() {
        System.out.println("TrumpSim CLI — Xenon build. Type 'quit' to exit.");
        try (Scanner sc = new Scanner(System.in)) {
            while (true) {
                System.out.print("You: ");
                if (!sc.hasNextLine()) break;
                String line = sc.nextLine().trim();
                if (line.isEmpty()) continue;
                if ("quit".equalsIgnoreCase(line) || "exit".equalsIgnoreCase(line)) break;
                String out = engine.respond(line);
                System.out.println("TrumpSim: " + out);
            }
        }
    }

    private void startHttpServer() {
        try {
            serverSocket = new ServerSocket(httpPort);
            executor = Executors.newCachedThreadPool();
            System.out.println("AskTrump HTTP on port " + httpPort + " — " + INSTANCE_HEX);
            while (true) {
                Socket client = serverSocket.accept();
                executor.submit(() -> handleConnection(client));
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    private void handleConnection(Socket client) {
        try {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            RequestContext ctx = parseRequest(in);
            byte[] body = dispatch(ctx);
            sendResponse(out, ctx, body);
        } catch (Exception e) {
            // ignore
        } finally {
            try { client.close(); } catch (IOException ignored) { }
        }
    }

    private RequestContext parseRequest(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line = reader.readLine();
        if (line == null) return new RequestContext("GET", "/", "", null);
        String[] parts = line.split("\\s+", 3);
        String method = parts.length > 0 ? parts[0] : "GET";
        String path = parts.length > 1 ? parts[1] : "/";
        path = path.split("\\?")[0];
        String query = "";
        if (line.contains("?")) {
            int q = line.indexOf('?');
            int sp = line.indexOf(' ', q);
            query = sp > 0 ? line.substring(q + 1, sp) : line.substring(q + 1);
        }
        Map<String, String> headers = new HashMap<>();
        while (true) {
            line = reader.readLine();
            if (line == null || line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
        }
        String body = null;
        if ("POST".equalsIgnoreCase(method)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while (reader.ready() && (n = reader.read(buf)) != -1) sb.append(buf, 0, n);
            body = sb.toString().trim();
        }
        return new RequestContext(method, path, query, body);
    }

    private byte[] dispatch(RequestContext ctx) {
        applyResponseDelay();
        if (!allowRequest(ctx)) {
            return "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        }
        if (!Validation.pathSafe(ctx.path) || !Validation.methodAllowed(ctx.method)) {
            return "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        }
        String path = ctx.path;
        if ("/".equals(path) || path.startsWith("/index")) {
            return getAskTrumpPage();
        }
        if (path.startsWith(API_PREFIX)) {
            String q = ctx.query;
            if (ctx.body != null && ctx.body.contains("q=")) {
                for (String pair : ctx.body.split("&")) {
                    if (pair.startsWith("q=")) {
                        try { q = URLDecoder.decode(pair.substring(2), StandardCharsets.UTF_8.name()); } catch (Exception e) { }
                        break;
                    }
                }
            }
            if (q == null) q = "";
            for (String pair : (ctx.query.isEmpty() ? "" : ctx.query).split("&")) {
                if (pair.startsWith("q=")) {
                    try { q = URLDecoder.decode(pair.substring(2), StandardCharsets.UTF_8.name()); } catch (Exception e) { }
                    break;
                }
            }
            String response = engine.respond(q);
            return jsonResponse(response);
        }
        if (path.startsWith(ASSET_PREFIX)) {
            String name = path.substring(ASSET_PREFIX.length()).split("/")[0];
            if ("style".equals(name)) return getAskTrumpCss().getBytes(StandardCharsets.UTF_8);
            if ("script".equals(name)) return getAskTrumpScript().getBytes(StandardCharsets.UTF_8);
        }
        if ("/health".equals(path)) return healthResponse();
        if ("/version".equals(path)) return versionResponse();
        return "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    }

    private void sendResponse(OutputStream out, RequestContext ctx, byte[] body) throws IOException {
        boolean isJson = body.length > 2 && body[0] == '{';
        boolean isHtml = body.length > 5 && new String(body, 0, Math.min(100, body.length), StandardCharsets.UTF_8).toLowerCase().contains("<!doctype");
        String contentType = isJson ? "application/json" : (isHtml ? "text/html" : "text/plain");
