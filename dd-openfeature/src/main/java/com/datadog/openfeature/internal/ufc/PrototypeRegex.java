package com.datadog.openfeature.internal.ufc;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * PROTOTYPE: engine-free tiers for MATCHES patterns that {@link RegexMatcher} would hand to RE2/J.
 *
 * <p>{@link #compile} first compiles the pattern with {@link RegexMatcher}, which validates it in
 * the Rust {@code regex} dialect and picks its literal tiers. Only when that falls back to RE2/J
 * does this try, in order:
 *
 * <ol>
 *   <li>a segment matcher, after the glob matcher of the sampling-rules prototype: literal pieces
 *       with single-character wildcards ({@code .}), separated by unbounded gaps ({@code .*},
 *       {@code .+}), matched left to right with {@code indexOf}. Greedy leftmost placement is exact
 *       for unbounded gaps, so it never backtracks.
 *   <li>a bit-parallel (shift-and) matcher for runs of literals and character classes with
 *       quantifiers, up to 64 positions after unrolling counted repetitions.
 * </ol>
 *
 * <p>Both only decide ASCII input, where the Rust dialect's Unicode classes ({@code \d}, {@code
 * \w}, {@code \s}) and case folding agree with their ASCII forms. Any other input, and a line feed
 * where {@code .} can't match it, goes to the {@link RegexMatcher} fallback.
 */
abstract class PrototypeRegex extends RegexMatcher {
  private static final int INF = Integer.MAX_VALUE;

  /** The tier chosen, for benchmarks and tests. */
  abstract String tier();

  /** The prototype tiers are engine-free, but stand where RE2/J would. */
  @Override
  Kind kind() {
    return Kind.ENGINE;
  }

  /**
   * For tests and benchmarks: #13487's {@link RegexMatcher} alone, or this prototype's tiers in
   * front of its RE2/J engine.
   */
  static PrototypeRegex compileStandalone(final String pattern) {
    final RegexMatcher fallback = RegexMatcher.compileWithoutPrototype(pattern);
    if (fallback.kind() != RegexMatcher.Kind.ENGINE) {
      return new Delegate(fallback, "regex-matcher:" + fallback.kind());
    }
    @Nullable final PrototypeRegex engineFree = engineFree(pattern, fallback);
    return engineFree != null ? engineFree : new Delegate(fallback, "regex-matcher:ENGINE");
  }

  /** An engine-free matcher for a pattern that would go to RE2/J, or {@code null}. */
  @Nullable
  static PrototypeRegex engineFree(final String pattern, final RegexMatcher fallback) {
    @Nullable final Parsed parsed = Parser.parse(pattern);
    if (parsed == null) {
      return null;
    }
    @Nullable final PrototypeRegex segments = SegmentMatcher.of(parsed, fallback);
    if (segments != null) {
      return segments;
    }
    return BitParallelMatcher.of(parsed, fallback);
  }

  /**
   * The fallback for inputs the engine-free tiers can't decide (non-ASCII, a line feed in a gap).
   * It keeps only the pattern, which the configuration already holds, and compiles #13487's matcher
   * on first use, so configurations whose inputs are all ASCII never keep an RE2/J program. A race
   * compiles it twice at worst; the compiled matcher is immutable.
   */
  static final class LazyFallback extends RegexMatcher {
    private final String pattern;
    @Nullable private volatile RegexMatcher compiled;

    LazyFallback(final String pattern) {
      this.pattern = pattern;
    }

    @Override
    Kind kind() {
      return Kind.ENGINE;
    }

    @Override
    public boolean matches(final String input) {
      RegexMatcher matcher = compiled;
      if (matcher == null) {
        matcher = RegexMatcher.compileWithoutPrototype(pattern);
        compiled = matcher;
      }
      return matcher.matches(input);
    }

    boolean isCompiled() {
      return compiled != null;
    }
  }

  /** Uses {@link RegexMatcher} as it is. */
  static final class Delegate extends PrototypeRegex {
    private final RegexMatcher matcher;
    private final String tier;

