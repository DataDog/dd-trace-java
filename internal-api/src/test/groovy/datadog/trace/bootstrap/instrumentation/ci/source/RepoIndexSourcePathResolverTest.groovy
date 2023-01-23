package datadog.trace.bootstrap.instrumentation.ci.source

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import groovy.transform.PackageScope
import spock.lang.Specification

import java.nio.file.Files

class RepoIndexSourcePathResolverTest extends Specification {

  def sourceRootResolver = Stub(SourceRootResolver)
  def fileSystem = Jimfs.newFileSystem(Configuration.unix())
  def repoRoot = "/repo/root"

  def "test source path resolution"() {
    setup:
    def expectedSourcePath = givenSourceFile(RepoIndexSourcePathResolverTest, repoRoot + "/src")

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(repoRoot, sourceRootResolver, fileSystem)

    then:
    sourcePathResolver.getSourcePath(RepoIndexSourcePathResolverTest) == expectedSourcePath
  }

  def "test source path resolution for inner class"() {
    setup:
    def expectedSourcePath = givenSourceFile(RepoIndexSourcePathResolverTest, repoRoot + "/src")

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(repoRoot, sourceRootResolver, fileSystem)

    then:
    sourcePathResolver.getSourcePath(InnerClass) == expectedSourcePath
  }

  def "test source path resolution for nested inner class"() {
    setup:
    def expectedSourcePath = givenSourceFile(RepoIndexSourcePathResolverTest, repoRoot + "/src")

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(repoRoot, sourceRootResolver, fileSystem)

    then:
    sourcePathResolver.getSourcePath(InnerClass.NestedInnerClass) == expectedSourcePath
  }

  def "test source path resolution for anonymous class"() {
    setup:
    def expectedSourcePath = givenSourceFile(RepoIndexSourcePathResolverTest, repoRoot + "/src")

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(repoRoot, sourceRootResolver, fileSystem)
    def r = new Runnable() {
        void run() {}
      }

    then:
    sourcePathResolver.getSourcePath(r.getClass()) == expectedSourcePath
  }

  def "test source path resolution for package-private class"() {
    setup:
    def expectedSourcePath = givenSourceFile(RepoIndexSourcePathResolverTest, repoRoot + "/src")

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(repoRoot, sourceRootResolver, fileSystem)

    then:
    sourcePathResolver.getSourcePath(PackagePrivateClass) == expectedSourcePath
  }

  private String givenSourceFile(Class c, String sourceRoot) {
    def classPath = fileSystem.getPath(generateSourceFileName(c, sourceRoot))
    sourceRootResolver.getSourceRoot(classPath) >> fileSystem.getPath(sourceRoot)

    Files.createDirectories(classPath.getParent())
    Files.write(classPath, "STUB CLASS BODY".getBytes())

    return fileSystem.getPath(repoRoot).relativize(classPath).toString()
  }

  private static String generateSourceFileName(Class c, String sourceRoot) {
    return sourceRoot + File.separator + c.getName().replace(".", File.separator) + RepoIndexSourcePathResolver.SourceType.GROOVY.extension
  }

  private static final class InnerClass {
    private static final class NestedInnerClass {
    }
  }
}

@PackageScope
class PackagePrivateClass {
}
