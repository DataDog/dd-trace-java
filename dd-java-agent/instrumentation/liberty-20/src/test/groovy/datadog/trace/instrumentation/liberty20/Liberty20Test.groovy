package datadog.trace.instrumentation.liberty20

import com.ibm.wsspi.kernel.embeddable.Server
import com.ibm.wsspi.kernel.embeddable.ServerBuilder
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.agent.test.utils.PortUtils
import groovy.xml.XmlUtil
import spock.lang.IgnoreIf

import java.util.concurrent.TimeUnit

abstract class Liberty20Test extends HttpServerTest<Server> {

  class Liberty20Server implements HttpServer {
    File serverXmlFile = new File(System.getProperty('server.xml'))
    long serverXmlLastModified
    byte[] origServerXml

    def port
    Server server

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
      new URI("http://localhost:$port/testapp/")
    }

    @Override
    String toString() {
      return this.class.name
    }
  }

  @Override
  HttpServer server() {
    new Liberty20Server()
  }

  @Override
  String expectedServiceName() {
    'testapp'
  }

  @Override
  String expectedControllerServiceName() {
    super.expectedServiceName()
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    def res = ['servlet.context': '/testapp']
    if (endpoint == ServerEndpoint.NOT_FOUND) {
      res['error.message'] = 'SRVE0190E: File not found: /not-found'
      res['error.type'] = 'java.io.FileNotFoundException'
      res['error.stack'] = ~/java\.io\.FileNotFoundException: SRVE0190E: File not found: \/not-found.*/
    } else {
      res['servlet.path'] = endpoint.path
    }
    res
  }

  @Override
  String component() {
    'liberty-server'
  }

  @Override
  String expectedOperationName() {
    component()
  }

  @Override
  boolean testExceptionBody() {
    false
  }

  @Override
  boolean testBodyUrlencoded() {
    true
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
  boolean hasExtraErrorInformation() {
    true
  }

  boolean expectedErrored(ServerEndpoint endpoint) {
    endpoint == ServerEndpoint.NOT_FOUND ? true : super.expectedErrored(endpoint)
  }
}

@IgnoreIf({
  // failing because org.apache.xalan.transformer.TransformerImpl is
  // instrumented while on the the global ignores list
  System.getProperty('java.vm.name') == 'IBM J9 VM' &&
  System.getProperty('java.specification.version') == '1.8' })
class Liberty20V0ForkedTest extends Liberty20Test implements TestingGenericHttpNamingConventions.ServerV0 {

}

@IgnoreIf({
  // failing because org.apache.xalan.transformer.TransformerImpl is
  // instrumented while on the the global ignores list
  System.getProperty('java.vm.name') == 'IBM J9 VM' &&
  System.getProperty('java.specification.version') == '1.8' })
class Liberty20V1ForkedTest extends Liberty20Test implements TestingGenericHttpNamingConventions.ServerV1 {

}
