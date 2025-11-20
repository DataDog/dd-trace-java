package datadog.trace.civisibility.source.index

import datadog.instrument.utils.ClassNameTrie
import datadog.trace.api.civisibility.domain.Language
import datadog.trace.civisibility.source.SourceResolutionException
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

    def repoIndex = new RepoIndex(trie, Collections.emptyList(), sourceRoots, Collections.emptyList())

    when:
    def serialized = repoIndex.serialize()
    def deserialized = RepoIndex.deserialize(serialized)

    then:
    deserialized.getSourcePath(RepoIndexTest) == "myClassSourceRoot/" + myClassName.replace('.' as char, File.separatorChar) + Language.GROOVY.extension
    deserialized.getSourcePath(RepoIndexSourcePathResolverTest) == "myOtherClassSourceRoot/" + myOtherClassName.replace('.' as char, File.separatorChar) + Language.GROOVY.extension
  }

  def "test trying to resolve a duplicate key throws exception"() {
    given:
    def duplicateKeys = [RepoIndexTest.name]
    def repoIndex = new RepoIndex(new ClassNameTrie.Builder().buildTrie(), duplicateKeys, Collections.emptyList(), Collections.emptyList())

    when:
    repoIndex.getSourcePath(RepoIndexTest)

    then:
    thrown SourceResolutionException
  }
}
