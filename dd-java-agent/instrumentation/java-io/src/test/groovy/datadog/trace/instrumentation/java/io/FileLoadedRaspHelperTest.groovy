package datadog.trace.instrumentation.java.io

import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.instrumentation.java.lang.FileLoadedRaspHelper

import java.util.function.BiFunction

class FileLoadedRaspHelperTest  extends  BaseIoRaspCallSiteTest {

  void 'test Helper'(){
    setup:
    final callbackProvider = Mock(CallbackProvider)
    final listener = Mock(BiFunction)
    tracer.getCallbackProvider(RequestContextSlot.APPSEC) >> callbackProvider

    when:
    FileLoadedRaspHelper.INSTANCE."onFileLoaded"(*args)

    then:
    1 * tracer.getCallbackProvider(RequestContextSlot.APPSEC)
    1 * listener.apply(reqCtx, expected)

    where:
    args | expected
    ['test.txt'] | 'test.txt'


  }

}
