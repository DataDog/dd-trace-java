package datadog.trace.agent.test.server.http;

import static datadog.trace.agent.test.server.http.HttpServletRequestExtractAdapter.GETTER;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.agent.test.base.HttpServer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

@SuppressFBWarnings({"IS2_INCONSISTENT_SYNC", "PA_PUBLIC_PRIMITIVE_ATTRIBUTE"})
public class JavaTestHttpServer implements AutoCloseable {

  @FunctionalInterface
  public interface RequestHandler {
    void handle(HandlerApi api) throws Exception;
  }

  private final Server internalServer;
  private HandlersSpec handlers;
  private Consumer<Server> customizer = s -> {};

  public String keystorePath;
  private URI address;
  private URI secureAddress;
  private final AtomicReference<HandlerApi.RequestApi> last = new AtomicReference<>();

  public final SSLContext sslContext;

  private final X509TrustManager trustManager =
      new X509TrustManager() {
        @Override
        public X509Certificate[] getAcceptedIssuers() {
          return new X509Certificate[0];
        }

        @Override
        public void checkClientTrusted(X509Certificate[] certificate, String str) {}

        @Override
        public void checkServerTrusted(X509Certificate[] certificate, String str) {}
      };

  private final HostnameVerifier hostnameVerifier =
      (hostname, session) -> "localhost".equals(hostname);

  public static JavaTestHttpServer httpServer(Consumer<JavaTestHttpServer> spec) {
    JavaTestHttpServer server = new JavaTestHttpServer();
    spec.accept(server);
    server.start();
    return server;
  }

