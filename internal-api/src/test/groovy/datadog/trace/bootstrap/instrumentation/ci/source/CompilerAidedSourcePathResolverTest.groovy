package datadog.trace.bootstrap.instrumentation.ci.source

import datadog.compiler.annotations.SourcePath
import spock.lang.Specification

class CompilerAidedSourcePathResolverTest extends Specification {

  public static final String SOURCE_PATH_VALUE = "/path/to/AClassWithSourceInfoInjected.java"

  def "test source info retrieval for #clazz"() {
    setup:
    def sourcePathResolver = new CompilerAidedSourcePathResolver()

    when:
    def path = sourcePathResolver.getSourcePath(clazz)

    then:
    path == expectedPath

    where:
    clazz                          | expectedPath
    AClassWithNoSourceInfoInjected | null
    AClassWithSourceInfoInjected   | SOURCE_PATH_VALUE
  }

  private static final class AClassWithNoSourceInfoInjected {}

  @SourcePath(SOURCE_PATH_VALUE)
  private static final class AClassWithSourceInfoInjected {
  }
}
