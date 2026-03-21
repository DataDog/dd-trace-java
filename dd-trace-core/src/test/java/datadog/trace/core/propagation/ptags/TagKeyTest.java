package datadog.trace.core.propagation.ptags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.core.test.DDCoreSpecification;
import org.junit.jupiter.params.ParameterizedTest;
import org.tabletest.junit.TableTest;

class TagKeyTest extends DDCoreSpecification {

  @TableTest({
    "scenario                    | seq1          | enc1    | seq2          | enc2    | same ",
    "same datadog key            | '_dd.p.foo1'  | DATADOG | '_dd.p.foo1'  | DATADOG | true ",
    "same w3c key                | 't.foo2'      | W3C     | 't.foo2'      | W3C     | true ",
    "datadog and w3c equivalent  | '_dd.p.foo3'  | DATADOG | 't.foo3'      | W3C     | true ",
    "w3c and datadog equivalent  | 't.foo4'      | W3C     | '_dd.p.foo4'  | DATADOG | true ",
    "w3c tilde vs datadog equals | 't.foo5~'     | W3C     | '_dd.p.foo5=' | DATADOG | false",
    "datadog tilde vs w3c tilde  | '_dd.p.foo6~' | DATADOG | 't.foo6~'     | W3C     | true ",
    "datadog tilde vs w3c equals | '_dd.p.foo7~' | DATADOG | 't.foo7='     | W3C     | false",
    "different suffixes          | '_dd.p.foo81' | DATADOG | 't.foo82'     | W3C     | false"
  })
  @ParameterizedTest(name = "[{index}] tag keys should use cached values when appropriate")
  void tagKeysShouldUseCachedValuesWhenAppropriate(
      String seq1, TagElement.Encoding enc1, String seq2, TagElement.Encoding enc2, boolean same) {
    TagKey tk1 = TagKey.from(enc1, seq1);
    TagKey tk2 = TagKey.from(enc2, seq2);

    assertEquals(seq1, tk1.forType(enc1).toString());
    assertEquals(seq2, tk2.forType(enc2).toString());
    if (same) {
      assertSame(tk1, tk2);
      assertEquals(seq2, tk1.forType(enc2).toString());
    } else {
      assertNotSame(tk1, tk2);
    }
  }

  @TableTest({
    "scenario                        | seq1           | enc1    | s1 | e1 | seq2           | enc2    | s2 | e2 | same ",
    "same datadog sub-key            | 'bb_dd.p.bar1' | DATADOG | 2  | 12 | 'r_dd.p.bar1r' | DATADOG | 1  | 11 | true ",
    "w3c and datadog sub-key equiv   | 't.bar2ss'     | W3C     | 0  | 6  | 't_dd.p.bar2t' | DATADOG | 1  | 11 | true ",
    "w3c tilde vs datadog equals sub | 't.bar3~s'     | W3C     | 0  | 7  | 't_dd.p.bar3=' | DATADOG | 1  | 12 | false",
    "w3c tilde vs w3c tilde sub      | 't.bar4~s'     | W3C     | 0  | 7  | 'tt.bar4~'     | W3C     | 1  | 8  | true ",
    "datadog equals vs w3c tilde sub | 's_dd.p.bar5=' | DATADOG | 1  | 12 | 't.bar5~t'     | W3C     | 0  | 7  | false"
  })
  @ParameterizedTest(name = "[{index}] tag values should use cached values from sub sequences")
  void tagValuesShouldUseCachedValuesFromSubSequences(
      String seq1,
      TagElement.Encoding enc1,
      int s1,
      int e1,
      String seq2,
      TagElement.Encoding enc2,
      int s2,
      int e2,
      boolean same) {
    TagKey tv1 = TagKey.from(enc1, seq1, s1, e1);
    String sub1 = seq1.substring(s1, e1);
    TagKey tv2 = TagKey.from(enc2, seq2, s2, e2);
    String sub2 = seq2.substring(s2, e2);

    assertEquals(sub1, tv1.forType(enc1).toString());
    assertEquals(sub2, tv2.forType(enc2).toString());
    if (same) {
      assertSame(tv1, tv2);
      assertEquals(sub2, tv1.forType(enc2).toString());
    } else {
      assertNotSame(tv1, tv2);
    }
  }
}
