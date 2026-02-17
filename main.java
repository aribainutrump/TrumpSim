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
        String header = "HTTP/1.1 200 OK\r\nContent-Type: " + contentType + "; charset=utf-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private byte[] jsonResponse(String text) {
        String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
        return ("{\"reply\":\"" + escaped + "\",\"build\":\"" + BUILD_SALT + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private byte[] healthResponse() {
        String json = "{\"status\":\"ok\",\"instance\":\"" + INSTANCE_HEX + "\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] versionResponse() {
        String json = "{\"name\":\"TrumpSim\",\"build\":\"" + BUILD_SALT + "\",\"api\":\"1.0\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] getAskTrumpPage() {
        String html = getAskTrumpHtml();
        return html.getBytes(StandardCharsets.UTF_8);
    }

    // --- Inline AskTrump HTML ---
    private static String getAskTrumpHtml() {
        return "<!DOCTYPE html>\n" +
            "<html lang=\"en\">\n" +
            "<head>\n" +
            "  <meta charset=\"UTF-8\">\n" +
            "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
            "  <title>AskTrump — What Would He Do?</title>\n" +
            "  <link rel=\"stylesheet\" href=\"/asset/style\">\n" +
            "</head>\n" +
            "<body>\n" +
            "  <div class=\"wrap\">\n" +
            "    <header>\n" +
            "      <h1>AskTrump</h1>\n" +
            "      <p class=\"tagline\">What would Trump do? Get the Xenon take.</p>\n" +
            "    </header>\n" +
            "    <main>\n" +
            "      <div class=\"input-area\">\n" +
            "        <textarea id=\"q\" placeholder=\"Ask anything: business, deals, winning...\"></textarea>\n" +
            "        <button id=\"go\">Ask</button>\n" +
            "      </div>\n" +
            "      <div id=\"reply\" class=\"reply\"></div>\n" +
            "    </main>\n" +
            "    <footer>TrumpSim Xenon build · Not regulatory advice</footer>\n" +
            "  </div>\n" +
            "  <script src=\"/asset/script\"></script>\n" +
            "</body>\n" +
            "</html>";
    }

    private static String getAskTrumpCss() {
        return "*,*::before,*::after{box-sizing:border-box}\n" +
            "body{margin:0;font-family:'Segoe UI',system-ui,sans-serif;background:linear-gradient(135deg,#1a0a2e 0%,#2d1b4e 50%,#1a0a2e 100%);min-height:100vh;color:#e8e0f0}\n" +
            ".wrap{max-width:640px;margin:0 auto;padding:2rem 1rem}\n" +
            "header{text-align:center;margin-bottom:2rem}\n" +
            "h1{font-size:2.2rem;font-weight:700;background:linear-gradient(90deg,#ffd700,#ffaa00);-webkit-background-clip:text;-webkit-text-fill-color:transparent;background-clip:text}\n" +
            ".tagline{color:#b8a0c8;font-size:1rem}\n" +
            ".input-area{display:flex;flex-direction:column;gap:0.75rem;margin-bottom:1.5rem}\n" +
            "#q{width:100%;min-height:100px;padding:1rem;border:2px solid #4a3a5a;border-radius:12px;background:rgba(40,25,60,0.8);color:#e8e0f0;font-size:1rem;resize:vertical}\n" +
            "#q:focus{outline:none;border-color:#8b7aa8}\n" +
            "#go{padding:0.75rem 1.5rem;background:linear-gradient(90deg,#c9a227,#a67c00);border:none;border-radius:8px;color:#1a0a2e;font-weight:700;cursor:pointer;font-size:1rem}\n" +
            "#go:hover{filter:brightness(1.1)}\n" +
            ".reply{padding:1.25rem;border-radius:12px;background:rgba(30,20,50,0.9);border:1px solid #4a3a5a;min-height:60px;white-space:pre-wrap}\n" +
            ".reply.loading{color:#8b7aa8}\n" +
            "footer{text-align:center;margin-top:2rem;color:#6a5a7a;font-size:0.85rem}\n";
    }

    private static String getAskTrumpScript() {
        return "(function(){var q=document.getElementById('q');var go=document.getElementById('go');var reply=document.getElementById('reply');\n" +
            "function ask(){var t=(q.value||'').trim();if(!t){reply.textContent='Type a question first.';return;}\n" +
            "reply.textContent='Thinking...';reply.classList.add('loading');\n" +
            "fetch('/ask?q='+encodeURIComponent(t)).then(function(r){return r.json();}).then(function(d){reply.textContent=d.reply||'';reply.classList.remove('loading');})\n" +
            ".catch(function(){reply.textContent='Network error. Try again.';reply.classList.remove('loading');});}\n" +
            "go.addEventListener('click',ask);q.addEventListener('keydown',function(e){if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();ask();}});})();\n";
    }

    // --- Request context ---
    private static final class RequestContext {
        final String method;
        final String path;
        final String query;
        final String body;

        RequestContext(String method, String path, String query, String body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.body = body;
        }
    }

    /** Immutable config: unique hex identifiers for this build only. */
    private static final class TrumpSimConfig {
        static final String CFG_NODE_A = "0xb3e8f1a2c5d7094e";
        static final String CFG_NODE_B = "0x2f6a9d4c8e1b3075";
        static final String CFG_SALT_HEX = "0x4d7c2e9f1a8b603d";
        static final int MAX_INPUT_LEN = 2000;
        static final int MAX_REPLY_LEN = 1500;
    }

    /** Sanitizes user input for safe handling. */
    private static final class InputSanitizer {
        static String apply(String raw) {
            if (raw == null) return "";
            String s = raw.trim();
            if (s.length() > TrumpSimConfig.MAX_INPUT_LEN) s = s.substring(0, TrumpSimConfig.MAX_INPUT_LEN);
            return s.replace("\u0000", "");
        }
    }

    /** Optional in-memory session; no PII persisted. */
    private static final class SessionHistory {
        private static final int CAP = 50;
        private final Deque<String> recent = new LinkedList<>();

        void push(String query) {
            recent.addFirst(InputSanitizer.apply(query));
            while (recent.size() > CAP) recent.removeLast();
        }

        int size() { return recent.size(); }
    }

    // ========== TrumpSim Engine ==========
    private static final class TrumpSimEngine {
        private static final long SEED = 0x5E7B9D1A3C4F6082L;
        private final Random rng;
        private final XenonResponseBank bank;

        TrumpSimEngine(XenonResponseBank bank) {
            this.rng = new Random(SEED);
            this.bank = bank;
        }

        private static final class KeywordExtractor {
            private static final Set<String> DEAL_TERMS = new HashSet<>(Arrays.asList(
                "deal", "negotiate", "contract", "merge", "acquisition", "leverage", "close", "terms", "agreement"
            ));
            private static final Set<String> MEDIA_TERMS = new HashSet<>(Arrays.asList(
                "media", "press", "twitter", "news", "tweet", "interview", "headline", "coverage"
            ));
            private static final Set<String> WIN_TERMS = new HashSet<>(Arrays.asList(
                "win", "lose", "winner", "loser", "beat", "best", "greatest", "victory", "champion"
            ));
            private static final Set<String> MONEY_TERMS = new HashSet<>(Arrays.asList(
                "money", "profit", "rich", "wealth", "billion", "invest", "revenue", "cash", "asset"
            ));
            private static final Set<String> LEAD_TERMS = new HashSet<>(Arrays.asList(
                "lead", "president", "america", "country", "nation", "govern", "policy"
            ));
            private static final Set<String> OPP_TERMS = new HashSet<>(Arrays.asList(
                "enemy", "opponent", "fight", "attack", "crooked", "rival", "competitor"
            ));
            private static final Set<String> TRUTH_TERMS = new HashSet<>(Arrays.asList(
                "truth", "fake", "lie", "wrong", "right", "believe", "fact", "real"
            ));
            private static final Set<String> PEOPLE_TERMS = new HashSet<>(Arrays.asList(
                "people", "crowd", "support", "love", "huge", "base", "voter", "citizen"
            ));
            private static final Set<String> ADVICE_TERMS = new HashSet<>(Arrays.asList(
                "advice", "should", "what would", "how do", "how to", "recommend", "suggest"
            ));

            static int scoreCategory(String text, Set<String> terms) {
                if (text == null || terms == null) return 0;
                String[] words = text.toLowerCase().split("\\W+");
                int score = 0;
                for (String w : words) {
                    if (terms.contains(w)) score++;
                    for (String t : terms) if (w.contains(t) || t.contains(w)) score++;
                }
                return score;
            }

            static boolean hasDealKeyword(String t) {
                return t != null && (t.contains("deal") || t.contains("negotiat") || t.contains("contract"));
            }
            static boolean hasMediaKeyword(String t) {
                return t != null && (t.contains("media") || t.contains("press") || t.contains("tweet"));
            }
            static boolean hasWinKeyword(String t) {
                return t != null && (t.contains("win") || t.contains("winner") || t.contains("beat"));
            }
            static boolean hasMoneyKeyword(String t) {
                return t != null && (t.contains("money") || t.contains("profit") || t.contains("billion"));
            }
            static boolean hasLeadKeyword(String t) {
                return t != null && (t.contains("lead") || t.contains("president") || t.contains("america"));
            }
            static boolean hasOppKeyword(String t) {
                return t != null && (t.contains("enemy") || t.contains("opponent") || t.contains("fight"));
            }
            static boolean hasTruthKeyword(String t) {
                return t != null && (t.contains("truth") || t.contains("fake") || t.contains("lie"));
            }
            static boolean hasPeopleKeyword(String t) {
                return t != null && (t.contains("people") || t.contains("crowd") || t.contains("support"));
            }
            static boolean hasAdviceKeyword(String t) {
                return t != null && (t.contains("advice") || t.contains("should") || t.contains("how to"));
            }
        }

        String respond(String input) {
