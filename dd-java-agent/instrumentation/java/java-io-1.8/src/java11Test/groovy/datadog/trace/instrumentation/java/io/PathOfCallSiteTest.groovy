package datadog.trace.instrumentation.java.io

import datadog.trace.instrumentation.java.lang.FileIORaspHelper
import foo.bar.TestPathOfSuite

class PathOfCallSiteTest extends BaseIoRaspCallSiteTest {

  void 'test RASP Path.of from strings'(final String first, final String... more) {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper

    when:
    TestPathOfSuite.of(first, more)

    then:
    1 * helper.beforeFileLoaded(first, more)

    where:
    first      | more
    'test.txt' | [] as String[]
    '/tmp'     | ['log', 'test.txt'] as String[]
  }

  void 'test RASP Path.of from URI'() {
    setup:
    final helper = Mock(FileIORaspHelper)
    FileIORaspHelper.INSTANCE = helper
    final uri = new URI('file:/test.txt')

    when:
    TestPathOfSuite.of(uri)

    then:
    1 * helper.beforeFileLoaded(uri)
  }
}
