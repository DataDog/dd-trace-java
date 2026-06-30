package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class SubSequenceTest {
  static final String LOREM_IPSUM =
      "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.";

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
    SubSequence dde = SubSequence.of(s, 13, 20); // "dde='x'"
    assertFalse(dde.contains("ddps=")); // ddps= is before this view's range
  }

  @Test
  public void equalsString() {
    // "call" sits at [6, 10) inside the backing string, flanked by other text.
    SubSequence call = SubSequence.of("xxxxx call yyyyy", 6, 10);
    assertTrue(call.equals("call"));
    assertFalse(call.equals("CALL")); // case-sensitive
    assertFalse(call.equals("cal")); // shorter
    assertFalse(call.equals("calls")); // longer (would overshoot endIndex)
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
    SubSequence call = SubSequence.of("xxxxx call yyyyy", 6, 10); // "call"
    assertTrue(call.contentEquals("call")); // String is a CharSequence
    assertTrue(call.contentEquals(new StringBuilder("call")));
    assertTrue(call.contentEquals(SubSequence.of("a call b", 2, 6)));
    assertFalse(call.contentEquals("CALL")); // case-sensitive
    assertFalse(call.contentEquals("cal")); // length mismatch
    assertFalse(call.contentEquals(null));
  }

  @Test
  public void equalsIgnoreCaseString() {
    SubSequence call = SubSequence.of("xxxxx CaLl yyyyy", 6, 10);
    assertTrue(call.equalsIgnoreCase("call"));
    assertTrue(call.equalsIgnoreCase("CALL"));
    assertFalse(call.equalsIgnoreCase("cal"));
    assertFalse(call.equalsIgnoreCase("calls"));
    assertFalse(call.equalsIgnoreCase(null)); // matches String.equalsIgnoreCase(null)
  }

  @Test
  public void startsWithString() {
    SubSequence view = SubSequence.of("xx{call}xx", 2, 8); // "{call}"
    assertTrue(view.startsWith("{"));
    assertTrue(view.startsWith("{call"));
    assertTrue(view.startsWith("{call}"));
    assertFalse(view.startsWith("call"));
    assertFalse(view.startsWith("{call}x")); // overshoots endIndex even though backing has 'x'
    assertTrue(view.startsWith("")); // empty prefix
  }

  @Test
  public void endsWithString() {
    SubSequence view = SubSequence.of("xx{call}xx", 2, 8); // "{call}"
    assertTrue(view.endsWith("}"));
    assertTrue(view.endsWith("call}"));
    assertTrue(view.endsWith("{call}"));
    assertFalse(view.endsWith("call"));
    assertFalse(view.endsWith("x{call}")); // undershoots beginIndex even though backing has 'x'
    assertTrue(view.endsWith("")); // empty suffix
  }

  @Test
  public void indexOfString() {
    SubSequence view = SubSequence.of("aa-bc-bc-aa", 3, 8); // "bc-bc"
    assertEquals(0, view.indexOf("bc")); // window-relative offset of the first occurrence
    assertEquals(2, view.indexOf("-bc")); // non-zero relative offset
    assertEquals(2, view.indexOf("-"));
    assertEquals(-1, view.indexOf("aa")); // present in backing string but outside the window
    assertEquals(-1, view.indexOf("bc-bc-")); // overshoots endIndex
  }

  @Test
  public void lastIndexOfString() {
    SubSequence view = SubSequence.of("aa-bc-bc-aa", 3, 8); // "bc-bc"
    assertEquals(3, view.lastIndexOf("bc")); // last "bc" -> relative 3
    assertEquals(2, view.lastIndexOf("-"));
    assertEquals(-1, view.lastIndexOf("aa")); // outside the window on both ends
    assertEquals(-1, view.lastIndexOf("bc-bc-")); // overshoots endIndex
  }

  @Test
  public void startsWithChar() {
    SubSequence view = SubSequence.of("xx{call}xx", 2, 8); // "{call}"
    assertTrue(view.startsWith('{'));
    assertFalse(view.startsWith('c')); // 'c' is at offset 1, not the start
    assertFalse(view.startsWith('x')); // backing char before beginIndex, outside the window
    assertFalse(SubSequence.EMPTY.startsWith('x')); // empty window
  }

  @Test
  public void endsWithChar() {
    SubSequence view = SubSequence.of("xx{call}xx", 2, 8); // "{call}"
    assertTrue(view.endsWith('}'));
    assertFalse(view.endsWith('l')); // 'l' is one before the end
    assertFalse(view.endsWith('x')); // backing char at endIndex, outside the window
    assertFalse(SubSequence.EMPTY.endsWith('x')); // empty window
  }

  @Test
  public void indexOfChar() {
    SubSequence view = SubSequence.of("aa-bc-bc-aa", 3, 8); // "bc-bc"
    assertEquals(0, view.indexOf('b')); // window-relative offset of the first occurrence
    assertEquals(1, view.indexOf('c'));
    assertEquals(2, view.indexOf('-'));
    assertEquals(-1, view.indexOf('a')); // present in backing string but outside the window
  }

  @Test
  public void lastIndexOfChar() {
    SubSequence view = SubSequence.of("aa-bc-bc-aa", 3, 8); // "bc-bc"
    assertEquals(3, view.lastIndexOf('b')); // last 'b' -> relative 3
    assertEquals(4, view.lastIndexOf('c'));
    assertEquals(2, view.lastIndexOf('-'));
    assertEquals(-1, view.lastIndexOf('a')); // outside the window on both ends
  }
}
