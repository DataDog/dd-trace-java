package datadog.trace.instrumentation.java.io

import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.PathTraversalModule
import datadog.trace.instrumentation.java.lang.FileLoadedRaspHelper
import foo.bar.TestFileSuite

import java.util.function.BiFunction

import static datadog.trace.api.gateway.Events.EVENTS

class FileCallSiteTest extends BaseIoRaspCallSiteTest {

  void 'test  IAST new file with path'() {
    setup:
    PathTraversalModule iastModule = Mock(PathTraversalModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final path = 'test.txt'

    when:
    TestFileSuite.newFile(path)

    then:
    1 * iastModule.onPathTraversal(path)
  }

  void 'test IAST new file with parent and child'() {
    setup:
    PathTraversalModule iastModule = Mock(PathTraversalModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final parent = '/home/test'
    final child = 'test.txt'

    when:
    TestFileSuite.newFile(parent, child)

    then:
    1 * iastModule.onPathTraversal(parent, child)
  }

  void 'test IAST new file with parent file and child'() {
    setup:
    PathTraversalModule iastModule = Mock(PathTraversalModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final parent = new File('/home/test')
    final child = 'test.txt'

    when:
    TestFileSuite.newFile(parent, child)

    then:
    1 * iastModule.onPathTraversal(parent, child)
  }

  void 'test IAST new file with uri'() {
    setup:
    PathTraversalModule iastModule = Mock(PathTraversalModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final file = new URI('file:/test.txt')

    when:
    TestFileSuite.newFile(file)

    then:
    1 * iastModule.onPathTraversal(file)
  }

  void 'test  RASP new file with path'() {
    setup:
    final callbackProvider = Mock(CallbackProvider)
    final listener = Mock(BiFunction)
    tracer.getCallbackProvider(RequestContextSlot.APPSEC) >> callbackProvider
    final path = 'test.txt'

    when:
    TestFileSuite.newFile(path)

    then:
    1 * callbackProvider.getCallback(EVENTS.fileLoaded()) >> listener
    1 * listener.apply(reqCtx, path)
  }

  void 'test RASP new file with parent and child'() {
    setup:
    final helper = Mock(FileLoadedRaspHelper)
    FileLoadedRaspHelper.INSTANCE = helper
    final parent = '/home/test'
    final child = 'test.txt'

    when:
    TestFileSuite.newFile(parent, child)

    then:
    1 *  helper.beforeFileLoaded(parent, child)
  }

  void 'test RASP new file with parent file and child'() {
    setup:
    final helper = Mock(FileLoadedRaspHelper)
    FileLoadedRaspHelper.INSTANCE = helper
    final parent = new File('/home/test')
    final child = 'test.txt'

    when:
    TestFileSuite.newFile(parent, child)

    then:
    1 *  helper.beforeFileLoaded(parent, child)
  }

  void 'test RASP new file with uri'() {
    setup:
    final helper = Mock(FileLoadedRaspHelper)
    FileLoadedRaspHelper.INSTANCE = helper
    final file = new URI('file:/test.txt')

    when:
    TestFileSuite.newFile(file)

    then:
    1 *  helper.beforeFileLoaded(file)
  }
}
