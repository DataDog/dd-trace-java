package datadog.trace.civisibility.source


import spock.lang.Specification

class BestEffortSourcePathResolverTest extends Specification {

  def "test get source paths from first delegate"() {
    setup:
    def expectedPaths = ["source/path/TestClass.java"]
    def delegate = Stub(SourcePathResolver)
    def secondDelegate = Stub(SourcePathResolver)
    def resolver = new BestEffortSourcePathResolver(delegate, secondDelegate)

    delegate.getSourcePaths(TestClass) >> expectedPaths
    secondDelegate.getSourcePaths(TestClass) >> []

    when:
    def paths = resolver.getSourcePaths(TestClass)

    then:
    paths == expectedPaths
  }

  def "test get source paths from second delegate when first returns empty"() {
    setup:
    def expectedPaths = ["debug/path/TestClass.java", "release/path/TestClass.java"]
    def delegate = Stub(SourcePathResolver)
    def secondDelegate = Stub(SourcePathResolver)
    def resolver = new BestEffortSourcePathResolver(delegate, secondDelegate)

    delegate.getSourcePaths(TestClass) >> []
    secondDelegate.getSourcePaths(TestClass) >> expectedPaths

    when:
    def paths = resolver.getSourcePaths(TestClass)

    then:
    paths == expectedPaths
  }

  def "test failed to get source paths from both delegates"() {
    setup:
    def delegate = Stub(SourcePathResolver)
    def secondDelegate = Stub(SourcePathResolver)
    def resolver = new BestEffortSourcePathResolver(delegate, secondDelegate)

    delegate.getSourcePaths(TestClass) >> []
    secondDelegate.getSourcePaths(TestClass) >> []

    when:
    def paths = resolver.getSourcePaths(TestClass)

    then:
    paths.isEmpty()
  }

  private static final class TestClass {}
}
