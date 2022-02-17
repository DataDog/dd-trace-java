import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import io.undertow.Undertow
import io.undertow.UndertowOptions
import io.undertow.server.handlers.PathHandler
import io.undertow.servlet.api.DeploymentInfo
import io.undertow.servlet.api.DeploymentManager
import io.undertow.servlet.api.ServletContainer
import io.undertow.servlet.api.ServletInfo

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class UndertowServletTest extends HttpServerTest<Undertow> {
  class UndertowServer implements HttpServer {
    def port = 0
    Undertow undertowServer;

    UndertowServer() {
      final PathHandler root = new PathHandler();
      final ServletContainer container = ServletContainer.Factory.newInstance();

      DeploymentInfo builder = new DeploymentInfo()
        .setClassLoader(UndertowServletTest.class.getClassLoader())
        .setContextPath("/")
        .setDeploymentName("servletContext.war")

      builder.addServlet(new ServletInfo("SuccessServlet", SuccessServlet.class).addMapping(SUCCESS.getPath()))
        .addServlet(new ServletInfo("ForwardedServlet", ForwardedServlet.class).addMapping(FORWARDED.getPath()))
        .addServlet(new ServletInfo("QueryEncodedBothServlet", QueryEncodedBothServlet.class).addMapping(QUERY_ENCODED_BOTH.getPath()))
        .addServlet(new ServletInfo("QueryEncodedServlet", QueryEncodedServlet.class).addMapping(QUERY_ENCODED_QUERY.getPath()))
        .addServlet(new ServletInfo("QueryParamServlet", QueryServlet.class).addMapping(QUERY_PARAM.getPath()))
        .addServlet(new ServletInfo("RedirectServlet", RedirectServlet.class).addMapping(REDIRECT.getPath()))
        .addServlet(new ServletInfo("ErrorServlet", ErrorServlet.class).addMapping(ERROR.getPath()))
        .addServlet(new ServletInfo("ExceptionServlet", ExceptionServlet.class).addMapping(EXCEPTION.getPath()))

      DeploymentManager manager = container.addDeployment(builder)
      manager.deploy()
      root.addPrefixPath(builder.getContextPath(), manager.start())

      undertowServer = Undertow.builder()
        .addHttpListener(port, "localhost")
        .setServerOption(UndertowOptions.DECODE_URL, true)
        .setHandler(root)
        .build()
    }

    @Override
    void start() {
      undertowServer.start();
      InetSocketAddress addr = (InetSocketAddress) undertowServer.getListenerInfo().get(0).getAddress();
      port = addr.getPort();
    }

    @Override
    void stop() {
      undertowServer.stop()
    }

    @Override
    URI address() {
      return new URI("http://localhost:$port/")
    }
  }

  @Override
  UndertowServer server() {
    return new UndertowServer();
  }

  @Override
  String component() {
    return 'undertow-http-server';
  }

  @Override
  String expectedOperationName() {
    return 'servlet.request';
  }

  @Override
  boolean testExceptionBody() {
    false
  }
}
