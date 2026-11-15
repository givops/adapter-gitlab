package io.givops.forge.gitlab;

import static io.givops.forge.gitlab.GitLabAdapterTest.assertCode;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.givops.forge.gitlab.GitLabTestFixture.Gate;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitLabTransportTest {
    private static final String TOKEN = "synthetic-tls-test-token";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final AtomicInteger REDIRECT_TARGET_REQUESTS = new AtomicInteger();
    private static final CountDownLatch RELEASE_BODY = new CountDownLatch(1);
    private static HttpsServer server;
    private static ExecutorService workers;
    private static SSLContext trust;

    @BeforeAll
    static void startServer(@TempDir Path temp) throws Exception {
        KeyStore keys = testKeys(temp);
        var keyManager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManager.init(keys, "test-store".toCharArray());
        var serverTls = SSLContext.getInstance("TLS");
        serverTls.init(keyManager.getKeyManagers(), null, null);

        var trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        trusted.setCertificateEntry("localhost", keys.getCertificate("localhost"));
        var trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManager.init(trusted);
        trust = SSLContext.getInstance("TLS");
        trust.init(null, trustManager.getTrustManagers(), null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        workers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        server.createContext("/ok", exchange -> {
            try (exchange) {
                byte[] body = exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN").equals(TOKEN)
                        ? "[]".getBytes(StandardCharsets.UTF_8) : "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.createContext("/redirect", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Location", "/redirect-target");
                exchange.sendResponseHeaders(302, -1);
            }
        });
        server.createContext("/redirect-target", exchange -> {
            try (exchange) {
                REDIRECT_TARGET_REQUESTS.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
            }
        });
        server.createContext("/oversize", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                var body = exchange.getResponseBody();
                body.write(new byte[4]);
                body.flush();
                body.write(new byte[8]);
                body.flush();
            }
        });
        server.createContext("/slow", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('[');
                exchange.getResponseBody().flush();
                try {
                    RELEASE_BODY.await(8, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        RELEASE_BODY.countDown();
        if (server != null) {
            server.stop(0);
        }
        if (workers != null) {
            workers.close();
        }
    }

    @Test
    void verifiesCertificatesAndHostnames() {
        try (var production = new GitLabTransport(CONNECT_TIMEOUT, 1024);
             var localTrust = trustedTransport(1024)) {
            assertCode(() -> production.get(uri("localhost", "/ok"), TOKEN, new Gate()), "gitlab_transport_failed");
            assertCode(() -> localTrust.get(uri("127.0.0.1", "/ok"), TOKEN, new Gate()), "gitlab_transport_failed");
            var response = localTrust.get(uri("localhost", "/ok"), TOKEN, new Gate());
            assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("[]");
        }
    }

    @Test
    void refusesRedirectsWithoutContactingTheDestination() {
        try (var transport = trustedTransport(1024)) {
            assertCode(() -> transport.get(uri("localhost", "/redirect"), TOKEN, new Gate()), "gitlab_redirect");
            assertThat(REDIRECT_TARGET_REQUESTS.get()).isZero();
        }
    }

    @Test
    void enforcesResponseAndAggregateByteLimitsWhileStreaming() {
        try (var transport = trustedTransport(8)) {
            var gate = new Gate();
            assertCode(() -> transport.get(uri("localhost", "/oversize"), TOKEN, gate), "forge_budget_exhausted");
            assertThat(gate.bytes.get()).isGreaterThan(8);
        }
        try (var transport = trustedTransport(1024)) {
            var gate = new Gate() {
                @Override
                public void receivedBytes(long count) {
                    super.receivedBytes(count);
                    if (bytes.get() > 3) {
                        throw GitLabErrors.budgetExceeded();
                    }
                }
            };
            transport.get(uri("localhost", "/ok"), TOKEN, gate);
            assertCode(() -> transport.get(uri("localhost", "/ok"), TOKEN, gate), "forge_budget_exhausted");
        }
    }

    @Test
    void requestDeadlineIncludesTheBodyAfterHeadersArrive() {
        try (var transport = trustedTransport(1024)) {
            transport.get(uri("localhost", "/ok"), TOKEN, new Gate());
            var gate = new Gate();
            gate.timeout = Duration.ofSeconds(2);
            try {
                assertCode(() -> transport.get(uri("localhost", "/slow"), TOKEN, gate), "gitlab_timeout");
                assertThat(gate.bytes.get()).isEqualTo(1);
            } finally {
                RELEASE_BODY.countDown();
            }
        }
    }

    private static GitLabTransport trustedTransport(long maxBytes) {
        return new GitLabTransport(GitLabTransport.clientBuilder(CONNECT_TIMEOUT).sslContext(trust).build(), maxBytes);
    }

    private static URI uri(String host, String path) {
        return URI.create("https://" + host + ":" + server.getAddress().getPort() + path);
    }

    private static KeyStore testKeys(Path temp) throws Exception {
        var storeFile = temp.resolve("localhost.p12");
        var keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
        Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-noprompt",
                "-alias", "localhost", "-keyalg", "RSA", "-storetype", "PKCS12", "-keystore", storeFile.toString(),
                "-storepass", "test-store", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
                "-startdate", "2020/01/01 00:00:00", "-validity", "7305").redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Test TLS certificate generation timed out.");
        }
        try (var output = process.getInputStream()) {
            assertThat(process.exitValue()).as(new String(output.readAllBytes(), StandardCharsets.UTF_8)).isZero();
        }
        var keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(storeFile)) {
            keys.load(input, "test-store".toCharArray());
        }
        return keys;
    }
}
