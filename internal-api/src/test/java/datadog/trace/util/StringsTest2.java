package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class StringsTest2 {
  @Test
  @DisplayName("replaceAll - single replace")
  public void replaceAllNoReplace() {
    assertEquals("foobar", Strings.replaceAll("foobar", "dne", "unchanged"));
  }

  @Test
  @DisplayName("replaceAll - single replace")
  public void replaceAllSingleReplace() {
    assertEquals("foobaz", Strings.replaceAll("foobar", "bar", "baz"));
  }

  @Test
  @DisplayName("replaceAll - single replace")
  public void replaceAllMultiReplace() {
    assertEquals("foo=baz&quux=baz", Strings.replaceAll("foo=bar&quux=bar", "bar", "baz"));
  }

  @Test
  @DisplayName("split - empty")
  public void splitEmpty() {
    Iterator<SubSequence> iter = Strings.split("", '&').iterator();
    assertFalse(iter.hasNext());
  }

  @Test
  @DisplayName("split - no separator")
  public void splitNoSeparator() {
    Iterator<SubSequence> iter = Strings.split("foo=bar", '&').iterator();
    assertContentEquals("foo=bar", iter.next());
    assertFalse(iter.hasNext());
  }

  @Test
  @DisplayName("split - with separator")
  public void splitWithSeparator() {
    Iterator<SubSequence> iter = Strings.split("foo=bar&hello=world", '&').iterator();
    assertContentEquals("foo=bar", iter.next());
    assertContentEquals("hello=world", iter.next());
    assertFalse(iter.hasNext());
  }

  @Test
  @DisplayName("split - leading separator")
  public void splitLeadingSeparator() {
    Iterator<SubSequence> iter = Strings.split("&foo=bar", '&').iterator();
    assertSubSeq("", 0, 0, iter.next()); // empty string before the separator
    assertSubSeq("foo=bar", 1, 8, iter.next());
    assertFalse(iter.hasNext());
  }

  @Test
  @DisplayName("split - trailing separator")
  public void splitTrailingSeparator() {
    Iterator<SubSequence> iter = Strings.split("foo=bar&", '&').iterator();
    assertSubSeq("foo=bar", 0, 7, iter.next());
    assertSubSeq("", 8, 8, iter.next()); // empty string after the separator
    assertFalse(iter.hasNext());
  }

  @Test
  @DisplayName("split - only separator")
  public void splitOnlySeparator() {
    Iterator<SubSequence> iter = Strings.split("&", '&').iterator();
    assertSubSeq("", 0, 0, iter.next()); // empty string before the separator
    assertSubSeq("", 1, 1, iter.next()); // empty string after the separator
    assertFalse(iter.hasNext());
  }

  @Test
  @DisplayName("split - sanity check")
  public void splitSanityCheck() {
    String testStr = "foo=bar&hello=world&baz=quux&firstName=Jon&lastName=Doe";

    String[] strSplit = testStr.split("\\&");
    Iterable<SubSequence> iterable = Strings.split(testStr, '&');

    Iterator<SubSequence> iter = iterable.iterator();
    for (String expected : strSplit) {
      assertTrue(iter.hasNext());
      assertContentEquals(expected, iter.next());
    }
    assertFalse(iter.hasNext());

    // repeat, just to check iterable functionality
    Iterator<SubSequence> iter2 = iterable.iterator();
    for (String expected : strSplit) {
      assertTrue(iter2.hasNext());
      assertContentEquals(expected, iter2.next());
    }
    assertFalse(iter2.hasNext());
  }

  static void assertContentEquals(String expectedStr, SubSequence actualSeq) {
    assertTrue(actualSeq.equals(expectedStr), "equals String");
    assertEquals(expectedStr, actualSeq.toString(), "toString");
  }

  static void assertSubSeq(
      String expected, int expectedBeginIndex, int expectedEndIndex, SubSequence actualSeq) {
    assertContentEquals(expected, actualSeq);
    assertEquals(expectedBeginIndex, actualSeq.beginIndex(), "beginIndex");
    assertEquals(expectedEndIndex, actualSeq.endIndex(), "endIndex");
  }
}
