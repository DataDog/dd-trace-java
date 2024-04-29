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
    clazz                                                    | anonymous
    'datadog.trace.agent.test.EnclosedClasses'               | false
    'datadog.trace.agent.test.EnclosedClasses$Inner'         | false
    'datadog.trace.agent.test.EnclosedClasses$InnerStatic'   | false
    'datadog.trace.agent.test.EnclosedClasses$1'             | true
    'datadog.trace.agent.test.EnclosedClasses$2'             | true
    'datadog.trace.agent.test.EnclosedClasses$Inner$1'       | true
    'datadog.trace.agent.test.EnclosedClasses$InnerStatic$1' | true
  }
}
