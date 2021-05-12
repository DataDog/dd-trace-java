import com.sun.enterprise.v3.services.impl.GlassfishNetworkListener
import com.sun.enterprise.v3.services.impl.GrizzlyProxy
import com.sun.enterprise.v3.services.impl.GrizzlyService
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.servlet3.Servlet3Decorator
import org.glassfish.embeddable.BootstrapProperties
import org.glassfish.embeddable.Deployer
import org.glassfish.embeddable.GlassFish
import org.glassfish.embeddable.GlassFishProperties
import org.glassfish.embeddable.GlassFishRuntime
import org.glassfish.embeddable.archive.ScatteredArchive
import org.glassfish.grizzly.nio.transport.TCPNIOTransport

import java.nio.channels.ServerSocketChannel
import java.util.concurrent.TimeoutException

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

/**
 * Unfortunately because we're using an embedded GlassFish instance, we aren't exercising the standard
 * OSGi setup that required {@link datadog.trace.instrumentation.glassfish.GlassFishInstrumentation}.
 */
// TODO: Figure out a better way to test with OSGi included.
class GlassFishServerTest extends HttpServerTest<GlassFish> {

  private class GlassFishServer implements HttpServer {
    final GlassFish server
    final testDir = new File(TestServlets.protectionDomain.codeSource.location.path)
    final testResourcesDir = new File(TestServlets.getResource("error.jsp").path).parentFile
    final archive = new ScatteredArchive(context, ScatteredArchive.Type.WAR, testResourcesDir)

    int port = 0

    GlassFishServer() {
      // Setup the deployment archive
      assert testDir.exists() && testDir.directory
      assert testResourcesDir.exists() && testResourcesDir.directory
      archive.addClassPath(testDir)

      // Initialize the server
      BootstrapProperties bootstrapProperties = new BootstrapProperties()
      GlassFishRuntime glassfishRuntime = GlassFishRuntime.bootstrap(bootstrapProperties)
      GlassFishProperties glassfishProperties = new GlassFishProperties()
      glassfishProperties.setPort('http-listener', 0)
      server = glassfishRuntime.newGlassFish(glassfishProperties)
    }

    @Override
    void start() throws TimeoutException {
      server.start()

      // Deploy war to server
      Deployer deployer = server.getDeployer()
      println "Deploying $testDir.absolutePath with $testResourcesDir.absolutePath"
      deployer.deploy(archive.toURI())

      // Extract the actual bound port
      def service = server.getService(GrizzlyService)
      def proxy = service.proxies[0] as GrizzlyProxy
      def networkListener = proxy.grizzlyListener as GlassfishNetworkListener
      assert networkListener.name == "http-listener"
      def transport = networkListener.transport as TCPNIOTransport
      def channel = transport.serverConnections[0].channel as ServerSocketChannel
      port = channel.socket().localPort
    }

    @Override
    void stop() {
      server.stop()
    }

    @Override
    URI address() {
      return new URI("http://localhost:$port/$context/")
    }
  }

  @Override
  HttpServer server() {
    return new GlassFishServer()
  }

  String getContext() {
    "test-gf"
  }

  @Override
  String component() {
    return Servlet3Decorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return "servlet.request"
  }

  @Override
  String expectedServiceName() {
    context
  }

  @Override
  boolean redirectHasBody() {
    true
  }

  @Override
  void serverSpan(TraceAssert trace, BigInteger traceID = null, BigInteger parentID = null, String method = "GET", ServerEndpoint endpoint = SUCCESS) {
    trace.span {
      serviceName expectedServiceName()
      operationName expectedOperationName()
      resourceName endpoint.status == 404 ? "404" : "$method ${endpoint.resolve(address).path}"
      spanType DDSpanTypes.HTTP_SERVER
      errored endpoint.errored
      if (parentID != null) {
        traceId traceID
        parentId parentID
      } else {
        parent()
      }
      statusCode endpoint.status
      tags {
        "$Tags.COMPONENT" component
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.PEER_HOST_IPV4" "127.0.0.1"
        "$Tags.PEER_PORT" Integer
        "$Tags.HTTP_METHOD" method
        "$Tags.HTTP_URL" "${endpoint.resolve(address)}"
        if (endpoint == FORWARDED) {
          "$Tags.HTTP_FORWARDED_IP" endpoint.body
        }
        "servlet.context" "/$context"
        "servlet.path" endpoint.path
        if (endpoint.errored) {
          "error.msg" { it == null || it == EXCEPTION.body }
          "error.type" { it == null || it == Exception.name }
          "error.stack" { it == null || it instanceof String }
        }
        if (endpoint.query) {
          "$DDTags.HTTP_QUERY" endpoint.query
        }
        defaultTags(true)
      }
    }
  }
}

