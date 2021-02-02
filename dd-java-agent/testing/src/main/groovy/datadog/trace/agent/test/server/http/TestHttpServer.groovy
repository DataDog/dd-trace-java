package datadog.trace.agent.test.server.http

import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
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

import javax.servlet.ServletException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.agent.test.server.http.HttpServletRequestExtractAdapter.GETTER
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan
import static org.eclipse.jetty.http.HttpMethod.CONNECT
import static org.eclipse.jetty.http.HttpMethod.GET
import static org.eclipse.jetty.http.HttpMethod.POST
import static org.eclipse.jetty.http.HttpMethod.PUT

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

  public String keystorePath
  private URI address
  private URI secureAddress
  private final AtomicReference<HandlerApi.RequestApi> last = new AtomicReference<>()

  private TestHttpServer() {
    internalServer = new Server()
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

  def getLastRequest() {
    return last.get()
  }

  void handlers(@DelegatesTo(value = HandlersSpec, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    assert handlers == null: "handlers already defined"
    handlers = new HandlersSpec()

    def clone = (Closure) spec.clone()
    clone.delegate = handlers
    clone.resolveStrategy = Closure.DELEGATE_FIRST
    clone(handlers)
  }

  static distributedRequestTrace(ListWriterAssert traces, DDSpan parentSpan = null) {
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
        }
        metrics {
          defaultMetrics()
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
      if (request.method == method) {
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

  class HandlerApi {
    private final Request req
    private final HttpServletResponse resp

    private HandlerApi(Request request, HttpServletResponse response) {
      this.req = request
      this.resp = response
    }

    def getRequest() {
      return new RequestApi()
    }


    def getResponse() {
      return new ResponseApi()
    }

    void redirect(String uri) {
      resp.sendRedirect(uri)
      req.handled = true
    }

    void handleDistributedRequest() {
      boolean isDDServer = true
      if (request.getHeader("is-dd-server") != null) {
        isDDServer = Boolean.parseBoolean(request.getHeader("is-dd-server"))
      }
      if (isDDServer) {
        final AgentSpan.Context extractedContext = propagate().extract(req, GETTER)
        if (extractedContext != null) {
          startSpan("test-http-server", extractedContext)
            .setTag("path", request.path)
            .setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER).finish()
        } else {
          startSpan("test-http-server")
            .setTag("path", request.path)
            .setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER).finish()
        }
      }
    }

    class RequestApi {
      def path = req.pathInfo
      def headers = new Headers(req)
      def contentLength = req.contentLength
      def contentType = req.contentType?.split(";")

      def body = req.inputStream.bytes

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
    }

    class ResponseApi {
      private int status = 200

      ResponseApi status(int status) {
        this.status = status
        return this
      }

      void send() {
        assert !req.handled
        req.contentType = "text/plain;charset=utf-8"
        resp.status = status
        req.handled = true
      }

      void send(String body) {
        assert body != null

        send()
        resp.setContentLength(body.bytes.length)
        resp.writer.print(body)
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
  }
}
