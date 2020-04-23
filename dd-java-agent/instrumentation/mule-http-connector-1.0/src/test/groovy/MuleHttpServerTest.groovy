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

class MuleHttpServerTest extends HttpServerTest<HttpServer> {

  private TCPNIOTransport transport;
  private TCPNIOServerConnection serverConnection;
  private final int DEFAULT_SERVER_TIMEOUT_MILLIS = 80000;
  private final int DEFAULT_CLIENT_TIMEOUT_MILLIS = 30000;
  private final int DEFAULT_MAX_HTTP_PACKET_HEADER_SIZE = 8192;
  private final int DEFAULT_CONNECTION_IDLE_TIMEOUT_MILLIS = 30000;
  private final int MAX_KEEP_ALIVE_REQUESTS = -1;
  private final int DEFAULT_SERVER_CONNECTION_BACKLOG = 50;
  private final int DEFAULT_SELECTOR_THREAD_COUNT = max(getRuntime().availableProcessors(), 2);
  private final int MIN_SELECTORS_FOR_DEDICATED_ACCEPTOR = 4;
  private DelayedExecutor delayedExecutor
  private OkHttpClient client = OkHttpUtils.client()

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
    FilterChainBuilder serverFilterChainBuilder = FilterChainBuilder.stateless();
    serverFilterChainBuilder.add(createTransportFilter());
    serverFilterChainBuilder.add(createIdleTimeoutFilter());
    serverFilterChainBuilder.add(createHttpServerFilter())
    serverFilterChainBuilder.add(new LastFilter())
    return serverFilterChainBuilder.build();
  }

  TransportFilter createTransportFilter() {
    return new TransportFilter();
  }

  IdleTimeoutFilter createIdleTimeoutFilter() {
    ExecutorService executorService = Executors.newCachedThreadPool();
    delayedExecutor = new DelayedExecutor(executorService);

    IdleTimeoutFilter idleTimeoutFilter = new IdleTimeoutFilter(delayedExecutor, DEFAULT_SERVER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    return idleTimeoutFilter;
  }

  HttpServerFilter createHttpServerFilter() {
    KeepAlive keepAlive = new KeepAlive();
    keepAlive.setMaxRequestsCount(MAX_KEEP_ALIVE_REQUESTS);
    keepAlive.setIdleTimeoutInSeconds(convertToSeconds(DEFAULT_CONNECTION_IDLE_TIMEOUT_MILLIS));
    return new HttpServerFilter();
  }

  private static int convertToSeconds(int connectionIdleTimeout) {
    if (connectionIdleTimeout < 0) {
      return -1;
    } else {
      return (int) MILLISECONDS.toSeconds(connectionIdleTimeout);
    }
  }

  class LastFilter extends BaseFilter {

    @Override
    public NextAction handleRead(final FilterChainContext ctx) throws IOException {
      if (ctx.getMessage() instanceof HttpContent) {
        final HttpContent httpContent = ctx.getMessage();
        final HttpHeader httpHeader = httpContent.getHttpHeader();
        if (httpHeader instanceof HttpRequestPacket) {
          final HttpRequestPacket request = (HttpRequestPacket) httpContent.getHttpHeader();

          final ResponseParameters responseParameters = buildResponse(request);
          final HttpResponsePacket response = HttpResponsePacket.builder(request).status(responseParameters.getStatus()).build()

          final String CONTENT_LENGTH = "Content-Length";

          final HttpResponsePacket.Builder responsePacketBuilder = HttpResponsePacket.builder(request);
          responsePacketBuilder.status(responseParameters.getStatus());

          responsePacketBuilder.header(CONTENT_LENGTH, valueOf(responseParameters.getResponseBody().length));

          controller(responseParameters.getEndpoint()) {
            ctx.write(HttpContent.builder(responsePacketBuilder.build())
              .content(wrap(ctx.getMemoryManager(), responseParameters.getResponseBody()))
              .build());
          }
        }
      }
      return ctx.getStopAction();
    }

    public ResponseParameters buildResponse(HttpRequestPacket request) {
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

      public ResponseParameters(HttpServerTest.ServerEndpoint endpoint, status, byte[] responseBody) {
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


