package datadog.trace.core.propagation.ptags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.core.propagation.ptags.TagElement.Encoding;
import org.tabletest.junit.TableTest;

class TagKeyTest {
  @TableTest({
    "scenario      | seq1          | enc1    | seq2          | enc2    | same ",
    "DD/DD same    | '_dd.p.foo1'  | DATADOG | '_dd.p.foo1'  | DATADOG | true ",
    "W3C/W3C same  | 't.foo2'      | W3C     | 't.foo2'      | W3C     | true ",
    "DD/W3C same   | '_dd.p.foo3'  | DATADOG | 't.foo3'      | W3C     | true ",
    "W3C/DD same   | 't.foo4'      | W3C     | '_dd.p.foo4'  | DATADOG | true ",
    "W3C ~ != DD = | 't.foo5~'     | W3C     | '_dd.p.foo5=' | DATADOG | false",
    "DD ~ == W3C ~ | '_dd.p.foo6~' | DATADOG | 't.foo6~'     | W3C     | true ",
    "DD ~ != W3C = | '_dd.p.foo7~' | DATADOG | 't.foo7='     | W3C     | false",
    "diff suffixes | '_dd.p.foo81' | DATADOG | 't.foo82'     | W3C     | false"
  })
  void tagKeysShouldUseCachedValuesWhenAppropriate(
      String seq1, Encoding enc1, String seq2, Encoding enc2, boolean same) {
    TagKey tk1 = TagKey.from(enc1, seq1);
    TagKey tk2 = TagKey.from(enc2, seq2);

    assertNotNull(tk1);
    assertNotNull(tk2);
    assertEquals(seq1, tk1.forType(enc1));
    assertEquals(seq2, tk2.forType(enc2));
    if (same) {
      assertSame(tk1, tk2);
      assertEquals(seq2, tk1.forType(enc2));
    } else {
      assertNotSame(tk1, tk2);
    }
  }

  @TableTest({
    "scenario      | seq1           | enc1    | s1 | e1 | seq2           | enc2    | s2 | e2 | same ",
    "DD/DD sub     | 'bb_dd.p.bar1' | DATADOG | 2  | 12 | 'r_dd.p.bar1r' | DATADOG | 1  | 11 | true ",
    "W3C/DD sub    | 't.bar2ss'     | W3C     | 0  | 6  | 't_dd.p.bar2t' | DATADOG | 1  | 11 | true ",
    "W3C ~ != DD = | 't.bar3~s'     | W3C     | 0  | 7  | 't_dd.p.bar3=' | DATADOG | 1  | 12 | false",
    "W3C/W3C ~ sub | 't.bar4~s'     | W3C     | 0  | 7  | 'tt.bar4~'     | W3C     | 1  | 8  | true ",
    "DD = != W3C ~ | 's_dd.p.bar5=' | DATADOG | 1  | 12 | 't.bar5~t'     | W3C     | 0  | 7  | false"
  })
  void tagKeysShouldUseCachedValuesFromSubSequences(
      String seq1,
      Encoding enc1,
      int s1,
      int e1,
      String seq2,
      Encoding enc2,
      int s2,
      int e2,
      boolean same) {
    TagKey tk1 = TagKey.from(enc1, seq1, s1, e1);
    String sub1 = seq1.substring(s1, e1);
    TagKey tk2 = TagKey.from(enc2, seq2, s2, e2);
    String sub2 = seq2.substring(s2, e2);

    assertNotNull(tk1);
    assertNotNull(tk2);
    assertEquals(sub1, tk1.forType(enc1));
    assertEquals(sub2, tk2.forType(enc2));
    if (same) {
      assertSame(tk1, tk2);
      assertEquals(sub2, tk1.forType(enc2));
    } else {
      assertNotSame(tk1, tk2);
    }
  }
}
