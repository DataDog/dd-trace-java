package datadog.trace.instrumentation.java.io

import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.PathTraversalModule
import datadog.trace.instrumentation.java.lang.FileLoadedRaspHelper
import foo.bar.TestPathSuite

class PathCallSiteTest extends BaseIoRaspCallSiteTest {

  void 'test IAST resolve path'() {
    setup:
    PathTraversalModule iastModule = Mock(PathTraversalModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final path = 'test.txt'

    when:
    TestPathSuite.resolve(getRootFolder().toPath(), path)

    then:
    1 * iastModule.onPathTraversal(path)
  }

  void 'test IAST resolve sibling'() {
    setup:
    PathTraversalModule iastModule = Mock(PathTraversalModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final sibling = newFile('test1.txt').toPath()
    final path = 'test2.txt'

    when:
    TestPathSuite.resolveSibling(sibling, path)

    then:
    1 * iastModule.onPathTraversal(path)
  }

  void 'test RASP resolve path'() {
    setup:
    final helper = Mock(FileLoadedRaspHelper)
    FileLoadedRaspHelper.INSTANCE = helper
    final path = 'test.txt'

    when:
    TestPathSuite.resolve(getRootFolder().toPath(), path)

    then:
    1 * helper.onFileLoaded(path)
  }

  void 'test RASP resolve sibling'() {
    setup:
    final helper = Mock(FileLoadedRaspHelper)
    FileLoadedRaspHelper.INSTANCE = helper
    final sibling = newFile('test1.txt').toPath()
    final path = 'test2.txt'

    when:
    TestPathSuite.resolveSibling(sibling, path)

    then:
    1 * helper.onFileLoaded(path)
  }
}
