package datadog.trace.bootstrap.regex;

import static datadog.trace.bootstrap.regex.RegexOpsDifferentialSupport.checkAllOps;
import static datadog.trace.bootstrap.regex.RegexOpsDifferentialSupport.checkCompileWithFlags;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.regex.RegexOpsDifferentialSupport.Divergences;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class RegexOpsDifferentialFuzzTest {

  private static final long SEED = 0x5EED_CAFE_F00DL;
  private static final int REGEX_COUNT = 1_500;
  private static final int INPUTS_PER_REGEX = 3;

  private static final String[] ATOMS = {
    "a",
    "b",
    "c",
    "A",
    ",",
    ";",
    " ",
    "-",
    ".",
    "\\.",
    "\\d",
    "\\w",
    "\\s",
    "\\W",
    "[ab]",
    "[^a]",
    "[a-c]",
    "\\b",
    "^",
    "$",
    "\\,",
    "\\\\",
    "\\$",
    "\\uD83D\\uDE00",
    "\\u00e9",
    "\\p{Lu}",
    "\\1",
    "(?=a)",
    "(?<=b)",
    "(?!c)",
    "\\Qa.\\E",
    "x",
  };

  private static final String[] QUANTIFIERS = {
    "", "", "", "*", "+", "?", "*?", "+?", "{2}", "{0,2}", "{1,}", "*+",
  };

  private static final String[] BROKEN = {"(", ")", "[", "{", "*", "\\", "(?<", "a{3,1}", "\\k<z>"};

  private static final String[] EMBEDDED_FLAGS = {"(?i)", "(?m)", "(?s)", "(?x)", "(?iu)", "(?d)"};

  private static final String INPUT_ALPHABET = "abcA,; -.1\n\\$\u00e9";

  private static final String[] REPLACEMENTS = {
    "", "_", "$0", "$1", "${g}", "\\$", "$", "\\", "$9", "<$0>",
  };

  private static final int[] FLAG_BITS = {
    Pattern.CASE_INSENSITIVE,
    Pattern.UNICODE_CASE,
    Pattern.MULTILINE,
    Pattern.DOTALL,
    Pattern.COMMENTS,
    Pattern.LITERAL,
    Pattern.UNIX_LINES,
    Pattern.UNICODE_CHARACTER_CLASS,
  };

  @Test
  void fuzzAllOps() {
    Random rnd = new Random(SEED);
    List<String> regexes = new ArrayList<>(REGEX_COUNT);
    List<Integer> flags = new ArrayList<>(REGEX_COUNT);
    Divergences d = new Divergences();

    for (int i = 0; i < REGEX_COUNT; i++) {
      String regex = regex(rnd, 0);
      regexes.add(regex);
      int f = randomFlags(rnd);
      flags.add(f);
      for (int j = 0; j < INPUTS_PER_REGEX; j++) {
        String input = input(rnd);
        String[] reps = {pick(rnd, REPLACEMENTS), pick(rnd, REPLACEMENTS)};
        int[] limits = {rnd.nextInt(7) - 3};
        checkAllOps(d, regex, input, reps, limits);
      }
      checkCompileWithFlags(d, regex, f);
    }

    for (int i = 0; i < regexes.size(); i++) {
      checkCompileWithFlags(d, regexes.get(i), 0);
      checkCompileWithFlags(d, regexes.get(i), flags.get(i));
    }

    d.assertNone();
    assertTrue(d.comparisons() > 100_000, "expected a large fuzz corpus, got " + d.comparisons());
  }

  private static String regex(Random rnd, int depth) {
    StringBuilder sb = new StringBuilder();
    if (depth == 0 && rnd.nextInt(8) == 0) {
      sb.append(pick(rnd, EMBEDDED_FLAGS));
    }
    int terms = 1 + rnd.nextInt(3);
    for (int t = 0; t < terms; t++) {
      if (t > 0 && rnd.nextInt(5) == 0) {
        sb.append('|');
      }
      int kind = rnd.nextInt(20);
      if (kind == 0) {
        sb.append(pick(rnd, BROKEN));
      } else if (kind <= 3 && depth < 2) {
        String[] open = {"(", "(?:", "(?<g>", "(?>"};
        sb.append(pick(rnd, open)).append(regex(rnd, depth + 1)).append(')');
      } else {
        sb.append(pick(rnd, ATOMS));
      }
      sb.append(pick(rnd, QUANTIFIERS));
    }
    return new String(sb.toString().toCharArray());
  }

  private static String input(Random rnd) {
    int len = rnd.nextInt(13);
    StringBuilder sb = new StringBuilder(len);
    for (int i = 0; i < len; i++) {
      if (rnd.nextInt(30) == 0) {
        sb.append("\uD83D\uDE00");
      } else {
        sb.append(INPUT_ALPHABET.charAt(rnd.nextInt(INPUT_ALPHABET.length())));
      }
    }
    return sb.toString();
  }

  private static int randomFlags(Random rnd) {
    int f = 0;
    for (int bit : FLAG_BITS) {
      if (rnd.nextInt(4) == 0) {
        f |= bit;
      }
    }
    return f;
  }

  private static String pick(Random rnd, String[] values) {
    return values[rnd.nextInt(values.length)];
  }
}
