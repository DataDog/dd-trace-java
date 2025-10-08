package datadog.communication.http;

import static datadog.common.socket.SocketUtils.discoverApmSocket;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.common.container.ContainerInfo;
import datadog.common.socket.NamedPipeSocketFactory;
import datadog.common.socket.UnixDomainSocketFactory;
import datadog.environment.SystemProperties;
import datadog.trace.api.Config;
import datadog.trace.util.AgentProxySelector;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.Credentials;
import okhttp3.Dispatcher;
import okhttp3.EventListener;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import okio.GzipSink;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OkHttpUtils {
  private static final Logger log = LoggerFactory.getLogger(OkHttpUtils.class);

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

  public static OkHttpClient buildHttpClient(final HttpUrl url, final long timeoutMillis) {
    return buildHttpClient(isPlainHttp(url), null, null, timeoutMillis);
  }

  public static OkHttpClient buildHttpClient(
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

  public static OkHttpClient buildHttpClient(
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

  public abstract static class CustomListener extends EventListener {}

  private static OkHttpClient buildHttpClient(
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
    final OkHttpClient.Builder builder = new OkHttpClient.Builder();

    try {
      builder.eventListenerFactory(
          call -> {
            Request request = call.request();
            CustomListener listener = request.tag(CustomListener.class);
            return listener != null ? listener : EventListener.NONE;
          });
    } catch (NoSuchMethodError e) {
      // A workaround for OKHTTP instrumentation tests
      // where the version of OKHTTP conflicts with the one used in this module.
      // This should never happen in "real life" as OKHTTP classes
      // used by the tracer core are relocated to a different package
    }

    builder
        .connectTimeout(timeoutMillis, MILLISECONDS)
        .writeTimeout(timeoutMillis, MILLISECONDS)
        .readTimeout(timeoutMillis, MILLISECONDS)
        .proxySelector(AgentProxySelector.INSTANCE)
        .dispatcher(
            dispatcher != null ? dispatcher : new Dispatcher(RejectingExecutorService.INSTANCE));

    if (unixDomainSocketPath != null) {
      builder.socketFactory(new UnixDomainSocketFactory(new File(unixDomainSocketPath)));
      log.debug("Using UnixDomainSocket as http transport");
    } else if (namedPipe != null) {
      builder.socketFactory(new NamedPipeSocketFactory(namedPipe));
      log.debug("Using NamedPipe as http transport");
    }

    if (isHttp) {
      // force clear text when using http to avoid failures for JVMs without TLS
      builder.connectionSpecs(Collections.singletonList(ConnectionSpec.CLEARTEXT));
    }

    if (retryOnConnectionFailure != null) {
      builder.retryOnConnectionFailure(retryOnConnectionFailure);
    }

    if (maxRunningRequests != null) {
      // Reusing connections causes non daemon threads to be created which causes agent to prevent
      // app from exiting. See https://github.com/square/okhttp/issues/4029 for some details.
      builder.connectionPool(new ConnectionPool(maxRunningRequests, 1, SECONDS));
    }

    if (proxyHost != null) {
      builder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
      if (proxyUsername != null) {
        builder.proxyAuthenticator(
            (route, response) -> {
              final String credential =
                  Credentials.basic(proxyUsername, proxyPassword == null ? "" : proxyPassword);

              return response
                  .request()
                  .newBuilder()
                  .header("Proxy-Authorization", credential)
                  .build();
            });
      }
    }

    OkHttpClient client = builder.build();

    if (maxRunningRequests != null) {
      client.dispatcher().setMaxRequests(maxRunningRequests);
      // We are mainly talking to the same(ish) host so we need to raise this limit
      client.dispatcher().setMaxRequestsPerHost(maxRunningRequests);
    }

    return client;
  }

  public static Request.Builder prepareRequest(final HttpUrl url, Map<String, String> headers) {

    final Request.Builder builder =
        new Request.Builder()
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

  public static Request.Builder prepareRequest(
      final HttpUrl url,
      final Map<String, String> headers,
      final Config config,
      final boolean agentless) {
    Request.Builder builder = prepareRequest(url, headers);

    final String apiKey = config.getApiKey();
    if (agentless && apiKey != null) {
      // we only add the api key header if we know we're doing agentless. No point in adding it to
      // other agent-based requests since we know the datadog-agent isn't going to make use of it.
      builder = builder.addHeader(DD_API_KEY, apiKey);
    }

    return builder;
  }

  public static RequestBody msgpackRequestBodyOf(List<ByteBuffer> buffers) {
    return new ByteBufferRequestBody(buffers);
  }

  public static RequestBody gzippedMsgpackRequestBodyOf(List<ByteBuffer> buffers) {
    return new GZipByteBufferRequestBody(buffers);
  }

  public static RequestBody gzippedRequestBodyOf(RequestBody delegate) {
    return new GZipRequestBodyDecorator(delegate);
  }

  public static RequestBody jsonRequestBodyOf(byte[] json) {
    return new JsonRequestBody(json);
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

  public static Response sendWithRetries(
      OkHttpClient httpClient, HttpRetryPolicy.Factory retryPolicyFactory, Request request)
      throws IOException {
    try (HttpRetryPolicy retryPolicy = retryPolicyFactory.create()) {
      while (true) {
        try {
          Response response = httpClient.newCall(request).execute();
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

  private static void closeQuietly(Response response) {
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
