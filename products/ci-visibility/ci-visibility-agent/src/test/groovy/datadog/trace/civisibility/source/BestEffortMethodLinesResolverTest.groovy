package datadog.trace.civisibility.source


import spock.lang.Specification

class BestEffortMethodLinesResolverTest extends Specification {

  def "test get source info from delegate"() {
    setup:
    def testMethod = TestClass.getDeclaredMethod("testMethod")
    def expectedLines = new MethodLinesResolver.MethodLines(42, 43)

    def delegate = Stub(MethodLinesResolver)
    def secondDelegate = Stub(MethodLinesResolver)
    def resolver = new BestEffortMethodLinesResolver(delegate, secondDelegate)

    delegate.getLines(testMethod) >> expectedLines
    secondDelegate.getLines(testMethod) >> MethodLinesResolver.MethodLines.EMPTY

    when:
    def lines = resolver.getLines(testMethod)

    then:
    lines == expectedLines
  }

  def "test get source info from second delegate"() {
    setup:
    def testMethod = TestClass.getDeclaredMethod("testMethod")
    def expectedLines = new MethodLinesResolver.MethodLines(42, 43)

    def delegate = Stub(MethodLinesResolver)
    def secondDelegate = Stub(MethodLinesResolver)
    def resolver = new BestEffortMethodLinesResolver(delegate, secondDelegate)

    delegate.getLines(testMethod) >> MethodLinesResolver.MethodLines.EMPTY
    secondDelegate.getLines(testMethod) >> expectedLines

    when:
    def lines = resolver.getLines(testMethod)

    then:
    lines == expectedLines
  }

  def "test failed to get info from both delegates"() {
    setup:
    def testMethod = TestClass.getDeclaredMethod("testMethod")

    def delegate = Stub(MethodLinesResolver)
    def secondDelegate = Stub(MethodLinesResolver)
    def resolver = new BestEffortMethodLinesResolver(delegate, secondDelegate)

    delegate.getLines(testMethod) >> MethodLinesResolver.MethodLines.EMPTY
    secondDelegate.getLines(testMethod) >> MethodLinesResolver.MethodLines.EMPTY

    when:
    def lines = resolver.getLines(testMethod)

    then:
    lines == MethodLinesResolver.MethodLines.EMPTY
  }

  private static final class TestClass {
    void testMethod() {}
  }
}
