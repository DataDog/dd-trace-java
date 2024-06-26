package datadog.trace.civisibility.source.index

import datadog.trace.util.ClassNameTrie
import spock.lang.Specification

class RepoIndexTest extends Specification {

  def "test serialization and deserialization"() {
    given:
    def trieBuilder = new ClassNameTrie.Builder()
    trieBuilder.put(RepoIndexTest.name + Language.GROOVY.extension, 0)
    trieBuilder.put(RepoIndexSourcePathResolverTest.name + Language.GROOVY.extension, 1)

    def sourceRoots = Arrays.asList("myClassSourceRoot", "myOtherClassSourceRoot")
    def repoIndex = new RepoIndex(trieBuilder.buildTrie(), sourceRoots, Collections.emptyList())

    when:
    def serialized = repoIndex.serialize()
    def deserialized = RepoIndex.deserialize(serialized)

    then:
    deserialized.getSourcePath(RepoIndexTest) == "myClassSourceRoot/" + RepoIndexTest.name.replace('.', '/') + Language.GROOVY.extension
    deserialized.getSourcePath(RepoIndexSourcePathResolverTest) == "myOtherClassSourceRoot/" + RepoIndexSourcePathResolverTest.name.replace('.', '/') + Language.GROOVY.extension
  }
}
