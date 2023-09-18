import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.undertow.Handlers
import io.undertow.Undertow
import io.undertow.UndertowOptions
import io.undertow.channels.DetachableStreamSinkChannel
import io.undertow.server.HttpServerExchange
import io.undertow.servlet.api.DeploymentInfo
import io.undertow.servlet.api.DeploymentManager
import io.undertow.servlet.api.InstanceFactory
import io.undertow.servlet.api.InstanceHandle
import io.undertow.servlet.api.ServletContainer
import io.undertow.servlet.api.ServletInfo
import io.undertow.servlet.util.ImmediateInstanceHandle
import org.slf4j.LoggerFactory

import javax.servlet.AsyncContext
import javax.servlet.AsyncEvent
import javax.servlet.AsyncListener
import javax.servlet.MultipartConfigElement
import javax.servlet.Servlet
import javax.servlet.ServletException
import javax.servlet.WriteListener
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_MULTIPART
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED_IS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.LOGIN
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_HERE
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.USER_BLOCK
import static datadog.trace.agent.test.base.HttpServerTest.controller

class UndertowServletAsyncTest extends HttpServerTest<Undertow> {
  private static final CONTEXT = "ctx"
  private final static LOG = LoggerFactory.getLogger(UndertowServletAsyncTest)

  class UndertowServer implements HttpServer {
    def port = 0
    Undertow undertowServer

