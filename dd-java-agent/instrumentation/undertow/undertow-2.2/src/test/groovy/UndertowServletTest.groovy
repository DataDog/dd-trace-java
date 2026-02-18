import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.base.WebsocketServer
import io.undertow.Handlers
import io.undertow.Undertow
import io.undertow.UndertowOptions
import io.undertow.servlet.api.DeploymentInfo
import io.undertow.servlet.api.DeploymentManager
import io.undertow.servlet.api.ServletContainer
import io.undertow.servlet.api.ServletInfo
import io.undertow.websockets.jsr.WebSocketDeploymentInfo
import jakarta.servlet.MultipartConfigElement

import java.nio.ByteBuffer

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_MULTIPART
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED_IS
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

class UndertowServletTest extends HttpServerTest<Undertow> {
  private static final CONTEXT = "ctx"

  class UndertowServer implements WebsocketServer {
    def port = 0
    Undertow undertowServer

    UndertowServer() {
      def root = Handlers.path()
      final ServletContainer container = ServletContainer.Factory.newInstance()

      DeploymentInfo builder = new DeploymentInfo()
        .setDefaultMultipartConfig(new MultipartConfigElement(System.getProperty('java.io.tmpdir'), 1024, 1024, 1024))
        .setClassLoader(UndertowServletTest.getClassLoader())
        .setContextPath("/$CONTEXT")
        .setDeploymentName("servletContext.war")
        .addServlet(new ServletInfo("SuccessServlet", SuccessServlet).addMapping(SUCCESS.getPath()))
        .addServlet(new ServletInfo("ForwardedServlet", ForwardedServlet).addMapping(FORWARDED.getPath()))
        .addServlet(new ServletInfo("QueryEncodedBothServlet", QueryEncodedBothServlet).addMapping(QUERY_ENCODED_BOTH.getPath()))
        .addServlet(new ServletInfo("QueryEncodedServlet", QueryEncodedServlet).addMapping(QUERY_ENCODED_QUERY.getPath()))
        .addServlet(new ServletInfo("QueryParamServlet", QueryServlet).addMapping(QUERY_PARAM.getPath()))
        .addServlet(new ServletInfo("RedirectServlet", RedirectServlet).addMapping(REDIRECT.getPath()))
        .addServlet(new ServletInfo("ErrorServlet", ErrorServlet).addMapping(ERROR.getPath()))
        .addServlet(new ServletInfo("ExceptionServlet", ExceptionServlet).addMapping(EXCEPTION.getPath()))
        .addServlet(new ServletInfo("UserBlockServlet", UserBlockServlet).addMapping(USER_BLOCK.path))
        .addServlet(new ServletInfo("CreatedServlet", CreatedServlet).addMapping(CREATED.path))
        .addServlet(new ServletInfo("CreatedISServlet", CreatedISServlet).addMapping(CREATED_IS.path))
        .addServlet(new ServletInfo("BodyUrlEncodedServlet", BodyUrlEncodedServlet).addMapping(BODY_URLENCODED.path))
        .addServlet(new ServletInfo("BodyMultipartServlet", BodyMultipartServlet).addMapping(BODY_MULTIPART.path))
        .addServletContextAttribute(WebSocketDeploymentInfo.ATTRIBUTE_NAME, new WebSocketDeploymentInfo().addEndpoint(TestEndpoint))

      DeploymentManager manager = container.addDeployment(builder)
      manager.deploy()
      root.addPrefixPath(builder.getContextPath(), manager.start())

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
    }

    @Override
    void stop() {
      undertowServer.stop()
    }

    @Override
    URI address() {
      return new URI("http://localhost:$port/$CONTEXT/")
    }

    @Override
    void awaitConnected() {
      while (TestEndpoint.activeSession == null) {
        synchronized (TestEndpoint) {
          TestEndpoint.wait(1000)
        }
      }
    }

    @Override
    void serverSendText(String[] messages) {
      if (messages.length == 1) {
        TestEndpoint.activeSession.getBasicRemote().sendText(messages[0])
      } else {
        def remoteEndpoint = TestEndpoint.activeSession.getBasicRemote()
        for (int i = 0; i < messages.length; i++) {
          remoteEndpoint.sendText(messages[i], i == messages.length - 1)
        }
      }
    }

    @Override
    boolean canSplitLargeWebsocketPayloads() {
      false
    }

    @Override
    void serverSendBinary(byte[][] binaries) {
      if (binaries.length == 1) {
        TestEndpoint.activeSession.getBasicRemote().sendBinary(ByteBuffer.wrap(binaries[0]))
      } else {
        try (def stream = TestEndpoint.activeSession.getBasicRemote().getSendStream()) {
          binaries.each {
            stream.write(it)
          }
        }
      }
    }

    @Override
    void serverClose() {
      TestEndpoint.activeSession?.close()
    }

    @Override
    void setMaxPayloadSize(int size) {
      TestEndpoint.activeSession?.setMaxTextMessageBufferSize(size)
      TestEndpoint.activeSession?.setMaxBinaryMessageBufferSize(size)
    }
  }

  @Override
  UndertowServer server() {
    return new UndertowServer()
  }

  @Override
  String component() {
    return 'undertow-http-server'
  }

  @Override
  String expectedOperationName() {
    return 'servlet.request'
  }

  @Override
  protected boolean enabledFinishTimingChecks() {
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
  boolean testBlockingOnResponse() {
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

  boolean hasResponseSpan(ServerEndpoint endpoint) {
    // FIXME: re-enable when jakarta servlet will be fully supported
    // return endpoint == REDIRECT || endpoint == NOT_FOUND
    false
  }

  @Override
  String expectedServiceName() {
    CONTEXT
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
}
