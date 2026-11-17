package com.datadog.openfeature.internal.ufc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * A compiled MATCHES/NOT_MATCHES pattern, with {@code find} semantics: the pattern matches if it
 * matches any part of the input.
 *
 * <p>The design follows the Rust evaluator (dd-source {@code rkyv/regex.rs}). At parse time, each
 * pattern gets the cheapest matcher that gives the same result:
 *
 * <ol>
 *   <li>A literal check ({@code equals}, {@code startsWith}, {@code endsWith}, {@code contains})
 *       for a pattern that is only text, with optional anchors, an optional leading or trailing
 *       {@code .*}, and groups of literal alternatives, for example {@code .*@(a|b)\.com$}.
 *   <li>A check that needs no engine: the empty pattern and {@code .*} always match, {@code .+}
 *       matches any input that has a character other than a line feed, and {@code \bliteral\b}
 *       finds the literal at Unicode word boundaries.
 *   <li>RE2/J, a linear-time engine with the same syntax family as the Rust {@code regex} crate. It
 *       does not backtrack, so a long input cannot block the thread. A literal prefix ({@code
 *       ^abc...}) or suffix ({@code ...abc$}) that every match needs is checked first.
 * </ol>
 *
 * <p>The syntax and semantics are the RE2 and Rust ones, not the {@code java.util.regex} ones. For
 * example, {@code $} matches only at the end of the input, and {@code .} matches any character
 * except a line feed.
 *
 * <p>A pattern that RE2/J rejects is invalid, also when {@code java.util.regex} accepts it
 * (lookaround, backreferences, possessive quantifiers): {@link #compile} throws, and the parser
 * drops the flag with a parse error. The Rust SDK evaluator (libdatadog) does the same.
 *
 * <p>Instances are immutable and thread safe.
 */
public abstract class RegexMatcher {
  /** The most literals that a pattern can expand to and still use a literal check. */
  static final int MAX_LITERALS = 64;

  /** The matcher kind, for tests and diagnostics. */
  public enum Kind {
    EQUALS,
    STARTS_WITH,
    ENDS_WITH,
    CONTAINS,
    WORD_BOUNDARY,
    ALWAYS,
    ANY_CHARACTER,
    ENGINE
  }

  private final String pattern;

  RegexMatcher(final String pattern) {
    this.pattern = pattern;
  }

  public String pattern() {
    return pattern;
  }

  public abstract Kind kind();

  /** Tells whether the pattern matches a part of the input. */
  abstract boolean find(String input);

  /**
   * Evaluates the condition.
   *
   * @param input the attribute value.
   * @param expectMatch {@code true} for MATCHES, {@code false} for NOT_MATCHES.
   * @return whether the match result is {@code expectMatch}.
   */
  public boolean test(final String input, final boolean expectMatch) {
    return find(input) == expectMatch;
  }

  /**
   * Compiles a pattern.
   *
   * @throws java.util.regex.PatternSyntaxException if RE2/J rejects the pattern.
   */
  public static RegexMatcher compile(final String pattern) {
    // The Rust regex crate has Unicode mode on by default, and accepts the u flag. RE2/J has no u
    // flag, so remove it.
    final String re2Pattern = withoutUnicodeFlag(pattern);
    final com.google.re2j.Pattern engine;
    try {
      engine = com.google.re2j.Pattern.compile(re2Pattern);
    } catch (final com.google.re2j.PatternSyntaxException e) {
      throw new java.util.regex.PatternSyntaxException(e.getDescription(), pattern, e.getIndex());
    }
    @Nullable final List<Token> tokens = Lexer.tokenize(re2Pattern);
    if (tokens == null) {
      return new Engine(pattern, engine, null, null);
    }
    @Nullable final RegexMatcher fast = FastPath.of(pattern, tokens);
    if (fast != null) {
      return fast;
    }
    return new Engine(
        pattern, engine, Prefilter.requiredPrefix(tokens), Prefilter.requiredSuffixes(tokens));
  }

  /**
   * Removes the {@code u} flag from inline flag groups: {@code (?u)} is removed, {@code (?iu)}
   * becomes {@code (?i)}, and {@code (?u:x)} becomes {@code (?:x)}. Classes and escapes are not
   * changed.
   */
  static String withoutUnicodeFlag(final String pattern) {
    if (pattern.indexOf("(?") < 0) {
      return pattern;
    }
    final StringBuilder out = new StringBuilder(pattern.length());
    int i = 0;
    while (i < pattern.length()) {
      final char c = pattern.charAt(i);
      if (c == '\\' && i + 1 < pattern.length()) {
        out.append(c).append(pattern.charAt(i + 1));
        i += 2;
      } else if (c == '[') {
        final int end = Lexer.classEnd(pattern, i);
        final int stop = end < 0 ? pattern.length() : end + 1;
        out.append(pattern, i, stop);
        i = stop;
      } else if (c == '(' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '?') {
        int j = i + 2;
        final StringBuilder flags = new StringBuilder();
        while (j < pattern.length()
            && (Character.isLetter(pattern.charAt(j)) || pattern.charAt(j) == '-')) {
          if (pattern.charAt(j) != 'u') {
            flags.append(pattern.charAt(j));
          }
          j++;
        }
        final boolean flagGroup =
            j > i + 2
                && j < pattern.length()
                && (pattern.charAt(j) == ')' || pattern.charAt(j) == ':');
        if (!flagGroup) {
          out.append(c);
          i++;
        } else if (pattern.charAt(j) == ')' && (flags.length() == 0 || "-".contentEquals(flags))) {
          i = j + 1;
        } else {
          out.append("(?").append(flags).append(pattern.charAt(j));
          i = j + 1;
        }
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }

  // ---- Matchers ---------------------------------------------------------------------------

  /** The empty pattern, or {@code .*}: every input matches. */
  static final class Always extends RegexMatcher {
    Always(final String pattern) {
      super(pattern);
    }

    @Override
    public Kind kind() {
      return Kind.ALWAYS;
    }

    @Override
    boolean find(final String input) {
      return true;
    }
  }

  /** {@code .+}: the input has a character other than a line feed. */
  static final class AnyCharacter extends RegexMatcher {
    AnyCharacter(final String pattern) {
      super(pattern);
    }

    @Override
    public Kind kind() {
      return Kind.ANY_CHARACTER;
    }

    @Override
    boolean find(final String input) {
      for (int i = 0; i < input.length(); i++) {
        if (input.charAt(i) != '\n') {
          return true;
        }
      }
      return false;
    }
  }

  /** A literal check against one or more literals. */
  static final class Literals extends RegexMatcher {
    private final Kind kind;
    private final String[] literals;

    Literals(final String pattern, final Kind kind, final List<String> literals) {
      super(pattern);
      this.kind = kind;
      this.literals = literals.toArray(new String[0]);
    }

    @Override
    public Kind kind() {
      return kind;
    }

    List<String> literals() {
      return Collections.unmodifiableList(java.util.Arrays.asList(literals));
    }

    @Override
    boolean find(final String input) {
      for (final String literal : literals) {
        final boolean hit;
        switch (kind) {
          case EQUALS:
            hit = input.equals(literal);
            break;
          case STARTS_WITH:
            hit = input.startsWith(literal);
            break;
          case ENDS_WITH:
            hit = input.endsWith(literal);
            break;
          default:
            hit = input.contains(literal);
            break;
        }
        if (hit) {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * {@code \bliteral} or {@code \bliteral\b}: the literal, at a Unicode word boundary. RE2/J has
   * only ASCII word boundaries, so this case does not use it. It follows the Rust evaluator's
   * {@code UnicodeWordBoundaryStartsWithAny}.
   */
  static final class WordBoundary extends RegexMatcher {
    private final String[] literals;
    private final boolean trailingBoundary;

    WordBoundary(
        final String pattern, final List<String> literals, final boolean trailingBoundary) {
      super(pattern);
      this.literals = literals.toArray(new String[0]);
      this.trailingBoundary = trailingBoundary;
    }

    @Override
    public Kind kind() {
      return Kind.WORD_BOUNDARY;
    }

    @Override
    boolean find(final String input) {
      for (final String literal : literals) {
        for (int at = input.indexOf(literal); at >= 0; at = input.indexOf(literal, at + 1)) {
          if (isWordBoundary(input, at)
              && (!trailingBoundary || isWordBoundary(input, at + literal.length()))) {
            return true;
          }
        }
      }
      return false;
    }

    static boolean isWordBoundary(final String s, final int index) {
      final boolean before = index > 0 && isWordCharacter(s.codePointBefore(index));
      final boolean after = index < s.length() && isWordCharacter(s.codePointAt(index));
      return before != after;
    }

    /** The Unicode {@code \w} of the Rust regex crate (UTS #18 Annex C). */
    static boolean isWordCharacter(final int codePoint) {
      if (Character.isAlphabetic(codePoint) || codePoint == 0x200C || codePoint == 0x200D) {
        return true;
      }
      switch (Character.getType(codePoint)) {
        case Character.NON_SPACING_MARK:
        case Character.ENCLOSING_MARK:
        case Character.COMBINING_SPACING_MARK:
        case Character.DECIMAL_DIGIT_NUMBER:
        case Character.CONNECTOR_PUNCTUATION:
          return true;
        default:
          return false;
      }
    }
  }

  /**
   * RE2/J, with optional literal checks that every match needs: a prefix, and a set of suffixes of
   * which the input must end with one.
   */
  static final class Engine extends RegexMatcher {
    private final com.google.re2j.Pattern engine;
    @Nullable private final String requiredPrefix;
    @Nullable private final String[] requiredSuffixes;

    Engine(
        final String pattern,
        final com.google.re2j.Pattern engine,
        @Nullable final String requiredPrefix,
        @Nullable final List<String> requiredSuffixes) {
      super(pattern);
      this.engine = engine;
      this.requiredPrefix = requiredPrefix;
      this.requiredSuffixes =
          requiredSuffixes == null ? null : requiredSuffixes.toArray(new String[0]);
    }

    @Override
    public Kind kind() {
      return Kind.ENGINE;
    }

    @Nullable
    String requiredPrefix() {
      return requiredPrefix;
    }

    @Nullable
    List<String> requiredSuffixes() {
      return requiredSuffixes == null ? null : java.util.Arrays.asList(requiredSuffixes);
    }

    @Override
    boolean find(final String input) {
      if (requiredPrefix != null && !input.startsWith(requiredPrefix)) {
        return false;
      }
      if (requiredSuffixes != null && !endsWithAny(input, requiredSuffixes)) {
        return false;
      }
      return engine.matcher(input).find();
    }

    private static boolean endsWithAny(final String input, final String[] suffixes) {
      for (final String suffix : suffixes) {
        if (input.endsWith(suffix)) {
          return true;
        }
      }
      return false;
    }
  }

  // ---- Pattern analysis -------------------------------------------------------------------

  /** One lexical token of a pattern. */
  static final class Token {
    /** A literal character. */
    static final int LITERAL = 0;

    /** One of {@code . * + ? | ) ^ $ { }}. */
    static final int META = 1;

    /** {@code (} or {@code (?:}. */
    static final int GROUP = 2;

    /** Any other construct: a class, or an escape such as {@code \d} or {@code \b}. */
    static final int OTHER = 3;

    final int type;
    final char value;

    Token(final int type, final char value) {
      this.type = type;
      this.value = value;
    }

    boolean isMeta(final char c) {
      return type == META && value == c;
    }

    boolean isQuantifier() {
      return type == META && (value == '*' || value == '+' || value == '?' || value == '{');
    }
  }

  /**
   * A small, strict lexer. It reads only what the fast paths and prefilters need. RE2/J has already
   * accepted the pattern, so the lexer can assume that it is well formed.
   */
  static final class Lexer {
    private static final String ESCAPABLE = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";

    private Lexer() {}

    /**
     * @return the tokens, or {@code null} when the pattern has flags or a special group ({@code
     *     (?i)}, {@code (?P<name>...)}): their effect on anchors and case needs the full engine.
     */
    @Nullable
    static List<Token> tokenize(final String pattern) {
      final List<Token> tokens = new ArrayList<>(pattern.length());
      int i = 0;
      while (i < pattern.length()) {
        final char c = pattern.charAt(i);
        if (c == '\\') {
          if (i + 1 >= pattern.length()) {
            return null;
          }
          final char e = pattern.charAt(i + 1);
          i += 2;
          if (ESCAPABLE.indexOf(e) >= 0) {
            tokens.add(new Token(Token.LITERAL, e));
          } else if (e == 't') {
            tokens.add(new Token(Token.LITERAL, '\t'));
          } else if (e == 'n') {
            tokens.add(new Token(Token.LITERAL, '\n'));
          } else if (e == 'r') {
            tokens.add(new Token(Token.LITERAL, '\r'));
          } else if (e == 'f') {
            tokens.add(new Token(Token.LITERAL, '\f'));
          } else {
            // \d, \w, \s, \b, \A, \z, \p{..}, \x.., \Q...\E and others.
            tokens.add(new Token(Token.OTHER, e));
          }
        } else if (c == '[') {
          final int end = classEnd(pattern, i);
          if (end < 0) {
            return null;
          }
          tokens.add(new Token(Token.OTHER, '['));
          i = end + 1;
        } else if (c == '(') {
          if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '?') {
            if (i + 2 < pattern.length() && pattern.charAt(i + 2) == ':') {
              tokens.add(new Token(Token.GROUP, '('));
              i += 3;
            } else {
              return null;
            }
          } else {
            tokens.add(new Token(Token.GROUP, '('));
            i++;
          }
        } else if (".*+?|)^${}".indexOf(c) >= 0) {
          tokens.add(new Token(Token.META, c));
          i++;
        } else {
          tokens.add(new Token(Token.LITERAL, c));
          i++;
        }
      }
      return tokens;
    }

    /** Finds the {@code ]} that closes the class that starts at {@code start}, or -1. */
    static int classEnd(final String pattern, final int start) {
      int j = start + 1;
      if (j < pattern.length() && pattern.charAt(j) == '^') {
        j++;
      }
      if (j < pattern.length() && pattern.charAt(j) == ']') {
        j++;
      }
      while (j < pattern.length()) {
        final char c = pattern.charAt(j);
        if (c == '\\') {
          j += 2;
        } else if (c == '[' && j + 1 < pattern.length() && pattern.charAt(j + 1) == ':') {
          final int close = pattern.indexOf(":]", j + 2);
          if (close < 0) {
            return -1;
          }
          j = close + 2;
        } else if (c == ']') {
          return j;
        } else {
          j++;
        }
      }
      return -1;
    }
  }

  /** The literal fast paths, and the checks that need no engine. */
  static final class FastPath {
    private FastPath() {}

    @Nullable
    static RegexMatcher of(final String pattern, final List<Token> all) {
      List<Token> tokens = all;
      if (tokens.isEmpty() || isDotStar(tokens, 0) == tokens.size()) {
        return new Always(pattern);
      }
      if (isDotPlus(tokens)) {
        return new AnyCharacter(pattern);
      }
      if (isWordBoundary(tokens.get(0))) {
        final boolean trailing = tokens.size() > 1 && isWordBoundary(tokens.get(tokens.size() - 1));
        @Nullable
        final List<String> literals =
            expand(tokens.subList(1, tokens.size() - (trailing ? 1 : 0)), false);
        return literals == null ? null : new WordBoundary(pattern, literals, trailing);
      }
      final boolean startAnchored = tokens.get(0).isMeta('^');
      if (startAnchored) {
        tokens = tokens.subList(1, tokens.size());
      } else {
        // find() already tries each start position, so a leading .* changes nothing.
        tokens = tokens.subList(isDotStar(tokens, 0), tokens.size());
      }
      final boolean endAnchored = !tokens.isEmpty() && tokens.get(tokens.size() - 1).isMeta('$');
      if (endAnchored) {
        tokens = tokens.subList(0, tokens.size() - 1);
      } else {
        // A trailing .* can match the empty string, so it changes nothing.
        tokens = tokens.subList(0, tokens.size() - trailingDotStar(tokens));
      }
      @Nullable final List<String> literals = expand(tokens, !startAnchored && !endAnchored);
      if (literals == null) {
        return null;
      }
      final Kind kind =
          startAnchored
              ? (endAnchored ? Kind.EQUALS : Kind.STARTS_WITH)
              : (endAnchored ? Kind.ENDS_WITH : Kind.CONTAINS);
      return new Literals(pattern, kind, literals);
    }

    private static boolean isWordBoundary(final Token token) {
      return token.type == Token.OTHER && token.value == 'b';
    }

    /** The length of a {@code .*} or {@code .*?} at {@code index}, or 0. */
    private static int isDotStar(final List<Token> tokens, final int index) {
      if (index + 1 < tokens.size()
          && tokens.get(index).isMeta('.')
          && tokens.get(index + 1).isMeta('*')) {
        return index + 2 < tokens.size() && tokens.get(index + 2).isMeta('?') ? 3 : 2;
      }
      return 0;
    }

    private static int trailingDotStar(final List<Token> tokens) {
      final int n = tokens.size();
      if (n >= 3 && isDotStar(tokens, n - 3) == 3) {
        return 3;
      }
      if (n >= 2 && isDotStar(tokens, n - 2) == 2) {
        return 2;
      }
      return 0;
    }

    private static boolean isDotPlus(final List<Token> tokens) {
      return (tokens.size() == 2 || (tokens.size() == 3 && tokens.get(2).isMeta('?')))
          && tokens.get(0).isMeta('.')
          && tokens.get(1).isMeta('+');
    }

    /**
     * Expands literal text and groups of literal alternatives into the list of literals that the
     * body can match. Returns {@code null} for any other construct, for an empty literal, or for
     * more than {@link #MAX_LITERALS} literals.
     *
     * @param topLevelAlternation whether {@code a|b} at the top level is allowed. It is allowed
     *     only without anchors, because {@code ^a|b$} means {@code (^a)|(b$)}.
     */
    @Nullable
    private static List<String> expand(
        final List<Token> tokens, final boolean topLevelAlternation) {
      if (tokens.isEmpty()) {
        return null;
      }
      final List<List<Token>> branches = splitTopLevel(tokens);
      if (branches == null || (branches.size() > 1 && !topLevelAlternation)) {
        return null;
      }
      final List<String> out = new ArrayList<>();
      for (final List<Token> branch : branches) {
        @Nullable final List<String> literals = expandBranch(branch);
        if (literals == null || out.size() + literals.size() > MAX_LITERALS) {
          return null;
        }
        out.addAll(literals);
      }
      return out;
    }

    /** Splits on {@code |} outside groups. Returns {@code null} for unbalanced groups. */
    @Nullable
    private static List<List<Token>> splitTopLevel(final List<Token> tokens) {
      final List<List<Token>> branches = new ArrayList<>();
      int depth = 0;
      int start = 0;
      for (int i = 0; i < tokens.size(); i++) {
        final Token t = tokens.get(i);
        if (t.type == Token.GROUP) {
          depth++;
        } else if (t.isMeta(')')) {
          depth--;
          if (depth < 0) {
            return null;
          }
        } else if (depth == 0 && t.isMeta('|')) {
          branches.add(tokens.subList(start, i));
          start = i + 1;
        }
      }
      if (depth != 0) {
        return null;
      }
      branches.add(tokens.subList(start, tokens.size()));
      return branches;
    }

    @Nullable
    private static List<String> expandBranch(final List<Token> tokens) {
      List<StringBuilder> product = new ArrayList<>();
      product.add(new StringBuilder());
      int i = 0;
      while (i < tokens.size()) {
        final Token t = tokens.get(i);
        if (t.type == Token.LITERAL) {
          if (quantified(tokens, i + 1)) {
            return null;
          }
          for (final StringBuilder s : product) {
            s.append(t.value);
          }
          i++;
        } else if (t.type == Token.GROUP) {
          final List<String> alternatives = new ArrayList<>();
          StringBuilder current = new StringBuilder();
          int j = i + 1;
          for (; j < tokens.size() && !tokens.get(j).isMeta(')'); j++) {
            final Token u = tokens.get(j);
            if (u.type == Token.LITERAL) {
              current.append(u.value);
            } else if (u.isMeta('|')) {
              alternatives.add(current.toString());
              current = new StringBuilder();
            } else {
              return null;
            }
          }
          alternatives.add(current.toString());
          if (j >= tokens.size() || quantified(tokens, j + 1) || alternatives.contains("")) {
            return null;
          }
          if (product.size() * alternatives.size() > MAX_LITERALS) {
            return null;
          }
          final List<StringBuilder> next = new ArrayList<>(product.size() * alternatives.size());
          for (final StringBuilder prefix : product) {
            for (final String alternative : alternatives) {
              next.add(new StringBuilder(prefix).append(alternative));
            }
          }
          product = next;
          i = j + 1;
        } else {
          return null;
        }
      }
      final List<String> out = new ArrayList<>(product.size());
      for (final StringBuilder s : product) {
        if (s.length() == 0) {
          return null;
        }
        out.add(s.toString());
      }
      return out;
    }

    private static boolean quantified(final List<Token> tokens, final int next) {
      return next < tokens.size() && tokens.get(next).isQuantifier();
    }
  }

  /** Literal checks that every match needs, run before the engine. */
  static final class Prefilter {
    private Prefilter() {}

    /**
     * For {@code ^abc...}: {@code abc}. A last character that {@code *}, {@code ?} or {@code {}
     * follows can be absent, so it is not part of the prefix. One that {@code +} follows stays.
     */
    @Nullable
    static String requiredPrefix(final List<Token> tokens) {
      if (tokens.isEmpty() || !tokens.get(0).isMeta('^') || hasTopLevelAlternation(tokens)) {
        return null;
      }
      final StringBuilder prefix = new StringBuilder();
      int i = 1;
      for (; i < tokens.size() && tokens.get(i).type == Token.LITERAL; i++) {
        prefix.append(tokens.get(i).value);
      }
      if (prefix.length() > 0
          && i < tokens.size()
          && tokens.get(i).isQuantifier()
          && !tokens.get(i).isMeta('+')) {
        prefix.setLength(prefix.length() - 1);
      }
      return prefix.length() == 0 ? null : prefix.toString();
    }

    /**
     * For a pattern that ends with literal text and groups of literal alternatives before {@code
     * $}, the suffixes of which a matching input ends with one. For example, {@code
     * ^u\d+@(?:a|b)\.(?:io|net)$} gives {@code @a.io}, {@code @a.net}, {@code @b.io} and {@code
     * @b.net}. When the expansion has more than {@link #MAX_LITERALS} suffixes, the earlier parts
     * are left out, so the suffixes become shorter.
     */
    @Nullable
    static List<String> requiredSuffixes(final List<Token> tokens) {
      final int last = tokens.size() - 1;
      if (last < 1 || !tokens.get(last).isMeta('$') || hasTopLevelAlternation(tokens)) {
        return null;
      }
      // The parts before $, from the last one back. Each part is a list of alternatives.
      final List<List<String>> parts = new ArrayList<>();
      int i = last - 1;
      while (i >= 0) {
        final Token t = tokens.get(i);
        if (t.type == Token.LITERAL) {
          parts.add(Collections.singletonList(String.valueOf(t.value)));
          i--;
          continue;
        }
        if (!t.isMeta(')')) {
          break;
        }
        int open = i - 1;
        while (open >= 0 && tokens.get(open).type != Token.GROUP) {
          open--;
        }
        @Nullable final List<String> alternatives = alternatives(tokens, open + 1, i);
        if (open < 0 || alternatives == null) {
          break;
        }
        parts.add(alternatives);
        i = open - 1;
      }
      List<String> suffixes = Collections.singletonList("");
      for (final List<String> part : parts) {
        if (suffixes.size() * part.size() > MAX_LITERALS) {
          break;
        }
        final List<String> next = new ArrayList<>(suffixes.size() * part.size());
        for (final String alternative : part) {
          for (final String suffix : suffixes) {
            next.add(alternative + suffix);
          }
        }
        suffixes = next;
      }
      return suffixes.get(0).isEmpty() ? null : suffixes;
    }

    /** The literal alternatives between two tokens, or {@code null} for any other construct. */
    @Nullable
    private static List<String> alternatives(
        final List<Token> tokens, final int from, final int to) {
      final List<String> out = new ArrayList<>();
      StringBuilder current = new StringBuilder();
      for (int j = from; j < to; j++) {
        final Token u = tokens.get(j);
        if (u.type == Token.LITERAL) {
          current.append(u.value);
        } else if (u.isMeta('|')) {
          out.add(current.toString());
          current = new StringBuilder();
        } else {
          return null;
        }
      }
      out.add(current.toString());
      return out.contains("") ? null : out;
    }

    private static boolean hasTopLevelAlternation(final List<Token> tokens) {
      int depth = 0;
      for (final Token t : tokens) {
        if (t.type == Token.GROUP) {
          depth++;
        } else if (t.isMeta(')')) {
          depth--;
        } else if (depth == 0 && t.isMeta('|')) {
          return true;
        }
      }
      return false;
    }
  }
}
