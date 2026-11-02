package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

public class SubSequenceTest {
  static final String LOREM_IPSUM =
      "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor "
      + "incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis "
      + "nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. "
      + "Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu "
      + "fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in "
      + "culpa qui officia deserunt mollit anim id est laborum.";

  @Test
  public void empty() {
    SubSequence subSeq = SubSequence.EMPTY;
    assertEquals("", subSeq.toString());
    assertEquals("".hashCode(), subSeq.hashCode());

    StringBuilder builder0 = new StringBuilder();
    builder0.append(subSeq);
    assertEquals("", builder0.toString());

    StringBuilder builder1 = new StringBuilder();
    subSeq.appendTo(builder1);
    assertEquals("", builder1.toString());
  }

  @Test
  public void emptyTail() {
    // This is allowed to represent the logical empty string after a match at the end of
    // backing string.  There is an important distinction from the canonical empty
    // SubSequence which wouldn't have the correct beginIndex / endIndex pair.
    SubSequence subSeq = new SubSequence("foo", "foo".length(), "foo".length());
    assertEquals("foo".length(), subSeq.beginIndex());
    assertEquals("foo".length(), subSeq.endIndex());
    assertEquals("", subSeq.toString());
    assertEquals("".hashCode(), subSeq.hashCode());

    StringBuilder builder0 = new StringBuilder();
    builder0.append(subSeq);
    assertEquals("", builder0.toString());

    StringBuilder builder1 = new StringBuilder();
    subSeq.appendTo(builder1);
    assertEquals("", builder1.toString());
  }

  @Test
  public void subSequence() {
    String str = LOREM_IPSUM;
    int len = str.length();

    for (int i = 0; i < str.length(); i += 100) {
      int endIndex = Math.min(i + 100, len);

      String subStr = str.substring(i, endIndex);
      CharSequence subCharSeq = str.subSequence(i, endIndex);
      SubSequence subSeq = SubSequence.of(str, i, endIndex);
      SubSequence altSubSeq = Strings.subSequence(str, i, endIndex);

      assertTrue(subSeq.equals(subStr));
      assertEquals(subStr, subSeq.toString());
      assertEquals(subStr.hashCode(), subSeq.hashCode());

      assertTrue(subSeq.equals(subCharSeq));
      assertEquals(subCharSeq.toString(), subSeq.toString());

      assertEquals(subSeq, altSubSeq);

      assertSame(subSeq.toString(), subSeq.toString());
    }
  }

  @Test
  public void subSequenceToEnd() {
    String str = LOREM_IPSUM;
    int len = str.length();

    for (int i = 0; i < str.length(); i += 100) {
      String subStr = str.substring(i);
      SubSequence subSeq = SubSequence.of(str, i);
      SubSequence altSubSeq = Strings.subSequence(str, i);

      assertTrue(subSeq.equals(subStr));
      assertEquals(subStr, subSeq.toString());
      assertEquals(subStr.hashCode(), subSeq.hashCode());

      assertEquals(subSeq, altSubSeq);

      assertSame(subSeq.toString(), subSeq.toString());
    }
  }

  @Test
  public void appendToBuilder() {
    SubSequence subSeq = SubSequence.of(LOREM_IPSUM, 50, 150);

    StringBuilder expectedBuilder = new StringBuilder();
    expectedBuilder.append(LOREM_IPSUM, 50, 150);

    String expectedStr = expectedBuilder.toString();

    StringBuilder builder0 = new StringBuilder();
    builder0.append(subSeq);
    assertEquals(expectedStr, builder0.toString());

    StringBuilder builder1 = new StringBuilder();
    subSeq.appendTo(builder1);
    assertEquals(expectedStr, builder1.toString());
  }

  @Test
  public void contains() {
    // "/*ddps='svc',dde='x'*/ rest" -- the comment body "ddps='svc',dde='x'" spans [2, 20).
    String s = "/*ddps='svc',dde='x'*/ rest";
    SubSequence comment = SubSequence.of(s, 2, 20);
    assertTrue(comment.contains("ddps="));
    assertTrue(comment.contains("dde="));
    assertFalse(comment.contains("ddh="));
    // View-relative: a needle present in the backing string but outside this view is not found.
    // "dde='x'"
    SubSequence dde = SubSequence.of(s, 13, 20);
    // ddps= is before this view's range
    assertFalse(dde.contains("ddps="));
  }

  @Test
  public void subSequenceOfView() {
    // Instance subSequence(start, end): start/end are in THIS view's coordinates (CharSequence
    // contract), regardless of where the view sits in the backing string.
    // "cdefgh"
    SubSequence view = SubSequence.of("abcdefghij", 2, 8);
    // chars [1, 4) of "cdefgh" -> "def"
    SubSequence mid = view.subSequence(1, 4);
    assertEquals("def", mid.toString());
    // absolute begin = 2 + 1
    assertEquals(3, mid.beginIndex());
    // absolute end = 2 + 4 (NOT 2 + 1 + 4)
    assertEquals(6, mid.endIndex());
    // full window and empty are exact
    assertEquals("cdefgh", view.subSequence(0, view.length()).toString());
    assertEquals("", view.subSequence(2, 2).toString());
    // nested: subSequence of a non-zero-start view stays correct (the case the old bug broke worst)
    // chars [1, 3) of "def" -> "ef"
    assertEquals("ef", mid.subSequence(1, 3).toString());
  }

