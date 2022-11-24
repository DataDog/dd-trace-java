package datadog.trace.instrumentation.java.io

import datadog.trace.api.iast.IastModule
import datadog.trace.api.iast.InstrumentationBridge
import foo.bar.TestPathsSuite

class PathsCallSiteTest extends BaseIoCallSiteTest {

  def 'test get path from strings'(final String first, final String... other) {
    setup:
    final iastModule = Mock(IastModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    TestPathsSuite.get(first, other)

    then:
    1 * iastModule.onPathTraversal(first, other)
    0 * _

    where:
    first      | other
    'test.txt' | [] as String[]
    '/tmp'     | ['log', 'test.txt'] as String[]
  }

  def 'test get path from uri'() {
    setup:
    final iastModule = Mock(IastModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final file = new URI('file:/test.txt')

    when:
    TestPathsSuite.get(file)

    then:
    1 * iastModule.onPathTraversal(file)
    0 * _
  }
}
