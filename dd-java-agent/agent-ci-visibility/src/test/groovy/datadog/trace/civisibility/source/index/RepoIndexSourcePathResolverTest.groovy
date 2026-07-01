package datadog.trace.civisibility.source.index

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import datadog.trace.api.Config
import datadog.trace.api.civisibility.domain.Language
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
    def sourcePathResolver = new RepoIndexSourcePathResolver(new RepoIndexBuilder(config, repoRoot, packageResolver, resourceResolver, fileSystem))
    def sourcePaths = sourcePathResolver.getSourcePaths(RepoIndexSourcePathResolverTest)

    then:
    sourcePaths.size() == 1
    sourcePaths.contains(expectedSourcePath)
  }

  def "test source path resolution for inner class"() {
    setup:
    def expectedSourcePath = givenSourceFile(RepoIndexSourcePathResolverTest, repoRoot + "/src")

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(new RepoIndexBuilder(config, repoRoot, packageResolver, resourceResolver, fileSystem))
    def sourcePaths = sourcePathResolver.getSourcePaths(InnerClass)

    then:
    sourcePaths.size() == 1
    sourcePaths.contains(expectedSourcePath)
  }

  def "test source path resolution for nested inner class"() {
    setup:
    def expectedSourcePath = givenSourceFile(RepoIndexSourcePathResolverTest, repoRoot + "/src")

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(new RepoIndexBuilder(config, repoRoot, packageResolver, resourceResolver, fileSystem))
    def sourcePaths = sourcePathResolver.getSourcePaths(InnerClass.NestedInnerClass)

    then:
    sourcePaths.size() == 1
    sourcePaths.contains(expectedSourcePath)
  }

  def "test source path resolution for anonymous class"() {
    setup:
    def expectedSourcePath = givenSourceFile(RepoIndexSourcePathResolverTest, repoRoot + "/src")

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(new RepoIndexBuilder(config, repoRoot, packageResolver, resourceResolver, fileSystem))
    def r = new Runnable() {
        void run() {}
      }
    def sourcePaths = sourcePathResolver.getSourcePaths(r.getClass())

    then:
    sourcePaths.size() == 1
    sourcePaths.contains(expectedSourcePath)
  }

  def "test source path resolution for package-private class"() {
    setup:
    def expectedSourcePath = givenSourceFile(RepoIndexSourcePathResolverTest, repoRoot + "/src")

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(new RepoIndexBuilder(config, repoRoot, packageResolver, resourceResolver, fileSystem))
    def sourcePaths = sourcePathResolver.getSourcePaths(PackagePrivateClass)

    then:
    sourcePaths.size() == 1
    sourcePaths.contains(expectedSourcePath)
  }

  def "test source path resolution for class nested into package-private class"() {
    setup:
    def expectedSourcePath = givenSourceFile(RepoIndexSourcePathResolverTest, repoRoot + "/src")

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(new RepoIndexBuilder(config, repoRoot, packageResolver, resourceResolver, fileSystem))
    def sourcePaths = sourcePathResolver.getSourcePaths(PackagePrivateClass.NestedIntoPackagePrivateClass)

    then:
    sourcePaths.size() == 1
    sourcePaths.contains(expectedSourcePath)
  }

  def "test source path resolution for non-java class whose file name is different from class name"() {
    setup:
    def expectedSourcePath = givenSourceFile(RepoIndexSourcePathResolverTest, repoRoot + "/src")

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(new RepoIndexBuilder(config, repoRoot, packageResolver, resourceResolver, fileSystem))
    def sourcePaths = sourcePathResolver.getSourcePaths(PublicClassWhoseNameDoesNotCorrespondToFileName)

    then:
    sourcePaths.size() == 1
    sourcePaths.contains(expectedSourcePath)
  }

  def "test source path for non-indexed class"() {
    setup:

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(new RepoIndexBuilder(config, repoRoot, packageResolver, resourceResolver, fileSystem))

    then:
    sourcePathResolver.getSourcePaths(RepoIndexSourcePathResolver).isEmpty()
  }

  def "test source path for non-indexed package-private class"() {
    setup:

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(new RepoIndexBuilder(config, repoRoot, packageResolver, resourceResolver, fileSystem))

    then:
    sourcePathResolver.getSourcePaths(PackagePrivateClass).isEmpty()
  }

  def "test file-indexing failure"() {
    setup:
    def classPath = fileSystem.getPath(generateSourceFileName(RepoIndexSourcePathResolverTest, repoRoot))
    packageResolver.getPackage(classPath) >> { throw new IOException() }

    Files.createDirectories(classPath.getParent())
    Files.write(classPath, "STUB CLASS BODY".getBytes())

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(new RepoIndexBuilder(config, repoRoot, packageResolver, resourceResolver, fileSystem))

    then:
    sourcePathResolver.getSourcePaths(RepoIndexSourcePathResolverTest).isEmpty()
  }

  def "test source path resolution for repo with multiple files"() {
    setup:
    def expectedSourcePathOne = givenSourceFile(RepoIndexSourcePathResolverTest, repoRoot + "/src")
    def expectedSourcePathTwo = givenSourceFile(RepoIndexSourcePathResolver, repoRoot + "/src", Language.JAVA)

    givenRepoFile(fileSystem.getPath(repoRoot, "README.md"))

    when:
    def sourcePathResolver = new RepoIndexSourcePathResolver(new RepoIndexBuilder(config, repoRoot, packageResolver, resourceResolver, fileSystem))
    def sourcePathsOne = sourcePathResolver.getSourcePaths(RepoIndexSourcePathResolverTest)
    def sourcePathsTwo = sourcePathResolver.getSourcePaths(InnerClass)
    def sourcePathsThree = sourcePathResolver.getSourcePaths(RepoIndexSourcePathResolver)

    then:
    sourcePathsOne.size() == 1
    sourcePathsOne.contains(expectedSourcePathOne)
    sourcePathsTwo.size() == 1
    sourcePathsTwo.contains(expectedSourcePathOne)
    sourcePathsThree.size() == 1
    sourcePathsThree.contains(expectedSourcePathTwo)
  }

  def "test trying to resolve a duplicate key returns both candidates"() {
    setup:
    def expectedJavaPath = givenSourceFile(RepoIndexSourcePathResolverTest, repoRoot + "/src/java")
    def expectedScalaPath = givenSourceFile(RepoIndexSourcePathResolverTest, repoRoot + "/src/scala")

    def sourcePathResolver = new RepoIndexSourcePathResolver(new RepoIndexBuilder(config, repoRoot, packageResolver, resourceResolver, fileSystem))

    when:
    def sourcePaths = sourcePathResolver.getSourcePaths(RepoIndexSourcePathResolverTest)

    then:
    sourcePaths.size() == 2
    sourcePaths.containsAll([expectedJavaPath, expectedScalaPath])
  }

  private String givenSourceFile(Class c, String sourceRoot, Language language = Language.GROOVY) {
    def classPath = fileSystem.getPath(generateSourceFileName(c, sourceRoot, language))
    packageResolver.getPackage(classPath) >> fileSystem.getPath(sourceRoot).relativize(classPath).getParent()

    givenRepoFile(classPath)

    return fileSystem.getPath(repoRoot).relativize(classPath).toString()
  }

  private static void givenRepoFile(Path file) {
    Files.createDirectories(file.getParent())
    Files.write(file, "STUB FILE BODY".getBytes())
  }

  private static String generateSourceFileName(Class c, String sourceRoot, Language language = Language.GROOVY) {
    return sourceRoot + File.separator + c.getName().replace(".", File.separator) + language.extension
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
