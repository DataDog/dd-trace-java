package datadog.trace.instrumentation.liberty23

import com.ibm.wsspi.kernel.embeddable.Server
import com.ibm.wsspi.kernel.embeddable.ServerBuilder
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.utils.PortUtils
import groovy.xml.XmlParser
import groovy.xml.XmlUtil

import java.util.concurrent.TimeUnit

class Liberty23Server implements HttpServer {
  File serverXmlFile = new File(System.getProperty('server.xml'))
  long serverXmlLastModified
  byte[] origServerXml
  final String prefix

  def port
  Server server

  Liberty23Server(String prefix = '') {
    this.prefix = prefix
  }

  @Override
  void start() {
    findRandomPort()
    changeServerXml()
    ServerBuilder sb = new ServerBuilder(name: 'defaultServer')
    server = sb.build()
    // set bootdelegation to mimic how our -javaagent delegates requests for our shaded slf4j package
    // (at this point in the build we haven't shaded slf4j or transformed any framework class-loaders)
    def result = server.start(["org.osgi.framework.bootdelegation":"org.slf4j"]).get(45, TimeUnit.SECONDS)
    if (!result.successful()) {
      throw new IllegalStateException("OpenLiberty startup has failed")
    }
  }

  private void findRandomPort() {
    port = PortUtils.randomOpenPort()
  }

  private void changeServerXml() {
    serverXmlLastModified = serverXmlFile.lastModified()
    origServerXml = serverXmlFile.bytes
    def xml = new XmlParser().parse(serverXmlFile)
    xml.httpEndpoint[0].'@httpPort' = port as String
    xml.httpEndpoint[0].attributes().remove 'httpsPort'

    // Remove JSP
    def featureManager = xml.featureManager[0]
    featureManager.feature[0]?.replaceNode {}

    // Add servlet-5.0
    featureManager.appendNode('feature', 'servlet-5.0')

    serverXmlFile.text = XmlUtil.serialize(xml)
  }

  @Override
  void stop() {
    def result = server.stop().get(30, TimeUnit.SECONDS)
    if (!result.successful()) {
      throw new IllegalStateException("OpenLiberty stop has failed")
    }
    serverXmlFile.bytes = origServerXml
    serverXmlFile.lastModified = serverXmlLastModified
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
