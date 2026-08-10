package datadog.trace.core.propagation.ptags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.core.propagation.ptags.TagElement.Encoding;
import org.tabletest.junit.TableTest;

class TagValueTest {
  @TableTest({
    "scenario      | seq1    | enc1    | seq2    | enc2    | same ",
    "DD/DD same    | 'foo1'  | DATADOG | 'foo1'  | DATADOG | true ",
    "W3C/W3C same  | 'foo2'  | W3C     | 'foo2'  | W3C     | true ",
    "DD/W3C same   | 'foo3'  | DATADOG | 'foo3'  | W3C     | true ",
    "W3C/DD same   | 'foo4'  | W3C     | 'foo4'  | DATADOG | true ",
    "W3C ~ == DD = | 'foo5~' | W3C     | 'foo5=' | DATADOG | true ",
    "DD = == W3C ~ | 'foo6=' | DATADOG | 'foo6~' | W3C     | true ",
    "DD ~ != W3C ~ | 'foo7~' | DATADOG | 'foo7~' | W3C     | false",
    "diff suffixes | 'foo81' | DATADOG | 'foo82' | W3C     | false",
    "DD ; != W3C _ | 'foo9;' | DATADOG | 'foo9_' | W3C     | false"
  })
  void tagValuesShouldUseCachedValuesWhenAppropriate(
      String seq1, Encoding enc1, String seq2, Encoding enc2, boolean same) {
    TagValue tv1 = TagValue.from(enc1, seq1);
    TagValue tv2 = TagValue.from(enc2, seq2);

    assertNotNull(tv1);
    assertNotNull(tv2);
    assertEquals(seq1, tv1.forType(enc1));
    assertEquals(seq2, tv2.forType(enc2));
    if (same) {
      assertSame(tv1, tv2);
      assertEquals(seq2, tv1.forType(enc2));
    } else {
      assertNotSame(tv1, tv2);
    }
  }

  @TableTest({
    "scenario          | seq1     | enc1    | s1 | e1 | seq2     | enc2    | s2 | e2 | same ",
    "DD/DD same sub    | 'bbbar1' | DATADOG | 2  | 6  | 'rbar1r' | DATADOG | 1  | 5  | true ",
    "W3C/DD same sub   | 'bar2ss' | W3C     | 0  | 4  | 'tbar2t' | DATADOG | 1  | 5  | true ",
    "W3C ~ == DD = sub | 'bar3~s' | W3C     | 0  | 5  | 'tbar3=' | DATADOG | 1  | 6  | true ",
    "W3C/W3C ~ sub     | 'bar4~s' | W3C     | 0  | 5  | 'tbar4~' | W3C     | 1  | 6  | true ",
    "DD = == W3C ~ sub | 'sbar5=' | DATADOG | 1  | 6  | 'bar5~t' | W3C     | 0  | 5  | true ",
    "DD ? != W3C ! sub | 'sbar6?' | DATADOG | 1  | 6  | 'bar5!t' | W3C     | 0  | 5  | false",
    "DD , != W3C _ sub | 'sbar6,' | DATADOG | 1  | 6  | 'bar6_t' | W3C     | 0  | 5  | false"
  })
  void tagValuesShouldUseCachedValuesFromSubSequences(
      String seq1,
      Encoding enc1,
      int s1,
      int e1,
      String seq2,
      Encoding enc2,
      int s2,
      int e2,
      boolean same) {
    TagValue tv1 = TagValue.from(enc1, seq1, s1, e1);
    String sub1 = seq1.substring(s1, e1);
    TagValue tv2 = TagValue.from(enc2, seq2, s2, e2);
    String sub2 = seq2.substring(s2, e2);

    assertNotNull(tv1);
    assertNotNull(tv2);
    assertEquals(sub1, tv1.forType(enc1));
    assertEquals(sub2, tv2.forType(enc2));
    if (same) {
      assertSame(tv1, tv2);
      assertEquals(sub2, tv1.forType(enc2));
    } else {
      assertNotSame(tv1, tv2);
    }
  }
}
