package com.datadog.openfeature.internal.ufc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.internal.JsonReading;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;

/** PROTOTYPE: checks every prototype tier against {@link RegexMatcher} on generated inputs. */
class PrototypeRegexTest {
  private static final String FIXTURES = "dd-openfeature/src/test/fixtures/ffe-system-test-data";

  private static final List<String> HAND_PICKED =
      Arrays.asList(
          "@mycompany.com",
          "^api-.*-prod$",
          "checkout.*v2",
          "^a.*b.*c$",
          ".*?abc",
          "^a.b$",
          "(?s)^a.b$",
          "(?i)^admin",
          "(?i)admin.*panel",
          "^.+abc",
          "abc.+$",
          "a.+b.+c",
          ".*.*.*abc.*.*",
          "^[a-zA-Z0-9._%+-]+@site\\.test$",
          "^org-0000-[a-z]+-[0-9]{2,6}$",
          "^\\d+$",
          "^v[0-9]+\\.[0-9]+\\.[0-9]+$",
          "a{1,3}",
          "^a{2,3}$",
          "^a\\sb$",
          "^[[:alnum:]]+$",
          "(?i)^[a-z]+-[0-9]?$",
          "^\\w+@\\w+\\.com$",
          "x*",
          "^x*$",
          "^a?b?c?$",
          "[^a-z]+",
          "\\D\\W\\S",
          "^tenant-0-[0-9]+$",
          "^[a-f0-9]{8}-[a-f0-9]{4}$",
          // (?i) with non-ASCII pattern characters that fold to ASCII: the long s matches s/S,
          // the Kelvin sign matches k/K. The fast tiers must turn these down.
          "(?i)\u017f",
          "(?i)[\u017f]",
          "(?i)^\u212a+$",
          "(?i)\\x{17F}",
          "(?i)[a-z\\x{212A}]+",
          "(?i)^sk.*end$",
          // Rust's \s is 0x09-0x0D and the space; 0x1C-0x1F are not White_Space.
          "^\\s+$",
          "a\\sb",
          "^[\\s\\d]+$",
          "^[[:space:]]+$",
          "^[[:cntrl:]]+$",
          "\\S+",
          "^[^\\s]+$");

  @Test
  void agreesWithRegexMatcherOnEveryFixtureAndHandPickedPattern() throws IOException {
    final Set<String> patterns = new LinkedHashSet<>(HAND_PICKED);
    final Path root = fixtures();
    for (final String file :
        new String[] {
          "ufc-config.json",
          "evaluation-suites/regex/ufc-config.json",
          "evaluation-suites/scale/ufc-config.json"
        }) {
      collect(JsonReading.read(Files.readAllBytes(root.resolve(file))), patterns);
    }
    final Map<String, Integer> tiers = new TreeMap<>();
    final Random random = new Random(42);
    int checked = 0;
    for (final String pattern : patterns) {
      final PrototypeRegex prototype;
      final RegexMatcher reference;
      final RegexMatcher wired;
      try {
        prototype = PrototypeRegex.compileStandalone(pattern);
        reference = RegexMatcher.compileWithoutPrototype(pattern);
        wired = RegexMatcher.compile(pattern);
      } catch (final PatternSyntaxException e) {
        continue; // invalid in the Rust dialect: the flag is dropped either way
      }
      tiers.merge(prototype.tier(), 1, Integer::sum);
      for (final String input : inputs(pattern, random)) {
        assertEquals(
            reference.matches(input),
            prototype.matches(input),
            () -> prototype.tier() + " disagrees on /" + pattern + "/ for \"" + input + "\"");
        assertEquals(
            reference.matches(input),
            wired.matches(input),
            () -> "RegexMatcher.compile disagrees on /" + pattern + "/ for \"" + input + "\"");
        checked++;
      }
    }
    System.out.println("prototype tiers: " + tiers + ", inputs checked: " + checked);
    assertTrue(checked > 500_000, "too few inputs: " + checked);
  }

