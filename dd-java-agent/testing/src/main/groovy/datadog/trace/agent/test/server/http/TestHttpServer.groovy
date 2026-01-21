package datadog.trace.agent.test.server.http

import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.eclipse.jetty.http.HttpMethod
import org.eclipse.jetty.http.HttpVersion
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.HttpConfiguration
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.SecureRequestCustomizer
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.SslConnectionFactory
import org.eclipse.jetty.server.handler.AbstractHandler
import org.eclipse.jetty.server.handler.HandlerList
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.eclipse.jetty.util.thread.QueuedThreadPool

import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.servlet.ServletException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.agent.test.server.http.HttpServletRequestExtractAdapter.GETTER
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan
import static org.eclipse.jetty.http.HttpMethod.CONNECT
import static org.eclipse.jetty.http.HttpMethod.GET
import static org.eclipse.jetty.http.HttpMethod.POST
import static org.eclipse.jetty.http.HttpMethod.PUT

@SuppressFBWarnings([
  "IS2_INCONSISTENT_SYNC",
  "PA_PUBLIC_PRIMITIVE_ATTRIBUTE"
])
class TestHttpServer implements AutoCloseable {

  static TestHttpServer httpServer(@DelegatesTo(value = TestHttpServer, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    def server = new TestHttpServer()
    def clone = (Closure) spec.clone()
    clone.delegate = server
    clone.resolveStrategy = Closure.DELEGATE_FIRST
    clone(server)
    server.start()
    return server
  }

  private final Server internalServer
  private HandlersSpec handlers
  private Closure customizer =  {}

  public String keystorePath
  private URI address
  private URI secureAddress
  private final AtomicReference<HandlerApi.RequestApi> last = new AtomicReference<>()

  public final SSLContext sslContext = SSLContext.getInstance("TLSv1.2")

  private final X509TrustManager trustManager = new X509TrustManager() {
    X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0]
    }

    void checkClientTrusted(X509Certificate[] certificate, String str) {}

