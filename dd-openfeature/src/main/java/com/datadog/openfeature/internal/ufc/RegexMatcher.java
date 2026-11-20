package com.datadog.openfeature.internal.ufc;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.Collections.unmodifiableList;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;
import javax.annotation.Nullable;

/**
 * A compiled MATCHES/NOT_MATCHES pattern, with {@code find} semantics: the pattern matches if it
 * matches any part of the input.
 *
 * <p>At parse time, each pattern gets the cheapest matcher that gives the same result:
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
 *       ^abc...}) or a set of literal suffixes ({@code ...(?:a|b)$}) that every match needs is
 *       checked first.
 * </ol>
 *
 * <p>The syntax and semantics are those of the Rust {@code regex} crate, which the libdatadog
 * evaluator uses, not those of {@code java.util.regex}. For example, {@code $} matches only at the
 * end of the input, {@code .} matches any character except a line feed, and {@code \d} and {@code
 * \s} are Unicode classes. A pattern that the Rust crate rejects (lookaround, backreferences,
 * atomic groups, {@code \Q...\E}) is invalid: {@link #compile} throws, and the parser drops the
 * flag with a parse error, as libdatadog does.
 *
 * <p>Known differences from the Rust crate:
 *
 * <ul>
 *   <li>{@code \w}, {@code \W}, {@code \b} and {@code \B} use ASCII word characters in the RE2/J
 *       tier. Only the {@code \bliteral} fast path uses Unicode word characters.
 *   <li>{@code \D} and {@code \S} inside a bracketed class are ASCII classes.
 *   <li>Class set operations ({@code &&}, {@code --}, {@code ~~}), nested classes, the {@code x}
 *       and {@code R} flags, {@code \<}, {@code \>}, {@code \b{...}}, and nested repetitions such
 *       as {@code a*+} are a parse error here; the Rust crate accepts them.
 * </ul>
 *
 * <p>Instances are immutable and thread safe.
 */
public abstract class RegexMatcher {
  /** The most literals that a pattern can expand to and still use a literal check. */
  static final int MAX_LITERALS = 64;

  /** The matcher kind, for tests. */
  enum Kind {
    EQUALS,
    STARTS_WITH,
    ENDS_WITH,
    CONTAINS,
    WORD_BOUNDARY,
    ALWAYS,
    ANY_CHARACTER,
    ENGINE
  }

  RegexMatcher() {}

  abstract Kind kind();

  /** Tells whether the pattern matches a part of the input. */
  public abstract boolean matches(String input);

  /**
   * Compiles a pattern.
   *
   * @throws PatternSyntaxException if the pattern is invalid in the Rust {@code regex} dialect, or
   *     uses syntax that RE2/J reads differently.
   */
  public static RegexMatcher compile(final String pattern) {
    return compile(pattern, true);
  }

  /** As {@link #compile}, without the engine-free tiers of {@link PrototypeRegex}: #13487 alone. */
  static RegexMatcher compileWithoutPrototype(final String pattern) {
    return compile(pattern, false);
  }

  private static RegexMatcher compile(final String pattern, final boolean prototype) {
    final String re2Pattern = Dialect.toRe2(pattern);
    Limits.check(pattern, re2Pattern);
    final com.google.re2j.Pattern engine;
    try {
      engine = com.google.re2j.Pattern.compile(re2Pattern);
    } catch (final com.google.re2j.PatternSyntaxException e) {
      final PatternSyntaxException error =
          new PatternSyntaxException(e.getDescription(), pattern, e.getIndex());
      error.initCause(e);
      throw error;
    } catch (final StackOverflowError e) {
      // The RE2/J compiler recurses once for each unrolled repetition, for example 1000 times
      // for x{1,1000}. Reject the pattern instead of failing the whole configuration.
      throw new PatternSyntaxException("the pattern is too complex", pattern, -1);
    }
    @Nullable final List<Token> tokens = Lexer.tokenize(re2Pattern);
    if (tokens == null) {
      return engineOrPrototype(pattern, new Engine(engine, null, null), prototype);
    }
    @Nullable final RegexMatcher fast = FastPath.of(tokens);
    if (fast != null) {
      return fast;
    }
    return engineOrPrototype(
        pattern,
        new Engine(engine, Prefilter.requiredPrefix(tokens), Prefilter.requiredSuffixes(tokens)),
        prototype);
  }

