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

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static java.lang.Integer.max
import static java.lang.Runtime.getRuntime
import static java.util.concurrent.TimeUnit.MILLISECONDS

class MuleHttpServerTest extends HttpServerTest<HttpServer> {

  private TCPNIOTransport transport;
  private TCPNIOServerConnection serverConnection;
  private final int DEFAULT_SERVER_TIMEOUT_MILLIS = 60000
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
    return "mule.http.server"
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
        final HttpRequestPacket request = (HttpRequestPacket) httpContent.getHttpHeader();

        final HttpResponsePacket response = buildResponse(request)
        ctx.write(response);
      }
      return ctx.getInvokeAction();
    }

    public HttpResponsePacket buildResponse(HttpRequestPacket request) {
      final String uri = request.getRequestURI()
      println uri

      int status
      String reasonPhrase
      switch (uri) {
        case "/success":
          status = SUCCESS.status
          reasonPhrase = SUCCESS.body
          break
        case "/redirect":
          status = REDIRECT.status
          reasonPhrase = REDIRECT.body
          break
        case "/error-status":
          status = ERROR.status
          reasonPhrase = ERROR.body
          break
        case "/exception":
          status = EXCEPTION.status
          reasonPhrase = EXCEPTION.body
          break
        case "/notFound":
          status = NOT_FOUND.status
          reasonPhrase = NOT_FOUND.body
          break
        case "/query?some=query":
          status = QUERY_PARAM.status
          reasonPhrase = QUERY_PARAM.body
          break
        case "/path/123/param":
          status = PATH_PARAM.status
          reasonPhrase = PATH_PARAM.body
          break
        case "/authRequired":
          status = PATH_PARAM.status
          reasonPhrase = PATH_PARAM.body
          break
        default:
          status = NOT_FOUND.status
          reasonPhrase = NOT_FOUND.body
          break
      }

      return HttpResponsePacket.builder(request).status(status).reasonPhrase(reasonPhrase).build();
    }
  }
}


