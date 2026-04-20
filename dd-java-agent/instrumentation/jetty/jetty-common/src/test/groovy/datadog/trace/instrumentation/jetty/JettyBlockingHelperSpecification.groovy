package datadog.trace.instrumentation.jetty

import datadog.trace.api.gateway.Flow
import datadog.trace.api.internal.TraceSegment
import datadog.trace.test.util.DDSpecification
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response

import javax.servlet.ServletOutputStream

import static datadog.appsec.api.blocking.BlockingContentType.AUTO

class JettyBlockingHelperSpecification extends DDSpecification {
  def 'block completes successfully'() {
    setup:
    Request req = Mock()
    Response resp = Mock()
    ServletOutputStream os = Mock()
    TraceSegment seg = Mock()
    def rba = new Flow.Action.RequestBlockingAction(402, AUTO)

    when:
    JettyBlockingHelper.block(seg, req, resp, rba.getStatusCode(), rba.getBlockingContentType(), rba.getExtraHeaders(), rba.getSecurityResponseId())

    then:
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
