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
