package datadog.trace.civisibility.codeowners;

import datadog.trace.civisibility.codeowners.matcher.AsteriskMatcher;
import datadog.trace.civisibility.codeowners.matcher.CharacterMatcher;
import datadog.trace.civisibility.codeowners.matcher.CompositeMatcher;
import datadog.trace.civisibility.codeowners.matcher.DoubleAsteriskMatcher;
import datadog.trace.civisibility.codeowners.matcher.EndOfLineMatcher;
import datadog.trace.civisibility.codeowners.matcher.EndOfSegmentMatcher;
import datadog.trace.civisibility.codeowners.matcher.Matcher;
import datadog.trace.civisibility.codeowners.matcher.NegatingMatcher;
import datadog.trace.civisibility.codeowners.matcher.QuestionMarkMatcher;
import datadog.trace.civisibility.codeowners.matcher.RangeMatcher;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A builder for parsing CODEOWNERS file entries. A parsed entry contains two main components:
 *
 * <ul>
 *   <li>a matcher that can be used to test if the entry applies to a file/directory
 *   <li>a list of teams/people who own the matched files
 * </ul>
 *
 * @see <a href="https://git-scm.com/docs/gitignore#_pattern_format">.gitignore pattern format</a>
 * @see <a
 *     href="https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners">CODEOWNERS
 *     file description</a>
 */
public class EntryBuilder {

  private static final Logger log = LoggerFactory.getLogger(EntryBuilder.class);

  private final CharacterMatcher.Factory characterMatcherFactory;
  private final char[] c;
  private int offset;

  public EntryBuilder(CharacterMatcher.Factory characterMatcherFactory, String s) {
    this.characterMatcherFactory = characterMatcherFactory;
    c = s.toCharArray();
  }

  public @Nullable Entry parse() {
    try {
      // skip trailing whitespace
      while (offset < c.length && Character.isWhitespace(c[offset])) {
        offset++;
      }

      if (offset == c.length // empty line
          || c[offset] == '#' // comment
          || c[offset] == '[') { // section header
        return null;
      }

      Matcher matcher = parseMatcher();
      Collection<String> owners = parseOwners();
      return new Entry(matcher, owners);

    } catch (Exception e) {
      log.error("error parsing CODEOWNERS pattern: {}", Arrays.toString(c), e);
      return null;
    }
  }

  private Matcher parseMatcher() {
    if (c[offset] == '!') {
      offset++;
      return new NegatingMatcher(parseMatcher());
    }

    boolean patternContainsSlashes = false;
    if (c[offset] == '/') {
      // opening slash gets special treatment
      offset++;
      patternContainsSlashes = true;
    }

    Deque<Matcher> characterMatchers = new ArrayDeque<>();
    for (; offset < c.length; offset++) {
      if (isPatternTerminator(c[offset])) {
        break;

      } else if (consumeDoubleAsterisk()) {
        characterMatchers.offerLast(DoubleAsteriskMatcher.INSTANCE);
        patternContainsSlashes = true;

      } else if (c[offset] == '/') {
        // closing slash gets special treatment
        if (offset + 1 < c.length && !isPatternTerminator(c[offset + 1])) {
          characterMatchers.offerLast(characterMatcherFactory.create('/'));
          patternContainsSlashes = true;
        }

      } else if (c[offset] == '*') {
        characterMatchers.offerLast(AsteriskMatcher.INSTANCE);

      } else if (c[offset] == '?') {
        characterMatchers.offerLast(QuestionMarkMatcher.INSTANCE);

      } else if (c[offset] == '[') {
        characterMatchers.offerLast(parseRangeCharacterMatcher());

      } else if (c[offset] == '\\') {
        characterMatchers.offerLast(characterMatcherFactory.create(c[++offset]));

      } else {
        characterMatchers.offerLast(characterMatcherFactory.create(c[offset]));
      }
    }

    if (characterMatchers.isEmpty()) {
      throw new IllegalArgumentException("No matchers found");
    }

    boolean patternEndsWithSlash = c[offset - 1] == '/';
    if (!patternEndsWithSlash) { // pattern should match the end of the string
      if (c[offset - 1] == '*') {
        characterMatchers.offerLast(EndOfLineMatcher.INSTANCE);
      } else {
        characterMatchers.offerLast(EndOfSegmentMatcher.INSTANCE);
      }
    }

    if (!patternContainsSlashes) {
      // pattern does not have to match the beginning of the string
      characterMatchers.offerFirst(DoubleAsteriskMatcher.INSTANCE);
    }

    return new CompositeMatcher(characterMatchers.toArray(new Matcher[0]));
  }

  private boolean consumeDoubleAsterisk() {
    int position = offset;

    if (c[offset] == '/') {
      // consuming first '/'
      position++;
    } else if (position > 0 && c[position - 1] != '!') {
      // pattern does not start with a '/', valid alternatives are beginning of the string or '!'
      return false;
    }

    if (position == c.length || c[position++] != '*') {
      // failed to consume first '*'
      return false;
    }
    if (position == c.length || c[position++] != '*') {
      // failed to consume second '*'
      return false;
    }

    if (position == c.length || isPatternTerminator(c[position])) {
      // there is no last '/', releasing last character
      offset = position - 1;
      return true;

    } else if (c[position] == '/') {
      // consuming last '/'
      offset = position;
      return true;

    } else {
      return false;
    }
  }

  private boolean isPatternTerminator(char c) {
    return c == '#' || Character.isWhitespace(c);
  }

  private Matcher parseRangeCharacterMatcher() {
    offset++; // consume opening '['

    Collection<RangeMatcher.Range> ranges = new ArrayList<>();
    for (; offset < c.length; offset++) {
      if (c[offset] == ']') {
        if (!ranges.isEmpty()) {
          return new RangeMatcher(ranges.toArray(new RangeMatcher.Range[0]));
        } else {
          throw new IllegalArgumentException("Empty character range");
        }

      } else {
        ranges.add(parseRange());
      }
    }
    throw new IllegalArgumentException("Unterminated character range");
  }

  private RangeMatcher.Range parseRange() {
    if (offset + 2 >= c.length) {
      throw new IllegalArgumentException("Unterminated character range");
    }
    if (c[offset + 1] != '-') {
      throw new IllegalArgumentException("Malformed character range");
    }
    offset += 2;
    return new RangeMatcher.Range(c[offset - 2], c[offset]);
  }

  private Collection<String> parseOwners() {
    Collection<String> owners = new ArrayList<>();
    for (; offset < c.length; offset++) {
      // skip whitespace until the next token
      while (offset < c.length && Character.isWhitespace(c[offset])) {
        offset++;
      }

      if (offset == c.length || c[offset] == '#') {
        break; // anything that goes after # is a comment, stop parsing
      }

      int ownerIdx = offset;
      while (ownerIdx < c.length && !isPatternTerminator(c[ownerIdx])) {
        ownerIdx++;
      }
      owners.add(new String(c, offset, ownerIdx - offset));
      offset = ownerIdx;
    }
    return owners;
  }
}
