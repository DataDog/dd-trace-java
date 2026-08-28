package datadog.trace.civisibility.source.index

import datadog.instrument.utils.ClassNameTrie
import datadog.trace.api.civisibility.domain.Language
import java.nio.file.Paths
import spock.lang.Specification

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
    deserialized.getSourcePaths(RepoIndexTest).contains(sourcePath("myClassSourceRoot", myClassName))
    deserialized.getSourcePaths(RepoIndexSourcePathResolverTest).size() == 1
    deserialized.getSourcePaths(RepoIndexSourcePathResolverTest).contains(sourcePath("myOtherClassSourceRoot", myOtherClassName))
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
        sourcePath("sourceRoot1", myClassName),
        sourcePath("sourceRoot2", myClassName)
      ]]

    def repoIndex = new RepoIndex(trie, duplicateKeys, sourceRoots, Collections.emptyList())

    when:
    def serialized = repoIndex.serialize()
    def deserialized = RepoIndex.deserialize(serialized)

    then:
    def paths = deserialized.getSourcePaths(RepoIndexTest)
    paths.size() == 2
    paths.containsAll([sourcePath("sourceRoot1", myClassName), sourcePath("sourceRoot2", myClassName)])
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

    def expectedPath1 = sourcePath("debug", myClassName)
    def expectedPath2 = sourcePath("release", myClassName)
    def duplicateKeys = [(myClassName): [expectedPath1, expectedPath2]]

    def repoIndex = new RepoIndex(trie, duplicateKeys, sourceRoots, Collections.emptyList())

    when:
    def paths = repoIndex.getSourcePaths(RepoIndexTest)

    then:
    paths.size() == 2
    paths.containsAll([expectedPath1, expectedPath2])
  }

  def "test getSourcePaths returns single path for non-duplicate key"() {
    given:
    def myClassName = RepoIndexTest.name

    def trieBuilder = new ClassNameTrie.Builder()
    trieBuilder.put(myClassName, 0)
    def trie = trieBuilder.buildTrie()

    def sourceRoots = Arrays.asList(
      new RepoIndex.SourceRoot("src/main/groovy", Language.GROOVY))

    def repoIndex = new RepoIndex(trie, Collections.emptyMap(), sourceRoots, Collections.emptyList())

    when:
    def paths = repoIndex.getSourcePaths(RepoIndexTest)

    then:
    paths.size() == 1
    paths.first() == sourcePath("src/main/groovy", myClassName)
  }

  private static String sourcePath(String sourceRoot, String className) {
    return Paths.get(sourceRoot, className.replace('.' as char, File.separatorChar) + Language.GROOVY.extension).toString()
  }
}
