package datadog.trace.api.iast

import datadog.trace.test.util.DDSpecification

class IastModuleTest extends DDSpecification {

  void 'exceptions are logged'() {
    setup:
    final module = new IastModule() { }

    when:
    module.onUnexpectedException('hello', new Error('Boom!!!'))

    then:
    noExceptionThrown()
  }
}
