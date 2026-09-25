package datadog.trace.instrumentation.liberty20

import com.ibm.wsspi.kernel.embeddable.Server
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.env.CapturedEnvironment
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import okhttp3.MediaType
import okhttp3.RequestBody
import spock.lang.IgnoreIf

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_MULTIPART
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR

abstract class Liberty20Test extends HttpServerTest<Server> {

  @Override
  HttpServer server() {
    new Liberty20Server()
  }

  protected String getPathPrefix() {
    ''
  }

  @Override
  String expectedServiceName() {
    'testapp'
  }

  @Override
  String expectedControllerServiceName() {
    super.expectedServiceName()
  }

  boolean expectedErrored(ServerEndpoint endpoint) {
    // our test makes openliberty generate a FileNotFoundException
    endpoint == ServerEndpoint.NOT_FOUND ? true : super.expectedErrored(endpoint)
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    def res = ['servlet.context': '/testapp']
    if (endpoint == ServerEndpoint.NOT_FOUND) {
      res['error.message'] = 'SRVE0190E: File not found: /not-found'
      res['error.type'] = 'java.io.FileNotFoundException'
      res['error.stack'] = ~/java\.io\.FileNotFoundException: SRVE0190E: File not found: \/not-found.*/
    } else {
      res['servlet.path'] = "${pathPrefix}${endpoint.path}"
    }
    res
  }

  @Override
  Map<String, Serializable> expectedExtraErrorInformation(ServerEndpoint endpoint) {
    // sendError produces an error span (as expected by the test)
    if (endpoint == ServerEndpoint.ERROR) {
      [
        "error.message": 'controller error' // not "controller exception", as super indicates
      ]
    } else {
      super.expectedExtraErrorInformation(endpoint)
    }
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
  protected boolean enabledFinishTimingChecks() {
    true
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
  boolean hasExtraErrorInformation() {
    true
  }

  @Override
  boolean testBodyMultipart() {
    true
  }

  @Override
  boolean testBodyFilenames() {
    true
  }

  @Override
  boolean testSessionId() {
    true
  }

  @IgnoreIf({ !instance.testBlockingOnResponse()})
  def 'test blocking on response with commit during the response'() {
    setup:
    def request = request(SUCCESS, 'GET', null)
      .header(IG_BLOCK_RESPONSE_HEADER, 'json')
      .header('x-commit-during-response', 'true')
      .build()

    when:
    def response = client.newCall(request).execute()

    then:
    if (isDataStreamsEnabled()) {
      TEST_DATA_STREAMS_WRITER.waitForGroups(1)
    }
    response.code() == 413
    response.header('Content-type') =~ /(?i)\Aapplication\/json(?:;\s?charset=utf-8)?\z/
    response.body().charStream().text.contains('"title":"You\'ve been blocked"')
    TEST_WRITER.waitForTraces(1)

    then:
    TEST_WRITER.flatten().find { DDSpan it ->
      it.tags['http.status_code'] == 413 &&
        it.tags['appsec.blocked'] == 'true'
    } != null
  }

  // Pins the pre-existing blocking behavior of ParsePostDataInstrumentation and
  // ParseParametersInstrumentation after the block-telemetry-3 changes, which added a
  // reportBlockFailure() call when BlockResponseFunction#tryCommitBlockingResponse returns
  // false. The real Liberty BlockResponseFunction (LibertyDecorator$LibertyBlockResponseFunction)
  // delegates to a void helper that swallows failures and then always returns true for a genuine
  // commit attempt, so the reportBlockFailure() branch itself remains unreachable through this
  // end-to-end test: this test only verifies the blocking response is produced exactly as before.
  //
  // GetPartsInstrumentation (liberty-20.0 only) is not separately covered here: the shared
  // HttpServerTest file-upload callback (requestFilesFilenamesCb) never returns a blocking Flow
  // action, so its blocking branch cannot be exercised through existing test infrastructure
  // without changing that shared fixture, which is out of scope for this task.
  @IgnoreIf({ !instance.testBlocking() })
  def 'test blocking of request body variant #variant pins block-telemetry-3 behavior'() {
    setup:
    def request = request(
      endpoint, 'POST',
      RequestBody.create(MediaType.get(contentType), body))
      .header(IG_BODY_CONVERTED_HEADER, 'true')
      .build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.code() == 413
    response.header('Content-type') =~ /(?i)\Aapplication\/json(?:;\s?charset=(?:utf-8|iso-8859-1))?\z/
    response.body().charStream().text.contains('"title":"You\'ve been blocked"')
    !handlerRan

    where:
    variant       | endpoint        | contentType                          | body
    'urlencoded'  | BODY_URLENCODED | 'application/x-www-form-urlencoded'  | 'a=x'
    'multipart'   | BODY_MULTIPART  | MULTIPART_TEST_CONTENT_TYPE          | MULTIPART_TEST_BODY
  }

  private static final String MULTIPART_TEST_CONTENT_TYPE =
  'multipart/form-data; charset=utf-8; boundary=------------------------943d3207457896a3'
  private static final String MULTIPART_TEST_BODY =
  '--------------------------943d3207457896a3\r\n' +
  'Content-Disposition: form-data; name="a"\r\n' +
  '\r\n' +
  'x\r\n' +
  '--------------------------943d3207457896a3--'
}

// make it forked because there are instrumentation errors when we shutdown and
// restart the server on the same JVM
class Liberty20AsyncForkedTest extends Liberty20Test implements TestingGenericHttpNamingConventions.ServerV0 {
  @Override
  HttpServer server() {
    new Liberty20Server('async/')
  }

  @Override
  protected String getPathPrefix() {
    '/async'
  }

  @Override
  boolean hasHandlerSpan() {
    true
  }

  @Override
  boolean testNotFound() {
    false // only has 1 span; test expects the handler span there too
  }

  @Override
  boolean testResponseHeadersMapping() {
    false
  }

  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint) {
    trace.span {
      serviceName CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME)
      operationName "servlet.dispatch"
      resourceName 'servlet.dispatch'
      errored(endpoint.throwsException || endpoint == TIMEOUT_ERROR)
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" 'java-web-servlet-async-dispatcher'
        if (endpoint == EXCEPTION) {
          "error.message" 'controller exception'
          "error.type" Exception.name
          "error.stack" String
        }
        defaultTags()
      }
    }
  }
}

@IgnoreIf({
  // failing because org.apache.xalan.transformer.TransformerImpl is
  // instrumented while on the the global ignores list
  System.getProperty('java.vm.name') == 'IBM J9 VM' &&
  System.getProperty('java.specification.version') == '1.8'
})
class LibertyServletClassloaderNamingForkedTest extends Liberty20V0ForkedTest {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    // will not set the service name according to the servlet context value
    injectSysConfig("trace.experimental.jee.split-by-deployment", "true")
  }
}

@IgnoreIf({
  // failing because org.apache.xalan.transformer.TransformerImpl is
  // instrumented while on the the global ignores list
  System.getProperty('java.vm.name') == 'IBM J9 VM' &&
  System.getProperty('java.specification.version') == '1.8'
})
class Liberty20V0ForkedTest extends Liberty20Test implements TestingGenericHttpNamingConventions.ServerV0 {
}

@IgnoreIf({
  // failing because org.apache.xalan.transformer.TransformerImpl is
  // instrumented while on the the global ignores list
  System.getProperty('java.vm.name') == 'IBM J9 VM' &&
  System.getProperty('java.specification.version') == '1.8'
})
class Liberty20V1ForkedTest extends Liberty20Test implements TestingGenericHttpNamingConventions.ServerV1 {
}
