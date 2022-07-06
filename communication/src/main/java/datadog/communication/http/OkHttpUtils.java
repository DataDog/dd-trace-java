package datadog.communication.http;

import static datadog.common.socket.SocketUtils.discoverApmSocket;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.common.container.ContainerInfo;
import datadog.common.socket.NamedPipeSocketFactory;
import datadog.common.socket.UnixDomainSocketFactory;
import datadog.common.version.VersionInfo;
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
import okhttp3.Authenticator;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.Credentials;
import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;
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
  private static final String DATADOG_CONTAINER_ID = "Datadog-Container-ID";

  private static final String DD_EVP_ORIGIN = "DD-EVP-ORIGIN";
  private static final String DD_EVP_ORIGIN_VERSION = "DD-EVP-ORIGIN-VERSION";

  private static final String JAVA_VERSION = System.getProperty("java.version", "unknown");
  private static final String JAVA_VM_NAME = System.getProperty("java.vm.name", "unknown");
  private static final String JAVA_VM_VENDOR = System.getProperty("java.vm.vendor", "unknown");

  private static final String JAVA_TRACING_LIBRARY = "dd-trace-java";

  public static OkHttpClient buildHttpClient(final HttpUrl url, final long timeoutMillis) {
    return buildHttpClient(url, null, null, timeoutMillis);
  }

  public static OkHttpClient buildHttpClient(
      final HttpUrl url,
      final String unixDomainSocketPath,
      final String namedPipe,
      final long timeoutMillis) {
    return buildHttpClient(
        unixDomainSocketPath,
        namedPipe,
        null,
        !url.scheme().equals("https"),
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
      final boolean retryOnConnectionFailure,
      final int maxRunningRequests,
      final String proxyHost,
      final Integer proxyPort,
      final String proxyUsername,
      final String proxyPassword,
      final long timeoutMillis) {
    return buildHttpClient(
        discoverApmSocket(config),
        config.getAgentNamedPipe(),
        dispatcher,
        !url.scheme().equals("https"),
        retryOnConnectionFailure,
        maxRunningRequests,
        proxyHost,
        proxyPort,
        proxyUsername,
        proxyPassword,
        timeoutMillis);
  }

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
      builder.connectionPool(new ConnectionPool(maxRunningRequests, 1, SECONDS));
    }

    if (proxyHost != null) {
      builder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
      if (proxyUsername != null) {
        builder.proxyAuthenticator(
            new Authenticator() {
              @Override
              public Request authenticate(final Route route, final Response response) {
                final String credential =
                    Credentials.basic(proxyUsername, proxyPassword == null ? "" : proxyPassword);
                return response
                    .request()
                    .newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build();
              }
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
            .addHeader(DATADOG_META_LANG_INTERPRETER_VENDOR, JAVA_VM_VENDOR)
            .addHeader(DD_EVP_ORIGIN, JAVA_TRACING_LIBRARY)
            .addHeader(DD_EVP_ORIGIN_VERSION, VersionInfo.VERSION);

    final String containerId = ContainerInfo.get().getContainerId();
    if (containerId != null) {
      builder.addHeader(DATADOG_CONTAINER_ID, containerId);
    }

    for (Map.Entry<String, String> e : headers.entrySet()) {
      builder.addHeader(e.getKey(), e.getValue());
    }

    return builder;
  }

  public static RequestBody msgpackRequestBodyOf(List<ByteBuffer> buffers) {
    return new ByteBufferRequestBody(buffers);
  }

  public static RequestBody gzippedMsgpackRequestBodyOf(List<ByteBuffer> buffers) {
    return new GZipByteBufferRequestBody(buffers);
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
}
