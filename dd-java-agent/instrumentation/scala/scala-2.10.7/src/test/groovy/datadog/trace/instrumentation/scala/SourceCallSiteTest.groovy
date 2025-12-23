package datadog.trace.instrumentation.scala


import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.PathTraversalModule
import datadog.trace.api.iast.sink.SsrfModule
import spock.lang.AutoCleanup
import spock.lang.Shared

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class SourceCallSiteTest extends AbstractIastScalaTest {

  @AutoCleanup
  @Shared
  private final TestHttpServer server = httpServer {
    handlers {
      prefix('/') {
        response.status(200).send('Hello.')
      }
    }
  }

  @Override
  String suiteName() {
    return 'foo.bar.TestSourceSuite'
  }

  void 'test scala.io.Source.#method'() {
    setup:
    final module = Mock(PathTraversalModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    testSuite.&"$method".call(args as Object[])

    then:
    1 * module.onPathTraversal(args[0])
    0 * _

    where:
    method     | args
    'fromFile' | ['/etc/passwd']
    'fromFile' | ['/etc/passwd', 'utf-8']
    'fromFile' | [new URI('file:/etc/passwd')]
    'fromFile' | [new URI('file:/etc/passwd'), 'utf-8']
    'fromURI'  | [new URI('file:/etc/passwd')]
  }

  void 'test scala.io.Source.#method'() {
    setup:
    final module = Mock(SsrfModule)
    InstrumentationBridge.registerIastModule(module)

    when:
    testSuite.&"$method".call(args as Object[])

    then:
    1 * module.onURLConnection(args[0])

    where:
    method     | args
    'fromURL'  | [server.address.toString(), 'utf-8']
  }
}