    Delegate(final RegexMatcher matcher, final String tier) {
      this.matcher = matcher;
      this.tier = tier;
    }

    @Override
    public boolean matches(final String input) {
      return matcher.matches(input);
    }

    @Override
    String tier() {
      return tier;
    }

    @Override
    Kind kind() {
      return matcher.kind();
    }
  }

  // ---- Parsing --------------------------------------------------------------------------------

  /** One pattern element: a set of ASCII characters and how many times it repeats. */
  static final class Atom {
    final boolean[] set; // the ASCII characters it matches, 128 entries
    final boolean dot; // '.', which also matches non-ASCII characters (decided by the fallback)
    final char literal; // the character, when it is a single literal
    final boolean isLiteral;
    final int min;
    final int max; // INF when unbounded

    Atom(final boolean[] set, final boolean dot, final char literal, final boolean isLiteral) {
      this(set, dot, literal, isLiteral, 1, 1);
    }

    Atom(
        final boolean[] set,
        final boolean dot,
        final char literal,
        final boolean isLiteral,
        final int min,
        final int max) {
      this.set = set;
      this.dot = dot;
      this.literal = literal;
      this.isLiteral = isLiteral;
      this.min = min;
      this.max = max;
    }

    Atom repeated(final int min, final int max) {
      return new Atom(set, dot, literal, isLiteral, min, max);
    }

    boolean quantified() {
      return min != 1 || max != 1;
    }
  }

  /** A pattern in the subset both tiers understand. */
  static final class Parsed {
    final boolean caseInsensitive;
    final boolean dotAll;
    final boolean anchoredStart;
    final boolean anchoredEnd;
    final List<Atom> atoms;

    Parsed(
        final boolean caseInsensitive,
        final boolean dotAll,
        final boolean anchoredStart,
        final boolean anchoredEnd,
        final List<Atom> atoms) {
      this.caseInsensitive = caseInsensitive;
      this.dotAll = dotAll;
      this.anchoredStart = anchoredStart;
      this.anchoredEnd = anchoredEnd;
      this.atoms = atoms;
    }
  }

  /**
   * Parses the subset: leading flags {@code (?isu)}, anchors {@code ^ \A $ \z} at the ends,
   * literals, {@code .}, classes and their quantifiers. Returns {@code null} for anything else. The
   * pattern has already been validated by {@link RegexMatcher#compile}.
   */
  static final class Parser {
    private final String p;
    private int i;
    private boolean ci;
    private boolean dotAll;

    private Parser(final String pattern) {
      this.p = pattern;
    }

    @Nullable
    static Parsed parse(final String pattern) {
      try {
        return new Parser(pattern).parse();
      } catch (final Unsupported e) {
        return null;
      }
    }

    private Parsed parse() {
      while (p.startsWith("(?", i)) {
        final int end = p.indexOf(')', i);
        if (end < 0) {
          throw new Unsupported();
        }
        for (int j = i + 2; j < end; j++) {
          switch (p.charAt(j)) {
            case 'i':
              ci = true;
              break;
            case 's':
              dotAll = true;
              break;
            case 'u':
              break;
            default:
              throw new Unsupported(); // m, x, U, R, '-', ':' groups, lookaround, ...
          }
        }
        i = end + 1;
      }
      boolean anchoredStart = false;
      if (p.startsWith("^", i)) {
        anchoredStart = true;
        i += 1;
      } else if (p.startsWith("\\A", i)) {
        anchoredStart = true;
        i += 2;
      }
      boolean anchoredEnd = false;
      final List<Atom> atoms = new ArrayList<>();
      while (i < p.length()) {
        final char c = p.charAt(i);
        if (c == '$' && i == p.length() - 1) {
          anchoredEnd = true;
          i += 1;
          break;
        }
        if (p.startsWith("\\z", i) && i + 2 == p.length()) {
          anchoredEnd = true;
          i += 2;
          break;
        }
        Atom atom = atom();
        atom = quantifier(atom);
        atoms.add(atom);
      }
      return new Parsed(ci, dotAll, anchoredStart, anchoredEnd, atoms);
    }

