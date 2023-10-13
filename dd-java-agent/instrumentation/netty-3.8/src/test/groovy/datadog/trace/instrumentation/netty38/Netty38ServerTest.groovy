package datadog.trace.instrumentation.netty38


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
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.forPath
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.LOCATION
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1

import datadog.appsec.api.blocking.Blocking
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingNettyHttpNamingConventions
import datadog.trace.bootstrap.instrumentation.api.URIUtils
import datadog.trace.instrumentation.netty38.server.NettyHttpServerDecorator
import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.buffer.ChannelBuffer
import org.jboss.netty.buffer.ChannelBuffers
import org.jboss.netty.channel.ChannelHandlerContext
import org.jboss.netty.channel.ChannelPipeline
import org.jboss.netty.channel.ChannelPipelineFactory
import org.jboss.netty.channel.DefaultChannelPipeline
import org.jboss.netty.channel.DownstreamMessageEvent
import org.jboss.netty.channel.ExceptionEvent
import org.jboss.netty.channel.FailedChannelFuture
import org.jboss.netty.channel.MessageEvent
import org.jboss.netty.channel.SimpleChannelHandler
import org.jboss.netty.channel.SucceededChannelFuture
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.handler.codec.http.DefaultHttpResponse
import org.jboss.netty.handler.codec.http.HttpHeaders
import org.jboss.netty.handler.codec.http.HttpRequest
import org.jboss.netty.handler.codec.http.HttpResponse
import org.jboss.netty.handler.codec.http.HttpResponseStatus
import org.jboss.netty.handler.codec.http.HttpServerCodec
import org.jboss.netty.handler.logging.LoggingHandler
import org.jboss.netty.logging.InternalLogLevel
import org.jboss.netty.logging.InternalLoggerFactory
import org.jboss.netty.logging.Slf4JLoggerFactory
import org.jboss.netty.util.CharsetUtil
import spock.lang.Ignore

abstract class Netty38ServerTest extends HttpServerTest<ServerBootstrap> {

  static final LoggingHandler LOGGING_HANDLER
  static {
    InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory())
    LOGGING_HANDLER = new LoggingHandler(SERVER_LOGGER.name, InternalLogLevel.DEBUG, true)
  }

  ChannelPipeline channelPipeline() {
    ChannelPipeline channelPipeline = new DefaultChannelPipeline()
    channelPipeline.addFirst("logger", LOGGING_HANDLER)

    channelPipeline.addLast("http-codec", new HttpServerCodec())
    channelPipeline.addLast("controller", new SimpleChannelHandler() {
        @Override
        void messageReceived(ChannelHandlerContext ctx, MessageEvent msg) throws Exception {
          if (msg.getMessage() instanceof HttpRequest) {
            def request = msg.getMessage() as HttpRequest
            if (HttpHeaders.is100ContinueExpected(request)) {
              ctx.sendDownstream(new DownstreamMessageEvent(ctx.getChannel(),
                new SucceededChannelFuture(ctx.getChannel()), new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.CONTINUE),
                ctx.getChannel().getRemoteAddress()))
            }
            def uri = URIUtils.safeParse(request.uri)
            if (uri == null) {
              ctx.sendDownstream(new DownstreamMessageEvent(ctx.getChannel(),
                new SucceededChannelFuture(ctx.getChannel()), new DefaultHttpResponse(request.protocolVersion, HttpResponseStatus.BAD_REQUEST),
                ctx.getChannel().getRemoteAddress()))
              return
            }
            HttpServerTest.ServerEndpoint endpoint = forPath(uri.rawPath)
            ctx.sendDownstream controller(endpoint) {
              HttpResponse response
              ChannelBuffer responseContent = null
              switch (endpoint) {
                case SUCCESS:
                case ERROR:
                  responseContent = ChannelBuffers.copiedBuffer(endpoint.body, CharsetUtil.UTF_8)
                  response = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(endpoint.status))
                  response.setContent(responseContent)
                  break
                case FORWARDED:
                  responseContent = ChannelBuffers.copiedBuffer(request.headers().get("x-forwarded-for"), CharsetUtil.UTF_8)
                  response = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(endpoint.status))
                  response.setContent(responseContent)
                  break
                case QUERY_ENCODED_BOTH:
                case QUERY_ENCODED_QUERY:
                case QUERY_PARAM:
                  responseContent = ChannelBuffers.copiedBuffer(endpoint.bodyForQuery(uri.query), CharsetUtil.UTF_8)
                  response = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(endpoint.status))
                  response.setContent(responseContent)
                  break
                case REDIRECT:
                  response = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(endpoint.status))
                  response.headers().set(LOCATION, endpoint.body)
                  break
                case EXCEPTION:
                  throw new Exception(endpoint.body)
                case USER_BLOCK:
                  Blocking.forUser('user-to-block').blockIfMatch()
                // should never be output:
                  response = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(200))
                  response.content = 'should never be reached'
                  break
                default:
                  responseContent = ChannelBuffers.copiedBuffer(NOT_FOUND.body, CharsetUtil.UTF_8)
                  response = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(endpoint.status))
                  response.setContent(responseContent)
                  break
              }
              response.headers().set(CONTENT_TYPE, "text/plain").set(HttpServerTest.IG_RESPONSE_HEADER, HttpServerTest.IG_RESPONSE_HEADER_VALUE)
              if (responseContent) {
                response.headers().set(CONTENT_LENGTH, responseContent.readableBytes())
              }
              return new DownstreamMessageEvent(
                ctx.getChannel(),
                new SucceededChannelFuture(ctx.getChannel()),
                response,
                ctx.getChannel().getRemoteAddress())
            }
          }
        }

        @Override
        void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent ex) throws Exception {
          def message = ex.cause == null ? "<no cause> " + ex.message : ex.cause.message == null ? "<null>" : ex.cause.message
          ChannelBuffer buffer = ChannelBuffers.copiedBuffer(message, CharsetUtil.UTF_8)
          HttpResponse response = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR)
          response.setContent(buffer)
          response.headers().set(CONTENT_TYPE, "text/plain")
          response.headers().set(CONTENT_LENGTH, buffer.readableBytes())
          ctx.sendDownstream(new DownstreamMessageEvent(
            ctx.getChannel(),
            new FailedChannelFuture(ctx.getChannel(), ex.getCause()),
            response,
            ctx.getChannel().getRemoteAddress()))
        }
      })

    return channelPipeline
  }

  private class NettyServer implements HttpServer {
    final ServerBootstrap server = new ServerBootstrap(new NioServerSocketChannelFactory())
    int port = 0

    @Override
    void start() {
      server.setParentHandler(LOGGING_HANDLER)
      server.setPipelineFactory(new ChannelPipelineFactory() {
          @Override
          ChannelPipeline getPipeline() throws Exception {
            // don't invoke until after instrumentation is applied
            return channelPipeline()
          }
        })

      InetSocketAddress address = new InetSocketAddress(0)
      def channel = server.bind(address)
      port = ((InetSocketAddress) channel.localAddress).port
    }

    @Override
    void stop() {
      server.shutdown()
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
    component()
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

class Netty38ServerV0Test extends Netty38ServerTest implements TestingNettyHttpNamingConventions.ServerV0 {
}

class Netty38ServerV1ForkedTest extends Netty38ServerTest implements TestingNettyHttpNamingConventions.ServerV1 {
}
