package com.datadog.openfeature.internal.ufc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class StringCanonicalizerTest {

  @Test
  void returnsOneInstanceForEqualText() {
    final StringCanonicalizer strings = new StringCanonicalizer();
    final char[] buffer = "xxcountryxx".toCharArray();

    final String first = strings.canonical(buffer, 2, 7);
    final String second = strings.canonical("country".toCharArray(), 0, 7);
    final String fromString = strings.canonical(new String("country"));

    assertEquals("country", first);
    assertSame(first, second);
    assertSame(first, fromString);
    assertEquals(1, strings.size());
  }

  @Test
  void copiesTheCharactersOnAMiss() {
    final StringCanonicalizer strings = new StringCanonicalizer();
    final char[] buffer = "abc".toCharArray();

    final String text = strings.canonical(buffer, 0, 3);
    buffer[0] = 'z';

    assertEquals("abc", text);
    assertSame(text, strings.canonical("abc"));
  }

  @Test
  void keepsTextsWithEqualHashesApart() {
    final StringCanonicalizer strings = new StringCanonicalizer();
    assertEquals("Aa".hashCode(), "BB".hashCode());

    final String aa = strings.canonical("Aa".toCharArray(), 0, 2);
    final String bb = strings.canonical("BB".toCharArray(), 0, 2);

    assertEquals("Aa", aa);
    assertEquals("BB", bb);
    assertNotEquals(aa, bb);
    assertSame(aa, strings.canonical("Aa"));
    assertSame(bb, strings.canonical("BB"));
  }

  @Test
  void handlesEmptyNonAsciiAndSurrogatePairs() {
    final StringCanonicalizer strings = new StringCanonicalizer();
    for (final String text : new String[] {"", "é", "日本語", "😀", "a\"b\\c"}) {
      final String shared = strings.canonical(text.toCharArray(), 0, text.length());
      assertEquals(text, shared);
      assertSame(shared, strings.canonical(new String(text)));
    }
  }

  @Test
  void keepsEveryTextAcrossGrowth() {
    final StringCanonicalizer strings = new StringCanonicalizer();
    final String[] first = new String[10_000];
    for (int i = 0; i < first.length; i++) {
      final char[] text = ("value-" + i).toCharArray();
      first[i] = strings.canonical(text, 0, text.length);
    }

    assertEquals(first.length, strings.size());
    for (int i = 0; i < first.length; i++) {
      assertSame(first[i], strings.canonical("value-" + i));
    }
  }
}