    private Atom atom() {
      final char c = p.charAt(i);
      switch (c) {
        case '.':
          i += 1;
          final boolean[] any = new boolean[128];
          for (int x = 0; x < 128; x++) {
            any[x] = dotAll || x != '\n';
          }
          return new Atom(any, true, '\0', false);
        case '[':
          return new Atom(fold(charClass()), false, '\0', false);
        case '\\':
          return escape();
        case '(':
        case ')':
        case '|':
        case '*':
        case '+':
        case '?':
        case '{':
        case '}':
        case '^':
        case '$':
          throw new Unsupported();
        default:
          if (c >= 128) {
            throw new Unsupported();
          }
          i += 1;
          return literal(c);
      }
    }

    private Atom literal(final char c) {
      final boolean[] set = new boolean[128];
      set[c] = true;
      if (ci && Character.isLetter(c)) {
        set[Character.toLowerCase(c)] = true;
        set[Character.toUpperCase(c)] = true;
        return new Atom(set, false, '\0', false); // a folded letter is a two-character set
      }
      return new Atom(set, false, c, true);
    }

    private Atom escape() {
      if (i + 1 >= p.length()) {
        throw new Unsupported();
      }
      final char n = p.charAt(i + 1);
      final boolean[] cls = shorthand(n);
      if (cls != null) {
        i += 2;
        return new Atom(fold(cls), false, '\0', false);
      }
      final int ch = escapedChar(n);
      return literal((char) ch);
    }

    /** Reads an escaped literal character at {@code i} (the backslash), advancing past it. */
    private int escapedChar(final char n) {
      switch (n) {
        case 't':
          i += 2;
          return '\t';
        case 'n':
          i += 2;
          return '\n';
        case 'r':
          i += 2;
          return '\r';
        case 'f':
          i += 2;
          return '\f';
        case 'v':
          i += 2;
          return 0x0B;
        case 'x':
          if (i + 3 < p.length() + 0 && isHex(p.charAt(i + 2)) && isHex(p.charAt(i + 3))) {
            final int v = Integer.parseInt(p.substring(i + 2, i + 4), 16);
            i += 4;
            if (v >= 128) {
              throw new Unsupported();
            }
            return v;
          }
          throw new Unsupported();
        default:
          // Punctuation escapes are literals; letters and digits are classes, anchors,
          // backreferences or Unicode properties, which this subset doesn't handle.
          if (n < 128 && !Character.isLetterOrDigit(n)) {
            i += 2;
            return n;
          }
          throw new Unsupported();
      }
    }

    @Nullable
    private static boolean[] shorthand(final char n) {
      final boolean[] set = new boolean[128];
      switch (n) {
        case 'd':
        case 'D':
          for (int x = '0'; x <= '9'; x++) {
            set[x] = true;
          }
          break;
        case 'w':
        case 'W':
          for (int x = 0; x < 128; x++) {
            set[x] = Character.isLetterOrDigit(x) || x == '_';
          }
          break;
        case 's':
        case 'S':
          for (final char x : new char[] {' ', '\t', '\n', 0x0B, '\f', '\r'}) {
            set[x] = true;
          }
          break;
        default:
          return null;
      }
      if (Character.isUpperCase(n)) {
        negate(set);
      }
      return set;
    }

