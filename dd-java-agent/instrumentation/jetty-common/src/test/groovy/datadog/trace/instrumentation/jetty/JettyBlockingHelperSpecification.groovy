package datadog.trace.instrumentation.jetty

import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import spock.lang.Specification

import javax.servlet.ServletOutputStream

import static datadog.appsec.api.blocking.BlockingContentType.AUTO

// WARNING: Test is never found nor run
class JettyBlockingHelperSpecification extends Specification {
  def 'block completes successfully'() {
    setup:
    Request req = Mock()
    Response resp = Mock()
    ServletOutputStream os = Mock()
    TraceSegment seg = Mock()
    def rba = new Flow.Action.RequestBlockingAction(402, AUTO)
    RequestContext requestContext = Stub(RequestContext) {
      getTraceSegment() >> seg
      getBlockResponseFunction() >> rba
    }
    AgentSpan span = Stub(AgentSpan) {
      getRequestContext() >> requestContext
      getRequestBlockingAction() >> rba
    }

    when:
    JettyBlockingHelper.block(req, resp, span, rba.getStatusCode(), rba.getBlockingContentType(), rba.getExtraHeaders())

    then:
    false == true // Proof test is never run
    1 * resp.isCommitted() >> false
    1 * resp.setStatus(402)
    1 * req.getHeader('Accept') >> 'text/html'
    1 * resp.setHeader('Content-type', 'text/html;charset=utf-8')
    1 * resp.setHeader('Content-length', _)
    1 * resp.getOutputStream() >> os
    1 * os.write(_)
    1 * os.close()
    if (resp.respondsTo('complete')) {
      1 * resp.complete()
    } else {
      1 * resp.closeOutput()
    }
    1 * seg.effectivelyBlocked()
  }
}
