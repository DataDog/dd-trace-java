package datadog.trace.common.metrics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.trace.api.Config;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.monitor.TracerHealthMetrics;
import datadog.trace.core.test.DDCoreSpecification;
import datadog.trace.util.Strings;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MetricsReliabilityTest extends DDCoreSpecification {

  @Test
  void metricsShouldReliablyHandleMomentaryDowngrades() throws Exception {
    AtomicBoolean agentMetricsAvailable = new AtomicBoolean(true);
    AtomicInteger statsResponseCode = new AtomicInteger(200);
    AtomicBoolean receivedStats = new AtomicBoolean(false);
    AtomicBoolean receivedClientComputedHeader = new AtomicBoolean(false);
    AtomicReference<CountDownLatch> latchRef = new AtomicReference<>(new CountDownLatch(1));
    AtomicReference<String> hashRef = new AtomicReference<>();

    HttpServer agent = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    agent.createContext(
        "/info",
        exchange -> {
          String res =
              "{\"version\":\"7.65.0\",\"endpoints\":["
                  + (agentMetricsAvailable.get() ? "\"/v0.6/stats\", " : "")
                  + "\"/v0.4/traces\"], \"client_drop_p0s\" : true}";
          try {
            hashRef.set(Strings.sha256(res));
          } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
          }
          byte[] bytes = res.getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          OutputStream os = exchange.getResponseBody();
          os.write(bytes);
          os.close();
          latchRef.get().countDown();
        });
    agent.createContext(
        "/v0.6/stats",
        exchange -> {
          receivedStats.set(true);
          int code = statsResponseCode.get();
          exchange.sendResponseHeaders(code, -1);
          exchange.close();
        });
    agent.createContext(
        "/v0.4/traces",
        exchange -> {
          String header = exchange.getRequestHeaders().getFirst("Datadog-Client-Computed-Stats");
          receivedClientComputedHeader.set("true".equals(header));
          exchange
              .getResponseHeaders()
              .set("Datadog-Agent-State", hashRef.get() != null ? hashRef.get() : "");
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    agent.start();

    String agentUrl = "http://localhost:" + agent.getAddress().getPort();
    Properties props = new Properties();
    props.put("trace.agent.url", agentUrl);
    props.put("trace.stats.computation.enabled", "true");
    Config config = Config.get(props);
    SharedCommunicationObjects sharedComm = new SharedCommunicationObjects();
    sharedComm.createRemaining(config);
    datadog.communication.ddagent.DDAgentFeaturesDiscovery featuresDiscovery =
        sharedComm.featuresDiscovery(config);
    TracerHealthMetrics healthMetrics = new TracerHealthMetrics(StatsDClient.NO_OP);
    datadog.trace.core.CoreTracer tracer =
        tracerBuilder()
            .sharedCommunicationObjects(sharedComm)
            .healthMetrics(healthMetrics)
            .config(config)
            .build();

    try {
      // metrics enabled and discovery is performed
      featuresDiscovery.discover();
      latchRef.get().await();
      assertTrue(featuresDiscovery.supportsMetrics());

      // a span is published
      receivedStats.set(false);
      receivedClientComputedHeader.set(false);
      tracer.startSpan("test", "test").finish();
      tracer.flush();
      tracer.flushMetrics();

      // should have sent statistics and informed the agent that we calculate the stats
      assertTrue(receivedClientComputedHeader.get());
      assertTrue(receivedStats.get());
      assertMetrics(healthMetrics, 1, 1, 1, 0, 0);

      // simulate an agent downgrade
      agentMetricsAvailable.set(false);
      statsResponseCode.set(404);
      latchRef.set(new CountDownLatch(1));
      receivedStats.set(false);
      receivedClientComputedHeader.set(false);
      tracer.startSpan("test", "test").finish();
      tracer.flush();
      tracer.flushMetrics();

      // a discovery should have been done - we do not support anymore stats calculation
      latchRef.get().await();
      assertFalse(featuresDiscovery.supportsMetrics());
      assertMetrics(healthMetrics, 2, 2, 2, 0, 1);

      // reset counters and test without stats
      agentMetricsAvailable.set(false);
      statsResponseCode.set(200);
      latchRef.set(new CountDownLatch(1));
      receivedStats.set(false);
      receivedClientComputedHeader.set(false);
      tracer.startSpan("test", "test").finish();
      tracer.flush();
      tracer.flushMetrics();

      assertFalse(receivedClientComputedHeader.get());
      assertFalse(receivedStats.get());
      assertMetrics(healthMetrics, 2, 2, 2, 0, 1);

      // we detect that the agent can calculate the stats again
      agentMetricsAvailable.set(true);
      latchRef.set(new CountDownLatch(1));
      featuresDiscovery.discover();
      latchRef.get().await();
      assertTrue(featuresDiscovery.supportsMetrics());

      // a span is published
      receivedStats.set(false);
      receivedClientComputedHeader.set(false);
      tracer.startSpan("test", "test").finish();
      tracer.flush();
      tracer.flushMetrics();

      assertTrue(receivedClientComputedHeader.get());
      assertTrue(receivedStats.get());
      assertMetrics(healthMetrics, 3, 3, 3, 0, 1);

      // an error occurred on the agent stats endpoint
      statsResponseCode.set(500);
      receivedStats.set(false);
      receivedClientComputedHeader.set(false);
      tracer.startSpan("test", "test").finish();
      tracer.flush();
      tracer.flushMetrics();

      assertTrue(receivedClientComputedHeader.get());
      assertTrue(receivedStats.get());
      assertMetrics(healthMetrics, 4, 4, 4, 1, 1);

      // the next call succeed
      statsResponseCode.set(200);
      receivedStats.set(false);
      receivedClientComputedHeader.set(false);
      tracer.startSpan("test", "test").setError(true).finish();
      tracer.flush();
      tracer.flushMetrics();

      assertTrue(receivedClientComputedHeader.get());
      assertTrue(receivedStats.get());
      assertMetrics(healthMetrics, 5, 4, 5, 1, 1);

    } finally {
      tracer.close();
      agent.stop(0);
    }
  }

  private void assertMetrics(
      HealthMetrics healthMetrics,
      int traces,
      int drops,
      int requests,
      int errors,
      int downgrades) {
    String summary = healthMetrics.summary();
    assertTrue(summary.contains("clientStatsRequests=" + requests), "summary: " + summary);
    assertTrue(summary.contains("clientStatsErrors=" + errors), "summary: " + summary);
    assertTrue(summary.contains("clientStatsDowngrades=" + downgrades), "summary: " + summary);
    assertTrue(summary.contains("clientStatsP0DroppedSpans=" + drops), "summary: " + summary);
    assertTrue(summary.contains("clientStatsP0DroppedTraces=" + drops), "summary: " + summary);
    assertTrue(summary.contains("clientStatsProcessedSpans=" + traces), "summary: " + summary);
    assertTrue(summary.contains("clientStatsProcessedTraces=" + traces), "summary: " + summary);
  }
}
