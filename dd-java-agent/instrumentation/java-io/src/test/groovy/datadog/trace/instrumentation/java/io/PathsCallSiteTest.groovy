package datadog.trace.instrumentation.java.io

import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.PathTraversalModule
import foo.bar.TestPathsSuite

import java.nio.file.FileSystems
import java.util.function.BiFunction

import static datadog.trace.api.gateway.Events.EVENTS

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
    final callbackProvider = Mock(CallbackProvider)
    final listener = Mock(BiFunction)
    tracer.getCallbackProvider(RequestContextSlot.APPSEC) >> callbackProvider
    final stringToCheck = first + FileSystems.getDefault().getSeparator() + String.join(FileSystems.getDefault().getSeparator(), other)

    when:
    TestPathsSuite.get(first, other)

    then:
    1 * callbackProvider.getCallback(EVENTS.fileLoaded()) >> listener
    1 * listener.apply(reqCtx, stringToCheck)

    where:
    first      | other
    'test.txt' | [] as String[]
    '/tmp'     | ['log', 'test.txt'] as String[]
  }

  void 'test RASP get path from uri'() {
    setup:
    final callbackProvider = Mock(CallbackProvider)
    final listener = Mock(BiFunction)
    tracer.getCallbackProvider(RequestContextSlot.APPSEC) >> callbackProvider
    final file = new URI('file:/test.txt')

    when:
    TestPathsSuite.get(file)

    then:
    1 * callbackProvider.getCallback(EVENTS.fileLoaded()) >> listener
    1 * listener.apply(reqCtx, file.toString())
  }
}
