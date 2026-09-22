package com.pocketledger.config;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Runs the local React development server alongside the backend. */
@Component
public class FrontendDevServer {
    private static final Logger log = LoggerFactory.getLogger(FrontendDevServer.class);
    private final boolean enabled;
    private Process frontendProcess;
    private volatile boolean stopping;

    public FrontendDevServer(@Value("${app.frontend.auto-start:true}") boolean enabled) {
        this.enabled = enabled;
    }

    // Wait until Spring has started successfully, including its database connection.
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!enabled || frontendProcess != null) {
            return;
        }

        Path frontend = findFrontendDirectory();
        if (frontend == null) {
            // A deployed backend without the frontend source does not need a dev server.
            log.info("Frontend source not found; skipping the local frontend server.");
            return;
        }

        Path vite = frontend.resolve("node_modules/vite/bin/vite.js");
        if (!Files.isRegularFile(vite)) {
            log.warn("Frontend dependencies are missing. Run 'npm install' in {} and restart the backend.", frontend);
            return;
        }

        try {
            // Start Node directly so shutdown can stop the actual server, without an npm shell wrapper.
            // A fixed port matches the controller's CORS configuration. If it is occupied,
            // Vite exits instead of silently choosing another port or replacing an existing server.
            frontendProcess = new ProcessBuilder("node", vite.toString(),
                    "--host", "127.0.0.1", "--port", "5173", "--strictPort")
                    .directory(frontend.toFile())
                    .inheritIO()
                    .start();
            log.info("Starting frontend at http://localhost:5173; Vite output appears in this console.");
            frontendProcess.onExit().thenAccept(process -> {
                if (!stopping && process.exitValue() != 0) {
                    log.warn("Frontend process exited with code {}. Check the Vite output above.", process.exitValue());
                }
            });
        } catch (IOException exception) {
            log.warn("Could not start the frontend. Check that Node.js is installed and on PATH: {}",
                    exception.getMessage());
        }
    }

    private Path findFrontendDirectory() {
        // Support running IntelliJ from the project root or Maven from backend/.
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        for (Path candidate : new Path[] {
                workingDirectory.resolve("frontend"),
                workingDirectory.resolve("../frontend").normalize()
        }) {
            if (Files.isRegularFile(candidate.resolve("package.json"))) {
                return candidate;
            }
        }
        return null;
    }

    // Spring calls this on normal shutdown and DevTools restarts. Only stop the process we started.
    @PreDestroy
    public synchronized void stop() {
        stopping = true;
        if (frontendProcess == null || !frontendProcess.isAlive()) {
            return;
        }
        frontendProcess.destroy();
        try {
            if (!frontendProcess.waitFor(5, TimeUnit.SECONDS)) {
                frontendProcess.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            frontendProcess.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
