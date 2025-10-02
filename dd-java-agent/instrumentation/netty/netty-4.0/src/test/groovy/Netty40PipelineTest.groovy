import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.instrumentation.netty40.client.HttpClientTracingHandler
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.HttpClientCodec

class Netty40PipelineTest extends InstrumentationSpecification {

  def "when a handler is added to the netty pipeline we add our tracing handler"() {
    setup:
    def channel = new EmbeddedChannel()
    def pipeline = channel.pipeline()

    when:
    pipeline.addFirst("name", new HttpClientCodec())

    then:
    pipeline.get(HttpClientTracingHandler) != null

    when:
    pipeline.remove("name")

    then: "We leave it around to avoid disrupting in-flight processing"
    def tracingHandler = pipeline.get(HttpClientTracingHandler)
    tracingHandler != null

    when:
    pipeline.addLast(new HttpClientCodec())

    then: "we remove the previous handler and add a new one"
    pipeline.get(HttpClientTracingHandler) != null
    pipeline.get(HttpClientTracingHandler) != tracingHandler
    // spotless:off
    pipeline.names() == [
      "EmbeddedChannel\$LastInboundHandler#0",
      "HttpClientCodec#0",
      "$HttpClientTracingHandler.name",
      "DefaultChannelPipeline\$TailContext#0"] ||
    pipeline.names() == [
      "HttpClientCodec#0",
      "$HttpClientTracingHandler.name",
      "DefaultChannelPipeline\$TailContext#0"
    ]
    // spotless:on
  }

  def "when a handler is added to the netty pipeline we add ONLY ONE tracing handler"() {
    setup:
    def channel = new EmbeddedChannel()
    def pipeline = channel.pipeline()

    when:
    pipeline.addLast("name", new HttpClientCodec())
    // The first one returns the removed tracing handler
    pipeline.remove(HttpClientTracingHandler)
    // There is only one
    pipeline.remove(HttpClientTracingHandler) == null

    then:
    thrown NoSuchElementException
  }

  def "handlers of different types can be added"() {
    setup:
    def channel = new EmbeddedChannel()
    def pipeline = channel.pipeline()

    when:
    pipeline.addLast("some_handler", new SimpleHandler())
    pipeline.addLast("a_traced_handler", new HttpClientCodec())

    then:
    // The first one returns the removed tracing handler
    null != pipeline.remove(HttpClientTracingHandler)
    null != pipeline.remove("some_handler")
    null != pipeline.remove("a_traced_handler")
  }

  def "tracing handlers added in the correct position in the pipeline"() {
    setup:
    def channel = new EmbeddedChannel()
    def pipeline = channel.pipeline()

    when:
    pipeline.addLast(new SimpleHandler(), new OtherSimpleHandler())
    pipeline.addAfter("$SimpleHandler.name#0", "http", new HttpClientCodec())

    then:
    // spotless:off
    pipeline.names() == [
      "EmbeddedChannel\$LastInboundHandler#0",
      "$SimpleHandler.name#0",
      "http",
      "$HttpClientTracingHandler.name",
      "$OtherSimpleHandler.name#0",
      "DefaultChannelPipeline\$TailContext#0"] ||
    pipeline.names() == [
      "$SimpleHandler.name#0",
      "http",
      "$HttpClientTracingHandler.name",
      "$OtherSimpleHandler.name#0",
      "DefaultChannelPipeline\$TailContext#0"
    ]
    // spotless:on
  }

  def "calling pipeline.addLast methods that use overloaded methods does not cause infinite loop"() {
    setup:
    def channel = new EmbeddedChannel()

    when:
    channel.pipeline().addLast(new SimpleHandler(), new OtherSimpleHandler())

    then:
    null != channel.pipeline().remove("$SimpleHandler.name#0")
    null != channel.pipeline().remove("$OtherSimpleHandler.name#0")
  }

  class SimpleHandler implements ChannelHandler {
    @Override
    void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    }

    @Override
    void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    }

    @Override
    void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    }
  }

  class OtherSimpleHandler implements ChannelHandler {
    @Override
    void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    }

    @Override
    void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    }

    @Override
    void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    }
  }
}