    private boolean[] charClass() {
      i += 1; // '['
      boolean negated = false;
      if (i < p.length() && p.charAt(i) == '^') {
        negated = true;
        i += 1;
      }
      final boolean[] set = new boolean[128];
      boolean first = true;
      while (true) {
        if (i >= p.length()) {
          throw new Unsupported();
        }
        final char c = p.charAt(i);
        if (c == ']' && !first) {
          i += 1;
          break;
        }
        first = false;
        if (p.startsWith("[:", i)) {
          final int end = p.indexOf(":]", i);
          if (end < 0) {
            throw new Unsupported();
          }
          or(set, posix(p.substring(i + 2, end)));
          i = end + 2;
          continue;
        }
        if (c == '['
            || p.startsWith("&&", i)
            || p.startsWith("--", i) && i + 2 < p.length() && p.charAt(i + 2) != ']'
            || p.startsWith("~~", i)) {
          throw new Unsupported(); // nested classes and set operations
        }
        final int low;
        if (c == '\\') {
          if (i + 1 >= p.length()) {
            throw new Unsupported();
          }
          final boolean[] cls = shorthand(p.charAt(i + 1));
          if (cls != null) {
            or(set, cls);
            i += 2;
            continue;
          }
          low = escapedChar(p.charAt(i + 1));
        } else {
          if (c >= 128) {
            throw new Unsupported();
          }
          low = c;
          i += 1;
        }
        if (i + 1 < p.length() && p.charAt(i) == '-' && p.charAt(i + 1) != ']') {
          i += 1;
          final int high;
          final char h = p.charAt(i);
          if (h == '\\') {
            high = escapedChar(p.charAt(i + 1));
          } else if (h == '[' || h >= 128) {
            throw new Unsupported();
          } else {
            high = h;
            i += 1;
          }
          if (high < low) {
            throw new Unsupported();
          }
          for (int x = low; x <= high; x++) {
            set[x] = true;
          }
        } else {
          set[low] = true;
        }
      }
      if (negated) {
        negate(set);
      }
      return set;
    }

    private static boolean[] posix(final String name) {
      final boolean[] set = new boolean[128];
      for (int x = 0; x < 128; x++) {
        final boolean in;
        switch (name) {
          case "alnum":
            in = Character.isLetterOrDigit(x);
            break;
          case "alpha":
            in = Character.isLetter(x);
            break;
          case "ascii":
            in = true;
            break;
          case "blank":
            in = x == ' ' || x == '\t';
            break;
          case "cntrl":
            in = x < 32 || x == 127;
            break;
          case "digit":
            in = x >= '0' && x <= '9';
            break;
          case "graph":
            in = x > 32 && x < 127;
            break;
          case "lower":
            in = x >= 'a' && x <= 'z';
            break;
          case "print":
            in = x >= 32 && x < 127;
            break;
          case "punct":
            in = x > 32 && x < 127 && !Character.isLetterOrDigit(x);
            break;
          case "space":
            in = x == ' ' || (x >= '\t' && x <= '\r');
            break;
          case "upper":
            in = x >= 'A' && x <= 'Z';
            break;
          case "word":
            in = Character.isLetterOrDigit(x) || x == '_';
            break;
          case "xdigit":
            in = isHex((char) x);
            break;
          default:
            throw new Unsupported();
        }
        set[x] = in;
      }
      return set;
    }

    private Atom quantifier(final Atom atom) {
      if (i >= p.length()) {
        return atom;
      }
      final int min;
      final int max;
      final char c = p.charAt(i);
      if (c == '*') {
        min = 0;
        max = INF;
        i += 1;
      } else if (c == '+') {
        min = 1;
        max = INF;
        i += 1;
      } else if (c == '?') {
        min = 0;
        max = 1;
        i += 1;
      } else if (c == '{') {
        final int end = p.indexOf('}', i);
        if (end < 0) {
          throw new Unsupported();
        }
        final String body = p.substring(i + 1, end);
        final int comma = body.indexOf(',');
        try {
          if (comma < 0) {
            min = Integer.parseInt(body);
            max = min;
          } else {
            min = Integer.parseInt(body.substring(0, comma));
            max = comma == body.length() - 1 ? INF : Integer.parseInt(body.substring(comma + 1));
          }
        } catch (final NumberFormatException e) {
          throw new Unsupported();
        }
        i = end + 1;
      } else {
        return atom;
      }
      if (i < p.length() && p.charAt(i) == '?') {
        i += 1; // lazy: the same yes/no answer
      }
      if (i < p.length() && "*+?{".indexOf(p.charAt(i)) >= 0) {
        throw new Unsupported(); // nested repetition
      }
      return atom.repeated(min, max);
    }

