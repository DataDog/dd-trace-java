package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StringCanonicalizerTest {

  @Test
  void sharesEqualTextsFromABufferAndFromStrings() {
    final StringCanonicalizer strings = new StringCanonicalizer();
    final char[] buffer = "xxflag-keyyy".toCharArray();

    final String first = strings.canonical(buffer, 2, 8);
    assertEquals("flag-key", first);
    assertSame(first, strings.canonical(buffer, 2, 8));
    assertSame(first, strings.canonical(new String("flag-key".toCharArray())));
    assertEquals(1, strings.size());

    final String other = new String("other".toCharArray());
    assertSame(other, strings.canonical(other));
    assertSame(other, strings.canonical("other".toCharArray(), 0, 5));
    assertEquals(2, strings.size());
  }

  @Test
  void copiesOnAMissSoTheBufferCanBeReused() {
    final StringCanonicalizer strings = new StringCanonicalizer();
    final char[] buffer = "abc".toCharArray();
    final String abc = strings.canonical(buffer, 0, 3);
    buffer[0] = 'x';
    assertEquals("abc", abc);
    assertEquals("xbc", strings.canonical(buffer, 0, 3));
  }

  @Test
  void sharesTheEmptyText() {
    final StringCanonicalizer strings = new StringCanonicalizer();
    final String empty = strings.canonical(new char[0], 0, 0);
    assertEquals("", empty);
    assertSame(empty, strings.canonical(new String()));
  }

  @Test
  void rejectsARangeOutsideTheArrayWhateverTheTableHolds() {
    final StringCanonicalizer strings = new StringCanonicalizer();
    final char[] empty = new char[0];
    // before and after the empty text is shared: a hit must not skip the range check
    assertThrows(IndexOutOfBoundsException.class, () -> strings.canonical(empty, 1, 0));
    assertEquals("", strings.canonical(empty, 0, 0));
    assertThrows(IndexOutOfBoundsException.class, () -> strings.canonical(empty, 1, 0));

    final char[] abc = "abc".toCharArray();
    assertEquals("bc", strings.canonical(abc, 1, 2));
    assertThrows(IndexOutOfBoundsException.class, () -> strings.canonical(abc, 2, 2));
    assertThrows(IndexOutOfBoundsException.class, () -> strings.canonical(abc, -1, 1));
    assertThrows(IndexOutOfBoundsException.class, () -> strings.canonical(abc, 0, -1));
    // a range far past the end mustn't overflow into looking valid
    assertThrows(
        IndexOutOfBoundsException.class, () -> strings.canonical(abc, Integer.MAX_VALUE, 2));
  }

  @Test
  void keepsSharingAcrossGrowth() {
    final StringCanonicalizer strings = new StringCanonicalizer();
    final List<String> firsts = new ArrayList<>();
    for (int i = 0; i < 10_000; i++) {
      firsts.add(strings.canonical(String.format("scale-bulk-%05d", i).toCharArray(), 0, 16));
    }
    assertEquals(10_000, strings.size());
    for (int i = 0; i < 10_000; i++) {
      assertSame(firsts.get(i), strings.canonical(String.format("scale-bulk-%05d", i)));
    }
  }

  @Test
  void doesNotShareTextsLongerThanTheLimit() {
    final StringCanonicalizer strings = new StringCanonicalizer(4);
    final char[] buffer = "abcde".toCharArray();
    final String first = strings.canonical(buffer, 0, 5);
    final String second = strings.canonical(buffer, 0, 5);
    assertEquals("abcde", first);
    assertEquals(first, second);
    assertNotSame(first, second);
    final String text = new String("abcde".toCharArray());
    assertSame(text, strings.canonical(text));
    assertEquals(0, strings.size());

    assertSame(strings.canonical(buffer, 0, 4), strings.canonical(buffer, 0, 4));
    assertThrows(IllegalArgumentException.class, () -> new StringCanonicalizer(-1));
  }

  @Test
  void stopsAddingTextsOnceFullButKeepsSharingThoseItHas() {
    final StringCanonicalizer strings = new StringCanonicalizer(16, 2);
    final String a = strings.canonical("a".toCharArray(), 0, 1);
    final String b = strings.canonical("b".toCharArray(), 0, 1);
    assertEquals(2, strings.size());

    final String c = strings.canonical("c".toCharArray(), 0, 1);
    assertEquals("c", c);
    assertNotSame(c, strings.canonical("c".toCharArray(), 0, 1));
    final String d = new String("d".toCharArray());
    assertSame(d, strings.canonical(d));
    assertNotSame(d, strings.canonical(new String("d".toCharArray())));
    assertEquals(2, strings.size());

    assertSame(a, strings.canonical("a".toCharArray(), 0, 1));
    assertSame(b, strings.canonical(new String("b".toCharArray())));
    assertThrows(IllegalArgumentException.class, () -> new StringCanonicalizer(16, -1));
  }

  @Test
  void boundsTheProbesForStringsWithEqualHashCodes() {
    // "Aa" and "BB" have the same hash code, so every string of k blocks of them collides: 2^k
    // distinct strings with one hash code.
    final List<String> colliding = new ArrayList<>();
    collide("", 10, colliding);
    final int hash = colliding.get(0).hashCode();
    for (final String s : colliding) {
      assertEquals(hash, s.hashCode());
    }

    final StringCanonicalizer strings = new StringCanonicalizer();
    for (final String s : colliding) {
      assertEquals(s, strings.canonical(s.toCharArray(), 0, s.length()));
    }
    // Each lookup compares at most MAX_EQUAL_HASH_PROBES of them, so only the first ones got a
    // slot; the rest are copied, not shared.
    assertEquals(StringCanonicalizer.MAX_EQUAL_HASH_PROBES, strings.size());
    final String shared = colliding.get(0);
    assertSame(
        strings.canonical(shared.toCharArray(), 0, shared.length()),
        strings.canonical(shared.toCharArray(), 0, shared.length()));
    final String late = colliding.get(colliding.size() - 1);
    assertTrue(late.equals(strings.canonical(late)));
  }

  private static void collide(final String prefix, final int blocks, final List<String> out) {
    if (blocks == 0) {
      out.add(prefix);
      return;
    }
    collide(prefix + "Aa", blocks - 1, out);
    collide(prefix + "BB", blocks - 1, out);
  }
}
