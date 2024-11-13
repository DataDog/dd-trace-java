package datadog.trace.civisibility.source


import spock.lang.Specification

class BestEffortLinesResolverTest extends Specification {

  def "test get method source info from delegate"() {
    setup:
    def testMethod = TestClass.getDeclaredMethod("testMethod")
    def expectedLines = new LinesResolver.Lines(42, 43)

    def delegate = Stub(LinesResolver)
    def secondDelegate = Stub(LinesResolver)
    def resolver = new BestEffortLinesResolver(delegate, secondDelegate)

    delegate.getMethodLines(testMethod) >> expectedLines
    secondDelegate.getMethodLines(testMethod) >> LinesResolver.Lines.EMPTY

    when:
    def methodLines = resolver.getMethodLines(testMethod)

    then:
    methodLines == expectedLines
  }

  def "test get class source info from delegate"() {
    setup:
    def expectedLines = new LinesResolver.Lines(42, 43)

    def delegate = Stub(LinesResolver)
    def secondDelegate = Stub(LinesResolver)
    def resolver = new BestEffortLinesResolver(delegate, secondDelegate)

    delegate.getClassLines(TestClass) >> expectedLines
    secondDelegate.getClassLines(TestClass) >> LinesResolver.Lines.EMPTY

    when:
    def classLines = resolver.getClassLines(TestClass)

    then:
    classLines == expectedLines
  }

  def "test get method source info from second delegate"() {
    setup:
    def testMethod = TestClass.getDeclaredMethod("testMethod")
    def expectedLines = new LinesResolver.Lines(42, 43)

    def delegate = Stub(LinesResolver)
    def secondDelegate = Stub(LinesResolver)
    def resolver = new BestEffortLinesResolver(delegate, secondDelegate)

    delegate.getMethodLines(testMethod) >> LinesResolver.Lines.EMPTY
    secondDelegate.getMethodLines(testMethod) >> expectedLines

    when:
    def methodLines = resolver.getMethodLines(testMethod)

    then:
    methodLines == expectedLines
  }

  def "test get class source info from second delegate"() {
    setup:
    def expectedLines = new LinesResolver.Lines(42, 43)

    def delegate = Stub(LinesResolver)
    def secondDelegate = Stub(LinesResolver)
    def resolver = new BestEffortLinesResolver(delegate, secondDelegate)

    delegate.getClassLines(TestClass) >> LinesResolver.Lines.EMPTY
    secondDelegate.getClassLines(TestClass) >> expectedLines

    when:
    def classLines = resolver.getClassLines(TestClass)

    then:
    classLines == expectedLines
  }

  def "test failed to get method info from both delegates"() {
    setup:
    def testMethod = TestClass.getDeclaredMethod("testMethod")

    def delegate = Stub(LinesResolver)
    def secondDelegate = Stub(LinesResolver)
    def resolver = new BestEffortLinesResolver(delegate, secondDelegate)

    delegate.getMethodLines(testMethod) >> LinesResolver.Lines.EMPTY
    secondDelegate.getMethodLines(testMethod) >> LinesResolver.Lines.EMPTY

    when:
    def methodLines = resolver.getMethodLines(testMethod)

    then:
    methodLines == LinesResolver.Lines.EMPTY
  }

  def "test failed to get class info from both delegates"() {
    setup:
    def delegate = Stub(LinesResolver)
    def secondDelegate = Stub(LinesResolver)
    def resolver = new BestEffortLinesResolver(delegate, secondDelegate)

    delegate.getClassLines(TestClass) >> LinesResolver.Lines.EMPTY
    secondDelegate.getClassLines(TestClass) >> LinesResolver.Lines.EMPTY

    when:
    def classLines = resolver.getClassLines(TestClass)

    then:
    classLines == LinesResolver.Lines.EMPTY
  }

  private static final class TestClass {
    void testMethod() {}
  }
}
