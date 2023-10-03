import com.sun.enterprise.v3.services.impl.GlassfishNetworkListener
import com.sun.enterprise.v3.services.impl.GrizzlyProxy
import com.sun.enterprise.v3.services.impl.GrizzlyService
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import org.glassfish.embeddable.BootstrapProperties
import org.glassfish.embeddable.Deployer
import org.glassfish.embeddable.GlassFish
import org.glassfish.embeddable.GlassFishProperties
import org.glassfish.embeddable.GlassFishRuntime
import org.glassfish.embeddable.archive.ScatteredArchive
import org.glassfish.grizzly.nio.transport.TCPNIOTransport

import java.nio.channels.ServerSocketChannel
import java.util.concurrent.TimeoutException

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
    "java-web-servlet"
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
  boolean hasExtraErrorInformation() {
    true
  }

  @Override
  boolean testBodyUrlencoded() {
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
  boolean testBlocking() {
    // TODO: the servlet instrumentation has no blocking request function yet
    // Relying on grizzly or grizzly-filterchain instrumentations doesn't work
    // for different reasons: the version of grizzly-http is too old for
    // glassfish 4 and for glassfish 5 the span is created after the servlet span.
    // The grizzly instrumentation doesn't work for a different reason: the blocking
    // exception throw on parseRequestParameters is gobbled inside
    // org.glassfish.grizzly.http.server.Request#parseRequestParameters and never
    // propagates.
    false
  }

  @Override
  boolean changesAll404s() {
    true
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    ["servlet.path": endpoint.path, "servlet.context": "/$context"]
  }
}
