package com.memorysystem.integration.feishu;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class LarkCliClient {
    public static final String ENV_CLI_PATH = "MEMORY_LARK_CLI_PATH";

    private static final Path DEFAULT_RELATIVE_PATH = Path.of(
            "cli-main", "cli-main", "dist", "lark-cli-memory-windows", "lark-cli.exe");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final Path cliPath;
    private final Duration timeout;

    public LarkCliClient() {
        this(resolveCliPath(System.getenv(), Path.of("").toAbsolutePath()), DEFAULT_TIMEOUT);
    }

    public LarkCliClient(Path cliPath) {
        this(cliPath, DEFAULT_TIMEOUT);
    }

    public LarkCliClient(Path cliPath, Duration timeout) {
        this.cliPath = cliPath;
        this.timeout = timeout;
    }

    public static Path resolveCliPath(Map<String, String> env, Path workingDirectory) {
        String override = env.get(ENV_CLI_PATH);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return workingDirectory.resolve(DEFAULT_RELATIVE_PATH);
    }

    public Result run(List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(cliPath.toString());
        command.addAll(args);

        if (!Files.isRegularFile(cliPath)) {
            return new Result(-1, "", "CLI not found: " + cliPath, List.copyOf(command));
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        try {
            Process process = builder.start();
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> readFully(process.getInputStream()));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readFully(process.getErrorStream()));

            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                return new Result(-1, stdout.join(), "CLI timed out after " + timeout.toSeconds() + "s", List.copyOf(command));
            }

            return new Result(process.exitValue(), stdout.join(), stderr.join(), List.copyOf(command));
        } catch (IOException e) {
            return new Result(-1, "", "Failed to start CLI: " + e.getMessage(), List.copyOf(command));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "", "CLI interrupted", List.copyOf(command));
        }
    }

    private static String readFully(InputStream stream) {
        try {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Failed to read process stream: " + e.getMessage();
        }
    }

    public record Result(int exitCode, String stdout, String stderr, List<String> command) {
    }
}
