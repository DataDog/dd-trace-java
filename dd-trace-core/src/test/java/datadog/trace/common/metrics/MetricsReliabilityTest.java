package datadog.trace.common.metrics;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.trace.agent.test.server.http.JavaTestHttpServer;
import datadog.trace.api.Config;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.monitor.TracerHealthMetrics;
import datadog.trace.util.Strings;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class MetricsReliabilityTest extends DDCoreJavaSpecification {

  @Test
  void metricsShouldReliablyHandleMomentaryDowngrades() throws Exception {
    State state = new State();
    state.reset(true);
    JavaTestHttpServer agent = newAgent(state);
    Properties props = new Properties();
    props.put("trace.agent.url", agent.getAddress().toString());
    props.put("trace.stats.computation.enabled", "true");
    Config config = Config.get(props);
    SharedCommunicationObjects sharedComm = new SharedCommunicationObjects();
    sharedComm.createRemaining(config);
    DDAgentFeaturesDiscovery featuresDiscovery = sharedComm.featuresDiscovery(config);
    TracerHealthMetrics healthMetrics = new TracerHealthMetrics(StatsDClient.NO_OP);
    CoreTracer tracer =
        tracerBuilder()
            .sharedCommunicationObjects(sharedComm)
            .healthMetrics(healthMetrics)
            .config(config)
            .build();

    try {
      // metrics enabled and discovery is performed
      featuresDiscovery.discover();

      // should support metrics
      assertTrue(state.latch.await(10, SECONDS));
      assertTrue(featuresDiscovery.supportsMetrics());

      // a span is published
      tracer.startSpan("test", "test").finish();
      tracer.flush();
      tracer.flushMetrics();

      // should have sent statistics and informed the agent that we calculate the stats
      assertTrue(state.receivedClientComputedHeader);
      assertTrue(state.receivedStats);
      // 1 trace processed. 1 p0 drop No errors
      assertMetrics(healthMetrics, 1, 1, 1, 0, 0);

      // simulate an agent downgrade
      state.reset(false, 404);
      tracer.startSpan("test", "test").finish();
      tracer.flush();
      tracer.flushMetrics();

      // a discovery should have done - we do not support anymore stats calculation
      assertTrue(state.latch.await(10, SECONDS));
      assertFalse(featuresDiscovery.supportsMetrics());
      // 2 traces processed. 2 p0 dropped. 2 requests and 1 downgrade no errors
      assertMetrics(healthMetrics, 2, 2, 2, 0, 1);

      // a span is published (we have a call to stats for the downgrade so reset the counter)
      state.reset(false);
      tracer.startSpan("test", "test").finish();
      tracer.flush();
      tracer.flushMetrics();

      // then: should have not sent statistics and informed the agent that we don't calculate the
      // stats anymore
      assertFalse(state.receivedClientComputedHeader);
      assertFalse(state.receivedStats);
      // 2 traces processed. 1 p0 dropped. 2 requests and 1 downgrade no errors
      assertMetrics(healthMetrics, 2, 2, 2, 0, 1);

      // we detect that the agent can calculate the stats again
      state.reset(true);
      featuresDiscovery.discover();

      // we should understand it
      assertTrue(state.latch.await(10, SECONDS));
      assertTrue(featuresDiscovery.supportsMetrics());

      // a span is published
      tracer.startSpan("test", "test").finish();
      tracer.flush();
      tracer.flushMetrics();

      // we should have sent the stats and informed the agent to not calculate the stats on
      // the trace payload
      assertTrue(state.receivedClientComputedHeader);
      assertTrue(state.receivedStats);
      // 3 traces processed. 2 p0 dropped. 3 requests and 1 downgrade no errors
      assertMetrics(healthMetrics, 3, 3, 3, 0, 1);

      // an error occurred on the agent stats endpoint
      state.reset(true, 500);
      tracer.startSpan("test", "test").finish();
      tracer.flush();
      tracer.flushMetrics();

      // the error counter is incremented
      assertTrue(state.receivedClientComputedHeader);
      assertTrue(state.receivedStats);
      // 4 traces processed. 3 p0 dropped. 4 requests and 1 downgrade - 1 error
      assertMetrics(healthMetrics, 4, 4, 4, 1, 1);

      // the next call succeed
      state.reset(true);
      tracer.startSpan("test", "test").setError(true).finish();
      tracer.flush();
      tracer.flushMetrics();

      // the request counter is incremented
      assertTrue(state.receivedClientComputedHeader);
      assertTrue(state.receivedStats);
      // 5 traces processed. 3 p0 dropped (this one is errored so it's not dropped).
      // 5 requests and 1 downgrade - 1 error
      assertMetrics(healthMetrics, 5, 4, 5, 1, 1);

    } finally {
      tracer.close();
      agent.stop();
    }
  }

  private static JavaTestHttpServer newAgent(State state) {
    return JavaTestHttpServer.httpServer(
        server ->
            server.handlers(
                h -> {
                  h.get(
                      "/info",
                      api -> {
                        String res =
                            "{\"version\":\"7.65.0\",\"endpoints\":["
                                + (state.agentMetricsAvailable ? "\"/v0.6/stats\", " : "")
                                + "\"/v0.4/traces\"], \"client_drop_p0s\" : true}";
                        try {
                          state.hash = Strings.sha256(res);
                        } catch (NoSuchAlgorithmException e) {
                          throw new RuntimeException(e);
                        }
                        api.getResponse().status(200).send(res);
                        state.latch.countDown();
                      });
                  h.post(
                      "/v0.6/stats",
                      api -> {
                        state.receivedStats = true;
                        api.getResponse().status(state.statsResponseCode).send();
                      });
                  h.put(
                      "/v0.4/traces",
                      api -> {
                        state.receivedClientComputedHeader =
                            "true"
                                .equals(
                                    api.getRequest().getHeader("Datadog-Client-Computed-Stats"));
                        api.getResponse()
                            .status(200)
                            .addHeader("Datadog-Agent-State", state.hash)
                            .send();
                      });
                }));
  }

  private static void assertMetrics(
      HealthMetrics healthMetrics,
      int traces,
      int drops,
      int requests,
      int errors,
      int downgrades) {
    String summary = healthMetrics.summary();
    assertTrue(
        summary.contains("clientStatsRequests=" + requests),
        "clientStatsRequests mismatch in: " + summary);
    assertTrue(
        summary.contains("clientStatsErrors=" + errors),
        "clientStatsErrors mismatch in: " + summary);
    assertTrue(
        summary.contains("clientStatsDowngrades=" + downgrades),
        "clientStatsDowngrades mismatch in: " + summary);
    assertTrue(
        summary.contains("clientStatsP0DroppedSpans=" + drops),
        "clientStatsP0DroppedSpans mismatch in: " + summary);
    assertTrue(
        summary.contains("clientStatsP0DroppedTraces=" + drops),
        "clientStatsP0DroppedTraces mismatch in: " + summary);
    assertTrue(
        summary.contains("clientStatsProcessedSpans=" + traces),
        "clientStatsProcessedSpans mismatch in: " + summary);
    assertTrue(
        summary.contains("clientStatsProcessedTraces=" + traces),
        "clientStatsProcessedTraces mismatch in: " + summary);
  }

  private static class State {
    volatile boolean agentMetricsAvailable = true;
    volatile int statsResponseCode = 200;
    volatile boolean receivedStats;
    volatile boolean receivedClientComputedHeader;
    volatile CountDownLatch latch;
    volatile String hash;

    void reset(boolean agentMetricsAvailable) {
      reset(agentMetricsAvailable, 200);
    }

    void reset(boolean agentMetricsAvailable, int statsResponseCode) {
      this.agentMetricsAvailable = agentMetricsAvailable;
      this.statsResponseCode = statsResponseCode;
      receivedStats = false;
      receivedClientComputedHeader = false;
      latch = new CountDownLatch(1);
    }
  }
}
