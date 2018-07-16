import com.fasterxml.jackson.databind.JsonNode
import stackstate.opentracing.STSSpan
import stackstate.opentracing.STSSpanContext
import stackstate.opentracing.STSTracer
import stackstate.opentracing.PendingTrace
import stackstate.trace.api.sampling.PrioritySampling
import stackstate.trace.common.writer.STSAgentWriter
import stackstate.trace.common.writer.STSApi
import stackstate.trace.common.writer.ListWriter
import spock.lang.Specification

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class STSApiIntegrationTest {
  static class STSApiIntegrationV4Test extends Specification {
    static final WRITER = new ListWriter()
    static final TRACER = new STSTracer(WRITER)
    static final CONTEXT = new STSSpanContext(
      1L,
      1L,
      0L,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      Collections.emptyMap(),
      false,
      "fakeType",
      Collections.emptyMap(),
      new PendingTrace(TRACER, 1L),
      TRACER)

    def api = new STSApi(STSAgentWriter.DEFAULT_HOSTNAME, STSAgentWriter.DEFAULT_PORT, v4())

    def endpoint = new AtomicReference<String>(null)
    def agentResponse = new AtomicReference<String>(null)

    STSApi.ResponseListener responseListener = { String receivedEndpoint, JsonNode responseJson ->
      endpoint.set(receivedEndpoint)
      agentResponse.set(responseJson.toString())
    }

    def setup() {
      api.addResponseListener(responseListener)
    }

    boolean v4() {
      return true
    }

    def "Sending traces succeeds (test #test)"() {
      expect:
      api.sendTraces(traces)
      if (v4()) {
        endpoint.get() == "http://localhost:8126/v0.4/traces"
        agentResponse.get() == '{"rate_by_service":{"service:,env:":1}}'
      }

      where:
      traces                                                                               | test
      []                                                                                   | 1
      [[], []]                                                                             | 2
      [[new STSSpan(1, CONTEXT)]]                                                          | 3
      [[new STSSpan(TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis()), CONTEXT)]] | 4
    }

    def "Sending bad trace fails (test #test)"() {
      expect:
      api.sendTraces(traces) == false

      where:
      traces         | test
      [""]           | 1
      ["", 123]      | 2
      [[:]]          | 3
      [new Object()] | 4
    }
  }

  static class STSApiIntegrationV3Test extends STSApiIntegrationV4Test {
    boolean v4() {
      return false
    }

    def cleanup() {
      assert endpoint.get() == null
      assert agentResponse.get() == null
    }
  }
}
