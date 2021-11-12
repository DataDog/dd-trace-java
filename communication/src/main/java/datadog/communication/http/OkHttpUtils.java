package datadog.communication.http;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.common.container.ContainerInfo;
import datadog.common.socket.NamedPipeSocketFactory;
import datadog.common.socket.UnixDomainSocketFactory;
import datadog.trace.util.AgentProxySelector;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import okhttp3.ConnectionSpec;
import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;
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

  private static final String JAVA_VERSION = System.getProperty("java.version", "unknown");
  private static final String JAVA_VM_NAME = System.getProperty("java.vm.name", "unknown");
  private static final String JAVA_VM_VENDOR = System.getProperty("java.vm.vendor", "unknown");

  public static OkHttpClient buildHttpClient(final HttpUrl url, final long timeoutMillis) {
    return buildHttpClient(url.scheme(), null, null, timeoutMillis);
  }

  public static OkHttpClient buildHttpClient(
      final HttpUrl url,
      final String unixDomainSocketPath,
      final String namedPipe,
      final long timeoutMillis) {
    return buildHttpClient(url.scheme(), unixDomainSocketPath, namedPipe, timeoutMillis);
  }

  public static OkHttpClient buildHttpClient(
      final String scheme,
      final String unixDomainSocketPath,
      final String namedPipe,
      final long timeoutMillis) {
    final OkHttpClient.Builder builder = new OkHttpClient.Builder();
    if (unixDomainSocketPath != null) {
      builder.socketFactory(new UnixDomainSocketFactory(new File(unixDomainSocketPath)));
      log.debug("Using UnixDomainSocket as trace transport");
    } else if (namedPipe != null) {
      builder.socketFactory(new NamedPipeSocketFactory(namedPipe));
      log.debug("Using NamedPipe as trace transport");
    }

    if (!"https".equals(scheme)) {
      // force clear text when using http to avoid failures for JVMs without TLS
      builder.connectionSpecs(Collections.singletonList(ConnectionSpec.CLEARTEXT));
    }

    builder
        .connectTimeout(timeoutMillis, MILLISECONDS)
        .writeTimeout(timeoutMillis, MILLISECONDS)
        .readTimeout(timeoutMillis, MILLISECONDS)
        .proxySelector(AgentProxySelector.INSTANCE)
        .dispatcher(new Dispatcher(RejectingExecutorService.INSTANCE));

    return builder.build();
  }

  public static Request.Builder prepareRequest(final HttpUrl url, Map<String, String> headers) {
    final Request.Builder builder =
        new Request.Builder()
            .url(url)
            .addHeader(DATADOG_META_LANG, "java")
            .addHeader(DATADOG_META_LANG_VERSION, JAVA_VERSION)
            .addHeader(DATADOG_META_LANG_INTERPRETER, JAVA_VM_NAME)
            .addHeader(DATADOG_META_LANG_INTERPRETER_VENDOR, JAVA_VM_VENDOR);
    for (Map.Entry<String, String> e : headers.entrySet()) {
      builder.addHeader(e.getKey(), e.getValue());
    }

    final String containerId = ContainerInfo.get().getContainerId();
    if (containerId == null) {
      return builder;
    } else {
      return builder.addHeader(DATADOG_CONTAINER_ID, containerId);
    }
  }

  public static RequestBody msgpackRequestBodyOf(List<ByteBuffer> buffers) {
    return new ByteBufferRequestBody(buffers);
  }

  private static final class ByteBufferRequestBody extends RequestBody {

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
}
