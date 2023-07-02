package datadog.trace.instrumentation.liberty20

import com.ibm.wsspi.kernel.embeddable.Server
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import spock.lang.IgnoreIf

abstract class Liberty20Test extends HttpServerTest<Server> {

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

  @Override
  boolean testBodyMultipart() {
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
