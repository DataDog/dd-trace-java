package datadog.trace.instrumentation.java.io

import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.PathTraversalModule
import foo.bar.TestPathSuite

import java.util.function.BiFunction

import static datadog.trace.api.gateway.Events.EVENTS

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
    final callbackProvider = Mock(CallbackProvider)
    final listener = Mock(BiFunction)
    tracer.getCallbackProvider(RequestContextSlot.APPSEC) >> callbackProvider
    final path = 'test.txt'

    when:
    TestPathSuite.resolve(getRootFolder().toPath(), path)

    then:
    1 * callbackProvider.getCallback(EVENTS.fileLoaded()) >> listener
    1 * listener.apply(reqCtx, path)
  }

  void 'test RASP resolve sibling'() {
    setup:
    final callbackProvider = Mock(CallbackProvider)
    final listener = Mock(BiFunction)
    tracer.getCallbackProvider(RequestContextSlot.APPSEC) >> callbackProvider
    final sibling = newFile('test1.txt').toPath()
    final path = 'test2.txt'

    when:
    TestPathSuite.resolveSibling(sibling, path)

    then:
    1 * callbackProvider.getCallback(EVENTS.fileLoaded()) >> listener
    1 * listener.apply(reqCtx, path)
  }
}
