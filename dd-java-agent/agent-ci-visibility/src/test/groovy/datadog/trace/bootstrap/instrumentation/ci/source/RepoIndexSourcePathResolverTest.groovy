package datadog.trace.bootstrap.instrumentation.ci.source

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import groovy.transform.PackageScope
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

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

  def "test source path for non-indexed class"() {
    setup:

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(repoRoot, sourceRootResolver, fileSystem)

    then:
    sourcePathResolver.getSourcePath(RepoIndexSourcePathResolver) == null
  }

  def "test source path for non-indexed package-private class"() {
    setup:

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(repoRoot, sourceRootResolver, fileSystem)

    then:
    sourcePathResolver.getSourcePath(PackagePrivateClass) == null
  }

  def "test file-indexing failure"() {
    setup:
    def classPath = fileSystem.getPath(generateSourceFileName(RepoIndexSourcePathResolverTest, repoRoot))
    sourceRootResolver.getSourceRoot(classPath) >> { throw new IOException() }

    Files.createDirectories(classPath.getParent())
    Files.write(classPath, "STUB CLASS BODY".getBytes())

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(repoRoot, sourceRootResolver, fileSystem)

    then:
    sourcePathResolver.getSourcePath(RepoIndexSourcePathResolverTest) == null
  }

  def "test source path resolution for repo with multiple files"() {
    setup:
    def expectedSourcePathOne = givenSourceFile(RepoIndexSourcePathResolverTest, repoRoot + "/src")
    def expectedSourcePathTwo = givenSourceFile(RepoIndexSourcePathResolver, repoRoot + "/src")

    givenRepoFile(fileSystem.getPath(repoRoot, "README.md"))

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(repoRoot, sourceRootResolver, fileSystem)

    then:
    sourcePathResolver.getSourcePath(RepoIndexSourcePathResolverTest) == expectedSourcePathOne
    sourcePathResolver.getSourcePath(InnerClass) == expectedSourcePathOne
    sourcePathResolver.getSourcePath(RepoIndexSourcePathResolver) == expectedSourcePathTwo
  }

  private String givenSourceFile(Class c, String sourceRoot) {
    def classPath = fileSystem.getPath(generateSourceFileName(c, sourceRoot))
    sourceRootResolver.getSourceRoot(classPath) >> fileSystem.getPath(sourceRoot)

    givenRepoFile(classPath)

    return fileSystem.getPath(repoRoot).relativize(classPath).toString()
  }

  private static void givenRepoFile(Path file) {
    Files.createDirectories(file.getParent())
    Files.write(file, "STUB FILE BODY".getBytes())
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