  private JavaTestHttpServer() {
    // In some versions, Jetty requires max threads > than some arbitrary calculated value.
    // The calculated value can be high in CI. There is no easy way to override the configuration
    // in a version-neutral way.
    internalServer = new Server(new QueuedThreadPool(400));
    try {
      sslContext = SSLContext.getInstance("TLSv1.2");
      sslContext.init(null, new TrustManager[] {trustManager}, null);
    } catch (NoSuchAlgorithmException | KeyManagementException e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressForbidden
  public JavaTestHttpServer start() {
    if (internalServer.isStarted()) {
      return this;
    }
    synchronized (this) {
      if (!internalServer.isRunning()) {
        if (handlers == null) {
          throw new IllegalStateException("handlers must be defined");
        }
        HandlerList handlerList = new HandlerList();
        handlerList.setHandlers(handlers.configured.toArray(new Handler[0]));
        internalServer.setHandler(handlerList);

        HttpConfiguration httpConfiguration = new HttpConfiguration();

        // HTTP
        ServerConnector http =
            new ServerConnector(internalServer, new HttpConnectionFactory(httpConfiguration));
        http.setHost("localhost");
        http.setPort(0);
        internalServer.addConnector(http);

        // HTTPS
        SslContextFactory sslContextFactory = new SslContextFactory();
        keystorePath =
            extractKeystoreToDisk(JavaTestHttpServer.class.getResource("datadog.jks")).getPath();
        sslContextFactory.setKeyStorePath(keystorePath);
        sslContextFactory.setKeyStorePassword("datadog");
        HttpConfiguration httpsConfiguration = new HttpConfiguration(httpConfiguration);
        httpsConfiguration.addCustomizer(new SecureRequestCustomizer());
        ServerConnector https =
            new ServerConnector(
                internalServer,
                new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
                new HttpConnectionFactory(httpsConfiguration));
        https.setHost("localhost");
        https.setPort(0);
        internalServer.addConnector(https);

        customizer.accept(internalServer);
        try {
          internalServer.start();
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
        // set after starting, otherwise two callbacks get added.
        internalServer.setStopAtShutdown(true);

        try {
          address = new URI("http://localhost:" + http.getLocalPort());
          secureAddress = new URI("https://localhost:" + https.getLocalPort());
        } catch (URISyntaxException e) {
          throw new IllegalStateException(e);
        }
      }
    }
    long startTime = System.nanoTime();
    long rem = TimeUnit.SECONDS.toMillis(5);
    while (!internalServer.isStarted()) {
      if (rem <= 0) {
        throw new RuntimeException(
            new TimeoutException(
                "Failed to start server " + this + " on port " + address.getPort()));
      }
      try {
        Thread.sleep(Math.min(rem, 100));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
      long endTime = System.nanoTime();
      rem -= TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
      startTime = endTime;
    }
    System.out.println("Started server " + this + " on " + address + " and  " + secureAddress);
    return this;
  }

  private File extractKeystoreToDisk(URL internalFile) {
    try (InputStream inputStream = internalFile.openStream()) {
      File tempFile = File.createTempFile("datadog", ".jks");
      tempFile.deleteOnExit();
      try (OutputStream out = new FileOutputStream(tempFile)) {
        byte[] buffer = new byte[1024];
        int len;
        while ((len = inputStream.read(buffer)) != -1) {
          out.write(buffer, 0, len);
        }
      }
      return tempFile;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressForbidden
  public JavaTestHttpServer stop() {
    System.out.println("Stopping server " + this + " on " + address + " and  " + secureAddress);
    try {
      internalServer.stop();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return this;
  }

  @Override
  public void close() {
    stop();
  }

  public URI getAddress() {
    return address;
  }

  public URI getSecureAddress() {
    return secureAddress;
  }

  public X509TrustManager getTrustManager() {
    return trustManager;
  }

  public HostnameVerifier getHostnameVerifier() {
    return hostnameVerifier;
  }

  public HandlerApi.RequestApi getLastRequest() {
    return last.get();
  }

  public void customizer(Consumer<Server> spec) {
    this.customizer = spec;
  }

  public void handlers(Consumer<HandlersSpec> spec) {
    if (handlers != null) {
      throw new IllegalStateException("handlers already defined");
    }
    handlers = new HandlersSpec();
    spec.accept(handlers);
  }

  public HttpServer asHttpServer() {
    return new HttpServerAdapter(this, false);
  }

  public HttpServer asHttpServer(boolean secure) {
    return new HttpServerAdapter(this, secure);
  }

  public final class HandlersSpec {
    final List<Handler> configured = new ArrayList<>();

    public void get(String path, RequestHandler spec) {
      if (path == null) {
        throw new IllegalArgumentException("path must not be null");
      }
      configured.add(new PathHandler(HttpMethod.GET, path, spec));
    }

    public void post(String path, RequestHandler spec) {
      if (path == null) {
        throw new IllegalArgumentException("path must not be null");
      }
      configured.add(new PathHandler(HttpMethod.POST, path, spec));
    }

    public void put(String path, RequestHandler spec) {
      if (path == null) {
        throw new IllegalArgumentException("path must not be null");
      }
      configured.add(new PathHandler(HttpMethod.PUT, path, spec));
    }

    public void connect(RequestHandler spec) {
      configured.add(new MethodHandler(HttpMethod.CONNECT, spec));
    }

    public void prefix(String path, RequestHandler spec) {
      configured.add(new PrefixHandler(path, spec));
    }

    public void all(RequestHandler spec) {
      configured.add(new AllHandler(spec));
    }
  }

  private class AllHandler extends AbstractHandler {
    final RequestHandler spec;

    AllHandler(RequestHandler spec) {
      this.spec = spec;
    }

    @Override
    public void handle(
        String target,
        Request baseRequest,
        HttpServletRequest request,
        HttpServletResponse response)
        throws IOException, ServletException {
      send(baseRequest, response);
    }

    void send(Request baseRequest, HttpServletResponse response) {
      HandlerApi api = new HandlerApi(baseRequest, response);
      last.set(api.getRequest());
      try {
        spec.handle(api);
      } catch (Exception e) {
        try {
          api.getResponse().status(500).send(e.getMessage());
        } catch (Exception ignored) {
          // ignore
        }
        e.printStackTrace();
      }
    }
  }

  private class MethodHandler extends AllHandler {
    private final String method;

    MethodHandler(HttpMethod method, RequestHandler spec) {
      super(spec);
      this.method = method.name();
    }

    @Override
    public void handle(
        String target,
        Request baseRequest,
        HttpServletRequest request,
        HttpServletResponse response)
        throws IOException, ServletException {
      if (request.getMethod().equalsIgnoreCase(method)) {
        super.handle(target, baseRequest, request, response);
      }
    }
  }

  private class PathHandler extends MethodHandler {
    private final String path;

    PathHandler(HttpMethod method, String path, RequestHandler spec) {
      super(method, spec);
      this.path = path.startsWith("/") ? path : "/" + path;
    }

    @Override
    public void handle(
        String target,
        Request baseRequest,
        HttpServletRequest request,
        HttpServletResponse response)
        throws IOException, ServletException {
      if (path.equals(target)) {
        super.handle(target, baseRequest, request, response);
      }
    }
  }

  private class PrefixHandler extends AllHandler {
    private final String prefix;

    PrefixHandler(String prefix, RequestHandler spec) {
      super(spec);
      this.prefix = prefix.startsWith("/") ? prefix : "/" + prefix;
    }

    @Override
    public void handle(
        String target,
        Request baseRequest,
        HttpServletRequest request,
        HttpServletResponse response)
        throws IOException, ServletException {
      if (target.startsWith(prefix)) {
        super.handle(target, baseRequest, request, response);
      }
    }
  }

  public static class HandlerApi {
    private final RequestApi req;
    private final HttpServletResponse resp;

    private HandlerApi(Request request, HttpServletResponse response) {
      this.req = new RequestApi(request);
      this.resp = response;
    }

    public RequestApi getRequest() {
      return req;
    }

    public ResponseApi getResponse() {
      return new ResponseApi();
    }

    public void redirect(String uri) throws IOException {
      resp.sendRedirect(uri);
      req.orig.setHandled(true);
    }

    public void handleDistributedRequest() {
      boolean isDDServer = true;
      String header = req.getHeader("is-dd-server");
      if (header != null) {
        isDDServer = Boolean.parseBoolean(header);
      }
      if (isDDServer) {
        AgentSpanContext extractedContext = extractContextAndGetSpanContext(req.orig, GETTER);
        if (extractedContext != null) {
          startSpan("test", "test-http-server", extractedContext)
              .setTag("path", req.getPath())
              .setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER)
              .finish();
        } else {
          startSpan("test", "test-http-server")
              .setTag("path", req.getPath())
              .setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER)
              .finish();
        }
      }
    }

    public static class RequestApi {
      final Request orig;
      final String path;
      final Headers headers;
      final int contentLength;
      final String contentType;
      final byte[] body;

      RequestApi(Request req) {
        this.orig = req;
        this.path = req.getPathInfo();
        this.headers = new Headers(req);
        this.contentLength = req.getContentLength();
        String ct = req.getContentType();
        this.contentType = ct;
        try (InputStream is = req.getInputStream()) {
          ByteArrayOutputStream baos = new ByteArrayOutputStream();
          byte[] buffer = new byte[1024];
          int read;
          while ((read = is.read(buffer)) != -1) {
            baos.write(buffer, 0, read);
          }
          this.body = baos.toByteArray();
        } catch (IOException e) {
          throw new IllegalStateException(e);
        }
      }

      public String getPath() {
        return path;
      }

      public int getContentLength() {
        return contentLength;
      }

      public String getContentType() {
        if (contentType == null) {
          return null;
        }
        int idx = contentType.indexOf(';');
        return idx >= 0 ? contentType.substring(0, idx) : contentType;
      }

      public Headers getHeaders() {
        return headers;
      }

      public String getHeader(String header) {
        return headers.get(header);
      }

      public byte[] getBody() {
        return body;
      }

      public String getText() {
        return new String(body);
      }

      public String getParameter(String parameter) {
        return orig.getParameter(parameter);
      }
    }

    public class ResponseApi {
      private static final String DEFAULT_TYPE = "text/plain;charset=utf-8";
      private int status = 200;
      private final Map<String, String> headers = new HashMap<>();

      public ResponseApi status(int status) {
        this.status = status;
        return this;
      }

      public ResponseApi addHeader(String headerName, String headerValue) {
        this.headers.put(headerName, headerValue);
        return this;
      }

      public void send() {
        sendWithType(DEFAULT_TYPE);
      }

      public void sendWithType(String contentType) {
        if (contentType == null) {
          throw new IllegalArgumentException("contentType must not be null");
        }
        if (req.orig.isHandled()) {
          throw new IllegalStateException("response already handled");
        }
        req.orig.setContentType(contentType);
        resp.setStatus(status);
        for (Map.Entry<String, String> e : headers.entrySet()) {
          resp.addHeader(e.getKey(), e.getValue());
        }
        req.orig.setHandled(true);
      }

      public void send(String body) {
        sendWithType(DEFAULT_TYPE, body);
      }

      public void sendWithType(String contentType, String body) {
        if (body == null) {
          throw new IllegalArgumentException("body must not be null");
        }
        sendWithType(contentType);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        resp.setContentLength(bytes.length);
        try {
          resp.getWriter().print(body);
        } catch (IOException e) {
          throw new IllegalStateException(e);
        }
      }

      public void send(byte[] body) {
        sendWithType(DEFAULT_TYPE, body);
      }

      public void sendWithType(String contentType, byte[] body) {
        if (body == null) {
          throw new IllegalArgumentException("body must not be null");
        }
        sendWithType(contentType);
        resp.setContentLength(body.length);
        try {
          resp.getOutputStream().write(body);
        } catch (IOException e) {
          throw new IllegalStateException(e);
        }
      }
    }
  }

  public static class Headers {
    private final Map<String, String> headers = new HashMap<>();

    private Headers(Request request) {
      Enumeration<String> names = request.getHeaderNames();
      if (names != null) {
        while (names.hasMoreElements()) {
          String name = names.nextElement();
          headers.put(name, request.getHeader(name));
        }
      }
    }

    public String get(String header) {
      return headers.get(header);
    }
  }

  public static class HttpServerAdapter implements HttpServer {
    final JavaTestHttpServer server;
    final boolean secure;
    URI address;

    public HttpServerAdapter(JavaTestHttpServer server, boolean secure) {
      this.server = server;
      this.secure = secure;
    }

    @Override
    public void start() throws TimeoutException {
      server.start();
      address = secure ? server.secureAddress : server.address;
      if (!address.getPath().endsWith("/")) {
        try {
          address = new URI(address.toString() + "/");
        } catch (URISyntaxException e) {
          throw new IllegalStateException(e);
        }
      }
    }

    @Override
    public void stop() {
      server.stop();
    }

    @Override
    public URI address() {
      return address;
    }
  }
}
