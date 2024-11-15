package datadog.trace.civisibility.source

import datadog.compiler.annotations.SourceLines
import spock.lang.Specification

class CompilerAidedLinesResolverTest extends Specification {

  def "test source info retrieval for #methodName"() {
    setup:
    def resolver = new CompilerAidedLinesResolver()
    def method = TestClass.getDeclaredMethod(methodName)

    when:
    def lines = resolver.getMethodLines(method)

    then:
    lines.valid == expectedValid

    if (lines.valid) {
      lines.startLineNumber == expectedStart
      lines.finishLineNumber == expectedFinish
    }

    where:
    methodName                           | expectedValid | expectedStart | expectedFinish
    "methodWithNoLinesInfoInjected"      | false         | -1            | -1
    "methodWithLinesInfoInjected"        | true          | 10            | 20
    "methodWithUnknownLinesInfoInjected" | false         | -1            | -1
  }

  def "test source info retrieval for #ClassTested"() {
    setup:
    def resolver = new CompilerAidedLinesResolver()

    when:
    def lines = resolver.getClassLines(ClassTested)

    then:
    lines.valid == expectedValid

    if (lines.valid) {
      lines.startLineNumber == expectedStart
      lines.finishLineNumber == expectedFinish
    }

    where:
    ClassTested                       | expectedValid | expectedStart | expectedFinish
    ClassWithNoLinesInfoInjected      | false         | -1            | -1
    ClassWithLinesInfoInjected        | true          | 10            | 20
    ClassWithUnknownLinesInfoInjected | false         | -1            | -1
  }

  private static final class TestClass {
    void methodWithNoLinesInfoInjected() {}

    @SourceLines(start = 10, end = 20)
    void methodWithLinesInfoInjected() {}

    @SourceLines(start = -1, end = -1)
    void methodWithUnknownLinesInfoInjected() {}
  }

  private static final class ClassWithNoLinesInfoInjected {}

  @SourceLines(start = 10, end = 20)
  private static final class ClassWithLinesInfoInjected {}

  @SourceLines(start = -1, end = -1)
  private static final class ClassWithUnknownLinesInfoInjected {}
}
