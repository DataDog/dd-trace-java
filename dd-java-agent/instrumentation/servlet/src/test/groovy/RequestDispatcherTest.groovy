
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.core.DDSpan

import javax.servlet.ServletException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE
import static datadog.trace.core.propagation.DatadogHttpCodec.SAMPLING_PRIORITY_KEY
import static datadog.trace.core.propagation.DatadogHttpCodec.SPAN_ID_KEY
import static datadog.trace.core.propagation.DatadogHttpCodec.TRACE_ID_KEY

class RequestDispatcherTest extends AgentTestRunner {

  def request = Mock(HttpServletRequest)
  def response = Mock(HttpServletResponse)
  def mockSpan = Stub(DDSpan)
  def dispatcher = new RequestDispatcherUtils(request, response)

  def "test dispatch no-parent"() {
    when:
    dispatcher.forward("")
    dispatcher.include("")

    then:
    2 * request.getAttribute(DD_SPAN_ATTRIBUTE)
    assertTraces(2) {
      trace(0, 1) {
        basicSpan(it, 0, "forward-child")
      }
      trace(1, 1) {
        basicSpan(it, 0, "include-child")
      }
    }
  }

  def "test dispatcher #method with parent"() {
    when:
    runUnderTrace("parent") {
      dispatcher."$method"(target)
    }

    then:
    1 * request.getAttribute(DD_SPAN_ATTRIBUTE)
    assertTraces(1) {
      trace(0, 3) {
        basicSpan(it, 0, "parent")
        span(1) {
          operationName "servlet.$operation"
          resourceName target
          childOf span(0)
          tags {
            "component" "java-web-servlet-dispatcher"
            defaultTags()
          }
        }
        basicSpan(it, 2, "$operation-child", span(1))
      }
    }

    then:
    1 * request.setAttribute(TRACE_ID_KEY, _)
    1 * request.setAttribute(SPAN_ID_KEY, _)
    1 * request.setAttribute(SAMPLING_PRIORITY_KEY, _)
    then:
    1 * request.getAttribute(DD_SPAN_ATTRIBUTE) >> mockSpan
    then:
    1 * request.setAttribute(DD_SPAN_ATTRIBUTE, { it.spanName.toString() == "servlet.$operation" })
    then:
    1 * request.setAttribute(DD_SPAN_ATTRIBUTE, mockSpan)

    where:
    operation | method
    "forward" | "forward"
    "forward" | "forwardNamed"
    "include" | "include"
    "include" | "includeNamed"

    target = "test-$method"
  }

  def "test dispatcher #method exception"() {
    setup:
    def ex = new ServletException("some error")
    def dispatcher = new RequestDispatcherUtils(request, response, ex)

    when:
    runUnderTrace("parent") {
      dispatcher."$method"(target)
    }

    then:
    def th = thrown(ServletException)
    th == ex

    1 * request.getAttribute(DD_SPAN_ATTRIBUTE)
    assertTraces(1) {
      trace(0, 3) {
        basicSpan(it, 0, "parent", null, ex)
        span(1) {
          operationName "servlet.$operation"
          resourceName target
          childOf span(0)
          errored true
          tags {
            "component" "java-web-servlet-dispatcher"
            defaultTags()
            errorTags(ex.class, ex.message)
          }
        }
        basicSpan(it, 2, "$operation-child", span(1))
      }
    }

    then:
    1 * request.setAttribute(TRACE_ID_KEY, _)
    1 * request.setAttribute(SPAN_ID_KEY, _)
    1 * request.setAttribute(SAMPLING_PRIORITY_KEY, _)
    then:
    1 * request.getAttribute(DD_SPAN_ATTRIBUTE) >> mockSpan
    then:
    1 * request.setAttribute(DD_SPAN_ATTRIBUTE, { it.spanName.toString() == "servlet.$operation" })
    then:
    1 * request.setAttribute(DD_SPAN_ATTRIBUTE, mockSpan)

    where:
    operation | method
    "forward" | "forward"
    "forward" | "forwardNamed"
    "include" | "include"
    "include" | "includeNamed"

    target = "test-$method"
  }
}
