package datadog.trace.bootstrap.regex;

import static datadog.trace.bootstrap.regex.RegexOpsDifferentialSupport.checkAllOps;
import static datadog.trace.bootstrap.regex.RegexOpsDifferentialSupport.checkCompileWithFlags;
import static datadog.trace.bootstrap.regex.RegexOpsDifferentialSupport.copy;
import static java.util.regex.Pattern.CANON_EQ;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static java.util.regex.Pattern.COMMENTS;
import static java.util.regex.Pattern.DOTALL;
import static java.util.regex.Pattern.LITERAL;
import static java.util.regex.Pattern.MULTILINE;
import static java.util.regex.Pattern.UNICODE_CASE;
import static java.util.regex.Pattern.UNICODE_CHARACTER_CLASS;
import static java.util.regex.Pattern.UNIX_LINES;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.regex.RegexOpsDifferentialSupport.Divergences;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@SuppressForbidden
class RegexOpsDifferentialCorpusTest {

  static final String[] REGEXES = {
    "",
    "a",
    "ab",
    "abc",
    ",",
    ";",
    " ",
    "-",
    "/",
    "=",
    "\t",
    "\n",
    "x",
    "#",
    "\u00e9",
    ".",
    "$",
    "|",
    "(",
    ")",
    "[",
    "{",
    "^",
    "?",
    "*",
    "+",
    "\\",
    "]",
    "}",
    "\\.",
    "\\$",
    "\\|",
    "\\(",
    "\\)",
    "\\[",
    "\\{",
    "\\^",
    "\\?",
    "\\*",
    "\\+",
    "\\\\",
    "\\,",
    "\\-",
    "\\ ",
    "\\#",
    "\\d",
    "\\D",
    "\\s",
    "\\S",
    "\\w",
    "\\W",
    "\\b",
    "\\B",
    "\\t",
    "\\n",
    "\\1",
    "\\0",
    "\\9",
    "\\A",
    "\\z",
    "\\Z",
    "\\R",
    "\\h",
    "\\G",
    "\\Q",
    "\\E",
    "\\y",
    "\\k",
    "\\p",
    "\\x",
    "\\u",
    "\\c",
    "\\N",
    "\uD83D",
    "\uDE00",
    "\uD83D\uDE00",
    "\\\uD83D\uDE00",
    "[\uD83D\uDE00]",
    "\u00e9|\u00c9",
    "\\b",
    "(?=a)",
    "(?<=a)",
    "(?!a)",
    "(?<!a)",
    "^",
    "$",
    "a*",
    "x*",
    "(?=[A-Z])",
    "a+",
    "[ab]",
    "[^ab]",
    "a|b",
    "(a)(b)?",
    "(a)|(b)",
    "\\s+",
    ",\\s*",
    "[,;]",
    "(?<word>[a-z]+)",
    "(?<x>a)(?<y>b)",
    "(a)\\1",
    "(\\w)\\1",
    "(?<=\\d)(?=(\\d{3})+$)",
    "(?<!\\\\),",
    "\\p{Lu}",
    "\\p{L}+",
    "\\p{IsAlphabetic}",
    "\\p{javaLowerCase}",
    "\\P{ASCII}",
    "\\x{1F600}",
    "\\u00e9",
    "\\Q.*\\E",
    "\\Qa|b",
    "a{2}",
    "a{1,}",
    "a{0,2}?",
    "a++",
    "(?>a+)b",
    ".+?",
    "[a-c&&[b]]",
    "\\R",
    "(?i)a",
    "(?i)\u00e9",
    "(?iu)\u00e9",
    "(?m)^a",
    "(?m)$",
    "(?s).",
    "(?x) a b # c",
    "(?d).",
    "(?U)\\w",
    "(?i:a)b",
    "(?-i)a",
    "(?i)(?-i)a",
    "(a",
    "a)",
    "[a",
    "a{",
    "a{2,1}",
    "*a",
    "+",
    "a**",
    "(?<n>a)(?<n>b)",
    "(?<1>a)",
    "\\k<missing>",
    "(?z)",
    "\\p{NoSuchProperty}",
    "\\x{110000}",
    "(?<=a+)b",
    "[z-a]",
    "\\uZZZZ",
  };

  static final String[] INPUTS = {
    "",
    "a",
    "A",
    "b",
    "ab",
    "aB",
    "abc",
    "aaa",
    "abab",
    ",",
    ",,",
    "a,b",
    ",a,b,",
    "a,,b,,",
    ",,a,,",
    "a, b ,c",
    "a;b,c",
    "a.b.c",
    "a|b|c",
    "x(y)z",
    "a b  c",
    " lead and trail ",
    "\ta\tb\t",
    "line1\nline2\n\nline4\n",
    "line1\r\nline2",
    "foo bar foo",
    "1234567",
    "a1b22c333",
    "word word",
    "\\a\\,b\\",
    "$1${x}",
    "CamelCaseString",
    "\u00e9\u00c9\u00e8",
    "\uD83D\uDE00",
    "a\uD83D\uDE00b\uD83D\uDE00",
    "\uD83Da\uDE00",
    "\u0130\u0131iI",
    "a\u030a\u00e5",
    "#",
    "a#b#",
    "-a-",
    "a/b/c/",
    "k=v=w",
  };

