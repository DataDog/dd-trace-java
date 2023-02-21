package datadog.trace.civisibility.source


import spock.lang.Specification

class MethodLinesResolverImplTest extends Specification {

  private static abstract class NestedClass {
    static double aTestMethod() {
      def random = Math.random()
      return random
    }

    abstract void abstractMethod()
  }

  def "test method lines resolution"() {
    setup:
    def aTestMethod = NestedClass.getDeclaredMethod("aTestMethod")

    when:
    def methodLinesResolver = new MethodLinesResolverImpl()
    def methodLines = methodLinesResolver.getLines(aTestMethod)

    then:
    methodLines.isValid()
    methodLines.startLineNumber > 0
    methodLines.finishLineNumber > methodLines.startLineNumber
  }

  def "test invalid method lines resolution"() {
    setup:
    def aTestMethod = NestedClass.getDeclaredMethod("abstractMethod")

    when:
    def methodLinesResolver = new MethodLinesResolverImpl()
    def methodLines = methodLinesResolver.getLines(aTestMethod)

    then:
    !methodLines.isValid()
  }
}

