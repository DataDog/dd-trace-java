package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
