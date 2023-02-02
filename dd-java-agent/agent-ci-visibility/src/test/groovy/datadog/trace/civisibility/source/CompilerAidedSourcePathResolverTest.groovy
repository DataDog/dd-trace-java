package datadog.trace.civisibility.source

import datadog.compiler.annotations.SourcePath
import spock.lang.Specification

class CompilerAidedSourcePathResolverTest extends Specification {

  public static final String REPO_ROOT = "/repo/root"
  public static final String SOURCE_PATH_VALUE = "/repo/root/path/to/AClassWithSourceInfoInjected.java"
  public static final String SOURCE_PATH_OUTSIDE_REPO_VALUE = "/outside/path/to/AClassWithSourceInfoInjected.java"

  def "test source info retrieval for #clazz"() {
    setup:
    def sourcePathResolver = new CompilerAidedSourcePathResolver(REPO_ROOT)

    when:
    def path = sourcePathResolver.getSourcePath(clazz)

    then:
    path == expectedPath

    where:
    clazz                             | expectedPath
    AClassWithNoSourceInfoInjected    | null
    AClassWithSourceInfoInjected      | "path/to/AClassWithSourceInfoInjected.java"
    AClassWithSourceOutsideRepository | null
  }

  private static final class AClassWithNoSourceInfoInjected {}

  @SourcePath(SOURCE_PATH_VALUE)
  private static final class AClassWithSourceInfoInjected {}

  @SourcePath(SOURCE_PATH_OUTSIDE_REPO_VALUE)
  private static final class AClassWithSourceOutsideRepository {}
}