    private boolean[] fold(final boolean[] set) {
      if (ci) {
        for (int x = 'a'; x <= 'z'; x++) {
          if (set[x] || set[x - 32]) {
            set[x] = true;
            set[x - 32] = true;
          }
        }
      }
      return set;
    }

    private static void negate(final boolean[] set) {
      for (int x = 0; x < 128; x++) {
        set[x] = !set[x];
      }
    }

    private static void or(final boolean[] set, final boolean[] other) {
      for (int x = 0; x < 128; x++) {
        set[x] |= other[x];
      }
    }

    private static boolean isHex(final char c) {
      return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static final class Unsupported extends RuntimeException {
      Unsupported() {
        super(null, null, false, false);
      }
    }
  }

  // ---- Segment matcher -------------------------------------------------------------------------

  /**
   * Literal segments, where {@code .} is a single-character wildcard, separated by unbounded gaps.
   * Gaps of {@code .*} and {@code .+} merge, so {@code .*.*.*} costs one gap. Each segment is
   * placed at its leftmost position after the previous one, which is exact when every gap is
   * unbounded.
   */
  static final class SegmentMatcher extends PrototypeRegex {
    private final char[][] segments; // ANY marks a '.' position
    private final int[] gapMin; // minimum gap before each segment
    private final boolean leadingBounded; // anchored start and no leading gap of .* or .+
    private final int trailingMin;
    private final boolean trailingBounded; // anchored end and no trailing .* or .+
    private final boolean caseInsensitive;
    private final boolean dotAll;
    private final boolean anchoredStart;
    private final boolean anchoredEnd;
    private final boolean endsWithGap;
    private final int minLength;
    private final RegexMatcher fallback;

    private static final char ANY = '￿';

    private SegmentMatcher(
        final char[][] segments,
        final int[] gapMin,
        final boolean leadingBounded,
        final int trailingMin,
        final boolean trailingBounded,
        final Parsed parsed,
        final boolean endsWithGap,
        final RegexMatcher fallback) {
      this.segments = segments;
      this.gapMin = gapMin;
      this.leadingBounded = leadingBounded;
      this.trailingMin = trailingMin;
      this.trailingBounded = trailingBounded;
      this.caseInsensitive = parsed.caseInsensitive;
      this.dotAll = parsed.dotAll;
      this.anchoredStart = parsed.anchoredStart;
      this.anchoredEnd = parsed.anchoredEnd;
      this.endsWithGap = endsWithGap;
      int min = trailingMin;
      for (int k = 0; k < segments.length; k++) {
        min += gapMin[k] + segments[k].length;
      }
      this.minLength = min;
      this.fallback = fallback;
    }

