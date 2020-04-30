import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.instrumentation.mulehttpconnector.server.ServerDecorator
import okhttp3.OkHttpClient
import org.glassfish.grizzly.filterchain.BaseFilter
import org.glassfish.grizzly.filterchain.FilterChain
import org.glassfish.grizzly.filterchain.FilterChainBuilder
import org.glassfish.grizzly.filterchain.FilterChainContext
import org.glassfish.grizzly.filterchain.NextAction
import org.glassfish.grizzly.filterchain.TransportFilter
import org.glassfish.grizzly.http.HttpContent
import org.glassfish.grizzly.http.HttpHeader
import org.glassfish.grizzly.http.HttpRequestPacket
import org.glassfish.grizzly.http.HttpResponsePacket
import org.glassfish.grizzly.http.HttpServerFilter
import org.glassfish.grizzly.http.KeepAlive
import org.glassfish.grizzly.http.server.HttpServer
import org.glassfish.grizzly.nio.transport.TCPNIOServerConnection
import org.glassfish.grizzly.nio.transport.TCPNIOTransport
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder
import org.glassfish.grizzly.utils.DelayedExecutor
import org.glassfish.grizzly.utils.IdleTimeoutFilter

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.AUTH_REQUIRED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static java.lang.Integer.max
import static java.lang.Runtime.getRuntime
import static java.lang.String.valueOf
import static java.nio.charset.Charset.defaultCharset
import static java.util.concurrent.TimeUnit.MILLISECONDS
import static org.glassfish.grizzly.memory.Buffers.wrap

class GrizzlyFilterchainServerTest extends HttpServerTest<HttpServer> {

  private TCPNIOTransport transport;
  private TCPNIOServerConnection serverConnection;
  private final int DEFAULT_SERVER_TIMEOUT_MILLIS = 80000;
  private final int DEFAULT_SERVER_CONNECTION_BACKLOG = 50;

  @Override
  boolean testNotFound() {
    // resource name is set by instrumentation, so not changed to 404
    false
  }

  @Override
  HttpServer startServer(int port) {
    FilterChain filterChain = setUpFilterChain()
    setUpTransport(filterChain)

    final String IP = "localhost"
    serverConnection = transport.bind(IP, port)
    transport.start()
    return null
  }

  @Override
  void stopServer(HttpServer httpServer) {
    transport.shutdownNow()
  }

  @Override
  String component() {
    return ServerDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return "http.request"
  }

  @Override
  boolean reorderControllerSpan() {
    true
  }

  void setUpTransport(FilterChain filterChain) {
    TCPNIOTransportBuilder transportBuilder = TCPNIOTransportBuilder.newInstance()
      .setOptimizedForMultiplexing(true)

    transportBuilder.setTcpNoDelay(true);
    transportBuilder.setKeepAlive(false)
    transportBuilder.setReuseAddress(true)
    transportBuilder.setServerConnectionBackLog(DEFAULT_SERVER_CONNECTION_BACKLOG)
    transportBuilder.setServerSocketSoTimeout(DEFAULT_SERVER_TIMEOUT_MILLIS)

    transport = transportBuilder.build();
    transport.setProcessor(filterChain);
  }

  FilterChain setUpFilterChain() {
    return FilterChainBuilder.stateless()
              .add(createTransportFilter())
              .add(createIdleTimeoutFilter())
              .add(new HttpServerFilter())
              .add(new LastFilter())
              .build();
  }

  TransportFilter createTransportFilter() {
    return new TransportFilter();
  }

  IdleTimeoutFilter createIdleTimeoutFilter() {
    return new IdleTimeoutFilter(new DelayedExecutor(Executors.newCachedThreadPool()), DEFAULT_SERVER_TIMEOUT_MILLIS, MILLISECONDS);
  }

  class LastFilter extends BaseFilter {

    @Override
    NextAction handleRead(final FilterChainContext ctx) throws IOException {
      if (ctx.getMessage() instanceof HttpContent) {
        final HttpContent httpContent = ctx.getMessage();
        final HttpHeader httpHeader = httpContent.getHttpHeader();
        if (httpHeader instanceof HttpRequestPacket) {
          HttpRequestPacket request = (HttpRequestPacket) httpContent.getHttpHeader();
          ResponseParameters responseParameters = buildResponse(request);
          HttpResponsePacket responsePacket = HttpResponsePacket.builder(request)
                            .status(responseParameters.getStatus())
                            .header("Content-Length", valueOf(responseParameters.getResponseBody().length))
                            .build();
          controller(responseParameters.getEndpoint()) {
            ctx.write(HttpContent.builder(responsePacket)
              .content(wrap(ctx.getMemoryManager(), responseParameters.getResponseBody()))
              .build());
          }
        }
      }
      return ctx.getStopAction();
    }

    ResponseParameters buildResponse(HttpRequestPacket request) {
      final String uri = request.getRequestURI()
      final String requestParams = request.getQueryString();
      final String fullPath = uri + (requestParams != null ? "?" + requestParams : "");

      println requestParams
      println uri
      println fullPath

      HttpServerTest.ServerEndpoint endpoint
      switch (fullPath) {
        case "/success":
          endpoint = SUCCESS
          break
        case "/redirect":
          endpoint = REDIRECT
          break
        case "/error-status":
          endpoint = ERROR
          break
        case "/exception":
          endpoint = EXCEPTION
          break
        case "/notFound":
          endpoint = NOT_FOUND
          break
        case "/query?some=query":
          endpoint = QUERY_PARAM
          break
        case "/path/123/param":
          endpoint = PATH_PARAM
          break
        case "/authRequired":
          endpoint = AUTH_REQUIRED
          break
        default:
          endpoint = NOT_FOUND
          break
      }

      int status = endpoint.status
      String responseBody = endpoint.body

      final byte[] responseBodyBytes = responseBody.getBytes(defaultCharset());
      return new ResponseParameters(endpoint, status, responseBodyBytes);
    }

    class ResponseParameters {
      HttpServerTest.ServerEndpoint endpoint
      int status
      byte[] responseBody

      ResponseParameters(HttpServerTest.ServerEndpoint endpoint, status, byte[] responseBody) {
        this.endpoint = endpoint
        this.status = status;
        this.responseBody = responseBody;
      }

      int getStatus() {
        return status;
      }

      byte[] getResponseBody() {
        return responseBody;
      }

      HttpServerTest.ServerEndpoint getEndpoint() {
        return endpoint;
      }
    }
  }
}


