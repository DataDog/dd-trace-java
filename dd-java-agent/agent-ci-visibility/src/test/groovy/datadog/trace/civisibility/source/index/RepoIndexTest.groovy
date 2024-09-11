package datadog.trace.civisibility.source.index

import datadog.trace.api.civisibility.domain.Language
import datadog.trace.util.ClassNameTrie
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

    def repoIndex = new RepoIndex(trie, sourceRoots, Collections.emptyList())

    when:
    def serialized = repoIndex.serialize()
    def deserialized = RepoIndex.deserialize(serialized)

    then:
    deserialized.getSourcePath(RepoIndexTest) == "myClassSourceRoot/" + myClassName.replace('.' as char, File.separatorChar) + Language.GROOVY.extension
    deserialized.getSourcePath(RepoIndexSourcePathResolverTest) == "myOtherClassSourceRoot/" + myOtherClassName.replace('.' as char, File.separatorChar) + Language.GROOVY.extension
  }
}
