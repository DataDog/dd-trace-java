package datadog.trace.instrumentation.liberty20

import static org.apache.commons.io.FileUtils.copyDirectory

import com.ibm.wsspi.kernel.embeddable.Server
import com.ibm.wsspi.kernel.embeddable.ServerBuilder
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.utils.PortUtils
import groovy.xml.XmlParser
import groovy.xml.XmlUtil

import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Runs the test app in a private Liberty user directory seeded from the build's server template. */
class Liberty20Server implements HttpServer {
  private static final String SERVER_NAME = 'defaultServer'

  final File serverTemplateDir = new File(System.getProperty('server.xml')).parentFile
  final String prefix

  def port
  File userDir
  Server server

  Liberty20Server(String prefix = '') {
    this.prefix = prefix
  }

  @Override
  void start() {
    port = PortUtils.randomOpenPort()
    userDir = Files.createTempDirectory('liberty-').toFile()
    File serverDir = new File(userDir, "servers/$SERVER_NAME")
    // Copy the shared template before rewriting server.xml so concurrent forks use their own ports.
    copyDirectory(serverTemplateDir, serverDir)
    changeServerXml(new File(serverDir, 'server.xml'))
    server = new ServerBuilder().setName(SERVER_NAME).setUserDir(userDir).build()
    // set bootdelegation to mimic how our -javaagent delegates requests for our shaded slf4j package
    // (at this point in the build we haven't shaded slf4j or transformed any framework class-loaders)
    def result = server.start(["org.osgi.framework.bootdelegation":"org.slf4j"]).get(45, TimeUnit.SECONDS)
    if (!result.successful()) {
      throw new IllegalStateException("OpenLiberty startup has failed")
    }
  }

  private void changeServerXml(File serverXmlFile) {
    def xml = new XmlParser().parse(serverXmlFile)
    xml.httpEndpoint[0].'@httpPort' = port as String
    xml.httpEndpoint[0].attributes().remove 'httpsPort'

    serverXmlFile.text = XmlUtil.serialize(xml)
  }

  @Override
  void stop() {
    try {
      if (server != null) {
        def result = server.stop().get(30, TimeUnit.SECONDS)
        if (!result.successful()) {
          throw new IllegalStateException("OpenLiberty stop has failed")
        }
      }
    } finally {
      userDir?.deleteDir()
    }
  }

  @Override
  URI address() {
    new URI("http://localhost:$port/testapp/${this.prefix}")
  }

  @Override
  String toString() {
    return this.class.name
  }
}
