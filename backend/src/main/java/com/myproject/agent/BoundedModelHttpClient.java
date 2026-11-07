package com.myproject.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** LangChain4j transport with a deadline covering headers and body, and bounded error bodies. */
public final class BoundedModelHttpClient implements HttpClient {
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final java.net.http.HttpClient shared;
    private final Duration readTimeout;

    private BoundedModelHttpClient(java.net.http.HttpClient shared, Duration readTimeout) {
        this.shared = shared;
        this.readTimeout = readTimeout;
    }

    public static HttpClientBuilder builder(java.net.http.HttpClient shared) {
        Objects.requireNonNull(shared, "shared HTTP client");
        if (shared.followRedirects() != java.net.http.HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Model HTTP client must not follow redirects");
        }
        return new Builder(shared);
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        if ((request.formDataFields() != null && !request.formDataFields().isEmpty())
                || (request.formDataFiles() != null && !request.formDataFiles().isEmpty())) {
            throw new UnsupportedOperationException("Multipart model requests are not supported");
        }
        java.net.http.HttpRequest outbound;
        try {
            var builder = java.net.http.HttpRequest.newBuilder(URI.create(request.url())).timeout(readTimeout);
            if (request.headers() != null) {
                request.headers().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
            }
            var body = request.body() == null ? java.net.http.HttpRequest.BodyPublishers.noBody()
                    : java.net.http.HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8);
            outbound = builder.method(request.method().name(), body).build();
        } catch (IllegalArgumentException ex) {
            // Framework exception messages may contain request URLs or header values.
            throw new AgentException(502, "模型请求格式无效，请检查服务配置");
        }

        CompletableFuture<HttpResponse<byte[]>> pending = shared.sendAsync(outbound, info -> new BoundedBody());
        try {
            HttpResponse<byte[]> response = pending.get(Math.max(1, readTimeout.toMillis()), TimeUnit.MILLISECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AgentException(502, "模型服务暂不可用，请检查服务配置或稍后重试");
            }
            validateEnvelope(response.body());
            return SuccessfulHttpResponse.builder().statusCode(response.statusCode())
                    .headers(response.headers().map()).body(response.body()).build();
        } catch (TimeoutException ex) {
            pending.cancel(true);
            throw new AgentException(504, "模型请求超时，请稍后重试");
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new AgentException(503, "健康助手请求已中断");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof AgentException agent) throw agent;
            if (cause instanceof HttpTimeoutException) {
                pending.cancel(true);
                throw new AgentException(504, "模型请求超时，请稍后重试");
            }
            throw new AgentException(502, "无法连接模型服务，请检查网络或服务配置");
        }
    }

    private static void validateEnvelope(byte[] body) {
        // The SDK normalizes tool calls and drops their raw type. Validate untrusted wire fields first;
        // LangChain4j remains responsible for message conversion and provider protocol serialization.
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode message = root == null ? null : root.path("choices").path(0).path("message");
            if (message == null || !message.isObject() || !"assistant".equals(message.path("role").asText())) {
                throw new AgentException(502, "模型返回格式无效，请稍后重试");
            }
            JsonNode content = message.get("content");
            if (content != null && !content.isNull() && (!content.isTextual() || content.asText().length() > 12000)) {
                throw new AgentException(502, "模型回复格式无效");
            }
            JsonNode calls = message.get("tool_calls");
            if (calls == null || calls.isNull()) return;
            if (!calls.isArray()) throw new AgentException(502, "模型工具调用格式无效");
            for (JsonNode call : calls) {
                JsonNode function = call.path("function");
                if (!"function".equals(call.path("type").asText()) || !wireText(call.get("id"), 128)
                        || !wireText(function.get("name"), 80) || !wireText(function.get("arguments"), 4096)) {
                    throw new AgentException(502, "模型工具调用格式无效");
                }
            }
        } catch (IOException ex) {
            throw new AgentException(502, "模型返回格式无效，请稍后重试");
        }
    }

    private static boolean wireText(JsonNode node, int maxLength) {
        return node != null && node.isTextual() && !node.textValue().isBlank() && node.textValue().length() <= maxLength;
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        throw new UnsupportedOperationException("Streaming model responses are not supported");
    }

    private static final class Builder implements HttpClientBuilder {
        private final java.net.http.HttpClient shared;
        private Duration connectTimeout;
        private Duration readTimeout = Duration.ofSeconds(20);

        private Builder(java.net.http.HttpClient shared) {
            this.shared = shared;
            this.connectTimeout = shared.connectTimeout().orElse(Duration.ofSeconds(20));
        }

        @Override public Duration connectTimeout() { return connectTimeout; }

        @Override
        public HttpClientBuilder connectTimeout(Duration timeout) {
            connectTimeout = positive(timeout);
            return this;
        }

        @Override public Duration readTimeout() { return readTimeout; }

        @Override
        public HttpClientBuilder readTimeout(Duration timeout) {
            readTimeout = positive(timeout);
            return this;
        }

        @Override
        public HttpClient build() {
            // Connection setup is bounded by the shared JDK client's connectTimeout;
            // the per-request read timeout below additionally bounds the whole exchange.
            return new BoundedModelHttpClient(shared, readTimeout);
        }

        private static Duration positive(Duration timeout) {
            Objects.requireNonNull(timeout, "HTTP timeout");
            if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("HTTP timeout must be positive");
            return timeout;
        }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return result; }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > MAX_BODY_BYTES) {
                    subscription.cancel();
                    result.completeExceptionally(new AgentException(502, "模型响应过大，请缩小问题范围"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