    void checkServerTrusted(X509Certificate[] certificate, String str) {}
  }
  private final HostnameVerifier hostnameVerifier = new HostnameVerifier() {
    @Override
    boolean verify(String hostname, SSLSession session) {
      return "localhost" == hostname
    }
  }

  private TestHttpServer() {
    // In some versions, Jetty requires max threads > than some arbitrary calculated value
    // The calculated value can be high in CI
    // There is no easy way to override the configuration in a version-neutral way
    internalServer = new Server(new QueuedThreadPool(400))

    TrustManager[] trustManagers = new TrustManager[1]
    trustManagers[0] = trustManager
    sslContext.init(null, trustManagers, null)
  }

  TestHttpServer start() {
    if (internalServer.isStarted()) {
      return this
    }
    synchronized (this) {
      if (!internalServer.isRunning()) {
        assert handlers != null: "handlers must be defined"
        def handlerList = new HandlerList()
        handlerList.handlers = handlers.configured
        internalServer.handler = handlerList

        final HttpConfiguration httpConfiguration = new HttpConfiguration()

        // HTTP
        final ServerConnector http = new ServerConnector(internalServer,
          new HttpConnectionFactory(httpConfiguration))
        http.setHost('localhost')
        http.setPort(0)
        internalServer.addConnector(http)

        // HTTPS
        final SslContextFactory sslContextFactory = new SslContextFactory()
        keystorePath = extractKeystoreToDisk(TestHttpServer.getResource("datadog.jks")).path
        sslContextFactory.keyStorePath = keystorePath
        sslContextFactory.keyStorePassword = "datadog"
        final HttpConfiguration httpsConfiguration = new HttpConfiguration(httpConfiguration)
        httpsConfiguration.addCustomizer(new SecureRequestCustomizer())
        final ServerConnector https = new ServerConnector(internalServer,
          new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
          new HttpConnectionFactory(httpsConfiguration))
        https.setHost('localhost')
        https.setPort(0)
        internalServer.addConnector(https)

        // Guard against shading mismatch: test code may use non-shaded Jetty types
        // while TestHttpServer uses shaded Jetty (datadog.eclipse.jetty.*)
        if (customizer.maximumNumberOfParameters > 0) {
          def expectedType = customizer.parameterTypes[0]
          def actualType = internalServer.getClass()
          if (expectedType != Object && !expectedType.isAssignableFrom(actualType)) {
            throw new IllegalArgumentException(
            "Customizer closure expects '${expectedType.name}' but TestHttpServer uses shaded Jetty '${actualType.name}'. " +
            "Update your test imports to use 'datadog.eclipse.jetty.*' instead of 'org.eclipse.jetty.*'."
            )
          }
        }
        customizer.call(internalServer)
        internalServer.start()
        // set after starting, otherwise two callbacks get added.
        internalServer.stopAtShutdown = true

        address = new URI("http://localhost:${http.localPort}")
        secureAddress = new URI("https://localhost:${https.localPort}")
      }
      long startTime = System.nanoTime()
      long rem = TimeUnit.SECONDS.toMillis(5)
      while (!internalServer.isStarted()) {
        if (rem <= 0) {
          throw new TimeoutException("Failed to start server $this on port ${address.port}")
        }
        Thread.sleep(Math.min(rem, 100))
        long endTime = System.nanoTime()
        rem -= TimeUnit.NANOSECONDS.toMillis(endTime - startTime)
        startTime = endTime
      }
      System.out.println("Started server $this on ${address} and  ${secureAddress}")
    }
    return this
  }

  private File extractKeystoreToDisk(URL internalFile) {
    InputStream inputStream = internalFile.openStream()
    File tempFile = File.createTempFile("datadog", ".jks")
    tempFile.deleteOnExit()

    OutputStream out = new FileOutputStream(tempFile)

    byte[] buffer = new byte[1024]
    int len = inputStream.read(buffer)
    while (len != -1) {
      out.write(buffer, 0, len)
      len = inputStream.read(buffer)
    }

    inputStream.close()
    out.close()

    return tempFile
  }

  def stop() {
    System.out.println("Stopping server $this on ${address} and  ${secureAddress}")
    internalServer.stop()
    return this
  }

  void close() {
    stop()
  }

  URI getAddress() {
    return address
  }

  URI getSecureAddress() {
    return secureAddress
  }

  X509TrustManager getTrustManager() {
    return trustManager
  }

  HostnameVerifier getHostnameVerifier() {
    return hostnameVerifier
  }

  def getLastRequest() {
    return last.get()
  }

  void customizer(Closure<Closure> spec) {
    this.customizer = spec.call()
  }

  void handlers(@DelegatesTo(value = HandlersSpec, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    assert handlers == null: "handlers already defined"
    handlers = new HandlersSpec()

    def clone = (Closure) spec.clone()
    clone.delegate = handlers
    clone.resolveStrategy = Closure.DELEGATE_FIRST
    clone(handlers)
  }

  HttpServer asHttpServer(boolean secure = false) {
    return new HttpServerAdapter(this, secure)
  }

  static distributedRequestTrace(ListWriterAssert traces, DDSpan parentSpan = null, Map<String, Serializable> extraTags = null) {
    traces.trace(1) {
      span {
        operationName "test-http-server"
        errored false
        if (parentSpan == null) {
          parent()
        } else {
          childOf(parentSpan)
        }
        tags {
          "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
          "path" String
          defaultTags(parentSpan != null)
          if (extraTags) {
            it.addTags(extraTags)
          }
        }
      }
    }
  }

  private class HandlersSpec {

    List<Handler> configured = []

    void get(String path, @DelegatesTo(value = HandlerApi, strategy = Closure.DELEGATE_FIRST) Closure<Void> spec) {
      assert path != null
      configured << new HandlerSpec(GET, path, spec)
    }

    void post(String path, @DelegatesTo(value = HandlerApi, strategy = Closure.DELEGATE_FIRST) Closure<Void> spec) {
      assert path != null
      configured << new HandlerSpec(POST, path, spec)
    }

    void put(String path, @DelegatesTo(value = HandlerApi, strategy = Closure.DELEGATE_FIRST) Closure<Void> spec) {
      assert path != null
      configured << new HandlerSpec(PUT, path, spec)
    }

    void connect(@DelegatesTo(value = HandlerApi, strategy = Closure.DELEGATE_FIRST) Closure<Void> spec) {
      configured << new MethodSpec(CONNECT, spec)
    }

    void prefix(String path, @DelegatesTo(value = HandlerApi, strategy = Closure.DELEGATE_FIRST) Closure<Void> spec) {
      configured << new PrefixHandlerSpec(path, spec)
    }

    void all(@DelegatesTo(value = HandlerApi, strategy = Closure.DELEGATE_FIRST) Closure<Void> spec) {
      configured << new AllHandlerSpec(spec)
    }
  }

  private class MethodSpec extends AllHandlerSpec {

    private final String method

    protected MethodSpec(HttpMethod method, Closure<Void> spec) {
      super(spec)
      this.method = method.name()
    }

    @Override
    void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
      if (request.method.equalsIgnoreCase(method)) {
        super.handle(target, baseRequest, request, response)
      }
    }
  }

  private class HandlerSpec extends MethodSpec {

    private final String path

    protected HandlerSpec(HttpMethod method, String path, Closure<Void> spec) {
      super(method, spec)
      this.path = path.startsWith("/") ? path : "/" + path
    }

    @Override
    void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
      if (target == path) {
        super.handle(target, baseRequest, request, response)
      }
    }
  }

  private class PrefixHandlerSpec extends AllHandlerSpec {

    private final String prefix

    protected PrefixHandlerSpec(String prefix, Closure<Void> spec) {
      super(spec)
      this.prefix = prefix.startsWith("/") ? prefix : "/" + prefix
    }

    @Override
    void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
      if (target.startsWith(prefix)) {
        super.handle(target, baseRequest, request, response)
      }
    }
  }

  private class AllHandlerSpec extends AbstractHandler {
    protected final Closure<Void> spec

    protected AllHandlerSpec(Closure<Void> spec) {
      this.spec = spec
    }

    @Override
    void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
      send(baseRequest, response)
    }

    protected void send(Request baseRequest, HttpServletResponse response) {
      def api = new HandlerApi(baseRequest, response)
      last.set(api.request)

      def clone = (Closure) spec.clone()
      clone.delegate = api
      clone.resolveStrategy = Closure.DELEGATE_FIRST

      try {
        clone(api)
      } catch (Exception e) {
        api.response.status(500).send(e.getMessage())
        e.printStackTrace()
      }
    }
  }

  static class HandlerApi {
    private final RequestApi req
    private final HttpServletResponse resp

    private HandlerApi(Request request, HttpServletResponse response) {
      this.req = new RequestApi(request)
      this.resp = response
    }

    def getRequest() {
      return req
    }


    def getResponse() {
      return new ResponseApi()
    }

    void redirect(String uri) {
      resp.sendRedirect(uri)
      req.orig.handled = true
    }

    void handleDistributedRequest() {
      boolean isDDServer = true
      if (request.getHeader("is-dd-server") != null) {
        isDDServer = Boolean.parseBoolean(request.getHeader("is-dd-server"))
      }
      if (isDDServer) {
        final AgentSpanContext extractedContext = extractContextAndGetSpanContext(req.orig, GETTER)
        if (extractedContext != null) {
          startSpan("test", "test-http-server", extractedContext)
            .setTag("path", request.path)
            .setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER).finish()
        } else {
          startSpan("test", "test-http-server")
            .setTag("path", request.path)
            .setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER).finish()
        }
      }
    }

    static class RequestApi {
      final orig
      final path
      final Headers headers
      final contentLength
      final contentType
      final byte[] body
      final String method

      RequestApi(Request req) {
        this.orig = req
        this.path = req.pathInfo
        this.headers = new Headers(req)
        this.contentLength = req.contentLength
        this.contentType = req.contentType?.split(";")
        this.body = req.inputStream.bytes
      }

      def getPath() {
        return path
      }

      def getContentLength() {
        return contentLength
      }

      def getContentType() {
        return contentType ? contentType[0] : null
      }

      def getHeaders() {
        return headers
      }

      String getHeader(String header) {
        return headers[header]
      }

      def getBody() {
        return body
      }

      def getText() {
        return new String(body)
      }

      def getParameter(String parameter) {
        return orig.getParameter(parameter)
      }
    }

    class ResponseApi {
      private static final String DEFAULT_TYPE = "text/plain;charset=utf-8"
      private int status = 200
      private Map<String, String> headers = new HashMap<String, String>()

      ResponseApi status(int status) {
        this.status = status
        return this
      }

      ResponseApi addHeader(String headerName, String headerValue) {
        this.headers.put(headerName, headerValue)
        return this
      }

      void send() {
        sendWithType(DEFAULT_TYPE)
      }

      void sendWithType(String contentType) {
        assert contentType != null
        assert !req.orig.handled
        req.orig.contentType = contentType
        resp.status = status
        headers.each {
          resp.addHeader(it.key, it.value)
        }
        req.orig.handled = true
      }

      void send(String body) {
        sendWithType(DEFAULT_TYPE, body)
      }

      void sendWithType(String contentType, String body) {
        assert body != null

        sendWithType(contentType)
        resp.setContentLength(body.bytes.length)
        resp.writer.print(body)
      }

      void send(byte[] body) {
        sendWithType(DEFAULT_TYPE, body)
      }

      void sendWithType(String contentType, byte[] body) {
        assert body != null

        sendWithType(contentType)
        resp.setContentLength(body.length)
        resp.outputStream.write(body)
      }
    }
  }

  static class Headers {
    private final Map<String, String> headers

    private Headers(Request request) {
      this.headers = [:]
      request.getHeaderNames().each {
        headers.put(it, request.getHeader(it))
      }
    }

    def get(String header) {
      return headers[header]
    }
  }

  static class HttpServerAdapter implements HttpServer {
    final TestHttpServer server
    final boolean secure
    URI address

    HttpServerAdapter(TestHttpServer server, boolean secure = false) {
      this.server = server
      this.secure = secure
    }

    @Override
    void start() throws TimeoutException {
      server.start()
      address = secure ? server.secureAddress : server.address
      if (!address.path.endsWith('/')) {
        address = new URI(address.toString() + '/')
      }
    }

    @Override
    void stop() {
      server.stop()
    }

    @Override
    URI address() {
      return address
    }
  }
}