  @Test
  public void equalsString() {
    // "call" sits at [6, 10) inside the backing string, flanked by other text.
    SubSequence call = SubSequence.of("xxxxx call yyyyy", 6, 10);
    assertTrue(call.equals("call"));
    // case-sensitive
    assertFalse(call.equals("CALL"));
    // shorter
    assertFalse(call.equals("cal"));
    // longer (would overshoot endIndex)
    assertFalse(call.equals("calls"));
    assertFalse(call.equals((Object) null));
    // equals(Object) routes a String through the region-compare fast path...
    assertTrue(call.equals((Object) "call"));
    // ...and any other CharSequence (incl. another SubSequence) through contentEquals.
    assertTrue(call.equals((Object) new StringBuilder("call")));
    assertTrue(call.equals((Object) SubSequence.of("xxxxx call yyyyy", 6, 10)));
    assertFalse(call.equals((Object) Integer.valueOf(4)));
  }

  @Test
  public void contentEqualsCharSequence() {
    // "call"
    SubSequence call = SubSequence.of("xxxxx call yyyyy", 6, 10);
    // String is a CharSequence
    assertTrue(call.contentEquals("call"));
    assertTrue(call.contentEquals(new StringBuilder("call")));
    assertTrue(call.contentEquals(SubSequence.of("a call b", 2, 6)));
    // case-sensitive
    assertFalse(call.contentEquals("CALL"));
    // length mismatch
    assertFalse(call.contentEquals("cal"));
    assertFalse(call.contentEquals(null));
  }

  @Test
  public void equalsIgnoreCaseString() {
    SubSequence call = SubSequence.of("xxxxx CaLl yyyyy", 6, 10);
    assertTrue(call.equalsIgnoreCase("call"));
    assertTrue(call.equalsIgnoreCase("CALL"));
    assertFalse(call.equalsIgnoreCase("cal"));
    assertFalse(call.equalsIgnoreCase("calls"));
    // matches String.equalsIgnoreCase(null)
    assertFalse(call.equalsIgnoreCase(null));
  }

  @Test
  public void startsWithString() {
    // "{call}"
    SubSequence view = SubSequence.of("xx{call}xx", 2, 8);
    assertTrue(view.startsWith("{"));
    assertTrue(view.startsWith("{call"));
    assertTrue(view.startsWith("{call}"));
    assertFalse(view.startsWith("call"));
    // overshoots endIndex even though backing has 'x'
    assertFalse(view.startsWith("{call}x"));
    // empty prefix
    assertTrue(view.startsWith(""));
  }

  @Test
  public void endsWithString() {
    // "{call}"
    SubSequence view = SubSequence.of("xx{call}xx", 2, 8);
    assertTrue(view.endsWith("}"));
    assertTrue(view.endsWith("call}"));
    assertTrue(view.endsWith("{call}"));
    assertFalse(view.endsWith("call"));
    // undershoots beginIndex even though backing has 'x'
    assertFalse(view.endsWith("x{call}"));
    // empty suffix
    assertTrue(view.endsWith(""));
  }

  @Test
  public void indexOfString() {
    // "bc-bc"
    SubSequence view = SubSequence.of("aa-bc-bc-aa", 3, 8);
    // window-relative offset of the first occurrence
    assertEquals(0, view.indexOf("bc"));
    // non-zero relative offset
    assertEquals(2, view.indexOf("-bc"));
    assertEquals(2, view.indexOf("-"));
    // present in backing string but outside the window
    assertEquals(-1, view.indexOf("aa"));
    // overshoots endIndex
    assertEquals(-1, view.indexOf("bc-bc-"));
  }

  @Test
  public void lastIndexOfString() {
    // "bc-bc"
    SubSequence view = SubSequence.of("aa-bc-bc-aa", 3, 8);
    // last "bc" -> relative 3
    assertEquals(3, view.lastIndexOf("bc"));
    assertEquals(2, view.lastIndexOf("-"));
    // outside the window on both ends
    assertEquals(-1, view.lastIndexOf("aa"));
    // overshoots endIndex
    assertEquals(-1, view.lastIndexOf("bc-bc-"));
  }

  @Test
  public void startsWithChar() {
    // "{call}"
    SubSequence view = SubSequence.of("xx{call}xx", 2, 8);
    assertTrue(view.startsWith('{'));
    // 'c' is at offset 1, not the start
    assertFalse(view.startsWith('c'));
    // backing char before beginIndex, outside the window
    assertFalse(view.startsWith('x'));
    // empty window
    assertFalse(SubSequence.EMPTY.startsWith('x'));
  }

  @Test
  public void endsWithChar() {
    // "{call}"
    SubSequence view = SubSequence.of("xx{call}xx", 2, 8);
    assertTrue(view.endsWith('}'));
    // 'l' is one before the end
    assertFalse(view.endsWith('l'));
    // backing char at endIndex, outside the window
    assertFalse(view.endsWith('x'));
    // empty window
    assertFalse(SubSequence.EMPTY.endsWith('x'));
  }

  @Test
  public void indexOfChar() {
    // "bc-bc"
    SubSequence view = SubSequence.of("aa-bc-bc-aa", 3, 8);
    // window-relative offset of the first occurrence
    assertEquals(0, view.indexOf('b'));
    assertEquals(1, view.indexOf('c'));
    assertEquals(2, view.indexOf('-'));
    // present in backing string but outside the window
    assertEquals(-1, view.indexOf('a'));
  }

  @Test
  public void lastIndexOfChar() {
    // "bc-bc"
    SubSequence view = SubSequence.of("aa-bc-bc-aa", 3, 8);
    // last 'b' -> relative 3
    assertEquals(3, view.lastIndexOf('b'));
    assertEquals(4, view.lastIndexOf('c'));
    assertEquals(2, view.lastIndexOf('-'));
    // outside the window on both ends
    assertEquals(-1, view.lastIndexOf('a'));
  }
}
