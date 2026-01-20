import datadog.context.Context
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.instrumentation.netty41.AttributeKeys
import datadog.trace.instrumentation.netty41.server.HttpServerResponseTracingHandler
import datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http2.DefaultHttp2Headers
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame

class Netty41Http2StatusPropagationTest extends InstrumentationSpecification {

  def "http2 headers frame sets status on netty server span"() {
    setup:
    def channel = new EmbeddedChannel(HttpServerResponseTracingHandler.INSTANCE)
    def request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test")
    def context = NettyHttpServerDecorator.DECORATE.startSpan(request, Context.root())
    channel.attr(AttributeKeys.CONTEXT_ATTRIBUTE_KEY).set(context)

    when:
    def headers = new DefaultHttp2Headers().status("200")
    channel.writeAndFlush(new DefaultHttp2HeadersFrame(headers, true))

    then:
    assertTraces(1) {
      trace(1) {
        span {
          tags { "http.status_code" 200 }
        }
      }
    }

    cleanup:
    channel.finishAndReleaseAll()
  }
}