    UndertowServer() {
      def root = Handlers.path()
      final ServletContainer container = ServletContainer.Factory.newInstance()

      DeploymentInfo builder = new DeploymentInfo()

      def addServlet = { Class<? extends Servlet> servletClass, HttpServerTest.ServerEndpoint ep ->
        Servlet innerServlet = servletClass.newInstance(new Object[0])
        def instanceFactory = new InstanceFactory() {
            @Override
            InstanceHandle createInstance() {
              def servlet = new HttpServlet() {
                  @Override
                  protected void service(HttpServletRequest req, HttpServletResponse resp) {
                    def dispatched = req.getAttribute('ddog.dispatched')

                    if (!dispatched) {
                      AsyncContext asyncContext = req.startAsync()
                      asyncContext.addListener(new AsyncListener() {
                          @Override
                          void onComplete(AsyncEvent event) {
                            LOG.debug('completed: {}', dispatched)
                          }

                          @Override
                          void onTimeout(AsyncEvent event) {
                            LOG.warn('onTimeout')
                            asyncContext.complete()
                          }

                          @Override
                          void onError(AsyncEvent event) {
                            LOG.warn('onError', event.throwable)
                          }

                          @Override
                          void onStartAsync(AsyncEvent event) {
                            event.asyncContext.addListener(this)
                          }
                        })

                      asyncContext.start {
                        if (req.servletPath == REDIRECT.path) {
                          // sendRedirect() closes the response (responseDone()), which causes the same
                          // kind of problem as described below
                          req.setAttribute('ddog.dispatched', 1)
                          asyncContext.dispatch()
                          return
                        }

                        asyncContext.response.outputStream.writeListener = new WriteListener() {
                            private boolean dispatch = true

                            @Override
                            void onWritePossible() throws IOException {
                              if (!dispatch) {
                                return
                              }
                              dispatch = false
                              req.setAttribute('ddog.dispatched', 1)
                              if (req.servletPath.contains(' ')) {
                                // bug in undertow when dispatching from /foo%20bar
                                // (matches mapping "/foo bar" 1st and "/foo%20bar" after dispatch)
                                asyncContext.dispatch(req.servletPath)
                              } else {
                                asyncContext.dispatch()
                              }
                            }

                            @Override
                            void onError(Throwable t) {
                              LOG.warn('onError', t)
                              asyncContext.complete()
                            }
                          }
                      }
                    } else if (req.getAttribute('ddog.dispatched') == 1) {
                      // dispatched once already
                      def os = resp.outputStream
                      resp.metaClass['getWriter'] = {
                        ->
                        new Writer() {
                            @Override
                            void write(char[] cbuf, int off, int len) {
                              os << new String(cbuf, off, len).getBytes(StandardCharsets.UTF_8)
                              flush()
                            }

                            @Override
                            void flush() throws IOException {
                              os.flush()
                            }

                            @Override
                            void close() throws IOException {
                              os.close()
                            }
                          }
                      }
                      req.setAttribute('ddog.dispatched', 2)
                      innerServlet.service(req, resp)

                      // without the call to resumeWrites(), there is a race condition.
                      // Both io.undertow.server.Connectors#executeRootHandler (through
                      // endExchange()) and io.undertow.servlet.handlers.ServletInitialHandler#handleFirstRequest
                      // (through responseDone()) try to shutdown writes on the channel.
                      // This causes the corruption on chunked responses (duplicate 0\r\n).
                      // Whether it happens or not depends on whether closeAsync() (the servlet outputstream
                      // is in async mode due to the listener) runs last or 1st.
                      // see https://gist.github.com/cataphract/3496e239b0f80458b15a68dd18d3c92a
                      // resumeWrites suppresses the call to endExchange()
                      HttpServerExchange exchange = req.exchange
                      DetachableStreamSinkChannel channel = exchange.@responseChannel
                      channel?.resumeWrites()
                    }
                  }
                }
              new ImmediateInstanceHandle(servlet)
            }
          }

        builder.addServlet(new ServletInfo("$servletClass.simpleName.${ep.name()}", servletClass, instanceFactory)
          .setAsyncSupported(true).addMapping(ep.path))
      }

      builder
        .setDefaultMultipartConfig(new MultipartConfigElement(System.getProperty('java.io.tmpdir'), 1024, 1024, 1024))
        .setClassLoader(UndertowServletTest.getClassLoader())
        .setContextPath("/$CONTEXT")
        .setDeploymentName("servletContext.war")

      [
        [SuccessServlet, SUCCESS],
        [ForwardedServlet, FORWARDED],
        [QueryEncodedBothServlet, QUERY_ENCODED_BOTH],
        [QueryEncodedServlet, QUERY_ENCODED_QUERY],
        [QueryServlet, QUERY_PARAM],
        [RedirectServlet, REDIRECT],
        [ErrorServlet, ERROR],
        [ExceptionServlet, EXCEPTION],
        [UserBlockServlet, USER_BLOCK],
        [NotHereServlet, NOT_HERE],
        [CreatedServlet, CREATED],
        [CreatedISServlet, CREATED_IS],
        [BodyUrlEncodedServlet, BODY_URLENCODED],
        [BodyMultipartServlet, BODY_MULTIPART],
        [TimeoutServlet, TIMEOUT],
        [TimeoutServlet, TIMEOUT_ERROR],
      ].each {
        addServlet(*it)
      }

      DeploymentManager manager = container.addDeployment(builder)
      manager.deploy()
      root.addPrefixPath(builder.contextPath, manager.start())

      undertowServer = Undertow.builder()
        .addHttpListener(port, "localhost")
        .setServerOption(UndertowOptions.DECODE_URL, true)
        .setHandler(Handlers.httpContinueRead(root))
        .build()
    }

    @Override
    void start() {
      undertowServer.start()
      InetSocketAddress addr = (InetSocketAddress) undertowServer.getListenerInfo().get(0).getAddress()
      port = addr.getPort()
      //      System.sleep 3600_000
    }

    @Override
    void stop() {
      undertowServer.stop()
    }

    @Override
    URI address() {
      return new URI("http://localhost:$port/$CONTEXT/")
    }
  }

  @Override
  UndertowServer server() {
    new UndertowServer()
  }

  @Override
  String component() {
    'undertow-http-server'
  }

  @Override
  protected boolean enabledFinishTimingChecks() {
    true
  }

  @Override
  String expectedOperationName() {
    operation()
  }

  @Override
  boolean testTimeout() {
    true
  }

  @Override
  boolean testExceptionBody() {
    false
  }

  @Override
  boolean testBlocking() {
    true
  }

  @Override
  boolean testRequestBody() {
    true
  }