    @Nullable
    static PrototypeRegex of(final Parsed parsed, final RegexMatcher fallback) {
      final List<char[]> segments = new ArrayList<>();
      final List<Integer> gaps = new ArrayList<>();
      final StringBuilder segment = new StringBuilder();
      int gap = 0;
      boolean gapUnbounded = !parsed.anchoredStart;
      boolean leadingBounded = parsed.anchoredStart;
      boolean hasDot = false;
      for (final Atom atom : parsed.atoms) {
        if (atom.dot) {
          hasDot = true;
          if (!atom.quantified()) {
            segment.append(ANY);
            continue;
          }
          if (atom.max != INF) {
            return null; // .{n,m}: the bit-parallel matcher handles bounded gaps
          }
          if (segment.length() > 0) {
            segments.add(segment.toString().toCharArray());
            gaps.add(gap);
            segment.setLength(0);
            gap = 0;
          } else if (segments.isEmpty()) {
            leadingBounded = false;
          }
          gap += atom.min;
          gapUnbounded = true;
          continue;
        }
        if (!atom.isLiteral || atom.quantified()) {
          if (!parsed.caseInsensitive || atom.quantified() || !isFoldedLetter(atom)) {
            return null;
          }
          segment.append(lowerLetter(atom)); // (?i) letter, compared folded
          continue;
        }
        if (segment.length() == 0 && !segments.isEmpty() && !gapUnbounded) {
          return null; // unreachable: segments are always separated by a gap
        }
        segment.append(atom.literal);
      }
      int trailingMin = 0;
      boolean trailingBounded = parsed.anchoredEnd;
      if (segment.length() > 0) {
        segments.add(segment.toString().toCharArray());
        gaps.add(gap);
      } else {
        trailingMin = gap;
        if (gapUnbounded && gap >= 0 && !segments.isEmpty()) {
          trailingBounded = false;
        }
      }
      if (segments.isEmpty()) {
        return null; // only wildcards: RegexMatcher's tiers or the bit-parallel matcher
      }
      if (segments.size() == 1 && !hasDot && !parsed.caseInsensitive) {
        return null; // a single literal: RegexMatcher's literal tier already covers the rest
      }
      final int[] gapMin = new int[gaps.size()];
      for (int k = 0; k < gapMin.length; k++) {
        gapMin[k] = gaps.get(k);
      }
      // A trailing .* or .+ leaves the end unbounded even when anchored.
      final boolean endsWithGap = segment.length() == 0;
      return new SegmentMatcher(
          segments.toArray(new char[0][]),
          gapMin,
          leadingBounded,
          trailingMin,
          trailingBounded && !endsWithGap,
          parsed,
          endsWithGap,
          fallback);
    }

    private static boolean isFoldedLetter(final Atom atom) {
      int count = 0;
      for (int x = 0; x < 128; x++) {
        if (atom.set[x]) {
          count++;
        }
      }
      return count == 2 && atom.set[lowerLetter(atom)] && atom.set[lowerLetter(atom) - 32];
    }

    private static char lowerLetter(final Atom atom) {
      for (int x = 'a'; x <= 'z'; x++) {
        if (atom.set[x]) {
          return (char) x;
        }
      }
      return '\0';
    }

    @Override
    String tier() {
      return "prototype:SEGMENTS";
    }

    private static final int MATCH = 0;
    private static final int NO = -1;
    private static final int FALLBACK = -2;

    @Override
    public boolean matches(final String s) {
      final int n = s.length();
      if (n < minLength) {
        return false;
      }
      final int last = segments.length - 1;
      // Cheapest rejections first: an anchored last segment has a fixed position.
      int lastAt = -1;
      if (trailingBounded) {
        lastAt = n - segments[last].length;
        final int r = compareAt(segments[last], s, lastAt);
        if (r == NO) {
          return false;
        } else if (r == FALLBACK) {
          return fallback.matches(s);
        }
      }
      int pos = 0;
      for (int k = 0; k <= last; k++) {
        final char[] seg = segments[k];
        final int from = pos + gapMin[k];
        final int at;
        if (k == last && trailingBounded) {
          at = lastAt;
          if (k == 0 && leadingBounded ? at != from : at < from) {
            return false;
          }
        } else if (k == 0 && leadingBounded) {
          final int r = compareAt(seg, s, from);
          if (r == NO) {
            return false;
          } else if (r == FALLBACK) {
            return fallback.matches(s);
          }
          at = from;
        } else {
          at = find(seg, s, from);
          if (at == NO) {
            return false;
          } else if (at == FALLBACK) {
            return fallback.matches(s);
          }
        }
        // Without (?s), a gap of .* or .+ can't hold a line feed. A line feed in a gap could still
        // allow a later placement of an earlier segment, so let the fallback decide.
        if (!dotAll) {
          final int gapStart = k > 0 ? pos : anchoredStart ? 0 : at - gapMin[0];
          if (hasNewline(s, gapStart, at)) {
            return fallback.matches(s);
          }
        }
        pos = at + seg.length;
      }
      if (trailingBounded) {
        return true; // the last segment ends at the end of the input
      }
      if (pos + trailingMin > n) {
        return false;
      }
      if (!dotAll && endsWithGap) {
        if (hasNewline(s, pos, anchoredEnd ? n : pos + trailingMin)) {
          return fallback.matches(s);
        }
      }
      return true;
    }

