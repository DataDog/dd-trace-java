import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.instrumentation.netty41.client.HttpClientTracingHandler
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.HttpClientCodec

class Netty41PipelineTest extends InstrumentationSpecification {

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
    pipeline.names() == [
      "HttpClientCodec#0",
      "HttpClientTracingHandler#0",
      "DefaultChannelPipeline\$TailContext#0"
    ]
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
    pipeline.names() == [
      "$SimpleHandler.name#0",
      "http",
      "HttpClientTracingHandler#0",
      "$OtherSimpleHandler.name#0",
      "DefaultChannelPipeline\$TailContext#0"
    ]
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

  def "when a traced handler is added from an initializer we still detect it and add our channel handlers"() {
    // This test method replicates a scenario similar to how reactor 0.8.x register the `HttpClientCodec` handler
    // into the pipeline.

    setup:
    def channel = new EmbeddedChannel()

    when:
    channel.pipeline().addLast(new TracedHandlerFromInitializerHandler())

    then:
    null != channel.pipeline().remove("added_in_initializer")
    null != channel.pipeline().remove(HttpClientTracingHandler)
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

  class TracedHandlerFromInitializerHandler extends ChannelInitializer<Channel> implements ChannelHandler {
    @Override
    protected void initChannel(Channel ch) throws Exception {
      // This replicates how reactor 0.8.x add the HttpClientCodec
      ch.pipeline().addLast("added_in_initializer", new HttpClientCodec())
    }
  }
}