  @Override
  boolean testRequestBodyISVariant() {
    true
  }

  @Override
  boolean testBodyUrlencoded() {
    true
  }

  @Override
  boolean testBodyMultipart() {
    true
  }

  @Override
  boolean testBlockingOnResponse() {
    true
  }

  boolean hasResponseSpan(ServerEndpoint endpoint) {
    endpoint == REDIRECT || endpoint == NOT_FOUND
  }


  @Override
  int version() {
    0
  }

  @Override
  String service() {
    null
  }

  @Override
  String operation() {
    'servlet.request'
  }

  @Override
  String expectedServiceName() {
    CONTEXT
  }

  @Override
  String expectedResourceName(ServerEndpoint endpoint, String method, URI address) {
    if (endpoint.status == 404 && endpoint.path == "/not-found") {
      return "404"
    } else if (endpoint.hasPathParam) {
      return "$method ${testPathParam()}"
    }
    def base = endpoint == LOGIN ? address : address.resolve("/")
    "$method ${endpoint.resolve(base).path}"
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    ["servlet.path": endpoint.path, "servlet.context": "/$CONTEXT"]
  }

  @Override
  void responseSpan(TraceAssert trace, ServerEndpoint endpoint) {
    if (endpoint == REDIRECT) {
      trace.span {
        operationName "servlet.response"
        resourceName "HttpServletResponse.sendRedirect"
        childOfPrevious()
        tags {
          "component" "java-web-servlet-response"
          defaultTags()
        }
      }
    } else if (endpoint == NOT_FOUND) {
      trace.span {
        operationName "servlet.response"
        resourceName "HttpServletResponse.sendError"
        childOfPrevious()
        tags {
          "component" "java-web-servlet-response"
          defaultTags()
        }
      }
    } else {
      throw new UnsupportedOperationException("responseSpan not implemented for " + endpoint)
    }
  }

  @Override
  Serializable expectedServerSpanRoute(ServerEndpoint endpoint) {
    switch (endpoint) {
      case LOGIN:
      case NOT_FOUND:
        return null
      case PATH_PARAM:
        return testPathParam()
      default:
        return endpoint.path
    }
  }

  @Override
  boolean hasHandlerSpan() {
    true
  }

  @Override
  int spanCount(ServerEndpoint endpoint) {
    int res = super.spanCount(endpoint)
    if (endpoint in [NOT_FOUND]) {
      res--
    }
    res
  }

  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    if (endpoint == NOT_FOUND) {
      return
    }

    trace.span {
      serviceName expectedServiceName()
      operationName 'servlet.dispatch'
      String resName = endpoint.path == endpoint.rawPath ? 'servlet.dispatch' : endpoint.path
      resourceName resName
      errored(endpoint.throwsException || endpoint == TIMEOUT_ERROR)
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" 'java-web-servlet-async-dispatcher'
        if (endpoint == TIMEOUT || endpoint == TIMEOUT_ERROR) {
          timeout 50
        }
        if (endpoint.throwsException) {
          "error.message" endpoint.body
          "error.type" { it == Exception.name || it == InputMismatchException.name }
          "error.stack" String
        }
        defaultTags()
      }
    }
  }
}

class TimeoutServlet extends HttpServlet {
  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
    def async = req.startAsync()
    AtomicReference<Thread> threadRef = new AtomicReference()
    async.addListener(new AsyncListener() {
        @Override
        void onComplete(AsyncEvent event) throws IOException {
        }

        @Override
        void onTimeout(AsyncEvent event) throws IOException {
          threadRef.get().interrupt()
          resp.status = 500
          event.asyncContext.complete()
        }

        @Override
        void onError(AsyncEvent event) throws IOException {
        }

        @Override
        void onStartAsync(AsyncEvent event) throws IOException {
        }
      })
    async.timeout = 50

    async.start {
      controller(HttpServerTest.ServerEndpoint.forPath(req.servletPath)) {
        try {
          threadRef.set(Thread.currentThread())
          Thread.sleep(3600_000)
        } catch (InterruptedException ie) {
        }
      }
    }
  }
}
