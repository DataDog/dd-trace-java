package datadog.trace.common.writer;

import static datadog.trace.api.config.OtlpConfig.TRACE_OTEL_EXPORTER;
import static datadog.trace.junit.utils.config.WithConfigExtension.injectSysConfig;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class OtlpWriterCombinedTest extends DDCoreJavaSpecification {

  @Test
  void happyPathOverHttp() throws IOException, InterruptedException {
    injectSysConfig(TRACE_OTEL_EXPORTER, "otlp");

    CountDownLatch received = new CountDownLatch(1);
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/v1/traces",
        exchange -> {
          received.countDown();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();

    OtlpWriter writer =
        OtlpWriter.builder()
            .endpoint(
                "http://"
                    + server.getAddress().getHostString()
                    + ":"
                    + server.getAddress().getPort()
                    + "/v1/traces")
            .flushIntervalMilliseconds(-1)
            .build();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      tracer.buildSpan("test", "fakeOperation").start().finish();
      writer.flush();

      assertTrue(received.await(5, TimeUnit.SECONDS), "OTLP server should receive a request");
    } finally {
      tracer.close();
      server.stop(0);
    }
  }
}
