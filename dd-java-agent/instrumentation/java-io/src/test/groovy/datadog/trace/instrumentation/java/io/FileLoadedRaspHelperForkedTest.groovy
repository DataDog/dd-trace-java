package datadog.trace.instrumentation.java.io

import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.instrumentation.java.lang.FileLoadedRaspHelper

import java.util.function.BiFunction

import static datadog.trace.api.gateway.Events.EVENTS

class FileLoadedRaspHelperForkedTest extends BaseIoRaspCallSiteTest {

  void 'test Helper'() {
    setup:
    final callbackProvider = Mock(CallbackProvider)
    final listener = Mock(BiFunction)
    final flow = Mock(Flow)
    tracer.getCallbackProvider(RequestContextSlot.APPSEC) >> callbackProvider

    when:
    FileLoadedRaspHelper.INSTANCE.beforeFileLoaded(*args)

    then:
    1 * callbackProvider.getCallback(EVENTS.fileLoaded()) >> listener
    1 * listener.apply(reqCtx, expected) >> flow

    where:
    args                                      | expected
    ['test.txt']                              | 'test.txt'
    ['/home/test', 'test.txt']                | '/home/test/test.txt'
    [new File('/home/test'), 'test.txt']      | '/home/test/test.txt'
    [new URI('file:/test.txt')]               | 'file:/test.txt'
    ['/tmp', ['log', 'test.txt'] as String[]] | '/tmp/log/test.txt'
    ['test.txt', [] as String[]]              | 'test.txt'
  }
}
