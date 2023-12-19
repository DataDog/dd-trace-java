package datadog.trace.civisibility.source.index

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import datadog.trace.api.Config
import groovy.transform.PackageScope
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

class RepoIndexSourcePathResolverTest extends Specification {

  def config = Stub(Config)
  def packageResolver = Stub(PackageResolver)
  def resourceResolver = Stub(ResourceResolver)
  def fileSystem = Jimfs.newFileSystem(Configuration.unix())
  def repoRoot = getRepoRoot()

  def "test source path resolution"() {
    setup:
    def expectedSourcePath = givenSourceFile(RepoIndexSourcePathResolverTest, repoRoot + "/src")

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(config, repoRoot, packageResolver, resourceResolver, fileSystem)

    then:
    sourcePathResolver.getSourcePath(RepoIndexSourcePathResolverTest) == expectedSourcePath
  }

  def "test source path resolution for inner class"() {
    setup:
    def expectedSourcePath = givenSourceFile(RepoIndexSourcePathResolverTest, repoRoot + "/src")

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(config, repoRoot, packageResolver, resourceResolver, fileSystem)

    then:
    sourcePathResolver.getSourcePath(InnerClass) == expectedSourcePath
  }

  def "test source path resolution for nested inner class"() {
    setup:
    def expectedSourcePath = givenSourceFile(RepoIndexSourcePathResolverTest, repoRoot + "/src")

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(config, repoRoot, packageResolver, resourceResolver, fileSystem)

    then:
    sourcePathResolver.getSourcePath(InnerClass.NestedInnerClass) == expectedSourcePath
  }

  def "test source path resolution for anonymous class"() {
    setup:
    def expectedSourcePath = givenSourceFile(RepoIndexSourcePathResolverTest, repoRoot + "/src")

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(config, repoRoot, packageResolver, resourceResolver, fileSystem)
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
    def sourcePathResolver = new RepoIndexSourcePathResolver(config, repoRoot, packageResolver, resourceResolver, fileSystem)

    then:
    sourcePathResolver.getSourcePath(PackagePrivateClass) == expectedSourcePath
  }

  def "test source path resolution for class nested into package-private class"() {
    setup:
    def expectedSourcePath = givenSourceFile(RepoIndexSourcePathResolverTest, repoRoot + "/src")

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(config, repoRoot, packageResolver, resourceResolver, fileSystem)

    then:
    sourcePathResolver.getSourcePath(PackagePrivateClass.NestedIntoPackagePrivateClass) == expectedSourcePath
  }

  def "test source path resolution for non-java class whose file name is different from class name"() {
    setup:
    def expectedSourcePath = givenSourceFile(RepoIndexSourcePathResolverTest, repoRoot + "/src")

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(config, repoRoot, packageResolver, resourceResolver, fileSystem)

    then:
    sourcePathResolver.getSourcePath(PublicClassWhoseNameDoesNotCorrespondToFileName) == expectedSourcePath
  }

  def "test source path for non-indexed class"() {
    setup:

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(config, repoRoot, packageResolver, resourceResolver, fileSystem)

    then:
    sourcePathResolver.getSourcePath(RepoIndexSourcePathResolver) == null
  }

  def "test source path for non-indexed package-private class"() {
    setup:

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(config, repoRoot, packageResolver, resourceResolver, fileSystem)

    then:
    sourcePathResolver.getSourcePath(PackagePrivateClass) == null
  }

  def "test file-indexing failure"() {
    setup:
    def classPath = fileSystem.getPath(generateSourceFileName(RepoIndexSourcePathResolverTest, repoRoot))
    packageResolver.getPackage(classPath) >> { throw new IOException() }

    Files.createDirectories(classPath.getParent())
    Files.write(classPath, "STUB CLASS BODY".getBytes())

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(config, repoRoot, packageResolver, resourceResolver, fileSystem)

    then:
    sourcePathResolver.getSourcePath(RepoIndexSourcePathResolverTest) == null
  }

  def "test source path resolution for repo with multiple files"() {
    setup:
    def expectedSourcePathOne = givenSourceFile(RepoIndexSourcePathResolverTest, repoRoot + "/src")
    def expectedSourcePathTwo = givenSourceFile(RepoIndexSourcePathResolver, repoRoot + "/src", SourceType.JAVA)

    givenRepoFile(fileSystem.getPath(repoRoot, "README.md"))

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(config, repoRoot, packageResolver, resourceResolver, fileSystem)

    then:
    sourcePathResolver.getSourcePath(RepoIndexSourcePathResolverTest) == expectedSourcePathOne
    sourcePathResolver.getSourcePath(InnerClass) == expectedSourcePathOne
    sourcePathResolver.getSourcePath(RepoIndexSourcePathResolver) == expectedSourcePathTwo
  }

  private String givenSourceFile(Class c, String sourceRoot, SourceType sourceType = SourceType.GROOVY) {
    def classPath = fileSystem.getPath(generateSourceFileName(c, sourceRoot, sourceType))
    packageResolver.getPackage(classPath) >> fileSystem.getPath(sourceRoot).relativize(classPath).getParent()

    givenRepoFile(classPath)

    return fileSystem.getPath(repoRoot).relativize(classPath).toString()
  }

  private static void givenRepoFile(Path file) {
    Files.createDirectories(file.getParent())
    Files.write(file, "STUB FILE BODY".getBytes())
  }

  private static String generateSourceFileName(Class c, String sourceRoot, SourceType sourceType = SourceType.GROOVY) {
    return sourceRoot + File.separator + c.getName().replace(".", File.separator) + sourceType.extension
  }

  private static getRepoRoot() {
    def a = RepoIndexSourcePathResolverTest.protectionDomain.codeSource.location.file
    def b = RepoIndexSourcePathResolver.protectionDomain.codeSource.location.file

    def min = Math.min(a.length(), b.length())
    for (int i = 0; i < min; i++) {
      if (a.charAt(i) != b.charAt(i)) {
        return a.substring(0, i)
      }
    }
    return a.substring(0, min)
  }

  private static final class InnerClass {
    private static final class NestedInnerClass {
    }
  }
}

@PackageScope
class PackagePrivateClass {
  class NestedIntoPackagePrivateClass {}
}

class PublicClassWhoseNameDoesNotCorrespondToFileName {
}