    private static boolean hasNewline(final String s, final int from, final int to) {
      for (int x = from; x < to; x++) {
        if (s.charAt(x) == '\n') {
          return true;
        }
      }
      return false;
    }

    /** The leftmost position at or after {@code from} where {@code seg} matches, or NO/FALLBACK. */
    private int find(final char[] seg, final String s, final int from) {
      final int limit = s.length() - seg.length;
      final char first = seg[0];
      for (int at = from; at <= limit; at++) {
        if (first != ANY && !caseInsensitive) {
          at = s.indexOf(first, at);
          if (at < 0 || at > limit) {
            return NO;
          }
        }
        final int r = compareAt(seg, s, at);
        if (r == MATCH) {
          return at;
        } else if (r == FALLBACK) {
          return FALLBACK;
        }
      }
      return NO;
    }

    /**
     * Compares {@code seg} at {@code at}. Only the compared characters decide: a surrogate under
     * {@code .} (a code point spans two chars) or a non-ASCII character under (?i) (Unicode case
     * folding, such as the Kelvin sign) goes to the fallback.
     */
    private int compareAt(final char[] seg, final String s, final int at) {
      if (at < 0 || at + seg.length > s.length()) {
        return NO;
      }
      for (int j = 0; j < seg.length; j++) {
        final char p = seg[j];
        char c = s.charAt(at + j);
        if (p == ANY) {
          if (c == '\n' && !dotAll) {
            return NO;
          }
          if (Character.isSurrogate(c)) {
            return FALLBACK;
          }
          continue;
        }
        if (caseInsensitive) {
          if (c >= 128) {
            return FALLBACK;
          }
          if (c >= 'A' && c <= 'Z') {
            c = (char) (c + 32);
          }
        }
        if (c != p) {
          return NO;
        }
      }
      return MATCH;
    }
  }

  // ---- Bit-parallel matcher --------------------------------------------------------------------

  /**
   * Shift-and over pattern positions, one bit each, with self-loops for {@code +}/{@code *} and
   * epsilon skips for optional positions. Counted repetitions are unrolled. One table lookup, a
   * shift and a few masks per input character, and no allocation.
   */
  static final class BitParallelMatcher extends PrototypeRegex {
    // Each ASCII character maps to a class, and each class to the positions it matches. Most
    // patterns have a handful of distinct classes, so this is ~200 B instead of a long[128].
    private final byte[] classOf = new byte[128];
    private final long[] classMasks;
    private final long loops;
    private final long optional;
    private final long startBits; // positions reachable from the start by skipping optionals
    private final long finalBit;
    private final int optionalRun; // the longest run of consecutive optional positions
    private final boolean matchesEmpty;
    private final boolean anchoredStart;
    private final boolean anchoredEnd;
    private final int minLength;
    @Nullable private final String prefix; // a literal the input must start with (anchored start)
    @Nullable private final String suffix; // a literal the input must end with (anchored end)
    private final RegexMatcher fallback;