  /** Matching strings, near misses, random ASCII, and line feed / non-ASCII variants. */
  private static List<String> inputs(final String pattern, final Random random) {
    final List<String> inputs = new ArrayList<>();
    final PrototypeRegex.Parsed parsed = PrototypeRegex.Parser.parse(pattern);
    final String alphabet = alphabet(pattern);
    for (int k = 0; k < 40; k++) {
      final String base =
          parsed != null ? generate(parsed, random) : randomString(alphabet, random);
      inputs.add(base);
      inputs.add(mutate(base, alphabet, random));
      inputs.add(mutate(mutate(base, alphabet, random), alphabet, random));
      inputs.add(randomString(alphabet, random) + base + randomString(alphabet, random));
      inputs.add(base + "\n" + randomString(alphabet, random));
      inputs.add(insert(base, "é", random));
      inputs.add(insert(base, "K", random)); // the Kelvin sign folds to k under (?i)
      inputs.add(insert(base, "😀", random));
      inputs.add(randomString(alphabet, random));
    }
    // Every ASCII code point, control characters included: inserted into and substituted in a
    // generated input, and alone and repeated.
    final String base = parsed != null ? generate(parsed, random) : randomString(alphabet, random);
    for (int c = 0; c < 128; c++) {
      final String ch = String.valueOf((char) c);
      inputs.add(insert(base, ch, random));
      inputs.add(base.isEmpty() ? ch : substitute(base, (char) c, random));
      inputs.add(ch);
      inputs.add(ch + ch + ch);
    }
    for (final String probe :
        new String[] {"s", "S", "k", "K", "sk", "SK", "\u017f", "\u212a", "skend", "SKEND"}) {
      inputs.add(probe);
      inputs.add(base + probe);
      inputs.add(probe + base);
    }
    inputs.add("");
    return inputs;
  }

  private static String generate(final PrototypeRegex.Parsed parsed, final Random random) {
    final StringBuilder s = new StringBuilder();
    for (final PrototypeRegex.Atom atom : parsed.atoms) {
      final int extra = atom.max == Integer.MAX_VALUE ? random.nextInt(4) : atom.max - atom.min;
      final int count = atom.min + (extra > 0 ? random.nextInt(extra + 1) : 0);
      for (int c = 0; c < count; c++) {
        s.append(pick(atom.set, random));
      }
    }
    return s.toString();
  }

  private static char pick(final boolean[] set, final Random random) {
    final List<Character> members = new ArrayList<>();
    for (int x = 32; x < 127; x++) {
      if (set[x]) {
        members.add((char) x);
      }
    }
    if (members.isEmpty()) {
      return set['\n'] ? '\n' : 'a';
    }
    return members.get(random.nextInt(members.size()));
  }

  private static String mutate(final String s, final String alphabet, final Random random) {
    if (s.isEmpty()) {
      return String.valueOf(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    final int at = random.nextInt(s.length());
    final char c = alphabet.charAt(random.nextInt(alphabet.length()));
    switch (random.nextInt(3)) {
      case 0:
        return s.substring(0, at) + s.substring(at + 1);
      case 1:
        return s.substring(0, at) + c + s.substring(at);
      default:
        return s.substring(0, at) + c + s.substring(at + 1);
    }
  }

  private static String substitute(final String s, final char c, final Random random) {
    final int at = random.nextInt(s.length());
    return s.substring(0, at) + c + s.substring(at + 1);
  }

  private static String insert(final String s, final String extra, final Random random) {
    final int at = s.isEmpty() ? 0 : random.nextInt(s.length() + 1);
    return s.substring(0, at) + extra + s.substring(at);
  }

  private static String randomString(final String alphabet, final Random random) {
    final StringBuilder s = new StringBuilder();
    final int length = random.nextInt(12);
    for (int k = 0; k < length; k++) {
      s.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    return s.toString();
  }

  /** The pattern's own characters plus a few others, so random strings get near matches. */
  private static String alphabet(final String pattern) {
    final StringBuilder s = new StringBuilder("aAzZ09-_.@ ");
    for (final char c : pattern.toCharArray()) {
      if (c >= 32 && c < 127 && "\\[](){}*+?^$|".indexOf(c) < 0) {
        s.append(c);
      }
    }
    return s.toString();
  }

  private static void collect(final Object json, final Set<String> patterns) {
    if (json instanceof Map) {
      final Map<?, ?> map = (Map<?, ?>) json;
      final Object operator = map.get("operator");
      if (("MATCHES".equals(operator) || "NOT_MATCHES".equals(operator))
          && map.get("value") instanceof String) {
        patterns.add((String) map.get("value"));
      }
      for (final Object value : map.values()) {
        collect(value, patterns);
      }
    } else if (json instanceof List) {
      for (final Object value : (List<?>) json) {
        collect(value, patterns);
      }
    }
  }

  private static Path fixtures() {
    Path directory = Paths.get("").toAbsolutePath();
    while (directory != null) {
      final Path candidate = directory.resolve(FIXTURES);
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      directory = directory.getParent();
    }
    throw new IllegalStateException("initialize the ffe-system-test-data submodule");
  }
}
