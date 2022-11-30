package datadog.trace.instrumentation.java.io

import datadog.trace.api.iast.IastModule
import datadog.trace.api.iast.InstrumentationBridge
import foo.bar.TestPathSuite

class PathCallSiteTest extends BaseIoCallSiteTest {

  def 'test resolve path'() {
    setup:
    final iastModule = Mock(IastModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final path = 'test.txt'

    when:
    TestPathSuite.resolve(getRootFolder().toPath(), path)

    then:
    1 * iastModule.onPathTraversal(path)
    0 * _
  }

  def 'test resolve sibling'() {
    setup:
    final iastModule = Mock(IastModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final sibling = newFile('test1.txt').toPath()
    final path = 'test2.txt'

    when:
    TestPathSuite.resolveSibling(sibling, path)

    then:
    1 * iastModule.onPathTraversal(path)
    0 * _
  }
}
