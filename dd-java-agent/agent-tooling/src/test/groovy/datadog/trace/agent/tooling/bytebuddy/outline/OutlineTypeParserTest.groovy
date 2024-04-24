package datadog.trace.agent.tooling.bytebuddy.outline

import datadog.trace.agent.tooling.bytebuddy.ClassFileLocators
import spock.lang.Specification

class OutlineTypeParserTest extends Specification {

  void 'test anonymous classes are detected'() {
    setup:
    final parser = new OutlineTypeParser()
    final locator = ClassFileLocators.classFileLocator(Thread.currentThread().contextClassLoader)
    final bytes = locator.locate(clazz).resolve()

    when:
    final outline = parser.parse(bytes)

    then:
    outline.anonymousType == anonymous

    where:
    clazz                                       | anonymous
    'datadog.trace.agent.test.AnonymousClass'   | false
    'datadog.trace.agent.test.AnonymousClass$1' | true
    'datadog.trace.agent.test.AnonymousClass$2' | true
  }
}
