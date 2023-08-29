package datadog.trace.civisibility.source


import spock.lang.Specification

class BestEffortSourcePathResolverTest extends Specification {

  def "test get source info from delegate"() {
    setup:
    def expectedPath = "source/path/TestClass.java"
    def delegate = Stub(SourcePathResolver)
    def secondDelegate = Stub(SourcePathResolver)
    def resolver = new BestEffortSourcePathResolver(delegate, secondDelegate)

    delegate.getSourcePath(TestClass) >> expectedPath
    secondDelegate.getSourcePath(TestClass) >> null

    when:
    def path = resolver.getSourcePath(TestClass)

    then:
    path == expectedPath
  }

  def "test get source info from second delegate"() {
    setup:
    def expectedPath = "source/path/TestClass.java"
    def delegate = Stub(SourcePathResolver)
    def secondDelegate = Stub(SourcePathResolver)
    def resolver = new BestEffortSourcePathResolver(delegate, secondDelegate)

    delegate.getSourcePath(TestClass) >> null
    secondDelegate.getSourcePath(TestClass) >> expectedPath

    when:
    def path = resolver.getSourcePath(TestClass)

    then:
    path == expectedPath
  }

  def "test failed to get info from both delegates"() {
    setup:
    def delegate = Stub(SourcePathResolver)
    def secondDelegate = Stub(SourcePathResolver)
    def resolver = new BestEffortSourcePathResolver(delegate, secondDelegate)

    delegate.getSourcePath(TestClass) >> null
    secondDelegate.getSourcePath(TestClass) >> null

    when:
    def path = resolver.getSourcePath(TestClass)

    then:
    path == null
  }

  private static final class TestClass {}
}
