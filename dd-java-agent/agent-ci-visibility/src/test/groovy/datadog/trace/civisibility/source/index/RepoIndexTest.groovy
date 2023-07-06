package datadog.trace.civisibility.source.index

import datadog.trace.util.ClassNameTrie
import spock.lang.Specification

class RepoIndexTest extends Specification {

  def "test serialization and deserialization"() {
    given:
    def trieBuilder = new ClassNameTrie.Builder()
    trieBuilder.put(RepoIndexTest.name + SourceType.GROOVY.extension, 0)
    trieBuilder.put(RepoIndexSourcePathResolverTest.name + SourceType.GROOVY.extension, 1)

    def sourceRoots = Arrays.asList("myClassSourceRoot", "myOtherClassSourceRoot")
    def repoIndex = new RepoIndex(trieBuilder.buildTrie(), sourceRoots)

    when:
    def serialized = repoIndex.serialize()
    def deserialized = RepoIndex.deserialize(serialized)

    then:
    deserialized.getSourcePath(RepoIndexTest) == "myClassSourceRoot/" + RepoIndexTest.name.replace('.', '/') + SourceType.GROOVY.extension
    deserialized.getSourcePath(RepoIndexSourcePathResolverTest) == "myOtherClassSourceRoot/" + RepoIndexSourcePathResolverTest.name.replace('.', '/') + SourceType.GROOVY.extension
  }
}
