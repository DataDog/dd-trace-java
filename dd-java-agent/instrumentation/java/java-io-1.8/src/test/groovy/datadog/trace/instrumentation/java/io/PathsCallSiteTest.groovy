package datadog.trace.instrumentation.java.io

import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.PathTraversalModule
import datadog.trace.instrumentation.java.lang.FileLoadedRaspHelper
import foo.bar.TestPathsSuite

class PathsCallSiteTest extends BaseIoRaspCallSiteTest {

  void 'test IAST get path from strings'(final String first, final String... other) {
    setup:
    PathTraversalModule iastModule = Mock(PathTraversalModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    TestPathsSuite.get(first, other)

    then:
    1 * iastModule.onPathTraversal(first, other)

    where:
    first      | other
    'test.txt' | [] as String[]
    '/tmp'     | ['log', 'test.txt'] as String[]
  }

  void 'test IAST get path from uri'() {
    setup:
    PathTraversalModule iastModule = Mock(PathTraversalModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final file = new URI('file:/test.txt')

    when:
    TestPathsSuite.get(file)

    then:
    1 * iastModule.onPathTraversal(file)
  }

  void 'test RASP get path from strings'(final String first, final String... other) {
    setup:
    final helper = Mock(FileLoadedRaspHelper)
    FileLoadedRaspHelper.INSTANCE = helper

    when:
    TestPathsSuite.get(first, other)

    then:
    1 * helper.beforeFileLoaded(first, other)

    where:
    first      | other
    'test.txt' | [] as String[]
    '/tmp'     | ['log', 'test.txt'] as String[]
  }

  void 'test RASP get path from uri'() {
    setup:
    final helper = Mock(FileLoadedRaspHelper)
    FileLoadedRaspHelper.INSTANCE = helper
    final file = new URI('file:/test.txt')

    when:
    TestPathsSuite.get(file)

    then:
    1 * helper.beforeFileLoaded(file)
  }
}
