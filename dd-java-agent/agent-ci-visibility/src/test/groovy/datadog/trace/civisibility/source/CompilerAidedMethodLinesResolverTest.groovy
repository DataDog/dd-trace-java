package datadog.trace.civisibility.source

import datadog.compiler.annotations.MethodLines
import spock.lang.Specification

class CompilerAidedMethodLinesResolverTest extends Specification {

  def "test source info retrieval for #methodName"() {
    setup:
    def resolver = new CompilerAidedMethodLinesResolver()
    def method = TestClass.getDeclaredMethod(methodName)

    when:
    def lines = resolver.getLines(method)

    then:
    lines.valid == expectedValid

    if (lines.valid) {
      lines.startLineNumber == expectedStart
      lines.finishLineNumber == expectedFinish
    }

    where:
    methodName                                | expectedValid | expectedStart | expectedFinish
    "methodWithNoLinesInfoInjected"      | false         | -1            | -1
    "methodWithLinesInfoInjected"        | true          | 10            | 20
    "methodWithUnknownLinesInfoInjected" | false         | -1            | -1
  }

  private static final class TestClass {
    void methodWithNoLinesInfoInjected() {}

    @MethodLines(start = 10, end = 20)
    void methodWithLinesInfoInjected() {}

    @MethodLines(start = -1, end = -1)
    void methodWithUnknownLinesInfoInjected() {}
  }
}
