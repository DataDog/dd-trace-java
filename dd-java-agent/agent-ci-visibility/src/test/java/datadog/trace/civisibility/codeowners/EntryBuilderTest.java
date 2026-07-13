package datadog.trace.civisibility.codeowners;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.civisibility.codeowners.matcher.CharacterMatcher;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class EntryBuilderTest {

  @ParameterizedTest(name = "entry {0} match against {2} is {3}")
  @MethodSource("testEntryMatchArguments")
  void testEntryMatch(String pattern, List<String> owners, String path, boolean expectedResult) {
    CharacterMatcher.Factory matcherFactory = new CharacterMatcher.Factory();
    Entry entry = new EntryBuilder(matcherFactory, pattern + " " + ownersToString(owners)).parse();
    Entry negatedEntry =
        new EntryBuilder(matcherFactory, "!" + pattern + " " + ownersToString(owners)).parse();

    boolean result = entry.getMatcher().consume(path.toCharArray(), 0) >= 0;
    boolean negatedResult = negatedEntry.getMatcher().consume(path.toCharArray(), 0) >= 0;

    assertEquals(owners, entry.getOwners());
    assertEquals(expectedResult, result);
    assertEquals(!expectedResult, negatedResult);
  }

  static Stream<Arguments> testEntryMatchArguments() {
    List<String> owner = singletonList("owner");
    return Stream.of(
        arguments("someToken", owner, "someToken", true),
        arguments("someToken", owner, "sometoken", false),
        arguments("someToken", owner, "anotherToken", false),
        arguments("someToken", owner, "parent/someToken", true),
        arguments("someToken", owner, "grandparent/parent/someToken", true),
        arguments("/someToken", owner, "someToken", true),
        arguments("/someToken", owner, "sometoken", false),
        arguments("/someToken", owner, "anotherToken", false),
        arguments("/someToken", owner, "parent/someToken", false),
        arguments("/someToken", owner, "grandparent/parent/someToken", false),
        arguments("someTok?n", owner, "someTokkn", true),
        arguments("someTok?n", owner, "someTok/n", false),
        arguments("someTok?n", owner, "parent/someTokkn", true),
        arguments("someTok?n", owner, "grandparent/parent/someTokkn", true),
        arguments("/someTok?n", owner, "someTokkn", true),
        arguments("someTok*n", owner, "someTokkn", true),
        arguments("someTok*n", owner, "someTok/n", false),
        arguments("someTok*n", owner, "someTokkkkn", true),
        arguments("someTok*n", owner, "someTokenToken", true),
        arguments("someTok*n", owner, "someTokenTokkk", false),
        arguments("s*meTok*n", owner, "someToken", true),
        arguments("s*meTok*n", owner, "sabcdefghmeTokabcdefgn", true),
        arguments("s*meTok*n", owner, "sabcdefghmeTokabcdefg", false),
        arguments("someTok*n", owner, "parent/someTokkn", true),
        arguments("someTok*n", owner, "grandparent/parent/someTokkn", true),
        arguments("/someTok*n", owner, "someTokkn", true),
        arguments("someTok[a-b]n", owner, "someToken", false),
        arguments("someTok[a-z]n", owner, "someToken", true),
        arguments("someTok[a-z]n", owner, "someTokEn", false),
        arguments("someTok[a-z]n", owner, "someTok9n", false),
        arguments("someTok[a-zA-Z]n", owner, "someToken", true),
        arguments("someTok[a-zA-Z]n", owner, "someTokEn", true),
        arguments("someTok[a-zA-Z]n", owner, "someTok9n", false),
        arguments("someTok[a-zA-Z0-9]n", owner, "someToken", true),
        arguments("someTok[a-zA-Z0-9]n", owner, "someTokEn", true),
        arguments("someTok[a-zA-Z0-9]n", owner, "someTok9n", true),
        arguments("s*meTok[a-zA-Z0-9]n", owner, "smeToken", true),
        arguments("s*meTok[a-zA-Z0-9]n", owner, "smeTokEn", true),
        arguments("s*meTok[a-zA-Z0-9]n", owner, "smeTok9n", true),
        arguments("s*meTok[a-zA-Z0-9]n", owner, "sabcdmeToken", true),
        arguments("s*meTok[a-zA-Z0-9]n", owner, "sabcdmeTokEn", true),
        arguments("s*meTok[a-zA-Z0-9]n", owner, "sabcdmeTok9n", true),
        arguments("someToken/", owner, "someToken", true),
        arguments("someToken/", owner, "someToken/child", true),
        arguments("someToken/", owner, "someToken/child/grandChild", true),
        arguments("someToken/", owner, "parent/someToken", true),
        arguments("someToken/", owner, "parent/someToken/child", true),
        arguments("someToken/", owner, "parent/grandParent/someToken/child/grandChild", true),
        arguments("/someToken/", owner, "someToken", true),
        arguments("/someToken/", owner, "someToken/child", true),
        arguments("/someToken/", owner, "someToken/child/grandChild", true),
        arguments("/someToken/", owner, "parent/someToken", false),
        arguments("/someToken/", owner, "parent/someToken/child", false),
        arguments("/someToken/", owner, "parent/grandParent/someToken/child/grandChild", false),
        arguments("some/token", owner, "some/token", true),
        arguments("some/token", owner, "parent/some/token", false),
        arguments("some/token", owner, "some/token/child", true),
        arguments("some/token", owner, "some/tokenSuffix", false),
        arguments("some/token/", owner, "some/token/child", true),
        arguments("some/token/", owner, "parent/some/token/child", false),
        arguments("some\\ Token", owner, "some Token", true),
        arguments("\\#someToken", owner, "#someToken", true),
        arguments("**/someToken", owner, "someToken", true),
        arguments("**/someToken", owner, "parent/someToken", true),
        arguments("**/someToken", owner, "grandparent/parent/someToken", true),
        arguments("**/someToken", owner, "anotherToken", false),
        arguments("**/some/token", owner, "some/token", true),
        arguments("**/some/token", owner, "parent/some/token", true),
        arguments("**/some/token", owner, "grandparent/parent/some/token", true),
        arguments("**/some/token/", owner, "some/token", true),
        arguments("**/some/token/", owner, "some/token/child", true),
        arguments("**/some/token/", owner, "some/token/child/grandchild", true),
        arguments("**/some/token/", owner, "parent/some/token/child", true),
        arguments("**/some/token/", owner, "parent/some/different/token", false),
        arguments("someToken/**", owner, "someToken", true),
        arguments("someToken/**", owner, "someToken/child", true),
        arguments("someToken/**", owner, "someToken/child/grandChild", true),
        arguments("someToken/**", owner, "parent/someToken", false),
        arguments("someToken/**", owner, "parent/someToken/child", false),
        arguments("someToken/**", owner, "parent/grandParent/someToken/child/grandChild", false),
        arguments("/someToken/**", owner, "someToken", true),
        arguments("/someToken/**", owner, "someToken/child", true),
        arguments("/someToken/**", owner, "someToken/child/grandChild", true),
        arguments("/someToken/**", owner, "parent/someToken", false),
        arguments("/someToken/**", owner, "parent/someToken/child", false),
        arguments("/someToken/**", owner, "parent/grandParent/someToken/child/grandChild", false),
        arguments("some/**/token", owner, "some/token", true),
        arguments("some/**/token", owner, "some/other/token", true),
        arguments("some/**/token", owner, "some/yet/another/token", true),
        arguments("some/**/token", owner, "some/yet/another", false),
        arguments("some/**/token", owner, "yet/another/token", false),
        arguments("someToken", asList("owner", "anotherOwner"), "someToken", true),
        arguments("*", owner, "someToken", true),
        arguments("*", owner, "parent/someToken", true),
        arguments("*", owner, "parent/someToken/child", true),
        arguments("*", owner, "someFile.java", true),
        arguments("**", owner, "someToken", true),
        arguments("**", owner, "parent/someToken", true),
        arguments("**", owner, "parent/someToken/child", true),
        arguments("**", owner, "someFile.java", true),
        arguments("*.java", owner, "someFile.java", true),
        arguments("*.java", owner, "parent/someFile.java", true),
        arguments("*.java", owner, "grandparent/parent/someFile.java", true),
        arguments("*.java", owner, "someFile.js", false),
        arguments("*.java", owner, "someFile.java17", false),
        arguments("readme.md", owner, "readme.md", true),
        arguments("readme.md", owner, "parent/readme.md", true),
        arguments("readme.md", owner, "readme.md5", false),
        arguments("some/*", owner, "some/token", true),
        arguments("some/*", owner, "some/other/token", false),
        arguments("some/*", owner, "parent/some/token", false),
        arguments("some/*", owner, "parent/some/other/token", false),
        arguments("**/test*.java", owner, "test.java", true),
        arguments("**/test*.java", owner, "testFile.java", true),
        arguments("**/test*.java", owner, "parent/test.java", true),
        arguments("**/test*.java", owner, "parent/testFile.java", true),
        arguments("**/test*.java", owner, "grandparent/parent/test.java", true),
        arguments("**/test*.java", owner, "grandparent/parent/testFile.java", true),
        arguments("\\#someToken", owner, "#someToken", true));
  }

  private static String ownersToString(Collection<String> owners) {
    StringBuilder result = new StringBuilder();
    for (String owner : owners) {
      result.append(owner).append(" "); // trailing spaces will be ignored by the matcher
    }
    return result.toString();
  }

  @ParameterizedTest(name = "invalid entry parsing: \"{0}\"")
  @MethodSource("testInvalidEntryParsingArguments")
  void testInvalidEntryParsing(String entry) {
    CharacterMatcher.Factory matcherFactory = new CharacterMatcher.Factory();
    Entry parsedEntry = new EntryBuilder(matcherFactory, entry).parse();
    assertNull(parsedEntry);
  }

  static Stream<Arguments> testInvalidEntryParsingArguments() {
    return Stream.of(
        arguments("token[z-a] owner"),
        arguments("# comment"),
        arguments(" # comment with a leading space"),
        arguments("[section header]"),
        arguments(" [section header with a leading space]"),
        arguments(""),
        arguments(" "),
        arguments("^[section header]"),
        arguments("^[section header with] @default/owner"),
        arguments(" ^[section header with a leading space]"));
  }
}
