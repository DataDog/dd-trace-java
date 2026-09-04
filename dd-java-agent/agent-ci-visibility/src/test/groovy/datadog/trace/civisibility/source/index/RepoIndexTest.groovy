package datadog.trace.civisibility.source.index

import datadog.instrument.utils.ClassNameTrie
import datadog.trace.api.civisibility.domain.Language
import java.nio.file.Paths
import spock.lang.Specification

import static datadog.trace.test.util.PlatformTestUtils.normalizePathSeparators

class RepoIndexTest extends Specification {

  def "test serialization and deserialization"() {
    given:
    def myClassName = RepoIndexTest.name
    def myOtherClassName = RepoIndexSourcePathResolverTest.name

    def trieBuilder = new ClassNameTrie.Builder()
    trieBuilder.put(myClassName, 0)
    trieBuilder.put(myOtherClassName, 1)
    def trie = trieBuilder.buildTrie()

    def sourceRoots = Arrays.asList(
      new RepoIndex.SourceRoot("myClassSourceRoot", Language.GROOVY),
      new RepoIndex.SourceRoot("myOtherClassSourceRoot", Language.GROOVY))

    def repoIndex = new RepoIndex(trie, Collections.emptyMap(), sourceRoots, Collections.emptyList())

    when:
    def serialized = repoIndex.serialize()
    def deserialized = RepoIndex.deserialize(serialized)

    then:
    deserialized.getSourcePaths(RepoIndexTest).size() == 1
    normalizePathSeparators(deserialized.getSourcePaths(RepoIndexTest)).contains(
      "myClassSourceRoot/" + myClassName.replace('.' as char, '/' as char) + Language.GROOVY.extension)
    deserialized.getSourcePaths(RepoIndexSourcePathResolverTest).size() == 1
    normalizePathSeparators(deserialized.getSourcePaths(RepoIndexSourcePathResolverTest)).contains(
      "myOtherClassSourceRoot/" + myOtherClassName.replace('.' as char, '/' as char) + Language.GROOVY.extension)
  }

  def "test serialization and deserialization with duplicate keys"() {
    given:
    def myClassName = RepoIndexTest.name

    def trieBuilder = new ClassNameTrie.Builder()
    trieBuilder.put(myClassName, 0)
    def trie = trieBuilder.buildTrie()

    def sourceRoots = Arrays.asList(
      new RepoIndex.SourceRoot("sourceRoot1", Language.GROOVY),
      new RepoIndex.SourceRoot("sourceRoot2", Language.GROOVY))

    def duplicateKeys = [(myClassName): [
        "sourceRoot1/" + myClassName.replace('.' as char, '/' as char) + Language.GROOVY.extension,
        "sourceRoot2/" + myClassName.replace('.' as char, '/' as char) + Language.GROOVY.extension
      ]]

    def repoIndex = new RepoIndex(trie, duplicateKeys, sourceRoots, Collections.emptyList())

    when:
    def serialized = repoIndex.serialize()
    def deserialized = RepoIndex.deserialize(serialized)

    then:
    def paths = deserialized.getSourcePaths(RepoIndexTest)
    paths.size() == 2
    normalizePathSeparators(paths).containsAll([
      "sourceRoot1/" + myClassName.replace('.' as char, '/' as char) + Language.GROOVY.extension,
      "sourceRoot2/" + myClassName.replace('.' as char, '/' as char) + Language.GROOVY.extension
    ])
  }

  def "test getSourcePaths returns all paths for duplicate key"() {
    given:
    def myClassName = RepoIndexTest.name

    def trieBuilder = new ClassNameTrie.Builder()
    trieBuilder.put(myClassName, 0)
    def trie = trieBuilder.buildTrie()

    def sourceRoots = Arrays.asList(
      new RepoIndex.SourceRoot("debug", Language.GROOVY),
      new RepoIndex.SourceRoot("release", Language.GROOVY))

    def expectedPath1 = "debug/" + myClassName.replace('.' as char, '/' as char) + Language.GROOVY.extension
    def expectedPath2 = "release/" + myClassName.replace('.' as char, '/' as char) + Language.GROOVY.extension
    def duplicateKeys = [(myClassName): [expectedPath1, expectedPath2]]

    def repoIndex = new RepoIndex(trie, duplicateKeys, sourceRoots, Collections.emptyList())

    when:
    def paths = repoIndex.getSourcePaths(RepoIndexTest)

    then:
    paths.size() == 2
    normalizePathSeparators(paths).containsAll([expectedPath1, expectedPath2])
  }

  def "test getSourcePaths returns single path for non-duplicate key"() {
    given:
    def myClassName = RepoIndexTest.name

    def trieBuilder = new ClassNameTrie.Builder()
    trieBuilder.put(myClassName, 0)
    def trie = trieBuilder.buildTrie()

    def sourceRoots = Arrays.asList(
      new RepoIndex.SourceRoot(Paths.get("src", "main", "groovy").toString(), Language.GROOVY))

    def repoIndex = new RepoIndex(trie, Collections.emptyMap(), sourceRoots, Collections.emptyList())

    when:
    def paths = repoIndex.getSourcePaths(RepoIndexTest)

    then:
    paths.size() == 1
    normalizePathSeparators(paths.first()) == "src/main/groovy/" +
      myClassName.replace('.' as char, '/' as char) + Language.GROOVY.extension
  }

  def "test source root uses its filesystem separator"() {
    given:
    def sourceRoot = new RepoIndex.SourceRoot("src\\main\\groovy", Language.GROOVY, '\\' as char)

    expect:
    sourceRoot.resolveSourcePath("example.MyClass") == "src\\main\\groovy\\example\\MyClass.groovy"
  }
}
