import datadog.appsec.api.blocking.Blocking
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingNettyHttpNamingConventions
import datadog.trace.bootstrap.instrumentation.api.URIUtils
import datadog.trace.instrumentation.netty40.server.NettyHttpServerDecorator
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelPipeline
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaders
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpRequestDecoder
import io.netty.handler.codec.http.HttpResponseEncoder
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.util.CharsetUtil
import spock.lang.Ignore

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.USER_BLOCK
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE
import static io.netty.handler.codec.http.HttpHeaders.is100ContinueExpected
import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1

abstract class Netty40ServerTest extends HttpServerTest<EventLoopGroup> {

  static final LoggingHandler LOGGING_HANDLER = new LoggingHandler(SERVER_LOGGER.name, LogLevel.DEBUG)

  private class NettyServer implements HttpServer {
    final eventLoopGroup = new NioEventLoopGroup()
    int port = 0

    @Override
    void start() {
      ServerBootstrap bootstrap = new ServerBootstrap()
        .group(eventLoopGroup)
        .handler(LOGGING_HANDLER)
        .childHandler([
          initChannel: { ch ->
            ChannelPipeline pipeline = ch.pipeline()
            pipeline.addFirst("logger", LOGGING_HANDLER)

            def handlers = [new HttpRequestDecoder(), new HttpResponseEncoder()]
            handlers.each { pipeline.addLast(it) }
            pipeline.addLast([
              channelRead0       : { ChannelHandlerContext ctx, msg ->
                if (msg instanceof HttpRequest) {
                  def request = msg as HttpRequest
                  if (is100ContinueExpected(request)) {
                    ctx.write(new DefaultFullHttpResponse(HTTP_1_1, CONTINUE))
                  }
                  def uri = URIUtils.safeParse(request.uri)
                  if (uri == null) {
                    ctx.write(new DefaultFullHttpResponse(request.protocolVersion, HttpResponseStatus.BAD_REQUEST))
                    return
                  }
                  HttpServerTest.ServerEndpoint endpoint = HttpServerTest.ServerEndpoint.forPath(uri.path)
                  ctx.write controller(endpoint) {
                    ByteBuf content = null
                    FullHttpResponse response = null
                    switch (endpoint) {
                      case SUCCESS:
                      case ERROR:
                        content = Unpooled.copiedBuffer(endpoint.body, CharsetUtil.UTF_8)
                        response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(endpoint.status), content)
                        break
                      case FORWARDED:
                        content = Unpooled.copiedBuffer(request.headers().get("x-forwarded-for"), CharsetUtil.UTF_8)
                        response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(endpoint.status), content)
                        break
                      case QUERY_ENCODED_BOTH:
                      case QUERY_ENCODED_QUERY:
                      case QUERY_PARAM:
                        content = Unpooled.copiedBuffer(endpoint.bodyForQuery(uri.query), CharsetUtil.UTF_8)
                        response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(endpoint.status), content)
                        break
                      case REDIRECT:
                        response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(endpoint.status))
                        response.headers().set(HttpHeaders.Names.LOCATION, endpoint.body)
                        break
                      case EXCEPTION:
                        throw new Exception(endpoint.body)
                      case USER_BLOCK:
                        Blocking.forUser('user-to-block').blockIfMatch()
                      // should never be output:
                        response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(200))
                        response.content = 'should never be reached'
                        break
                      default:
                        content = Unpooled.copiedBuffer(NOT_FOUND.body, CharsetUtil.UTF_8)
                        response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(NOT_FOUND.status), content)
                        break
                    }
                    response.headers().set(CONTENT_TYPE, "text/plain")
                    response.headers().set(IG_RESPONSE_HEADER, IG_RESPONSE_HEADER_VALUE)
                    if (content) {
                      response.headers().set(CONTENT_LENGTH, content.readableBytes())
                    }
                    return response
                  }
                }
              },
              exceptionCaught    : { ChannelHandlerContext ctx, Throwable cause ->
                ByteBuf content = Unpooled.copiedBuffer(cause.message, CharsetUtil.UTF_8)
                FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR, content)
                response.headers().set(CONTENT_TYPE, "text/plain")
                response.headers().set(CONTENT_LENGTH, content.readableBytes())
                ctx.write(response)
              },
              channelReadComplete: {
                it.flush()
              }
            ] as SimpleChannelInboundHandler)
          }
        ] as ChannelInitializer).channel(NioServerSocketChannel)
      def channel = bootstrap.bind(port).sync().channel()
      port = ((InetSocketAddress) channel.localAddress()).port
    }

    @Override
    void stop() {
      eventLoopGroup.shutdownGracefully()
    }

    @Override
    URI address() {
      return new URI("http://localhost:$port/")
    }
  }

  @Override
  HttpServer server() {
    return new NettyServer()
  }

  @Override
  String component() {
    NettyHttpServerDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    operation()
  }

  @Override
  protected boolean enabledFinishTimingChecks() {
    true
  }

  @Override
  boolean testBlocking() {
    true
  }

  @Override
  boolean testBlockingOnResponse() {
    true
  }

  @Ignore("https://github.com/DataDog/dd-trace-java/pull/5213")
  @Override
  boolean testBadUrl() {
    false
  }
}

class Netty40ServerV0Test extends Netty40ServerTest implements TestingNettyHttpNamingConventions.ServerV0 {
}

class Netty40ServerV1ForkedTest extends Netty40ServerTest implements TestingNettyHttpNamingConventions.ServerV1 {
}
