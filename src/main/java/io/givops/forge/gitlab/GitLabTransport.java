package io.givops.forge.gitlab;


import io.givops.forge.model.ForgeException;
import io.givops.forge.port.ForgeRequestGate;
import io.givops.integrations.checks.ConnectionCheckException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;

final class GitLabTransport implements AutoCloseable {
    private final HttpClient client;
    private final long maxResponseBytes;

    GitLabTransport(Duration connectTimeout, long maxResponseBytes) {
        this(clientBuilder(connectTimeout).build(), maxResponseBytes);
    }

    GitLabTransport(HttpClient client, long maxResponseBytes) {
        this.client = client;
        this.maxResponseBytes = maxResponseBytes;
    }

    static HttpClient.Builder clientBuilder(Duration connectTimeout) {
        return HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER);
    }

    Response get(URI uri, String token, ForgeRequestGate gate) {
        var response = getForCheck(uri, token, gate);
        if (response.status() != 200) throw GitLabErrors.remoteStatus(response.status());
        return response;
    }

    Response getForCheck(URI uri, String token, ForgeRequestGate gate) {
        return send(uri, token, null, gate, status -> status == 200);
    }

    Response post(URI uri, String token, byte[] payload, ForgeRequestGate gate, int successStatus) {
        return send(uri, token, payload, gate, status -> status == successStatus);
    }

    private Response send(URI uri, String token, byte[] payload, ForgeRequestGate gate, IntPredicate retainBody) {
        // Construct and validate all outbound data before admitting the request.
        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(uri).header("Accept", "application/json")
                    .header("Accept-Encoding", "identity");
            if (payload == null) {
                request.GET();
            } else {
                request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(payload));
            }
            if (token != null && !token.isBlank()) {
                request.header("PRIVATE-TOKEN", token);
            }
        } catch (IllegalArgumentException exception) {
            throw GitLabErrors.invalidConfiguration();
        }
        Duration timeout = gate.beforeRequest();
        long started = System.nanoTime();
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request.timeout(timeout).build(),
                info -> new LimitedBody(gate, maxResponseBytes, retainBody.test(info.statusCode())));
        try {
            // The subscriber completes only after the whole bounded body has arrived.
            long remaining = Math.max(0, timeout.toNanos() - (System.nanoTime() - started));
            HttpResponse<byte[]> result = pending.get(remaining, TimeUnit.NANOSECONDS);
            gate.check();
            return new Response(result.headers(), result.body(), result.statusCode());
        } catch (TimeoutException exception) {
            pending.cancel(true);
            gate.check();
            throw GitLabErrors.timeout();
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new ForgeException("forge_cancelled", HttpStatus.SERVICE_UNAVAILABLE,
                    GitLabMessages.format("forge.gitLabTransport.the.forge.refresh.was.interrupted"));
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof ConnectionCheckException failure) throw failure;
            if (cause instanceof io.givops.ticketing.model.TicketException failure) throw failure;
            if (cause instanceof ForgeException failure) {
                throw failure;
            }
            gate.check();
            if (cause instanceof HttpTimeoutException) {
                throw GitLabErrors.timeout();
            }
            throw GitLabErrors.connectionFailed();
        }
    }

    @Override
    public void close() {
        client.close();
    }

    record Response(HttpHeaders headers, byte[] body, int status) {}

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final ForgeRequestGate gate;
        private final long limit;
        private final ByteArrayOutputStream bytes;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private long count;

        private LimitedBody(ForgeRequestGate gate, long limit, boolean retainBody) {
            this.gate = gate;
            this.limit = limit;
            bytes = retainBody ? new ByteArrayOutputStream() : null;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            try {
                for (ByteBuffer buffer : buffers) {
                    int size = buffer.remaining();
                    gate.receivedBytes(size);
                    count += size;
                    if (count > limit) {
                        throw GitLabErrors.budgetExceeded();
                    }
                    if (bytes != null) {
                        var chunk = new byte[size];
                        buffer.get(chunk);
                        bytes.writeBytes(chunk);
                    }
                }
                subscription.request(1);
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
                subscription.cancel();
            }
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            result.complete(bytes == null ? new byte[0] : bytes.toByteArray());
        }
    }
}
