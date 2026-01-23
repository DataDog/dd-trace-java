package datadog.communication.http;

import static datadog.common.socket.SocketUtils.discoverApmSocket;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.common.container.ContainerInfo;
import datadog.communication.http.client.HttpClient;
import datadog.communication.http.client.HttpRequest;
import datadog.communication.http.client.HttpRequestBody;
import datadog.communication.http.client.HttpResponse;
import datadog.communication.http.client.HttpUrl;
import datadog.communication.http.okhttp.OkHttpRequestBody;
import datadog.environment.SystemProperties;
import datadog.trace.api.Config;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import okhttp3.Dispatcher;
import okhttp3.EventListener;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;
import okio.GzipSink;
import okio.Okio;
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
        null,
        null,
        timeoutMillis);
  }

  public static HttpClient buildHttpClient(
      final Config config,
      final Dispatcher dispatcher,
      final HttpUrl url,
      final Boolean retryOnConnectionFailure,
      final Integer maxRunningRequests,
      final String proxyHost,
      final Integer proxyPort,
      final String proxyUsername,
      final String proxyPassword,
      final long timeoutMillis) {
    return buildHttpClient(
        discoverApmSocket(config),
        config.getAgentNamedPipe(),
        dispatcher,
        isPlainHttp(url),
        retryOnConnectionFailure,
        maxRunningRequests,
        proxyHost,
        proxyPort,
        proxyUsername,
        proxyPassword,
        timeoutMillis);
  }

  /**
   * Custom event listener for HTTP requests.
   * NOTE: This is OkHttp-specific and only works when OkHttp is the active implementation.
   * For generic event listening, use HttpListener in the abstract API.
   *
   * @deprecated Use HttpListener in the abstract API instead
   */
  @Deprecated
  public abstract static class CustomListener extends EventListener {}

  private static HttpClient buildHttpClient(
      final String unixDomainSocketPath,
      final String namedPipe,
      final Dispatcher dispatcher,
      final boolean isHttp,
      final Boolean retryOnConnectionFailure,
      final Integer maxRunningRequests,
      final String proxyHost,
      final Integer proxyPort,
      final String proxyUsername,
      final String proxyPassword,
      final long timeoutMillis) {
    // Use HttpClient.newBuilder() which will automatically select JDK or OkHttp implementation
    final HttpClient.Builder builder = HttpClient.newBuilder();

    // Configure timeouts
    builder
        .connectTimeout(timeoutMillis, MILLISECONDS)
        .writeTimeout(timeoutMillis, MILLISECONDS)
        .readTimeout(timeoutMillis, MILLISECONDS);

    // Configure dispatcher/executor
    if (dispatcher != null) {
      builder.dispatcher(dispatcher.executorService());
    } else {
      builder.dispatcher(RejectingExecutorService.INSTANCE);
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

    // Configure retry on connection failure
    if (retryOnConnectionFailure != null) {
      builder.retryOnConnectionFailure(retryOnConnectionFailure);
    }

    // Configure max concurrent requests
    if (maxRunningRequests != null) {
      builder.maxRequests(maxRunningRequests);
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

  public static HttpRequestBody msgpackRequestBodyOf(List<ByteBuffer> buffers) {
    return OkHttpRequestBody.wrap(new ByteBufferRequestBody(buffers));
  }

  public static HttpRequestBody gzippedMsgpackRequestBodyOf(List<ByteBuffer> buffers) {
    return OkHttpRequestBody.wrap(new GZipByteBufferRequestBody(buffers));
  }

  public static HttpRequestBody gzippedRequestBodyOf(HttpRequestBody delegate) {
    if (!(delegate instanceof OkHttpRequestBody)) {
      throw new IllegalArgumentException("HttpRequestBody must be OkHttpRequestBody implementation");
    }
    return OkHttpRequestBody.wrap(new GZipRequestBodyDecorator(((OkHttpRequestBody) delegate).unwrap()));
  }

  public static HttpRequestBody jsonRequestBodyOf(byte[] json) {
    return OkHttpRequestBody.wrap(new JsonRequestBody(json));
  }

  private static class JsonRequestBody extends RequestBody {

    private static final MediaType JSON = MediaType.get("application/json");

    private final byte[] json;

    private JsonRequestBody(byte[] json) {
      this.json = json;
    }

    @Override
    public long contentLength() {
      return json.length;
    }

    @Override
    public MediaType contentType() {
      return JSON;
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
      sink.write(json);
    }
  }

  private static class ByteBufferRequestBody extends RequestBody {

    private static final MediaType MSGPACK = MediaType.get("application/msgpack");

    private final List<ByteBuffer> buffers;

    private ByteBufferRequestBody(List<ByteBuffer> buffers) {
      this.buffers = buffers;
    }

    @Override
    public long contentLength() {
      long length = 0;
      for (ByteBuffer buffer : buffers) {
        length += buffer.remaining();
      }
      return length;
    }

    @Override
    public MediaType contentType() {
      return MSGPACK;
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
      for (ByteBuffer buffer : buffers) {
        while (buffer.hasRemaining()) {
          sink.write(buffer);
        }
      }
    }
  }

  private static final class GZipByteBufferRequestBody extends ByteBufferRequestBody {
    private GZipByteBufferRequestBody(List<ByteBuffer> buffers) {
      super(buffers);
    }

    @Override
    public long contentLength() {
      return -1;
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
      BufferedSink gzipSink = Okio.buffer(new GzipSink(sink));

      super.writeTo(gzipSink);

      gzipSink.close();
    }
  }

  private static final class GZipRequestBodyDecorator extends RequestBody {
    private final RequestBody delegate;

    private GZipRequestBodyDecorator(RequestBody delegate) {
      this.delegate = delegate;
    }

    @Nullable
    @Override
    public MediaType contentType() {
      return delegate.contentType();
    }

    @Override
    public long contentLength() {
      return -1;
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
      BufferedSink gzipSink = Okio.buffer(new GzipSink(sink));
      delegate.writeTo(gzipSink);
      gzipSink.close();
    }
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
