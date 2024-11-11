package datadog.trace.civisibility.source


import spock.lang.Specification

class BestEffortLinesResolverTest extends Specification {

  def "test get source info from delegate"() {
    setup:
    def testMethod = TestClass.getDeclaredMethod("testMethod")
    def expectedLines = new LinesResolver.MethodLines(42, 43)

    def delegate = Stub(LinesResolver)
    def secondDelegate = Stub(LinesResolver)
    def resolver = new BestEffortLinesResolver(delegate, secondDelegate)

    delegate.getMethodLines(testMethod) >> expectedLines
    secondDelegate.getMethodLines(testMethod) >> LinesResolver.MethodLines.EMPTY

    when:
    def lines = resolver.getMethodLines(testMethod)

    then:
    lines == expectedLines
  }

  def "test get source info from second delegate"() {
    setup:
    def testMethod = TestClass.getDeclaredMethod("testMethod")
    def expectedLines = new LinesResolver.MethodLines(42, 43)

    def delegate = Stub(LinesResolver)
    def secondDelegate = Stub(LinesResolver)
    def resolver = new BestEffortLinesResolver(delegate, secondDelegate)

    delegate.getMethodLines(testMethod) >> LinesResolver.MethodLines.EMPTY
    secondDelegate.getMethodLines(testMethod) >> expectedLines

    when:
    def lines = resolver.getMethodLines(testMethod)

    then:
    lines == expectedLines
  }

  def "test failed to get info from both delegates"() {
    setup:
    def testMethod = TestClass.getDeclaredMethod("testMethod")

    def delegate = Stub(LinesResolver)
    def secondDelegate = Stub(LinesResolver)
    def resolver = new BestEffortLinesResolver(delegate, secondDelegate)

    delegate.getMethodLines(testMethod) >> LinesResolver.MethodLines.EMPTY
    secondDelegate.getMethodLines(testMethod) >> LinesResolver.MethodLines.EMPTY

    when:
    def lines = resolver.getMethodLines(testMethod)

    then:
    lines == LinesResolver.MethodLines.EMPTY
  }

  private static final class TestClass {
    void testMethod() {}
  }
}
