package datadog.trace.instrumentation.java.io

import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.PathTraversalModule
import foo.bar.TestFileOutputStreamSuite
import groovy.transform.CompileDynamic

import java.util.function.BiFunction

import static datadog.trace.api.gateway.Events.EVENTS

@CompileDynamic
class FileOutputStreamCallSiteTest extends BaseIoRaspCallSiteTest {

  void 'test IAST new file input stream with path'() {
    setup:
    PathTraversalModule iastModule = Mock(PathTraversalModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final path = newFile('test.txt').toString()

    when:
    TestFileOutputStreamSuite.newFileOutputStream(path)

    then:
    1 * iastModule.onPathTraversal(path)
  }

  void 'test IAST new file input stream with path and append'() {
    setup:
    PathTraversalModule iastModule = Mock(PathTraversalModule)
    InstrumentationBridge.registerIastModule(iastModule)
    final path = newFile('test.txt').toString()

    when:
    TestFileOutputStreamSuite.newFileOutputStream(path, false)

    then:
    1 * iastModule.onPathTraversal(path)
  }

  void 'test RASP new file input stream with path'() {
    setup:
    final callbackProvider = Mock(CallbackProvider)
    final listener = Mock(BiFunction)
    tracer.getCallbackProvider(RequestContextSlot.APPSEC) >> callbackProvider
    final path = newFile('test.txt').toString()

    when:
    TestFileOutputStreamSuite.newFileOutputStream(path)

    then:
    1 * callbackProvider.getCallback(EVENTS.fileLoaded()) >> listener
    1 * listener.apply(reqCtx, path)
  }

  void 'test RASP new file input stream with path and append'() {
    setup:
    final callbackProvider = Mock(CallbackProvider)
    final listener = Mock(BiFunction)
    tracer.getCallbackProvider(RequestContextSlot.APPSEC) >> callbackProvider
    final path = newFile('test.txt').toString()

    when:
    TestFileOutputStreamSuite.newFileOutputStream(path, false)

    then:
    1 * callbackProvider.getCallback(EVENTS.fileLoaded()) >> listener
    1 * listener.apply(reqCtx, path)
  }
}
