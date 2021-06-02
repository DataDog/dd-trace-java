package datadog.trace.core;

import datadog.trace.api.DDId;
import datadog.trace.api.StatsDClient;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.common.writer.DDAgentWriter;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.DDAgentFeaturesDiscovery;
import datadog.trace.core.http.OkHttpUtils;
import datadog.trace.core.monitor.Monitoring;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@State(Scope.Benchmark)
public class OriginPropagation {

  private static final int SPAN_COUNT = 1000;
  private Server httpServer;
  private DDAgentWriter writer;
  private CoreTracer tracer;

  private final List<DDSpan> spans = new ArrayList<>(SPAN_COUNT);
  private final List<DDSpan> enrichedSpans = new ArrayList<>(SPAN_COUNT);
  private final List<DDSpan> spansWithOrigin = new ArrayList<>(SPAN_COUNT);
  private final List<DDSpan> enrichedSpansWithOrigin = new ArrayList<>(SPAN_COUNT);

  @Setup(Level.Trial)
  public void init() throws Exception {
    httpServer = new Server();
    final HttpConfiguration httpConfiguration = new HttpConfiguration();
    final ServerConnector http =
        new ServerConnector(httpServer, new HttpConnectionFactory(httpConfiguration));
    http.setHost("localhost");
    http.setPort(0);
    httpServer.addConnector(http);
    httpServer.setHandler(
        new AbstractHandler() {
          @Override
          public void handle(
              String target,
              Request baseRequest,
              HttpServletRequest request,
              HttpServletResponse response)
              throws IOException, ServletException {
            response.setStatus(HttpStatus.OK_200);
          }
        });
    httpServer.start();
    httpServer.setStopAtShutdown(true);
    final URI address = new URI("http://localhost:" + http.getLocalPort());

    final Monitoring monitoring = new Monitoring(StatsDClient.NO_OP, 1, TimeUnit.SECONDS);
    final HttpUrl agentUrl = HttpUrl.get(address.toString());
    final OkHttpClient client = OkHttpUtils.buildHttpClient(agentUrl, null, 1000);
    final DDAgentFeaturesDiscovery discovery =
        new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true);
    final DDAgentApi agentApi = new DDAgentApi(client, agentUrl, discovery, monitoring, false);

    writer =
        DDAgentWriter.builder()
            .agentApi(agentApi)
            .flushFrequencySeconds(9999) // Disabling automatic flushing.
            .build();

    tracer = CoreTracer.builder().writer(writer).strictTraceWrites(true).build();

    for (int i = 1; i <= SPAN_COUNT; i++) {
      spans.add(createSpanWithOrigin(i, null));
      enrichedSpans.add(createEnrichedSpanWithOrigin(i, null));
      spansWithOrigin.add(createSpanWithOrigin(i, "some-origin"));
      enrichedSpansWithOrigin.add(createEnrichedSpanWithOrigin(i, "some-origin"));
    }
  }

  @TearDown
  public void finish() throws Exception {
    httpServer.stop();
  }

  @Benchmark
  public void writeAndFlushTraces() {
    writer.write(spans);
    writer.flush();
  }

  @Benchmark
  public void writeAndFlushEnrichedTraces() {
    writer.write(enrichedSpans);
    writer.flush();
  }

  @Benchmark
  public void writeAndFlushTracesWithOrigin() {
    writer.write(spansWithOrigin);
    writer.flush();
  }

  @Benchmark
  public void writeAndEnrichedTracesWithOrigin() {
    writer.write(enrichedSpansWithOrigin);
    writer.flush();
  }

  private DDSpan createEnrichedSpanWithOrigin(int iter, final String origin) {
    final DDSpan span = createSpanWithOrigin(iter, origin);
    span.setTag("some-tag-key", "some-tag-value");
    span.setMetric("some-metric-key", 1.0);
    return span;
  }

  private DDSpan createSpanWithOrigin(int iter, final String origin) {
    final DDId traceId = DDId.from(iter);
    final PendingTrace trace = tracer.createTrace(traceId);
    return DDSpan.create(
        System.currentTimeMillis() * 1000,
        new DDSpanContext(
            traceId,
            DDId.from(1000 + iter),
            DDId.ZERO,
            null,
            "service",
            "operation",
            "resource",
            PrioritySampling.SAMPLER_KEEP,
            origin,
            Collections.<String, String>emptyMap(),
            false,
            "type",
            0,
            trace));
  }
}
