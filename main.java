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
            input = InputSanitizer.apply(input);
            String normalized = input.toLowerCase();
            if (normalized.isEmpty()) return bank.pick(bank.genericOpeners);
            if (normalized.length() <= 3) return bank.pickOneLiner();
            CategoryHint hint = categorize(normalized);
            List<String> pool = bank.forCategory(hint);
            String reply = bank.pick(pool);
            if (reply.length() > TrumpSimConfig.MAX_REPLY_LEN) reply = reply.substring(0, TrumpSimConfig.MAX_REPLY_LEN);
            return reply;
        }

        private CategoryHint categorize(String text) {
            int deal = KeywordExtractor.scoreCategory(text, KeywordExtractor.DEAL_TERMS);
            int media = KeywordExtractor.scoreCategory(text, KeywordExtractor.MEDIA_TERMS);
            int win = KeywordExtractor.scoreCategory(text, KeywordExtractor.WIN_TERMS);
            int money = KeywordExtractor.scoreCategory(text, KeywordExtractor.MONEY_TERMS);
            int lead = KeywordExtractor.scoreCategory(text, KeywordExtractor.LEAD_TERMS);
            int opp = KeywordExtractor.scoreCategory(text, KeywordExtractor.OPP_TERMS);
            int truth = KeywordExtractor.scoreCategory(text, KeywordExtractor.TRUTH_TERMS);
            int people = KeywordExtractor.scoreCategory(text, KeywordExtractor.PEOPLE_TERMS);
            int advice = KeywordExtractor.scoreCategory(text, KeywordExtractor.ADVICE_TERMS);
            int best = Math.max(Math.max(deal, media), Math.max(win, money));
            best = Math.max(best, Math.max(Math.max(lead, opp), Math.max(Math.max(truth, people), advice)));
            if (best == 0) return CategoryHint.GENERIC;
            if (deal == best) return CategoryHint.DEAL;
            if (media == best) return CategoryHint.MEDIA;
            if (win == best) return CategoryHint.WINNING;
            if (money == best) return CategoryHint.MONEY;
            if (lead == best) return CategoryHint.LEADERSHIP;
            if (opp == best) return CategoryHint.OPPONENT;
            if (truth == best) return CategoryHint.TRUTH;
            if (people == best) return CategoryHint.PEOPLE;
            if (advice == best) return CategoryHint.ADVICE;
            if (text.matches(".*\\b(deal|negotiat|contract|merge|acquisit)\\b.*")) return CategoryHint.DEAL;
            if (text.matches(".*\\b(media|press|twitter|news|tweet)\\b.*")) return CategoryHint.MEDIA;
            if (text.matches(".*\\b(win|lose|winner|loser|beat|best|greatest)\\b.*")) return CategoryHint.WINNING;
            if (text.matches(".*\\b(money|profit|rich|wealth|billion|invest)\\b.*")) return CategoryHint.MONEY;
            if (text.matches(".*\\b(lead|president|america|country|nation)\\b.*")) return CategoryHint.LEADERSHIP;
            if (text.matches(".*\\b(enemy|opponent|fight|attack|crooked)\\b.*")) return CategoryHint.OPPONENT;
            if (text.matches(".*\\b(truth|fake|lie|wrong|right|believe)\\b.*")) return CategoryHint.TRUTH;
            if (text.matches(".*\\b(people|crowd|support|love|huge)\\b.*")) return CategoryHint.PEOPLE;
            if (text.matches(".*\\b(advice|should|what would|how do|how to)\\b.*")) return CategoryHint.ADVICE;
            return CategoryHint.GENERIC;
        }
    }

    private enum CategoryHint {
        DEAL, MEDIA, WINNING, MONEY, LEADERSHIP, OPPONENT, TRUTH, PEOPLE, ADVICE, GENERIC
    }

    /** Extended phrase weights for scoring; unique token set for this build. */
    private static final class PhraseWeights {
        static final int WEIGHT_PRIMARY = 3;
        static final int WEIGHT_SECONDARY = 1;
        static final String TOKEN_PREFIX = "xenon:";
        static final long WEIGHT_SEED = 0x9A4F2C8E1B7D5036L;
    }

    // ========== Xenon Response Bank (unique phrases) ==========
    private static final class XenonResponseBank {
        private static final Random R = new Random(0x8F1E4A2B6C0D9E3AL);
        private static final List<String> CLOSERS = Arrays.asList(
            " Believe me.", " That's the way it is.", " Big league.",
            " Nobody else will tell you this.", " We're going to do great.",
            " You'll see.", " It's going to be huge.", " Trust me on this."
        );
        private final List<String> genericOpeners = Arrays.asList(
            "Look, you've got to think big. Really big.",
            "Nobody knows this better than me. Nobody.",
            "Let me tell you something—and I say this with total certainty.",
            "Here's the thing. The thing that a lot of people don't get.",
            "I've seen it a thousand times. A thousand."
        );
        private static final List<String> ONE_LINER_FALLBACKS = Arrays.asList(
            "Think big. Then go bigger.", "Winners don't quit. Period.",
            "Get the best people. Then get out of their way.", "Stay on offense. Always.",
            "The best deal is the one where you walk away happy.", "Never show desperation.",
            "When they go low, you go to the mat.", "Truth wins. Eventually.",
            "Protect your brand. It's everything.", "Confidence is contagious.",
            "One strong move beats ten weak ones.", "Know your numbers. Always.",
            "The people get it. The elite don't.", "Document everything.",
            "When you're right, say it. Don't soften it.", "Energy and persistence.",
            "Make the call. Don't wait for committee.", "Reputation takes years to build.",
            "Hit back twice as hard. They can't take it.", "Options are leverage.",
            "Say thank you. In writing.", "Read the room. Then lead the room.",
            "The best revenge is massive success.", "Stay hungry. Never get comfortable.",
            "One clear message. Repeat it until they hear it.", "Take the shot.",
            "When in doubt, go bigger.", "Protect your team. They'll protect you.",
            "The truth doesn't need a spin. It needs a megaphone.", "Finish what you start.",
            "Walk away when the deal doesn't serve you.", "Control the narrative or someone else will.",
            "Leverage beats effort. Get leverage.", "First in, first win. Move fast.",
            "Never negotiate against yourself. Let them make the offer.",
            "One tweet can change the story. Use it.", "Facts beat spin. Every time.",
            "The crowd knows. Listen to the crowd.", "Decide. Then execute. No second-guessing.",
            "Big league thinking wins. Small thinking loses.", "Your name on the door. Your standards.",
            "When they attack, counterpunch. Hard.", "Cash flow is oxygen. Protect it.",
            "Trust your gut. Then verify with data.", "Winning is a habit. Build the habit.",
            "Don't explain. Don't apologize. State and move.", "The base is everything. Protect the base.",
            "Expose the lie. Then move on.", "One priority. One message. Repeat.",
            "Take the blame. Share the credit. That's leadership.", "No drama. Just results.",
            "The best defense is a great offense.", "Stay focused. The rest is noise.",
            "When you have the facts, you have the power.", "Be bold. The bold get remembered."
        );
        private final Map<CategoryHint, List<String>> byCategory = new EnumMap<>(CategoryHint.class);

        XenonResponseBank() {
            byCategory.put(CategoryHint.DEAL, Arrays.asList(
                "You go in strong. You never show weakness. The deal gets made when they need you more than you need them.",
                "The best deals happen when the other guy thinks he's winning until the last second. Then you close. Beautiful.",
                "Never take the first offer. Never. Let them sweat. Then you get what you want.",
                "Deals are about leverage. Get leverage. Then you can do anything.",
                "I've made deals my whole life. The key? Walk away. Be ready to walk. They always come back.",
                "You've got to have options. When they know you've got options, the numbers move. Believe me.",
                "The art of the deal is simple: know what you want, know what they want, and make them think they're winning until the ink is dry.",
                "Never get emotional in a deal. Stay cool. Let them get emotional. That's when you win.",
                "Big league negotiators don't blink first. You don't blink.",
                "Always leave something on the table—something small. They feel good, you get the rest.",
                "Timing is everything. Strike when they're eager. Don't strike when you're desperate.",
                "Get everything in writing. Handshakes are for cameras. Paper is for courts.",
                "The best deal is the one where both sides think they won. You just make sure you won more.",
                "Never reveal your bottom line early. Make them work for every inch.",
                "Use silence. After you make an offer, shut up. The next person who talks loses.",
                "Know their deadline. If they need to close by Friday, don't move until Thursday.",
                "Bring a closer. Sometimes you need someone to say the number so you can shrug.",
                "Renegotiate when the facts change. A good deal today can be a bad deal tomorrow.",
                "Never bad-mouth the other side in public. You might need them again.",
                "Celebrate after the ink dries. Until then, act like you could walk any second.",
                "Partnerships work when both sides need each other. Make sure you're not the only one who needs.",
                "The closing is an art. Don't rush it. Let the silence do the work.",
                "Counterparty risk is real. Know who you're dealing with before you sign.",
                "Verbal agreements aren't worth the paper they're written on. Get it in writing.",
                "The best negotiators are the best listeners. Listen first. Then speak.",
                "Never let them see your deadline. Act like you have all the time in the world.",
                "Multiple bidders change everything. Create competition when you can.",
                "The deal you don't do is sometimes the best deal. Walk away when it's wrong.",
                "Relationship matters after the deal. Don't burn the bridge at the closing.",
                "Read every clause. The devil is in the details. Every time.",
                "Renegotiation is always an option if the facts change. Build that in.",
                "The other side has a boss too. Sometimes you need to go over their head.",
                "Win-win is real when both sides feel they won. Get them there.",
                "No deal is better than a bad deal. Remember that when the pressure is on."
            ));
            byCategory.put(CategoryHint.MEDIA, Arrays.asList(
                "The media? You don't need them. You have your own platform. Use it. Direct to the people.",
                "When they attack, you hit back harder. Twice as hard. They can't take it.",
                "Don't explain. Don't apologize. State your case and move on. The real people get it.",
                "Social media is the megaphone. You say it once, the whole world hears. Use it.",
                "The press loves conflict. Give them a show—but on your terms. You control the narrative.",
                "Never let them put words in your mouth. You say what you say. Clear and simple.",
                "When they spin, you counter-spin. One tweet. Done. They spend a week reacting.",
                "The best response to bad press is success. Win so big they have to cover it.",
                "You don't need their permission to be heard. You need a phone and the truth.",
                "They want a sound bite? Give them a sound bite. But make it unforgettable.",
                "Headlines fade. Results last. Focus on results and the headlines will follow.",
                "Don't chase every story. Let some die. Not everything deserves a response.",
                "When you're winning, they'll try to change the subject. Keep the subject on winning.",
                "Fact-check them back. They get one wrong, you put it on the record. Forever.",
                "Your base doesn't get their news from the same place. Speak to your base directly.",
                "Interviews are optional. You don't owe them access. Use it as leverage.",
                "The more they repeat your name, the more you win. Even negative coverage is coverage.",
                "Never do an interview without knowing the host. Know their angle before you sit down.",
                "Leaks are for people who can't win in the open. Win in the open.",
                "One strong statement beats ten corrections. Get it right the first time."
            ));
            byCategory.put(CategoryHint.WINNING, Arrays.asList(
                "Winning isn't everything—it's the only thing. We're going to win so much you'll get tired of winning.",
                "Losers quit. Winners find a way. You're a winner. Act like it.",
                "Nobody wins by playing small. Think big. Then win big.",
                "When you're winning, the critics get louder. That's how you know you're winning.",
                "We're going to win in a way nobody has ever won before. Big league.",
                "Winners don't make excuses. They make results. You want to be a winner? Produce.",
                "The biggest wins come when everybody says it's impossible. Then you do it.",
                "Winning is a habit. You build it one victory at a time. Start today.",
                "Don't hope to win. Plan to win. Then execute. That's winning.",
                "We're not here to participate. We're here to win. Period.",
