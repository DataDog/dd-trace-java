package datadog.trace.civisibility.codeowners


import datadog.trace.civisibility.codeowners.matcher.CharacterMatcher
import spock.lang.Specification

class EntryBuilderTest extends Specification {

  def "test entry #pattern match against #path is #expectedResult"() {
    setup:
    def matcherFactory = new CharacterMatcher.Factory()
    def entry = new EntryBuilder(matcherFactory, pattern + " " + ownersToString(owners)).parse()
    def negatedEntry = new EntryBuilder(matcherFactory, "!" + pattern + " " + ownersToString(owners)).parse()

    when:
    def result = entry.matcher.consume(path.toCharArray(), 0) >= 0
    def negatedResult = negatedEntry.matcher.consume(path.toCharArray(), 0) >= 0

    then:
    entry.owners == owners
    result == expectedResult
    negatedResult == !expectedResult

    where:
    pattern               | owners                    | path                                            | expectedResult
    "someToken"           | ["owner"]                 | "someToken"                                     | true
    "someToken"           | ["owner"]                 | "sometoken"                                     | false
    "someToken"           | ["owner"]                 | "anotherToken"                                  | false
    "someToken"           | ["owner"]                 | "parent/someToken"                              | true
    "someToken"           | ["owner"]                 | "grandparent/parent/someToken"                  | true
    "/someToken"          | ["owner"]                 | "someToken"                                     | true
    "/someToken"          | ["owner"]                 | "sometoken"                                     | false
    "/someToken"          | ["owner"]                 | "anotherToken"                                  | false
    "/someToken"          | ["owner"]                 | "parent/someToken"                              | false
    "/someToken"          | ["owner"]                 | "grandparent/parent/someToken"                  | false
    "someTok?n"           | ["owner"]                 | "someTokkn"                                     | true
    "someTok?n"           | ["owner"]                 | "someTok/n"                                     | false
    "someTok?n"           | ["owner"]                 | "parent/someTokkn"                              | true
    "someTok?n"           | ["owner"]                 | "grandparent/parent/someTokkn"                  | true
    "/someTok?n"          | ["owner"]                 | "someTokkn"                                     | true
    "someTok*n"           | ["owner"]                 | "someTokkn"                                     | true
    "someTok*n"           | ["owner"]                 | "someTok/n"                                     | false
    "someTok*n"           | ["owner"]                 | "someTokkkkn"                                   | true
    "someTok*n"           | ["owner"]                 | "someTokenToken"                                | true
    "someTok*n"           | ["owner"]                 | "someTokenTokkk"                                | false
    "s*meTok*n"           | ["owner"]                 | "someToken"                                     | true
    "s*meTok*n"           | ["owner"]                 | "sabcdefghmeTokabcdefgn"                        | true
    "s*meTok*n"           | ["owner"]                 | "sabcdefghmeTokabcdefg"                         | false
    "someTok*n"           | ["owner"]                 | "parent/someTokkn"                              | true
    "someTok*n"           | ["owner"]                 | "grandparent/parent/someTokkn"                  | true
    "/someTok*n"          | ["owner"]                 | "someTokkn"                                     | true
    "someTok[a-b]n"       | ["owner"]                 | "someToken"                                     | false
    "someTok[a-z]n"       | ["owner"]                 | "someToken"                                     | true
    "someTok[a-z]n"       | ["owner"]                 | "someTokEn"                                     | false
    "someTok[a-z]n"       | ["owner"]                 | "someTok9n"                                     | false
    "someTok[a-zA-Z]n"    | ["owner"]                 | "someToken"                                     | true
    "someTok[a-zA-Z]n"    | ["owner"]                 | "someTokEn"                                     | true
    "someTok[a-zA-Z]n"    | ["owner"]                 | "someTok9n"                                     | false
    "someTok[a-zA-Z0-9]n" | ["owner"]                 | "someToken"                                     | true
    "someTok[a-zA-Z0-9]n" | ["owner"]                 | "someTokEn"                                     | true
    "someTok[a-zA-Z0-9]n" | ["owner"]                 | "someTok9n"                                     | true
    "s*meTok[a-zA-Z0-9]n" | ["owner"]                 | "smeToken"                                      | true
    "s*meTok[a-zA-Z0-9]n" | ["owner"]                 | "smeTokEn"                                      | true
    "s*meTok[a-zA-Z0-9]n" | ["owner"]                 | "smeTok9n"                                      | true
    "s*meTok[a-zA-Z0-9]n" | ["owner"]                 | "sabcdmeToken"                                  | true
    "s*meTok[a-zA-Z0-9]n" | ["owner"]                 | "sabcdmeTokEn"                                  | true
    "s*meTok[a-zA-Z0-9]n" | ["owner"]                 | "sabcdmeTok9n"                                  | true
    "someToken/"          | ["owner"]                 | "someToken"                                     | true
    "someToken/"          | ["owner"]                 | "someToken/child"                               | true
    "someToken/"          | ["owner"]                 | "someToken/child/grandChild"                    | true
    "someToken/"          | ["owner"]                 | "parent/someToken"                              | true
    "someToken/"          | ["owner"]                 | "parent/someToken/child"                        | true
    "someToken/"          | ["owner"]                 | "parent/grandParent/someToken/child/grandChild" | true
    "/someToken/"         | ["owner"]                 | "someToken"                                     | true
    "/someToken/"         | ["owner"]                 | "someToken/child"                               | true
    "/someToken/"         | ["owner"]                 | "someToken/child/grandChild"                    | true
    "/someToken/"         | ["owner"]                 | "parent/someToken"                              | false
    "/someToken/"         | ["owner"]                 | "parent/someToken/child"                        | false
    "/someToken/"         | ["owner"]                 | "parent/grandParent/someToken/child/grandChild" | false
    "some/token"          | ["owner"]                 | "some/token"                                    | true
    "some/token"          | ["owner"]                 | "parent/some/token"                             | false
    "some/token"          | ["owner"]                 | "some/token/child"                              | true
    "some/token"          | ["owner"]                 | "some/tokenSuffix"                              | false
    "some/token/"         | ["owner"]                 | "some/token/child"                              | true
    "some/token/"         | ["owner"]                 | "parent/some/token/child"                       | false
    "some\\ Token"        | ["owner"]                 | "some Token"                                    | true
    "\\#someToken"        | ["owner"]                 | "#someToken"                                    | true
    "**/someToken"        | ["owner"]                 | "someToken"                                     | true
    "**/someToken"        | ["owner"]                 | "parent/someToken"                              | true
    "**/someToken"        | ["owner"]                 | "grandparent/parent/someToken"                  | true
    "**/someToken"        | ["owner"]                 | "anotherToken"                                  | false
    "**/some/token"       | ["owner"]                 | "some/token"                                    | true
    "**/some/token"       | ["owner"]                 | "parent/some/token"                             | true
    "**/some/token"       | ["owner"]                 | "grandparent/parent/some/token"                 | true
    "**/some/token/"      | ["owner"]                 | "some/token"                                    | true
    "**/some/token/"      | ["owner"]                 | "some/token/child"                              | true
    "**/some/token/"      | ["owner"]                 | "some/token/child/grandchild"                   | true
    "**/some/token/"      | ["owner"]                 | "parent/some/token/child"                       | true
    "**/some/token/"      | ["owner"]                 | "parent/some/different/token"                   | false
    "someToken/**"        | ["owner"]                 | "someToken"                                     | true
    "someToken/**"        | ["owner"]                 | "someToken/child"                               | true
    "someToken/**"        | ["owner"]                 | "someToken/child/grandChild"                    | true
    "someToken/**"        | ["owner"]                 | "parent/someToken"                              | false
    "someToken/**"        | ["owner"]                 | "parent/someToken/child"                        | false
    "someToken/**"        | ["owner"]                 | "parent/grandParent/someToken/child/grandChild" | false
    "/someToken/**"       | ["owner"]                 | "someToken"                                     | true
    "/someToken/**"       | ["owner"]                 | "someToken/child"                               | true
    "/someToken/**"       | ["owner"]                 | "someToken/child/grandChild"                    | true
    "/someToken/**"       | ["owner"]                 | "parent/someToken"                              | false
    "/someToken/**"       | ["owner"]                 | "parent/someToken/child"                        | false
    "/someToken/**"       | ["owner"]                 | "parent/grandParent/someToken/child/grandChild" | false
    "some/**/token"       | ["owner"]                 | "some/token"                                    | true
    "some/**/token"       | ["owner"]                 | "some/other/token"                              | true
    "some/**/token"       | ["owner"]                 | "some/yet/another/token"                        | true
    "some/**/token"       | ["owner"]                 | "some/yet/another"                              | false
    "some/**/token"       | ["owner"]                 | "yet/another/token"                             | false
    "someToken"           | ["owner", "anotherOwner"] | "someToken"                                     | true
    "*"                   | ["owner"]                 | "someToken"                                     | true
    "*"                   | ["owner"]                 | "parent/someToken"                              | true
    "*"                   | ["owner"]                 | "parent/someToken/child"                        | true
    "*"                   | ["owner"]                 | "someFile.java"                                 | true
    "**"                  | ["owner"]                 | "someToken"                                     | true
    "**"                  | ["owner"]                 | "parent/someToken"                              | true
    "**"                  | ["owner"]                 | "parent/someToken/child"                        | true
    "**"                  | ["owner"]                 | "someFile.java"                                 | true
    "*.java"              | ["owner"]                 | "someFile.java"                                 | true
    "*.java"              | ["owner"]                 | "parent/someFile.java"                          | true
    "*.java"              | ["owner"]                 | "grandparent/parent/someFile.java"              | true
    "*.java"              | ["owner"]                 | "someFile.js"                                   | false
    "*.java"              | ["owner"]                 | "someFile.java17"                               | false
    "readme.md"           | ["owner"]                 | "readme.md"                                     | true
    "readme.md"           | ["owner"]                 | "parent/readme.md"                              | true
    "readme.md"           | ["owner"]                 | "readme.md5"                                    | false
    "some/*"              | ["owner"]                 | "some/token"                                    | true
    "some/*"              | ["owner"]                 | "some/other/token"                              | false
    "some/*"              | ["owner"]                 | "parent/some/token"                             | false
    "some/*"              | ["owner"]                 | "parent/some/other/token"                       | false
    "**/test*.java"       | ["owner"]                 | "test.java"                                     | true
    "**/test*.java"       | ["owner"]                 | "testFile.java"                                 | true
    "**/test*.java"       | ["owner"]                 | "parent/test.java"                              | true
    "**/test*.java"       | ["owner"]                 | "parent/testFile.java"                          | true
    "**/test*.java"       | ["owner"]                 | "grandparent/parent/test.java"                  | true
    "**/test*.java"       | ["owner"]                 | "grandparent/parent/testFile.java"              | true
    "\\#someToken"        | ["owner"]                 | "#someToken"                                    | true
  }

  private static String ownersToString(Collection<String> owners) {
    def result = new StringBuilder()

    for (String owner : owners) {
      result += owner + " " // trailing spaces will be ignored by the matcher
    }

    return result.toString()
  }

  def "test invalid entry parsing: #entry"() {
    setup:
    def matcherFactory = new CharacterMatcher.Factory()

    when:
    def parsedEntry = new EntryBuilder(matcherFactory, entry).parse()

    then:
    parsedEntry == null

    where:
    entry << [
      "token[z-a] owner",
      "# comment",
      " # comment with a leading space",
      "[section header]",
      " [section header with a leading space]",
      "",
      " ",
    ]
  }
}
