package io.givops.forge.gitlab;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.givops.forge.port.ForgeRequestGate;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

final class GitLabTestFixture {
    final List<HttpRequest> requests = new ArrayList<>();
    final HttpClient client = mock(HttpClient.class);

    GitLabTestFixture(Function<HttpRequest, Reply> handle) {
        when(client.<byte[]>sendAsync(any(), any())).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
            requests.add(request);
            Reply reply = handle.apply(request);
            HttpHeaders headers = HttpHeaders.of(reply.headers(), (name, value) -> true);
            HttpResponse.ResponseInfo info = mock(HttpResponse.ResponseInfo.class);
            when(info.headers()).thenReturn(headers);
            when(info.statusCode()).thenReturn(reply.status());
            var subscriber = handler.apply(info);
            var subscription = new TestSubscription();
            subscriber.onSubscribe(subscription);
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            for (int offset = 0; offset < body.length && !subscription.cancelled; offset += 4) {
                subscriber.onNext(List.of(ByteBuffer.wrap(body, offset, Math.min(4, body.length - offset))));
            }
            if (!subscription.cancelled) {
                subscriber.onComplete();
            }
            return subscriber.getBody().thenApply(bytes -> {
                @SuppressWarnings("unchecked")
                HttpResponse<byte[]> response = mock(HttpResponse.class);
                when(response.statusCode()).thenReturn(reply.status());
                when(response.headers()).thenReturn(headers);
                when(response.body()).thenReturn(bytes);
                return response;
            }).toCompletableFuture();
        });
    }

    record Reply(int status, Map<String, List<String>> headers, String body) {
        Reply(String body) {
            this(200, Map.of(), body);
        }
    }

    static final class TestSubscription implements Flow.Subscription {
        private boolean cancelled;

        @Override
        public void request(long count) {}

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    static class Gate implements ForgeRequestGate {
        Duration timeout = Duration.ofSeconds(10);
        final AtomicLong bytes = new AtomicLong();
        int requests;
        int objects;

        @Override
        public Duration beforeRequest() {
            requests++;
            return timeout;
        }

        @Override
        public void receivedBytes(long count) {
            bytes.addAndGet(count);
        }

        @Override
        public void acceptedObjects(int count) {
            objects += count;
        }

        @Override
        public void check() {}
    }
}
