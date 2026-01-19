package datadog.trace.common.metrics

import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.trace.api.Config
import datadog.metrics.statsd.StatsDClient
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.core.monitor.TracerHealthMetrics
import datadog.trace.core.test.DDCoreSpecification
import datadog.trace.util.Strings

import java.util.concurrent.CountDownLatch

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class MetricsReliabilityTest extends DDCoreSpecification {

  static class State {
    boolean agentMetricsAvailable = true
    int statsResponseCode = 200
    boolean receivedStats
    boolean receivedClientComputedHeader
    CountDownLatch latch
    String hash

    def reset(agentMetricsAvailable, statsResponseCode = 200) {
      this.agentMetricsAvailable = agentMetricsAvailable
      this.statsResponseCode = statsResponseCode
      receivedStats = false
      receivedClientComputedHeader = false
      latch = new CountDownLatch(1)
    }
  }

  static newAgent(State state) {
    httpServer {
      handlers {
        get("/info") {
          final def res = '{"version":"7.65.0","endpoints":[' + (state.agentMetricsAvailable ? '"/v0.6/stats", ' : '') + '"/v0.4/traces"], "client_drop_p0s" : true}'
          state.hash = Strings.sha256(res)
          response.send(res)
          state.latch.countDown()
        }
        post("/v0.6/stats", {
          state.receivedStats = true
          response.status(state.statsResponseCode).send()
        })
        put("/v0.4/traces", {
          state.receivedClientComputedHeader = "true" == request.getHeader('Datadog-Client-Computed-Stats')
          response.status(200).addHeader("Datadog-Agent-State", state.hash).send()
        })
      }
    }
  }

  def "metrics should reliably handle momentary downgrades"() {
    setup:
    def state = new State()
    state.reset(true)
    def agent = newAgent(state)
    agent.start()
    def props = new Properties()
    props.put("trace.agent.url", agent.getAddress().toString())
    props.put("trace.stats.computation.enabled", "true")
    def config = Config.get(props)
    def sharedComm = new SharedCommunicationObjects()
    sharedComm.createRemaining(config)
    def featuresDiscovery = sharedComm.featuresDiscovery(config)
    def healthMetrics = new TracerHealthMetrics(StatsDClient.NO_OP)
    def tracer = tracerBuilder().sharedCommunicationObjects(sharedComm).healthMetrics(healthMetrics).config(config).build()
    when: "metrics enabled and discovery is performed"
    featuresDiscovery.discover()

    then: "should support metrics"
    state.latch.await()
    assert featuresDiscovery.supportsMetrics()

    when: "a span is published"
    tracer.startSpan("test", "test").finish()
    tracer.flush()
    tracer.flushMetrics()

    then: "should have sent statistics and informed the agent that we calculate the stats"
    assert state.receivedClientComputedHeader
    assert state.receivedStats
    // 1 trace processed. 1 p0 drop No errors
    assertMetrics(healthMetrics, 1, 1, 1, 0, 0)


    when: "simulate an agent downgrade"
    state.reset(false, 404)
    tracer.startSpan("test", "test").finish()
    tracer.flush()
    tracer.flushMetrics()

    then: "a discovery should have done - we do not support anymore stats calculation"
    state.latch.await()
    assert !featuresDiscovery.supportsMetrics()
    // 2 traces processed. 2 p0 dropped. 2 requests and 1 downgrade no errors
    assertMetrics(healthMetrics, 2, 2, 2, 0, 1)


    when: "a span is published"
    state.reset(false) // we have a call to stats for the downgrade so let's reset the counter
    tracer.startSpan("test", "test").finish()
    tracer.flush()
    tracer.flushMetrics()

    then: "should have not sent statistics and informed the agent that we don't calculate the stats anymore"
    assert !state.receivedClientComputedHeader
    assert !state.receivedStats
    // 2 traces processed. 1 p0 dropped. 2 requests and 1 downgrade no errors
    assertMetrics(healthMetrics, 2, 2, 2, 0, 1)

    when: "we detect that the agent can calculate the stats again"
    state.reset(true)
    featuresDiscovery.discover()

    then: "we should understand it"
    state.latch.await()
    assert featuresDiscovery.supportsMetrics()

    when: "a span is published"
    tracer.startSpan("test", "test").finish()
    tracer.flush()
    tracer.flushMetrics()

    then: "we should have sent the stats and informed the agent to not calculate the stats on the trace payload"
    assert state.receivedClientComputedHeader
    assert state.receivedStats
    // 3 traces processed. 2 p0 dropped. 3 requests and 1 downgrade no errors
    assertMetrics(healthMetrics, 3, 3, 3, 0, 1)

    when: "an error occurred on the agent stats endpoint"
    state.reset(true, 500)
    tracer.startSpan("test", "test").finish()
    tracer.flush()
    tracer.flushMetrics()

    then: "the error counter is incremented"
    assert state.receivedClientComputedHeader
    assert state.receivedStats
    // 4 traces processed. 3 p0 dropped. 4 requests and 1 downgrade - 1 error
    assertMetrics(healthMetrics, 4, 4, 4, 1, 1)

    when: "the next call succeed"
    state.reset(true)
    tracer.startSpan("test", "test").setError(true).finish()
    tracer.flush()
    tracer.flushMetrics()

    then: "the request counter is incremented"
    assert state.receivedClientComputedHeader
    assert state.receivedStats
    // 5 traces processed. 3 p0 dropped (this one is errored so it's not dropped).
    // 5 requests and 1 downgrade - 1 error
    assertMetrics(healthMetrics, 5, 4, 5, 1, 1)

    cleanup:
    tracer.close()
    agent.stop()
  }

  void assertMetrics(HealthMetrics healthMetrics, int traces, int drops, int requests, int errors, int downgrades) {
    def summary = healthMetrics.summary()
    assert summary.contains("clientStatsRequests=$requests")
    assert summary.contains("clientStatsErrors=$errors")
    assert summary.contains("clientStatsDowngrades=$downgrades")
    assert summary.contains("clientStatsP0DroppedSpans=$drops")
    assert summary.contains("clientStatsP0DroppedTraces=$drops")
    assert summary.contains("clientStatsProcessedSpans=$traces")
    assert summary.contains("clientStatsProcessedTraces=$traces")
  }
}
