package datadog.trace.common.metrics

import datadog.communication.ddagent.SharedCommunicationObjects
import datadog.trace.api.Config
import datadog.trace.core.test.DDCoreSpecification

import java.util.concurrent.CountDownLatch

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class MetricsReliabilityTest extends DDCoreSpecification {

  static class State {
    boolean agentMetricsAvailable
    boolean receivedStats
    boolean receivedClientComputedHeader
    CountDownLatch latch
    def reset(agentMetricsAvailable) {
      this.agentMetricsAvailable = agentMetricsAvailable
      receivedStats = false
      receivedClientComputedHeader = false
      latch = new CountDownLatch(1)
    }
  }

  static newAgent(State state) {
    httpServer {
      handlers {
        get("/info") {
          response.send('{"endpoints":[' + (state.agentMetricsAvailable ? '"/v0.6/stats", ' : '')
            + '"/v0.4/traces"]}')
          state.latch.countDown()
        }
        post("/v0.6/stats", {
          state.receivedStats = true
          response.status(state.agentMetricsAvailable ? 200 : 404).send()
        })
        put("/v0.4/traces", {
          state.receivedClientComputedHeader = "true" == request.getHeader('Datadog-Client-Computed-Stats')
          response.status(200).send()
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
    props.put("trace.tracer.metrics.enabled", "true")
    def config = Config.get(props)
    def sharedComm = new SharedCommunicationObjects()
    sharedComm.createRemaining(config)
    def featuresDiscovery = sharedComm.featuresDiscovery(config)
    def tracer = tracerBuilder().sharedCommunicationObjects(sharedComm).config(config).build()

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

    when: "simulate an agent downgrade"
    state.reset(false)
    tracer.startSpan("test", "test").finish()
    tracer.flush()
    tracer.flushMetrics()

    then: "a discovery should have done - we do not support anymore stats calculation"
    state.latch.await()
    assert !featuresDiscovery.supportsMetrics()

    when: "a span is published"
    tracer.startSpan("test", "test").finish()
    tracer.flush()
    tracer.flushMetrics()

    then: "should have not sent statistics and informed the agent that we don't calculate the stats anymore"
    assert !state.receivedClientComputedHeader
    assert !state.receivedStats

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

    cleanup:
    tracer.close()
    agent.stop()
  }
}
