import com.fasterxml.jackson.databind.JsonNode
import stackstate.opentracing.DDSpan
import stackstate.opentracing.DDSpanContext
import stackstate.opentracing.DDTracer
import stackstate.opentracing.PendingTrace
import stackstate.trace.common.Service
import stackstate.trace.common.sampling.PrioritySampling
import stackstate.trace.common.writer.DDAgentWriter
import stackstate.trace.common.writer.DDApi
import stackstate.trace.common.writer.ListWriter
import spock.lang.Specification
import spock.lang.Unroll

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DDApiIntegrationTest {
  static class DDApiIntegrationV4Test extends Specification {
    static final WRITER = new ListWriter()
    static final TRACER = new DDTracer(WRITER)
    static final CONTEXT = new DDSpanContext(
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

    def api = new DDApi(DDAgentWriter.DEFAULT_HOSTNAME, DDAgentWriter.DEFAULT_PORT, v4())

    def endpoint = new AtomicReference<String>(null)
    def agentResponse = new AtomicReference<String>(null)

    DDApi.ResponseListener responseListener = { String receivedEndpoint, JsonNode responseJson ->
      endpoint.set(receivedEndpoint)
      agentResponse.set(responseJson.toString())
    }

    def setup() {
      api.addResponseListener(responseListener)
    }

    boolean v4() {
      return true
    }

    @Unroll
    def "Sending traces succeeds (test #test)"() {
      expect:
      api.sendTraces(traces)
      if (v4()) {
        endpoint.get() == "http://localhost:8126/v0.4/traces"
        agentResponse.get() == '{"rate_by_service":{"service:,env:":1}}'
      }

      where:
      traces                                                                              | test
      []                                                                                  | 1
      [[], []]                                                                            | 2
      [[new DDSpan(1, CONTEXT)]]                                                          | 3
      [[new DDSpan(TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis()), CONTEXT)]] | 4
    }

    def "Sending services succeeds"() {
      expect:
      api.sendServices(services)
      endpoint.get() == null
      agentResponse.get() == null

      where:
      services                                                     | _
      [:]                                                          | _
      ['app': new Service("name", "appName", Service.AppType.WEB)] | _
    }

    @Unroll
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

  static class DDApiIntegrationV3Test extends DDApiIntegrationV4Test {
    boolean v4() {
      return false
    }

    def cleanup() {
      assert endpoint.get() == null
      assert agentResponse.get() == null
    }
  }
}
