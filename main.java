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