  static final String[] REPLACEMENTS = {
    "",
    "x",
    "$0",
    "$1",
    "$2",
    "$9",
    "$10",
    "${word}",
    "${x}",
    "${missing}",
    "${",
    "${1}",
    "\\$",
    "\\\\",
    "\\",
    "$",
    "a\\",
    "$a",
    "\\x",
    "[$0]",
    "$$",
    "\u00e9",
    "\uD83D\uDE00",
  };

  static final int[] LIMITS = {Integer.MIN_VALUE, -2, -1, 0, 1, 2, 3, 5, Integer.MAX_VALUE};

  static final int[] FLAGS = {
    0,
    CASE_INSENSITIVE,
    UNICODE_CASE,
    CASE_INSENSITIVE | UNICODE_CASE,
    MULTILINE,
    DOTALL,
    MULTILINE | DOTALL,
    COMMENTS,
    COMMENTS | CASE_INSENSITIVE,
    LITERAL,
    LITERAL | CASE_INSENSITIVE,
    LITERAL | COMMENTS | MULTILINE,
    UNIX_LINES,
    UNIX_LINES | DOTALL | MULTILINE,
    CANON_EQ,
    UNICODE_CHARACTER_CLASS,
    CASE_INSENSITIVE | UNICODE_CASE | MULTILINE | DOTALL | COMMENTS,
    CASE_INSENSITIVE
        | UNICODE_CASE
        | MULTILINE
        | DOTALL
        | COMMENTS
        | LITERAL
        | UNIX_LINES
        | CANON_EQ
        | UNICODE_CHARACTER_CLASS,
    0x10000,
    -1,
  };

  static Stream<String> regexes() {
    return Arrays.stream(REGEXES);
  }

  @ParameterizedTest(name = "[{index}]")
  @MethodSource("regexes")
  void allOpsMatchJdk(String regex) {
    Divergences d = new Divergences();
    for (String input : INPUTS) {
      checkAllOps(d, regex, input, REPLACEMENTS, LIMITS);
    }
    d.assertNone();
  }

  @ParameterizedTest(name = "[{index}]")
  @MethodSource("regexes")
  void compileWithFlagsMatchesJdk(String regex) {
    Divergences d = new Divergences();
    for (int flags : FLAGS) {
      checkCompileWithFlags(d, regex, flags);
    }
    d.assertNone();
  }

  @Test
  void sameReferenceDifferentFlagsInterleaved() {
    Divergences d = new Divergences();
    String[] regexes = {"a", "^a.$", "a b", "\u00e9", "a.b", "(?-i)a"};
    for (int round = 0; round < 6; round++) {
      for (String regex : regexes) {
        for (int flags : FLAGS) {
          checkCompileWithFlags(d, regex, flags);
          d.check("compile(no flags) after flags", regex, Pattern::compile, RegexOps::compile);
        }
      }
    }
    d.assertNone();
  }

  @Test
  void nullArguments() {
    Divergences d = new Divergences();
    String[] regexes = {null, "a", ",", "(", ""};
    String[] inputs = {null, "", "a,b"};
    String[] reps = {null, "x", "$1"};
    for (String regex : regexes) {
      for (String input : inputs) {
        checkAllOps(d, regex, input, reps, LIMITS);
      }
      for (int flags : FLAGS) {
        checkCompileWithFlags(d, regex, flags);
      }
    }
    d.assertNone();
  }

  @Test
  void veryLongInputs() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 20_000; i++) {
      sb.append("ab,").append(i % 10).append(i % 7 == 0 ? ",," : ";");
    }
    String csv = sb.toString();
    char[] as = new char[50_000];
    Arrays.fill(as, 'a');
    String aaa = new String(as);
    StringBuilder sur = new StringBuilder();
    for (int i = 0; i < 10_000; i++) {
      sur.append("\uD83D\uDE00x");
    }
    String surrogates = sur.toString();
    String longRegex;
    {
      StringBuilder r = new StringBuilder("(?:");
      for (int i = 0; i < 500; i++) {
        r.append(i == 0 ? "" : "|").append("tok").append(i);
      }
      longRegex = r.append(")").toString();
    }

    String[] regexes = {",", ";", "[,;]", "\\d", ",+", "a+", "a*", "\uD83D\uDE00", "x"};
    String[] inputs = {csv, aaa, surrogates, aaa + "tok499" + csv};
    String[] reps = {"", "$0$0", "\\$"};
    int[] limits = {-1, 0, 7};
    Divergences d = new Divergences();
    for (String regex : regexes) {
      for (String input : inputs) {
        checkAllOps(d, regex, input, reps, limits);
      }
    }
    String tokens = csv.substring(0, 5_000) + "tok77" + "tok499";
    checkAllOps(d, longRegex, tokens, reps, limits);
    for (int flags : FLAGS) {
      checkCompileWithFlags(d, longRegex, flags);
    }
    d.assertNone();
  }

  @Test
  void distinctReferencesWithEqualContentAndDifferentContent() {
    Divergences d = new Divergences();
    String base = "a,b;c";
    for (int i = 0; i < 2_000; i++) {
      String r1 = copy(i % 2 == 0 ? "," : ";");
      String r2 = copy("[" + (char) ('a' + i % 26) + "]");
      for (String r : new String[] {r1, r2, r1, r2}) {
        d.check("split", r, base::split, rr -> RegexOps.split(base, rr));
        d.check(
            "replaceAll",
            r,
            rr -> base.replaceAll(rr, "_"),
            rr -> RegexOps.replaceAll(base, rr, "_"));
      }
    }
    d.assertNone();
    assertTrue(d.comparisons() > 0);
  }
}
