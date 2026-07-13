package datadog.trace.bootstrap.instrumentation.buffer;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * A tiny streaming HTML tokenizer that locates the real {@code </head>} end tag so that content can
 * be injected right before it. Used by {@link HtmlInjectingPipeOutputStream}/{@link
 * HtmlInjectingPipeWriter}.
 *
 * <p>Unlike a naive literal search for the {@code </head>} byte sequence, this state machine is
 * aware of HTML structure. It ignores {@code </head>} occurrences that appear inside comments, raw
 * text elements ({@code <script>}/{@code <style>}) and CDATA sections, and it matches the end tag
 * case-insensitively with optional trailing whitespace/attributes ({@code </HEAD>}, {@code </head
 * >}, {@code </head\n>}), mirroring how browsers parse HTML.
 *
 * <p>It is a pure state machine: it consumes one code unit at a time and returns an {@link
 * #EMIT}/{@link #HOLD}/{@link #FLUSH_THEN_EMIT}/{@link #FLUSH_THEN_HOLD}/{@link #HOLD_THEN_INJECT}
 * action. It performs no IO and owns no buffer; the caller holds back the units it is told to
 * {@code HOLD} so content can be inserted before them. Because it works on {@code int} code units
 * it can drive both the byte pipe (units are {@code byte & 0xFF}) and the char pipe.
 */
@NotThreadSafe
public final class HtmlHeadMatcher {

  /** Emit this unit downstream as-is (nothing is being held back). */
  public static final int EMIT = 0;

  /** Hold this unit back: it may be part of a {@code </head...>} candidate. */
  public static final int HOLD = 1;

  /** Flush the held units downstream, then emit this unit (candidate abandoned). */
  public static final int FLUSH_THEN_EMIT = 2;

  /** Flush the held units downstream, then hold this unit back (a new candidate starts). */
  public static final int FLUSH_THEN_HOLD = 3;

  /**
   * Hold this unit back, then inject the content and flush the held {@code </head...>} units.
   * Injection happens once; afterwards the pipe stops filtering.
   */
  public static final int HOLD_THEN_INJECT = 4;

  /**
   * Maximum number of units held for a candidate before its closing {@code >}. {@code </head} is 6
   * units; the remainder tolerates whitespace and (invalid but ignored) attributes.
   */
  private static final int MAX_CANDIDATE = 64;

  /**
   * Lookbehind buffer size the caller must allocate: a maxed candidate plus its closing {@code >}.
   */
  public static final int LOOKBEHIND_SIZE = MAX_CANDIDATE + 1;

  private static final char[] HEAD = {'h', 'e', 'a', 'd'};
  private static final char[] SCRIPT = {'s', 'c', 'r', 'i', 'p', 't'};
  private static final char[] STYLE = {'s', 't', 'y', 'l', 'e'};
  private static final char[] CDATA = {'C', 'D', 'A', 'T', 'A', '['};

  // States.
  private static final int DATA = 0;
  private static final int TAG_OPEN = 1; // saw '<' (held)
  private static final int END_TAG_OPEN = 2; // saw '</' (held)
  private static final int HEAD_NAME = 3; // matching 'head' (held)
  private static final int AFTER_HEAD_NAME = 4; // matched '</head', awaiting delimiter (held)
  private static final int AFTER_HEAD_WS = 5; // committed head end tag, scanning to '>' (held)
  private static final int START_TAG_NAME = 6; // reading a start tag name (to detect script/style)
  private static final int BEFORE_RAWTEXT = 7; // script/style start tag, scanning to '>'
  private static final int RAWTEXT = 8; // inside script/style text
  private static final int RAWTEXT_LT = 9; // rawtext saw '<'
  private static final int RAWTEXT_SLASH = 10; // rawtext saw '</'
  private static final int RAWTEXT_NAME = 11; // rawtext matching close tag name
  private static final int RAWTEXT_AFTER_NAME =
      12; // rawtext matched close name, awaiting delimiter
  private static final int SCAN_TO_GT = 13; // generic: consume until '>'
  private static final int MARKUP_DECL = 14; // saw '<!'
  private static final int MARKUP_DASH = 15; // saw '<!-'
  private static final int MARKUP_CDATA = 16; // saw '<![', matching 'CDATA['
  private static final int COMMENT = 17; // inside comment, scanning for '-->'
  private static final int CDATA_SECTION = 18; // inside CDATA, scanning for ']]>'
  private static final int DONE = 19; // content injected, pure passthrough

  private int state = DATA;
  private int holdLen = 0;

  // HEAD_NAME progress within HEAD.
  private int headIdx;
  // Start tag name detection.
  private int startNameLen;
  private boolean startScriptPossible;
  private boolean startStylePossible;
  // Raw text close tag matching.
  private char[] rawTarget;
  private int rawIdx;
  // CDATA open matching / comment/cdata end matching.
  private int cdataOpenIdx;
  private int endMatch; // consecutive '-' (comment) or ']' (cdata)

  /**
   * @return {@code true} when nothing is held and the parser is in the plain data state, so the
   *     caller may bulk-emit any run of non-{@code '<'} units without feeding them one by one.
   */
  public boolean inData() {
    return state == DATA;
  }

  /**
   * Consumes the next code unit and returns the action the caller should take.
   *
   * @param c the next byte ({@code 0-255}) or char code unit
   * @return one of {@link #EMIT}, {@link #HOLD}, {@link #FLUSH_THEN_EMIT}, {@link #FLUSH_THEN_HOLD}
   *     or {@link #HOLD_THEN_INJECT}
   */
  public int accept(final int c) {
    switch (state) {
      case DATA:
        if (c == '<') {
          state = TAG_OPEN;
          return hold();
        }
        return EMIT;

      case TAG_OPEN:
        if (c == '/') {
          state = END_TAG_OPEN;
          return hold();
        }
        if (c == '!') {
          state = MARKUP_DECL;
          return flushThenEmit();
        }
        if (c == '<') {
          // The previous '<' was data; this '<' starts a new tag-open.
          return flushThenHold();
        }
        if (isLetter(c)) {
          startNameLen = 1;
          final int lower = toLower(c);
          startScriptPossible = lower == SCRIPT[0];
          startStylePossible = lower == STYLE[0];
          state = START_TAG_NAME;
          return flushThenEmit();
        }
        // Not a tag ('<' followed by e.g. whitespace): the '<' was data.
        state = DATA;
        return flushThenEmit();

      case END_TAG_OPEN:
        if (toLower(c) == HEAD[0]) {
          headIdx = 1;
          state = HEAD_NAME;
          return hold();
        }
        // Some other end tag (or bogus): '</' was data, scan the rest to '>'.
        return abandon(c);

      case HEAD_NAME:
        if (headIdx < HEAD.length && toLower(c) == HEAD[headIdx]) {
          headIdx++;
          if (headIdx == HEAD.length) {
            state = AFTER_HEAD_NAME;
          }
          return hold();
        }
        return abandon(c);

      case AFTER_HEAD_NAME:
        if (c == '>') {
          state = DONE;
          return holdThenInject();
        }
        if (isSpace(c)) {
          state = AFTER_HEAD_WS;
          return hold();
        }
        // Any other char means the tag name is longer than "head" (e.g. </header>): abandon.
        return abandon(c);

      case AFTER_HEAD_WS:
        if (c == '>') {
          state = DONE;
          return holdThenInject();
        }
        if (holdLen >= MAX_CANDIDATE) {
          // Pathological end tag (attributes/whitespace larger than our buffer): give up injecting.
          return abandon(c);
        }
        // Whitespace or (invalid, ignored) attribute chars before '>': keep holding.
        return hold();

      case START_TAG_NAME:
        if (isSpace(c) || c == '>' || c == '/') {
          final boolean isScript = startScriptPossible && startNameLen == SCRIPT.length;
          final boolean isStyle = startStylePossible && startNameLen == STYLE.length;
          if (isScript || isStyle) {
            rawTarget = isScript ? SCRIPT : STYLE;
            state = (c == '>') ? RAWTEXT : BEFORE_RAWTEXT;
          } else {
            state = (c == '>') ? DATA : SCAN_TO_GT;
          }
          return EMIT;
        }
        final int lower = toLower(c);
        if (startNameLen < SCRIPT.length) {
          startScriptPossible = startScriptPossible && lower == SCRIPT[startNameLen];
        } else {
          startScriptPossible = false;
        }
        if (startNameLen < STYLE.length) {
          startStylePossible = startStylePossible && lower == STYLE[startNameLen];
        } else {
          startStylePossible = false;
        }
        startNameLen++;
        return EMIT;

      case BEFORE_RAWTEXT:
        if (c == '>') {
          state = RAWTEXT;
        }
        return EMIT;

      case RAWTEXT:
        if (c == '<') {
          state = RAWTEXT_LT;
        }
        return EMIT;

      case RAWTEXT_LT:
        if (c == '/') {
          state = RAWTEXT_SLASH;
        } else if (c != '<') {
          state = RAWTEXT;
        }
        return EMIT;

      case RAWTEXT_SLASH:
        if (toLower(c) == rawTarget[0]) {
          rawIdx = 1;
          state = RAWTEXT_NAME;
        } else if (c == '<') {
          state = RAWTEXT_LT;
        } else {
          state = RAWTEXT;
        }
        return EMIT;

      case RAWTEXT_NAME:
        if (rawIdx < rawTarget.length && toLower(c) == rawTarget[rawIdx]) {
          rawIdx++;
          if (rawIdx == rawTarget.length) {
            state = RAWTEXT_AFTER_NAME;
          }
        } else if (c == '<') {
          state = RAWTEXT_LT;
        } else {
          state = RAWTEXT;
        }
        return EMIT;

      case RAWTEXT_AFTER_NAME:
        if (isSpace(c) || c == '/') {
          state = SCAN_TO_GT;
        } else if (c == '>') {
          state = DATA;
        } else if (c == '<') {
          state = RAWTEXT_LT;
        } else {
          state = RAWTEXT;
        }
        return EMIT;

      case SCAN_TO_GT:
        if (c == '>') {
          state = DATA;
        }
        return EMIT;

      case MARKUP_DECL:
        if (c == '-') {
          state = MARKUP_DASH;
        } else if (c == '[') {
          cdataOpenIdx = 0;
          state = MARKUP_CDATA;
        } else {
          state = SCAN_TO_GT; // doctype or bogus comment
        }
        return EMIT;

      case MARKUP_DASH:
        if (c == '-') {
          endMatch = 0;
          state = COMMENT;
        } else {
          state = SCAN_TO_GT;
        }
        return EMIT;

      case MARKUP_CDATA:
        if (c == CDATA[cdataOpenIdx]) {
          cdataOpenIdx++;
          if (cdataOpenIdx == CDATA.length) {
            endMatch = 0;
            state = CDATA_SECTION;
          }
        } else {
          state = SCAN_TO_GT;
        }
        return EMIT;

      case COMMENT:
        if (c == '-') {
          endMatch++;
        } else if (c == '>' && endMatch >= 2) {
          state = DATA;
        } else {
          endMatch = 0;
        }
        return EMIT;

      case CDATA_SECTION:
        if (c == ']') {
          endMatch++;
        } else if (c == '>' && endMatch >= 2) {
          state = DATA;
        } else {
          endMatch = 0;
        }
        return EMIT;

      case DONE:
      default:
        return EMIT;
    }
  }

  /**
   * Abandons the current {@code </head...} candidate. The held units are flushed and the current
   * unit is emitted; parsing resumes either in {@link #DATA} (if the tag just closed) or {@link
   * #SCAN_TO_GT} (consume the rest of the tag).
   */
  private int abandon(final int c) {
    state = (c == '>') ? DATA : SCAN_TO_GT;
    return flushThenEmit();
  }

  private int hold() {
    holdLen++;
    return HOLD;
  }

  private int flushThenEmit() {
    holdLen = 0;
    return FLUSH_THEN_EMIT;
  }

  private int flushThenHold() {
    holdLen = 1;
    return FLUSH_THEN_HOLD;
  }

  private int holdThenInject() {
    holdLen = 0;
    return HOLD_THEN_INJECT;
  }

  private static boolean isLetter(final int c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  private static boolean isSpace(final int c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\f' || c == '\r';
  }

  private static int toLower(final int c) {
    return (c >= 'A' && c <= 'Z') ? c + ('a' - 'A') : c;
  }
}
