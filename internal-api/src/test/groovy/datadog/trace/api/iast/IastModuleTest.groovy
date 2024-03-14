package datadog.trace.api.iast

import datadog.trace.api.iast.sink.ApplicationModule
import datadog.trace.api.iast.sink.HstsMissingHeaderModule
import datadog.trace.api.iast.sink.HttpResponseHeaderModule
import datadog.trace.api.iast.sink.InsecureCookieModule
import datadog.trace.api.iast.sink.NoHttpOnlyCookieModule
import datadog.trace.api.iast.sink.NoSameSiteCookieModule
import datadog.trace.api.iast.sink.StacktraceLeakModule
import datadog.trace.api.iast.sink.XContentTypeModule
import datadog.trace.test.util.DDSpecification

class IastModuleTest extends DDSpecification {

  void 'exceptions are logged'() {
    setup:
    final module = new IastModule() {}

    when:
    module.onUnexpectedException('hello', new Error('Boom!!!'))

    then:
    noExceptionThrown()
  }

  void 'test opt-out modules'() {
    given:
    final module = clazz
    final expected = shouldBeOptOut(clazz)

    when:
    final enabled = module.getDeclaredAnnotation(IastModule.OptOut) != null

    then:
    enabled == expected

    where:
    clazz << InstrumentationBridge.getIastModules()
  }

  @SuppressWarnings('GroovyFallthrough')
  private static boolean shouldBeOptOut(final Class<?> target) {
    switch (target) {
      case HttpResponseHeaderModule:
      case InsecureCookieModule:
      case NoHttpOnlyCookieModule:
      case NoSameSiteCookieModule:
      case HstsMissingHeaderModule:
      case XContentTypeModule:
      case ApplicationModule:
      case StacktraceLeakModule:
        return true
      default:
        return false
    }
  }
}
