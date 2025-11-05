package datadog.trace.agent.tooling.bytebuddy.outline

import datadog.trace.agent.tooling.bytebuddy.ClassFileLocators
import spock.lang.Specification

class OutlineTypeParserTest extends Specification {

  void 'test modifiers are correct'() {
    setup:
    final parser = new OutlineTypeParser()
    final locator = ClassFileLocators.classFileLocator(Thread.currentThread().contextClassLoader)
    final bytes = locator.locate(clazz).resolve()

    when:
    final outline = parser.parse(bytes)

    then:
    outline.interface == isinterface
    outline.abstract == isabstract
    outline.annotation == annotation
    outline.enum == isenum

    // isAnonymousType is no longer supported in outlines for performance reasons

    where:
    clazz                                                    | anonymous | isinterface | isabstract | annotation | isenum
    'datadog.trace.agent.test.EnclosedClasses'               | false     | false       | false      | false      | false
    'datadog.trace.agent.test.EnclosedClasses$Inner'         | false     | false       | false      | false      | false
    'datadog.trace.agent.test.EnclosedClasses$InnerStatic'   | false     | false       | false      | false      | false
    'datadog.trace.agent.test.EnclosedClasses$1'             | true      | false       | false      | false      | false
    'datadog.trace.agent.test.EnclosedClasses$2'             | true      | false       | false      | false      | false
    'datadog.trace.agent.test.EnclosedClasses$Inner$1'       | true      | false       | false      | false      | false
    'datadog.trace.agent.test.EnclosedClasses$InnerStatic$1' | true      | false       | false      | false      | false
    'datadog.trace.agent.test.EnclosedClasses$Interface'     | false     | true        | true       | false      | false
    'datadog.trace.agent.test.EnclosedClasses$Abstract'      | false     | false       | true       | false      | false
    'datadog.trace.agent.test.EnclosedClasses$Annotation'    | false     | true        | true       | true       | false
    'datadog.trace.agent.test.EnclosedClasses$Enum'          | false     | false       | false      | false      | true
  }
}
