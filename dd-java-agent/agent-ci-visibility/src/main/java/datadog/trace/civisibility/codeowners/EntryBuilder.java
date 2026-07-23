package datadog.trace.civisibility.codeowners;

import datadog.trace.civisibility.codeowners.matcher.AsteriskMatcher;
import datadog.trace.civisibility.codeowners.matcher.CharacterMatcher;
import datadog.trace.civisibility.codeowners.matcher.CompositeMatcher;
import datadog.trace.civisibility.codeowners.matcher.DoubleAsteriskMatcher;
import datadog.trace.civisibility.codeowners.matcher.EndOfLineMatcher;
import datadog.trace.civisibility.codeowners.matcher.EndOfSegmentMatcher;
import datadog.trace.civisibility.codeowners.matcher.Matcher;
import datadog.trace.civisibility.codeowners.matcher.QuestionMarkMatcher;
import datadog.trace.civisibility.codeowners.matcher.RangeMatcher;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
    return parse(Collections.emptyList());
  }

  /**
   * Parses the line as a CODEOWNERS entry: a path pattern optionally followed by one or more
   * owners.
   *
   * @param sectionDefaultOwners default owners declared on the enclosing GitLab section header,
   *     used when the entry declares no owners of its own. Pass an empty collection for entries
   *     that do not belong to a section (e.g. GitHub CODEOWNERS files).
   * @return the parsed entry, or {@code null} for blank lines, comments, section headers, or lines
   *     that cannot be parsed.
   */
  public @Nullable Entry parse(Collection<String> sectionDefaultOwners) {
    try {
      skipWhitespace();

      if (offset == c.length // empty line
          || c[offset] == '#' // comment
          || isSectionHeader()) { // GitLab section header (including optional '^[')
        return null;
      }

      boolean exclusion = c[offset] == '!';
      if (exclusion) {
        offset++;
      }

      String indexKey = parseIndexKey();
      Matcher matcher = parseMatcher();
      Collection<String> owners = exclusion ? Collections.emptyList() : parseOwners();
      if (!exclusion && owners.isEmpty()) {
        owners = sectionDefaultOwners;
      }
      return new Entry(matcher, owners, exclusion, indexKey);

    } catch (Exception e) {
      log.warn("Skipping malformed CODEOWNERS entry: {}", new String(c), e);
      return null;
    }
  }

  /**
   * If the line is a GitLab section header, consumes it and returns the parsed {@link
   * SectionHeader} (its name and default owners). Returns {@code null} when the line is not a
   * section header, leaving the cursor at the start of the pattern so the line can be parsed with
   * {@link #parse(Collection)}.
   *
   * <p>Supports the GitLab header syntax {@code ^[Section name][N] @owner}: an optional leading
   * {@code ^} (optional section), a bracketed name that may contain spaces, an optional {@code [N]}
   * required-approvals count, and trailing default owners.
   *
   * @see <a href="https://docs.gitlab.com/user/project/codeowners/reference/">GitLab Code Owners
   *     reference</a>
   */
  public @Nullable SectionHeader parseSectionHeader() {
    skipWhitespace();
    if (!isSectionHeader()) {
      return null;
    }
    if (c[offset] == '^') {
      offset++; // consume the optional-section marker
    }
    offset++; // consume the opening '['
    int nameStart = offset;
    while (offset < c.length && c[offset] != ']') {
      offset++; // consume the section name (which may contain spaces)
    }
    String name = new String(c, nameStart, offset - nameStart);
    if (offset < c.length) {
      offset++; // consume the closing ']'
    }
    // skip the optional [N] required-approvals count that may immediately follow the name
    if (offset < c.length && c[offset] == '[') {
      while (offset < c.length && c[offset] != ']') {
        offset++;
      }
      if (offset < c.length) {
        offset++; // consume the closing ']'
      }
    }
    return new SectionHeader(name, parseOwners());
  }

  private void skipWhitespace() {
    while (offset < c.length && Character.isWhitespace(c[offset])) {
      offset++;
    }
  }

  /**
   * Determines whether the line, starting at the current cursor (with leading whitespace already
   * skipped), is a section header. Does not move the cursor.
   *
   * <p>A section header begins with {@code [} — or the GitLab optional-section marker {@code ^[} —
   * and the section name's closing {@code ]} is followed by end-of-line, whitespace, a comment
   * ({@code #}), or the optional {@code [N]} approvals count. Requiring that trailing context
   * distinguishes a section header from a path pattern that merely begins with a character class
   * such as {@code [a-z]*.txt}.
   */
  private boolean isSectionHeader() {
    int i = offset;
    if (i < c.length && c[i] == '^') {
      i++; // optional-section marker
    }
    if (i >= c.length || c[i] != '[') {
      return false;
    }
    while (i < c.length && c[i] != ']') {
      i++; // scan to the section name's closing ']'
    }
    if (i >= c.length) {
      return false; // unterminated brackets: not a well-formed section header
    }
    i++; // move past ']'
    return i >= c.length || Character.isWhitespace(c[i]) || c[i] == '[' || c[i] == '#';
  }

  private Matcher parseMatcher() {
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

  private @Nullable String parseIndexKey() {
    // Index by the first two fixed path segments; patterns without a safe prefix stay linear.
    int position = offset;
    boolean patternContainsSlashes = c[position] == '/';
    if (patternContainsSlashes) {
      position++;
    }
    int prefixStart = position;
    int firstSeparator = -1;
    for (; position < c.length && !isPatternTerminator(c[position]); position++) {
      char character = c[position];
      if (character == '*' || character == '?' || character == '[') {
        return null;
      }
      if (character == '\\') {
        return null;
      }
      if (character == '/') {
        patternContainsSlashes = true;
        if (firstSeparator >= 0) {
          return new String(c, prefixStart, position - prefixStart);
        }
        firstSeparator = position;
      }
    }

    if (!patternContainsSlashes || firstSeparator < 0 || firstSeparator == position - 1) {
      return null;
    }
    return new String(c, prefixStart, position - prefixStart);
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