    private BitParallelMatcher(
        final List<boolean[]> sets,
        final List<Boolean> isLoop,
        final List<Boolean> isOptional,
        final Parsed parsed,
        final RegexMatcher fallback) {
      final int m = sets.size();
      final long[] masks = new long[128];
      long loops = 0;
      long optional = 0;
      int run = 0;
      int longest = 0;
      for (int j = 0; j < m; j++) {
        final boolean[] set = sets.get(j);
        for (int x = 0; x < 128; x++) {
          if (set[x]) {
            masks[x] |= 1L << j;
          }
        }
        if (isLoop.get(j)) {
          loops |= 1L << j;
        }
        if (isOptional.get(j)) {
          optional |= 1L << j;
          run += 1;
          longest = Math.max(longest, run);
        } else {
          run = 0;
        }
      }
      final long[] distinct = new long[128];
      int classes = 0;
      for (int x = 0; x < 128; x++) {
        int k = 0;
        while (k < classes && distinct[k] != masks[x]) {
          k++;
        }
        if (k == classes) {
          distinct[classes++] = masks[x];
        }
        classOf[x] = (byte) k;
      }
      this.classMasks = java.util.Arrays.copyOf(distinct, classes);
      long start = 0;
      for (int j = 0; j < m; j++) {
        start |= 1L << j;
        if (!isOptional.get(j)) {
          break;
        }
      }
      this.loops = loops;
      this.optional = optional;
      this.startBits = start;
      this.finalBit = 1L << (m - 1);
      this.optionalRun = longest;
      this.matchesEmpty = Long.bitCount(optional) == m;
      this.anchoredStart = parsed.anchoredStart;
      this.anchoredEnd = parsed.anchoredEnd;
      int min = 0;
      for (final Atom atom : parsed.atoms) {
        min += atom.min;
      }
      this.minLength = min;
      this.prefix = parsed.anchoredStart ? literalRun(parsed.atoms, true) : null;
      this.suffix = parsed.anchoredEnd ? literalRun(parsed.atoms, false) : null;
      this.fallback = fallback;
    }

    /** The literal characters at the start (or end) of the pattern, or null if there are none. */
    @Nullable
    private static String literalRun(final List<Atom> atoms, final boolean fromStart) {
      final StringBuilder run = new StringBuilder();
      for (int k = 0; k < atoms.size(); k++) {
        final Atom atom = atoms.get(fromStart ? k : atoms.size() - 1 - k);
        if (!atom.isLiteral || atom.quantified()) {
          break;
        }
        run.append(atom.literal);
      }
      if (run.length() == 0) {
        return null;
      }
      return fromStart ? run.toString() : run.reverse().toString();
    }

    @Nullable
    static PrototypeRegex of(final Parsed parsed, final RegexMatcher fallback) {
      final List<boolean[]> sets = new ArrayList<>();
      final List<Boolean> loops = new ArrayList<>();
      final List<Boolean> optional = new ArrayList<>();
      for (final Atom atom : parsed.atoms) {
        for (int k = 0; k < atom.min; k++) {
          sets.add(atom.set);
          loops.add(atom.max == INF && k == atom.min - 1);
          optional.add(false);
        }
        if (atom.max == INF) {
          if (atom.min == 0) {
            sets.add(atom.set);
            loops.add(true);
            optional.add(true);
          }
        } else {
          for (int k = atom.min; k < atom.max; k++) {
            sets.add(atom.set);
            loops.add(false);
            optional.add(true);
          }
        }
        if (sets.size() > 64) {
          return null;
        }
      }
      if (sets.isEmpty()) {
        return null;
      }
      return new BitParallelMatcher(sets, loops, optional, parsed, fallback);
    }

    @Override
    String tier() {
      return "prototype:BIT_PARALLEL";
    }

    @Override
    public boolean matches(final String s) {
      final int n = s.length();
      if (matchesEmpty && (!anchoredStart || !anchoredEnd || n == 0)) {
        return true;
      }
      // Literal prefilters, as RegexMatcher's engine tier has: reject without scanning.
      if (n < minLength
          || (suffix != null && !s.endsWith(suffix))
          || (prefix != null && !s.startsWith(prefix))) {
        return false;
      }
      long d = 0;
      for (int x = 0; x < n; x++) {
        final char c = s.charAt(x);
        if (c >= 128) {
          return fallback.matches(s);
        }
        final long inject = (!anchoredStart || x == 0) ? startBits : 0L;
        final long b = classMasks[classOf[c]];
        d = (((d << 1) | inject) & b) | (d & loops & b);
        for (int k = 0; k < optionalRun; k++) {
          d |= (d << 1) & optional;
        }
        if ((d & finalBit) != 0 && (!anchoredEnd || x == n - 1)) {
          return true;
        }
        if (d == 0 && anchoredStart) {
          return false;
        }
      }
      return false;
    }
  }
}
