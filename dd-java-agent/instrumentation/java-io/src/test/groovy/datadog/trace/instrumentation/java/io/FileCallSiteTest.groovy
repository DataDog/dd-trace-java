package datadog.trace.instrumentation.java.io

import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.PathTraversalModule
import foo.bar.TestFileSuite

class FileCallSiteTest extends BaseIoCallSiteTest {

  def 'test new file with path'() {
    setup:
    PathTraversalModule iastModule = Mock(PathTraversalModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final path = 'test.txt'

    when:
    TestFileSuite.newFile(path)

    then:
    1 * iastModule.onPathTraversal(path)
    0 * _
  }

  def 'test new file with parent and child'() {
    setup:
    PathTraversalModule iastModule = Mock(PathTraversalModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final parent = '/home/test'
    final child = 'test.txt'

    when:
    TestFileSuite.newFile(parent, child)

    then:
    1 * iastModule.onPathTraversal(parent, child)
    0 * _
  }

  def 'test new file with parent file and child'() {
    setup:
    PathTraversalModule iastModule = Mock(PathTraversalModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final parent = new File('/home/test')
    final child = 'test.txt'

    when:
    TestFileSuite.newFile(parent, child)

    then:
    1 * iastModule.onPathTraversal(parent, child)
    0 * _
  }

  def 'test new file with uri'() {
    setup:
    PathTraversalModule iastModule = Mock(PathTraversalModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final file = new URI('file:/test.txt')

    when:
    TestFileSuite.newFile(file)

    then:
    1 * iastModule.onPathTraversal(file)
    0 * _
  }
}
