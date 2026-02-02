package datadog.communication.http;

import static datadog.common.socket.SocketUtils.discoverApmSocket;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.temporal.ChronoUnit.MILLIS;

import datadog.common.container.ContainerInfo;
import datadog.environment.SystemProperties;
import datadog.http.client.HttpClient;
import datadog.http.client.HttpRequest;
import datadog.http.client.HttpRequestBody;
import datadog.http.client.HttpResponse;
import datadog.http.client.HttpUrl;
import datadog.trace.api.Config;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for HTTP operations with generic HTTP client support.
 * Automatically selects between JDK HttpClient (Java 11+) and OkHttp based on availability.
 */
public final class HttpUtils {
  private static final Logger log = LoggerFactory.getLogger(HttpUtils.class);

  private static final String DATADOG_META_LANG = "Datadog-Meta-Lang";
  private static final String DATADOG_META_LANG_VERSION = "Datadog-Meta-Lang-Version";
  private static final String DATADOG_META_LANG_INTERPRETER = "Datadog-Meta-Lang-Interpreter";
  private static final String DATADOG_META_LANG_INTERPRETER_VENDOR =
      "Datadog-Meta-Lang-Interpreter-Vendor";
  public static final String DATADOG_CONTAINER_ID = "Datadog-Container-ID";
  private static final String DATADOG_ENTITY_ID = "Datadog-Entity-ID";
  public static final String DATADOG_CONTAINER_TAGS_HASH = "Datadog-Container-Tags-Hash";

  private static final String DD_API_KEY = "DD-API-KEY";

  private static final String JAVA_VERSION =
      SystemProperties.getOrDefault("java.version", "unknown");
  private static final String JAVA_VM_NAME =
      SystemProperties.getOrDefault("java.vm.name", "unknown");
  private static final String JAVA_VM_VENDOR =
      SystemProperties.getOrDefault("java.vm.vendor", "unknown");

  public static HttpClient buildHttpClient(final HttpUrl url, final long timeoutMillis) {
    return buildHttpClient(isPlainHttp(url), null, null, timeoutMillis);
  }

  public static HttpClient buildHttpClient(
      final boolean isHttp,
      final String unixDomainSocketPath,
      final String namedPipe,
      final long timeoutMillis) {
    return buildHttpClient(
        unixDomainSocketPath,
        namedPipe,
        null,
        isHttp,
        null,
        null,
        null,
        null,
        timeoutMillis);
  }

  public static HttpClient buildHttpClient(
      final Config config,
      final Executor executor,
      final HttpUrl url,
      final String proxyHost,
      final Integer proxyPort,
      final String proxyUsername,
      final String proxyPassword,
      final long timeoutMillis) {
    return buildHttpClient(
        discoverApmSocket(config),
        config.getAgentNamedPipe(),
        executor,
        isPlainHttp(url),
        proxyHost,
        proxyPort,
        proxyUsername,
        proxyPassword,
        timeoutMillis);
  }

  private static HttpClient buildHttpClient(
      final String unixDomainSocketPath,
      final String namedPipe,
      final Executor executor,
      final boolean isHttp,
      final String proxyHost,
      final Integer proxyPort,
      final String proxyUsername,
      final String proxyPassword,
      final long timeoutMillis) {
    // Use HttpClient.newBuilder() which will automatically select JDK or OkHttp implementation
    final HttpClient.Builder builder = HttpClient.newBuilder();

    // Configure timeouts
    builder.connectTimeout(Duration.of(timeoutMillis, MILLIS));

    // Configure dispatcher/executor
    if (executor != null) {
      builder.executor(executor);
    } else {
      builder.executor(RejectingExecutorService.INSTANCE);
    }

    // Configure Unix domain socket or named pipe
    if (unixDomainSocketPath != null) {
      builder.unixDomainSocket(new File(unixDomainSocketPath));
      log.debug("Using UnixDomainSocket as http transport");
    } else if (namedPipe != null) {
      builder.namedPipe(namedPipe);
      log.debug("Using NamedPipe as http transport");
    }

    // Force clear text (HTTP) if needed
    if (isHttp) {
      builder.clearText(true);
    }

    // Configure proxy
    if (proxyHost != null) {
      builder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
      if (proxyUsername != null) {
        builder.proxyAuthenticator(proxyUsername, proxyPassword == null ? "" : proxyPassword);
      }
    }

    return builder.build();
  }