  /** ROLLUP: before RE2/J, tries the engine-free tiers of {@link PrototypeRegex}. */
  private static RegexMatcher engineOrPrototype(
      final String pattern, final RegexMatcher engine, final boolean prototype) {
    if (prototype) {
      // The pattern was compiled once above to validate it. The engine-free tiers don't keep that
      // program: their fallback compiles it again only if an input needs it.
      @Nullable
      final RegexMatcher engineFree =
          PrototypeRegex.engineFree(pattern, new PrototypeRegex.LazyFallback(pattern));
      if (engineFree != null) {
        return engineFree;
      }
    }
    return engine;
  }

  // ---- Matchers ---------------------------------------------------------------------------

  /** The empty pattern, or {@code .*}: every input matches. */
  static final class Always extends RegexMatcher {
    @Override
    Kind kind() {
      return Kind.ALWAYS;
    }

    @Override
    public boolean matches(final String input) {
      return true;
    }
  }

  /** {@code .+}: the input has a character other than a line feed. */
  static final class AnyCharacter extends RegexMatcher {
    @Override
    Kind kind() {
      return Kind.ANY_CHARACTER;
    }

    @Override
    public boolean matches(final String input) {
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

    Literals(final Kind kind, final List<String> literals) {
      this.kind = kind;
      this.literals = literals.toArray(new String[0]);
    }

    @Override
    Kind kind() {
      return kind;
    }

    List<String> literals() {
      return unmodifiableList(asList(literals));
    }

    @Override
    public boolean matches(final String input) {
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
   * only ASCII word boundaries, so this case does not use it.
   */
  static final class WordBoundary extends RegexMatcher {
    private final String[] literals;
    private final boolean trailingBoundary;

    WordBoundary(final List<String> literals, final boolean trailingBoundary) {
      this.literals = literals.toArray(new String[0]);
      this.trailingBoundary = trailingBoundary;
    }

    @Override
    Kind kind() {
      return Kind.WORD_BOUNDARY;
    }

    @Override
    public boolean matches(final String input) {
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
        final com.google.re2j.Pattern engine,
        @Nullable final String requiredPrefix,
        @Nullable final List<String> requiredSuffixes) {
      this.engine = engine;
      this.requiredPrefix = requiredPrefix;
      this.requiredSuffixes =
          requiredSuffixes == null ? null : requiredSuffixes.toArray(new String[0]);
    }

    @Override
    Kind kind() {
      return Kind.ENGINE;
    }

    @Nullable
    String requiredPrefix() {
      return requiredPrefix;
    }

    @Nullable
    List<String> requiredSuffixes() {
      return requiredSuffixes == null ? null : unmodifiableList(asList(requiredSuffixes));
    }

    @Override
    public boolean matches(final String input) {
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

  // ---- Dialect ----------------------------------------------------------------------------

  /** Translates a pattern from the Rust {@code regex} dialect to RE2/J syntax. */
  static final class Dialect {
    /** The Unicode White_Space property, which is {@code \s} in the Rust crate. */
    static final String WHITE_SPACE =
        "\\x{9}-\\x{D}\\x{20}\\x{85}\\x{A0}\\x{1680}\\x{2000}-\\x{200A}"
            + "\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}";

    private Dialect() {}

    /**
     * Translates the pattern:
     *
     * <ul>
     *   <li>{@code \d} and {@code \D} become {@code \p{Nd}} and {@code \P{Nd}}, and {@code \s} and
     *       {@code \S} become the White_Space class, as in the Rust crate. RE2/J classes are ASCII.
     *   <li>The {@code u} flag is removed: Unicode mode is the default in the Rust crate, and RE2/J
     *       has no {@code u} flag.
     * </ul>
     *
     * @throws PatternSyntaxException for {@code \Q}, which the Rust crate rejects, and for class
     *     set operations and nested classes, which RE2/J reads as literal characters.
     */
    static String toRe2(final String pattern) {
      final StringBuilder out = new StringBuilder(pattern.length() + 16);
      int i = 0;
      while (i < pattern.length()) {
        final char c = pattern.charAt(i);
        if (c == '\\' && i + 1 < pattern.length()) {
          i = escape(pattern, i, false, out);
        } else if (c == '[') {
          i = bracketClass(pattern, i, out);
        } else if (c == '(' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '?') {
          i = flagGroup(pattern, i, out);
        } else {
          out.append(c);
          i++;
        }
      }
      return out.toString();
    }

    /** Copies the escape at {@code i} and returns the index after it. */
    private static int escape(
        final String pattern, final int i, final boolean inClass, final StringBuilder out) {
      final char e = pattern.charAt(i + 1);
      switch (e) {
        case 'Q':
          throw new PatternSyntaxException("\\Q quoting is not supported", pattern, i);
        case '<':
        case '>':
          // Word boundaries in the Rust crate; RE2/J reads them as the literal characters.
          throw new PatternSyntaxException("\\< and \\> are not supported", pattern, i);
        case 'b':
        case 'B':
          if (!inClass && i + 2 < pattern.length() && pattern.charAt(i + 2) == '{') {
            // \b{start} and the like: RE2/J reads {start} as literal text after \b.
            throw new PatternSyntaxException("\\b{...} is not supported", pattern, i);
          }
          out.append('\\').append(e);
          return i + 2;
        case 'd':
          out.append("\\p{Nd}");
          return i + 2;
        case 's':
          out.append(inClass ? WHITE_SPACE : "[" + WHITE_SPACE + "]");
          return i + 2;
        case 'D':
          out.append(inClass ? "\\D" : "\\P{Nd}");
          return i + 2;
        case 'S':
          out.append(inClass ? "\\S" : "[^" + WHITE_SPACE + "]");
          return i + 2;
        default:
          out.append('\\').append(e);
          return i + 2;
      }
    }

    /** Copies the bracketed class at {@code start} and returns the index after it. */
    private static int bracketClass(
        final String pattern, final int start, final StringBuilder out) {
      final int end = Lexer.classEnd(pattern, start);
      if (end < 0) {
        // Unterminated: RE2/J reports the error.
        out.append(pattern, start, pattern.length());
        return pattern.length();
      }
      out.append('[');
      int j = start + 1;
      if (pattern.charAt(j) == '^') {
        out.append('^');
        j++;
      }
      if (pattern.charAt(j) == ']') {
        out.append(']');
        j++;
      }
      while (j < end) {
        final char c = pattern.charAt(j);
        if (c == '\\') {
          j = escape(pattern, j, true, out);
        } else if (c == '[' && pattern.charAt(j + 1) == ':') {
          final int close = pattern.indexOf(":]", j + 2) + 2;
          out.append(pattern, j, close);
          j = close;
        } else if (c == '[') {
          throw new PatternSyntaxException("nested classes are not supported", pattern, j);
        } else if ((c == '&' || c == '-' || c == '~') && pattern.charAt(j + 1) == c) {
          throw new PatternSyntaxException("class set operations are not supported", pattern, j);
        } else {
          out.append(c);
          j++;
        }
      }
      out.append(']');
      return end + 1;
    }

    /**
     * Copies the group start at {@code i} without the {@code u} flag: {@code (?u)} is removed,
     * {@code (?iu)} becomes {@code (?i)}, {@code (?i-u)} becomes {@code (?i)}, and {@code (?u:x)}
     * becomes {@code (?:x)}. Returns the index after the copied part.
     */
    private static int flagGroup(final String pattern, final int i, final StringBuilder out) {
      int j = i + 2;
      final StringBuilder flags = new StringBuilder();
      while (j < pattern.length()
          && (Character.isLetter(pattern.charAt(j)) || pattern.charAt(j) == '-')) {
        if (pattern.charAt(j) != 'u') {
          flags.append(pattern.charAt(j));
        }
        j++;
      }
      if (j == i + 2
          || j >= pattern.length()
          || (pattern.charAt(j) != ')' && pattern.charAt(j) != ':')) {
        // Not a flag group, for example (?: or (?P<name>.
        out.append("(?");
        return i + 2;
      }
      if (flags.length() > 0 && flags.charAt(flags.length() - 1) == '-') {
        flags.setLength(flags.length() - 1);
      }
      if (pattern.charAt(j) == ')' && flags.length() == 0) {
        return j + 1;
      }
      out.append("(?").append(flags).append(pattern.charAt(j));
      return j + 1;
    }
  }

  /**
   * Size limits, checked before RE2/J compiles the pattern. RE2/J unrolls counted repetitions and
   * compiles recursively, with no limit, so {@code ((a{1000}){1000}){1000}} would exhaust the heap
   * and deep nesting would overflow the stack. The Rust crate rejects both (its {@code nest_limit}
   * and {@code size_limit}).
   */
  static final class Limits {
    /**
     * The deepest group nesting. It is lower than the default {@code nest_limit} of the Rust crate
     * (250), so that the recursive RE2/J compiler stays well within a small thread stack.
     */
    static final int MAX_NESTING = 100;

    /** The most characters that a pattern can have after its counted repetitions are unrolled. */
    static final long MAX_SIZE = 100_000;

    private Limits() {}

    /**
     * @throws PatternSyntaxException if the pattern nests too deeply or unrolls to too many
     *     characters.
     */
    static void check(final String original, final String pattern) {
      // The size of each enclosing group so far, and of the atom that a repetition applies to.
      final long[] enclosing = new long[MAX_NESTING + 1];
      int depth = 0;
      long size = 0;
      long atom = 0;
      int i = 0;
      while (i < pattern.length()) {
        final char c = pattern.charAt(i);
        if (c == '\\' && i + 1 < pattern.length()) {
          i = Lexer.escapeEnd(pattern, i);
          atom = 1;
          size++;
        } else if (c == '[' && Lexer.classEnd(pattern, i) > 0) {
          i = Lexer.classEnd(pattern, i) + 1;
          atom = 1;
          size++;
        } else if (c == '(') {
          if (depth == MAX_NESTING) {
            throw new PatternSyntaxException("the pattern nests too deeply", original, i);
          }
          enclosing[depth++] = size;
          size = 0;
          atom = 0;
          i++;
        } else if (c == ')' && depth > 0) {
          atom = size + 1;
          size = enclosing[--depth] + atom;
          i++;
        } else if (c == '{' && repeatMax(pattern, i) >= 0) {
          final long max = repeatMax(pattern, i);
          size += atom * (max - 1);
          atom *= max;
          i = pattern.indexOf('}', i) + 1;
        } else {
          atom = 1;
          size++;
          i++;
        }
        if (size > MAX_SIZE) {
          throw new PatternSyntaxException("the pattern is too large", original, -1);
        }
      }
    }

    /** For {@code {n}}, {@code {n,}} or {@code {n,m}} at {@code open}: m, or n + 1; else -1. */
    private static long repeatMax(final String pattern, final int open) {
      final int close = pattern.indexOf('}', open);
      if (close < 0) {
        return -1;
      }
      final String body = pattern.substring(open + 1, close);
      final int comma = body.indexOf(',');
      final String max = comma < 0 ? body : body.substring(comma + 1);
      final String min = comma < 0 ? body : body.substring(0, comma);
      if (!isNumber(min) || !(max.isEmpty() || isNumber(max))) {
        return -1;
      }
      return max.isEmpty() ? Long.parseLong(min) + 1 : Long.parseLong(max);
    }

    /**
     * Whether a repetition count is 1 to 4 plain digits, with no sign. The prototype tiers also use
     * it, so they never read a count that wasn't sized here.
     */
    static boolean isNumber(final String s) {
      if (s.isEmpty() || s.length() > 4) {
        return false;
      }
      for (int i = 0; i < s.length(); i++) {
        if (s.charAt(i) < '0' || s.charAt(i) > '9') {
          return false;
        }
      }
      return true;
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

    /**
     * Any other construct: a class, or an escape such as {@code \d}, {@code \b} or {@code \x41}.
     */
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
            // \d, \w, \s, \b, \A, \z, \p{..}, \x.., octal and others: one token for the whole
            // escape.
            tokens.add(new Token(Token.OTHER, e));
          }
          i = escapeEnd(pattern, i);
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

    /**
     * Returns the index after the escape at {@code i}: {@code \p{..}}, {@code \x{..}}, {@code
     * \xHH}, {@code \pX}, octal {@code \NNN}, or one escaped character.
     */
    static int escapeEnd(final String pattern, final int i) {
      final char e = pattern.charAt(i + 1);
      final int next = i + 2;
      if ((e == 'p' || e == 'P' || e == 'x')
          && next < pattern.length()
          && pattern.charAt(next) == '{') {
        final int close = pattern.indexOf('}', next);
        return close < 0 ? pattern.length() : close + 1;
      }
      if (e == 'p' || e == 'P') {
        return Math.min(next + 1, pattern.length());
      }
      if (e == 'x') {
        return Math.min(next + 2, pattern.length());
      }
      if (e >= '0' && e <= '7') {
        int j = next;
        while (j < pattern.length()
            && j < next + 2
            && pattern.charAt(j) >= '0'
            && pattern.charAt(j) <= '7') {
          j++;
        }
        return j;
      }
      return next;
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

    /** Splits on {@code |} outside groups. Returns {@code null} for unbalanced groups. */
    @Nullable
    static List<List<Token>> splitTopLevel(final List<Token> tokens) {
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

    /** Tells whether the pattern is one branch with balanced groups. */
    static boolean isSingleBranch(final List<Token> tokens) {
      @Nullable final List<List<Token>> branches = splitTopLevel(tokens);
      return branches != null && branches.size() == 1;
    }

    /**
     * The literal alternatives of a group body (the tokens between {@code (} and {@code )}), or
     * {@code null} for any other construct or for an empty alternative.
     */
    @Nullable
    static List<String> alternatives(final List<Token> tokens, final int from, final int to) {
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

    /** The index of the {@code )} that closes the group at {@code open}, if the body is flat. */
    static int flatGroupEnd(final List<Token> tokens, final int open) {
      for (int j = open + 1; j < tokens.size(); j++) {
        final Token t = tokens.get(j);
        if (t.isMeta(')')) {
          return j;
        }
        if (t.type == Token.GROUP) {
          return -1;
        }
      }
      return -1;
    }
  }

  /** The literal fast paths, and the checks that need no engine. */
  static final class FastPath {
    private FastPath() {}

    @Nullable
    static RegexMatcher of(final List<Token> all) {
      List<Token> tokens = all;
      if (tokens.isEmpty() || dotStarLength(tokens, 0) == tokens.size()) {
        return new Always();
      }
      if (isDotPlus(tokens)) {
        return new AnyCharacter();
      }
      if (isWordBoundary(tokens.get(0))) {
        final boolean trailing = tokens.size() > 1 && isWordBoundary(tokens.get(tokens.size() - 1));
        @Nullable
        final List<String> literals =
            expand(tokens.subList(1, tokens.size() - (trailing ? 1 : 0)), false);
        return literals == null ? null : new WordBoundary(literals, trailing);
      }
      final boolean startAnchored = tokens.get(0).isMeta('^');
      if (startAnchored) {
        tokens = tokens.subList(1, tokens.size());
      } else {
        // find() already tries each start position, so a leading .* changes nothing.
        tokens = tokens.subList(dotStarLength(tokens, 0), tokens.size());
      }
      final boolean endAnchored = !tokens.isEmpty() && tokens.get(tokens.size() - 1).isMeta('$');
      if (endAnchored) {
        tokens = tokens.subList(0, tokens.size() - 1);
      } else {
        // A trailing .* can match the empty string, so it changes nothing.
        tokens = tokens.subList(0, tokens.size() - trailingDotStarLength(tokens));
      }
      @Nullable final List<String> literals = expand(tokens, !startAnchored && !endAnchored);
      if (literals == null) {
        return null;
      }
      final Kind kind =
          startAnchored
              ? (endAnchored ? Kind.EQUALS : Kind.STARTS_WITH)
              : (endAnchored ? Kind.ENDS_WITH : Kind.CONTAINS);
      return new Literals(kind, literals);
    }

    private static boolean isWordBoundary(final Token token) {
      return token.type == Token.OTHER && token.value == 'b';
    }

    /** The length of a {@code .*} or {@code .*?} at {@code index}, or 0. */
    private static int dotStarLength(final List<Token> tokens, final int index) {
      if (index + 1 < tokens.size()
          && tokens.get(index).isMeta('.')
          && tokens.get(index + 1).isMeta('*')) {
        return index + 2 < tokens.size() && tokens.get(index + 2).isMeta('?') ? 3 : 2;
      }
      return 0;
    }

    private static int trailingDotStarLength(final List<Token> tokens) {
      final int n = tokens.size();
      if (n >= 3 && dotStarLength(tokens, n - 3) == 3) {
        return 3;
      }
      if (n >= 2 && dotStarLength(tokens, n - 2) == 2) {
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
      @Nullable final List<List<Token>> branches = Lexer.splitTopLevel(tokens);
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
          final int close = Lexer.flatGroupEnd(tokens, i);
          @Nullable
          final List<String> alternatives =
              close < 0 ? null : Lexer.alternatives(tokens, i + 1, close);
          if (alternatives == null
              || quantified(tokens, close + 1)
              || product.size() * alternatives.size() > MAX_LITERALS) {
            return null;
          }
          final List<StringBuilder> next = new ArrayList<>(product.size() * alternatives.size());
          for (final StringBuilder prefix : product) {
            for (final String alternative : alternatives) {
              next.add(new StringBuilder(prefix).append(alternative));
            }
          }
          product = next;
          i = close + 1;
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
      if (tokens.isEmpty() || !tokens.get(0).isMeta('^') || !Lexer.isSingleBranch(tokens)) {
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
        // The quantifier applies to the whole last code point, which can be a surrogate pair.
        final int last = prefix.length() - 1;
        final boolean pair =
            last > 0
                && Character.isLowSurrogate(prefix.charAt(last))
                && Character.isHighSurrogate(prefix.charAt(last - 1));
        prefix.setLength(pair ? last - 1 : last);
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
      if (last < 1 || !tokens.get(last).isMeta('$') || !Lexer.isSingleBranch(tokens)) {
        return null;
      }
      // The parts before $, from the last one back. Each part is a list of alternatives.
      final List<List<String>> parts = new ArrayList<>();
      int i = last - 1;
      while (i >= 0) {
        final Token t = tokens.get(i);
        if (t.type == Token.LITERAL) {
          parts.add(singletonList(String.valueOf(t.value)));
          i--;
          continue;
        }
        if (!t.isMeta(')')) {
          break;
        }
        // The pattern is one balanced branch, so a group opens before this ).
        int open = i - 1;
        while (tokens.get(open).type != Token.GROUP) {
          open--;
        }
        @Nullable final List<String> alternatives = Lexer.alternatives(tokens, open + 1, i);
        if (alternatives == null) {
          break;
        }
        parts.add(alternatives);
        i = open - 1;
      }
      List<String> suffixes = singletonList("");
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
  }
}
