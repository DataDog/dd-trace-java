package datadog.trace.core.http;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.common.container.ContainerInfo;
import datadog.common.socket.UnixDomainSocketFactory;
import datadog.trace.core.DDTraceCoreInfo;
import datadog.trace.util.AgentProxySelector;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import okhttp3.ConnectionSpec;
import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;

public final class OkHttpUtils {

  private static final String DATADOG_META_LANG = "Datadog-Meta-Lang";
  private static final String DATADOG_META_LANG_VERSION = "Datadog-Meta-Lang-Version";
  private static final String DATADOG_META_LANG_INTERPRETER = "Datadog-Meta-Lang-Interpreter";
  private static final String DATADOG_META_LANG_INTERPRETER_VENDOR =
      "Datadog-Meta-Lang-Interpreter-Vendor";
  private static final String DATADOG_META_TRACER_VERSION = "Datadog-Meta-Tracer-Version";
  private static final String DATADOG_CONTAINER_ID = "Datadog-Container-ID";

  public static OkHttpClient buildHttpClient(final HttpUrl url, final long timeoutMillis) {
    return buildHttpClient(url.scheme(), null, timeoutMillis);
  }

  public static OkHttpClient buildHttpClient(
      final HttpUrl url, final String unixDomainSocketPath, final long timeoutMillis) {
    return buildHttpClient(url.scheme(), unixDomainSocketPath, timeoutMillis);
  }

  public static OkHttpClient buildHttpClient(
      final String scheme, final String unixDomainSocketPath, final long timeoutMillis) {
    OkHttpClient.Builder builder = new OkHttpClient.Builder();
    if (unixDomainSocketPath != null) {
      builder = builder.socketFactory(new UnixDomainSocketFactory(new File(unixDomainSocketPath)));
    }

    if (!"https".equals(scheme)) {
      // force clear text when using http to avoid failures for JVMs without TLS
      builder = builder.connectionSpecs(Collections.singletonList(ConnectionSpec.CLEARTEXT));
    }

    return builder
        .connectTimeout(timeoutMillis, MILLISECONDS)
        .writeTimeout(timeoutMillis, MILLISECONDS)
        .readTimeout(timeoutMillis, MILLISECONDS)
        .proxySelector(AgentProxySelector.INSTANCE)

        // We don't do async so this shouldn't matter, but just to be safe...
        .dispatcher(new Dispatcher(RejectingExecutorService.INSTANCE))
        .build();
  }

  public static Request.Builder prepareRequest(final HttpUrl url) {
    final Request.Builder builder =
        new Request.Builder()
            .url(url)
            .addHeader(DATADOG_META_LANG, "java")
            .addHeader(DATADOG_META_LANG_VERSION, DDTraceCoreInfo.JAVA_VERSION)
            .addHeader(DATADOG_META_LANG_INTERPRETER, DDTraceCoreInfo.JAVA_VM_NAME)
            .addHeader(DATADOG_META_LANG_INTERPRETER_VENDOR, DDTraceCoreInfo.JAVA_VM_VENDOR)
            .addHeader(DATADOG_META_TRACER_VERSION, DDTraceCoreInfo.VERSION);

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
