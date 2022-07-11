package datadog.trace.core.propagation


import datadog.trace.core.datastreams.DefaultPathwayContext
import datadog.trace.core.test.DDCoreSpecification

class ExtractedPathwayContextTest extends DDCoreSpecification {
  def "extract pathway context"() {
    setup:
    final pCtx = new DefaultPathwayContext(null, null)
    final ExtractedPathwayContext ctx = new ExtractedPathwayContext(pCtx)

    when:
    final extractedPCtx = ctx.getPathwayContext()

    then:
    extractedPCtx == pCtx
    ctx.getSpanId() == null
    ctx.getTraceId() == null
    ctx.baggageItems() == null
    ctx.getForwarded() == null
    ctx.getForwardedHost() == null
    ctx.getForwardedIp() == null
    ctx.getForwardedProto() == null
    ctx.getForwardedPort() == null
  }
}