  public static HttpRequest.Builder prepareRequest(final HttpUrl url, Map<String, String> headers) {

    final HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .url(url)
            .addHeader(DATADOG_META_LANG, "java")
            .addHeader(DATADOG_META_LANG_VERSION, JAVA_VERSION)
            .addHeader(DATADOG_META_LANG_INTERPRETER, JAVA_VM_NAME)
            .addHeader(DATADOG_META_LANG_INTERPRETER_VENDOR, JAVA_VM_VENDOR);

    final String containerId = ContainerInfo.get().getContainerId();
    final String entityId = ContainerInfo.getEntityId();
    if (containerId != null) {
      builder.addHeader(DATADOG_CONTAINER_ID, containerId);
    }
    if (entityId != null) {
      builder.addHeader(DATADOG_ENTITY_ID, entityId);
    }

    for (Map.Entry<String, String> e : headers.entrySet()) {
      builder.addHeader(e.getKey(), e.getValue());
    }

    return builder;
  }

  public static HttpRequest.Builder prepareRequest(
      final HttpUrl url,
      final Map<String, String> headers,
      final Config config,
      final boolean agentless) {
    HttpRequest.Builder builder = prepareRequest(url, headers);

    final String apiKey = config.getApiKey();
    if (agentless && apiKey != null) {
      // we only add the api key header if we know we're doing agentless. No point in adding it to
      // other agent-based requests since we know the datadog-agent isn't going to make use of it.
      builder = builder.addHeader(DD_API_KEY, apiKey);
    }

    return builder;
  }

  /**
   * Creates a msgpack request body from a list of ByteBuffers.
   * Equivalent to {@code of(buffers)} but semantically indicates msgpack content.
   * Content-Type header should be set to "application/msgpack" separately.
   *
   * @param buffers the msgpack content as ByteBuffers
   * @return a new HttpRequestBody
   * @throws NullPointerException if the list of buffers is null
   */
  public static HttpRequestBody msgpackRequestBodyOf(List<ByteBuffer> buffers) {
    return HttpRequestBody.of(buffers);
  }

  /**
   * Creates a gzipped msgpack request body from ByteBuffers.
   * Uses the factory pattern to automatically select JDK or OkHttp implementation.
   */
  public static HttpRequestBody gzippedMsgpackRequestBodyOf(List<ByteBuffer> buffers) {
    return HttpRequestBody.gzip(HttpRequestBody.of(buffers));
  }

  /**
   * Wraps a request body with gzip compression.
   * Uses the factory pattern to automatically select JDK or OkHttp implementation.
   */
  public static HttpRequestBody gzippedRequestBodyOf(HttpRequestBody delegate) {
    return HttpRequestBody.gzip(delegate);
  }

  /**
   * Creates a JSON request body from raw bytes.
   * Uses the factory pattern to automatically select JDK or OkHttp implementation.
   * Content-Type header should be set to "application/json" separately.
   */
  public static HttpRequestBody jsonRequestBodyOf(byte[] json) {
    return HttpRequestBody.of(new String(json, UTF_8));
  }

  public static HttpResponse sendWithRetries(
      HttpClient httpClient, HttpRetryPolicy.Factory retryPolicyFactory, HttpRequest request)
      throws IOException {
    try (HttpRetryPolicy retryPolicy = retryPolicyFactory.create()) {
      while (true) {
        try {
          HttpResponse response = httpClient.execute(request);
          if (response.isSuccessful()) {
            return response;
          }
          if (!retryPolicy.shouldRetry(response)) {
            return response;
          } else {
            closeQuietly(response);
          }
        } catch (Exception ex) {
          if (!retryPolicy.shouldRetry(ex)) {
            throw ex;
          }
        }
        // If we get here, there has been an error, and we still have retries left
        retryPolicy.backoff();
      }
    }
  }

  private static void closeQuietly(HttpResponse response) {
    try {
      response.close();
    } catch (Exception e) {
      // ignore
    }
  }

  public static boolean isPlainHttp(final HttpUrl url) {
    return url != null && "http".equalsIgnoreCase(url.scheme());
  }
}
