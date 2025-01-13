import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.agent.test.utils.PortUtils
import okhttp3.HttpUrl
import okhttp3.Request
import org.jboss.shrinkwrap.api.exporter.ExplodedExporter
import org.jboss.shrinkwrap.api.spec.WebArchive
import org.wildfly.core.embedded.Configuration
import org.wildfly.core.embedded.EmbeddedProcessFactory
import org.wildfly.core.embedded.StandaloneServer
import spock.util.concurrent.PollingConditions

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

class EmbeddedWildfly {

  private final StandaloneServer server
  private final Path home
  private final int port

  EmbeddedWildfly(String jbossHome, int httpPort) {
    home = Paths.get(jbossHome)
    port = httpPort
    def conf = Configuration.Builder
      .of(home)
      .addCommandArgument("-Djboss.management.http.port=0")
      .addCommandArgument("-Djboss.management.https.port=0")
      .addCommandArgument("-Djboss.ajp.port=0")
      .addCommandArgument("-Djboss.http.port=$httpPort")
      .addCommandArgument("-Djboss.https.port=0")
      .addSystemPackages("datadog", "groovy")
      .build()
    server = EmbeddedProcessFactory.createStandaloneServer(conf)
  }

  void start() {
    server.start()
    PortUtils.waitForPortToOpen(port, 30, TimeUnit.SECONDS)
  }

  void stop() {
    server.stop()
  }

  void deploy(WebArchive webArchive) {
    def deploymentPath = home.resolve("standalone/deployments")
    webArchive.as(ExplodedExporter).exportExploded(deploymentPath.toFile(), "test.war")
    Files.createFile(deploymentPath.resolve("test.war.dodeploy"))
    new PollingConditions(timeout: 30, delay: 2).eventually {
      def request = new Request.Builder().url(HttpUrl.get("http://localhost:$port/test")).build()
      def call = OkHttpUtils.client().newCall(request)
      assert call.execute().code() == 200
    }
  }
}
