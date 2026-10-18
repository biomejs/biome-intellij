package com.github.biomejs.intellijbiome.fixtures;

import java.io.BufferedInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Controlled LSP-only peer; unexpected CLI probes fail instead of returning a version. */
public final class StartupLspProcess {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].equals("lsp-proxy")) {
            System.err.println("Unexpected startup arguments: " + String.join(" ", args));
            System.exit(73);
        }
        var input = new BufferedInputStream(System.in);
        var idPattern = Pattern.compile("\"id\"\\s*:\\s*(\"[^\"]+\"|\\d+)");
        String configurationLog = System.getenv("BIOME_STARTUP_CONFIGURATION_LOG");
        String editorDirectory = System.getenv("BIOME_EDITOR_FIXTURE_DIRECTORY");
        while (true) {
            int length = -1;
            var line = new StringBuilder();
            while (true) {
                int next = input.read();
                if (next == -1) return;
                if (next == '\n') {
                    String header = line.toString().trim();
                    line.setLength(0);
                    if (header.isEmpty()) break;
                    if (header.startsWith("Content-Length:")) length = Integer.parseInt(header.substring(15).trim());
                } else line.append((char) next);
            }
            if (length < 0) throw new IllegalStateException("Missing content length");
            String message = new String(input.readNBytes(length), StandardCharsets.UTF_8);
            var id = idPattern.matcher(message);
            if (id.find()) {
                if (id.group(1).equals("\"startup-config\"")) {
                    Path log = Path.of(configurationLog);
                    Path pending = log.resolveSibling(log.getFileName() + ".pending");
                    Files.writeString(pending, message);
                    Files.move(pending, log, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    continue;
                }
                boolean initialize = message.contains("\"initialize\"");
                if (initialize) awaitInitializeRelease();
                String result = initialize ? (editorDirectory == null ? "{\"capabilities\":{}}" :
                    "{\"capabilities\":{\"textDocumentSync\":{\"openClose\":true,\"change\":1}}}") : "null";
                send("{\"jsonrpc\":\"2.0\",\"id\":" + id.group(1) + ",\"result\":" + result + "}");
            } else if (message.contains("\"initialized\"") && configurationLog != null) {
                send("{\"jsonrpc\":\"2.0\",\"id\":\"startup-config\",\"method\":\"workspace/configuration\",\"params\":{\"items\":[{\"section\":\"biome\"}]}}");
            } else if (message.contains("\"exit\"")) return;
        }
    }

    private static void awaitInitializeRelease() throws Exception {
        String started = System.getenv("BIOME_STARTUP_INITIALIZE_STARTED");
        if (started == null) return;
        Files.writeString(Path.of(started), Long.toString(ProcessHandle.current().pid()));
        Path release = Path.of(System.getenv("BIOME_STARTUP_INITIALIZE_RELEASE"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(release)) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Initialize response was not released");
            Thread.sleep(10);
        }
    }

    private static void send(String message) throws Exception {
        byte[] response = message.getBytes(StandardCharsets.UTF_8);
        System.out.print("Content-Length: " + response.length + "\r\n\r\n");
        System.out.write(response);
        System.out.flush();
    }
}
