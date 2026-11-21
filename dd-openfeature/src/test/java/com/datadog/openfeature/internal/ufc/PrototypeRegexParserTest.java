package com.datadog.openfeature.internal.ufc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * What the engine-free subset accepts, and what it leaves to RE2/J by returning {@code null}. The
 * differential test checks the results; these tests pin the subset's edges.
 */
class PrototypeRegexParserTest {

  @TableTest({
    "pattern     | supported",
    "'(?i)a'     | true     ",
    "'(?s)a.b'   | true     ",
    "'(?u)a'     | true     ",
    "'(?is)a'    | true     ",
    "'(?i)(?s)a' | true     ",
    "'(?m)^a$'   | false    ",
    "'(?x)a'     | false    ",
    "'(?-i)a'    | false    ",
    "'(?i:a)'    | false    ",
    "'(?'        | false    ",
    "'\\Aabc\\z' | true     ",
    "'a|b'       | false    ",
    "'(a)'       | false    ",
    "'a$b'       | false    ",
    "'a^b'       | false    ",
    "'\\bword'   | false    ",
    "'\\p{L}'    | false    ",
    "'\\1'       | false    ",
    "'\\'        | false    ",
    "'a{2}'      | true     ",
    "'a{2,}'     | true     ",
    "'a{2,4}'    | true     ",
    "'a{2,4}?'   | true     ",
    "'a*?'       | true     ",
    "'a{x}'      | false    ",
    "'a{2'       | false    ",
    "'a**'       | false    ",
    "'a+?+'      | false    ",
    "'a{0}'      | true     ",
    "'a{+2}'     | false    ",
    "'a{-1}'     | false    ",
    "'a{0002}'   | false    ",
    "'a{2,+3}'   | false    ",
    "'a{3,2}'    | false    ",
    "'a{12345}'  | false    "
  })
  void supportsOnlyItsSubset(final String pattern, final boolean supported) {
    assertEquals(supported, PrototypeRegex.Parser.parse(pattern) != null, pattern);
  }

  @TableTest({
    "pattern | member | supported",
    "'\\t'   | 9      | true     ",
    "'\\n'   | 10     | true     ",
    "'\\r'   | 13     | true     ",
    "'\\f'   | 12     | true     ",
    "'\\v'   | 11     | true     ",
    "'\\x41' | 65     | true     ",
    "'\\x7f' | 127    | true     ",
    "'\\.'   | 46     | true     ",
    "'\\-'   | 45     | true     ",
    "'\\x80' | 0      | false    ",
    "'\\xZZ' | 0      | false    ",
    "'\\x4'  | 0      | false    ",
    "'\\q'   | 0      | false    "
  })
  void readsEscapes(final String pattern, final int member, final boolean supported) {
    final PrototypeRegex.Parsed parsed = PrototypeRegex.Parser.parse(pattern);
    if (!supported) {
      assertNull(parsed, pattern);
      return;
    }
    assertNotNull(parsed, pattern);
    assertTrue(parsed.atoms.get(0).set[member], pattern);
  }

  @TableTest({
    "name   | member | nonmember",
    "alnum  | z      | _        ",
    "alpha  | Q      | 7        ",
    "ascii  | ~      |          ",
    "blank  | ' '    | a        ",
    "cntrl  | ''     | a        ",
    "digit  | 5      | a        ",
    "graph  | !      | ' '      ",
    "lower  | q      | Q        ",
    "print  | ' '    | ''       ",
    "punct  | !      | a        ",
    "space  | ' '    | a        ",
    "upper  | Q      | q        ",
    "word   | _      | -        ",
    "xdigit | f      | g        "
  })
  void readsPosixClasses(final String name, final String member, final String nonmember) {
    final boolean[] set = PrototypeRegex.Parser.parse("[[:" + name + ":]]").atoms.get(0).set;
    assertTrue(set[character(member)], name + " has " + member);
    if (nonmember != null && !nonmember.isEmpty()) {
      assertFalse(set[character(nonmember)], name + " lacks " + nonmember);
    }
  }

  @Test
  void rejectsUnknownPosixClassesSetOperationsAndNestedClasses() {
    for (final String pattern :
        new String[] {
          "[[:nope:]]",
          "[[:alpha]",
          "[a&&b]",
          "[a--b]",
          "[a~~b]",
          "[a[b]]",
          "[z-a]",
          "[a",
          "[\\p{L}]",
          "[é]",
          "[a-é]",
          "[\\x80]",
          "[\\"
        }) {
      assertNull(PrototypeRegex.Parser.parse(pattern), pattern);
    }
  }

  @Test
  void readsClassesWithEdgeCases() {
    final boolean[] firstBracket = set("[]a]");
    assertTrue(firstBracket[']']);
    assertTrue(firstBracket['a']);

    final boolean[] negated = set("[^a-c]");
    assertFalse(negated['b']);
    assertTrue(negated['d']);

    final boolean[] trailingDash = set("[a-]");
    assertTrue(trailingDash['-']);

    final boolean[] shorthands = set("[\\d\\s]");
    assertTrue(shorthands['7']);
    assertTrue(shorthands[0x0B]);
    assertFalse(shorthands['a']);

    final boolean[] escapedRange = set("[\\x41-\\x43\\-]");
    assertTrue(escapedRange['B']);
    assertTrue(escapedRange['-']);
    assertFalse(escapedRange['D']);

    final boolean[] folded = PrototypeRegex.Parser.parse("(?i)[a-c]").atoms.get(0).set;
    assertTrue(folded['B']);
    assertFalse(folded['d']);
  }

  @Test
  void leavesBracesThatAreNotACountToTheEngine() {
    // RE2/J reads a zero-padded count as literal text, and RegexMatcher.Limits skips counts over 4
    // digits, so nothing sized it. Reading it as a count of 2^31 used to exhaust the heap at
    // configuration load. (A signed count like {+2} is a syntax error in RE2/J, so it never gets
    // here; the parser test pins that the prototype rejects it too.)
    final RegexMatcher matcher = RegexMatcher.compile("^a{0002147483647}$");
    assertTrue(matcher.matches("a{0002147483647}"));
    assertFalse(matcher.matches("aa"));
  }

  @Test
  void aCaseInsensitiveClassWithoutLettersIsNotAFoldedLetter() {
    final RegexMatcher matcher = RegexMatcher.compile("(?i)x[\\x00\\x01]y");
    assertTrue(matcher.matches("x\u0001y"));
    assertFalse(matcher.matches("xay"));
  }

  @Test
  void delegateKeepsTheKindOfTheMatcherItWraps() {
    final PrototypeRegex literal = PrototypeRegex.compileStandalone("@example\\.com$");
    assertEquals(RegexMatcher.Kind.ENDS_WITH, literal.kind());
    final PrototypeRegex engine = PrototypeRegex.compileStandalone("(a|b)+c");
    assertEquals(RegexMatcher.Kind.ENGINE, engine.kind());
    assertNotEquals("prototype:SEGMENTS", engine.tier());
    assertTrue(engine.matches("abac"));
  }

  private static boolean[] set(final String pattern) {
    return PrototypeRegex.Parser.parse(pattern).atoms.get(0).set;
  }

  private static int character(final String cell) {
    return cell.isEmpty() ? 1 : cell.charAt(0);
  }
}
