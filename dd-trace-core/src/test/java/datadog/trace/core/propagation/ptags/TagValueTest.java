package datadog.trace.core.propagation.ptags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.core.test.DDCoreSpecification;
import org.junit.jupiter.params.ParameterizedTest;
import org.tabletest.junit.TableTest;

class TagValueTest extends DDCoreSpecification {

  @TableTest({
    "scenario                            | seq1    | enc1    | seq2    | enc2    | same ",
    "same datadog value                  | 'foo1'  | DATADOG | 'foo1'  | DATADOG | true ",
    "same w3c value                      | 'foo2'  | W3C     | 'foo2'  | W3C     | true ",
    "datadog and w3c equiv               | 'foo3'  | DATADOG | 'foo3'  | W3C     | true ",
    "w3c and datadog equiv               | 'foo4'  | W3C     | 'foo4'  | DATADOG | true ",
    "w3c tilde vs datadog equals         | 'foo5~' | W3C     | 'foo5=' | DATADOG | true ",
    "datadog equals vs w3c tilde         | 'foo6=' | DATADOG | 'foo6~' | W3C     | true ",
    "datadog tilde vs w3c tilde diff     | 'foo7~' | DATADOG | 'foo7~' | W3C     | false",
    "different suffixes                  | 'foo81' | DATADOG | 'foo82' | W3C     | false",
    "datadog semicolon vs w3c underscore | 'foo9;' | DATADOG | 'foo9_' | W3C     | false"
  })
  @ParameterizedTest(name = "[{index}] tag values should use cached values when appropriate")
  void tagValuesShouldUseCachedValuesWhenAppropriate(
      String seq1, TagElement.Encoding enc1, String seq2, TagElement.Encoding enc2, boolean same) {
    TagValue tv1 = TagValue.from(enc1, seq1);
    TagValue tv2 = TagValue.from(enc2, seq2);

    assertEquals(seq1, tv1.forType(enc1).toString());
    assertEquals(seq2, tv2.forType(enc2).toString());
    if (same) {
      assertSame(tv1, tv2);
      assertEquals(seq2, tv1.forType(enc2).toString());
    } else {
      assertNotSame(tv1, tv2);
    }
  }

  @TableTest({
    "scenario                            | seq1     | enc1    | s1 | e1 | seq2     | enc2    | s2 | e2 | same ",
    "same datadog sub-value              | 'bbbar1' | DATADOG | 2  | 6  | 'rbar1r' | DATADOG | 1  | 5  | true ",
    "w3c and datadog sub-value equiv     | 'bar2ss' | W3C     | 0  | 4  | 'tbar2t' | DATADOG | 1  | 5  | true ",
    "w3c tilde vs datadog equals sub     | 'bar3~s' | W3C     | 0  | 5  | 'tbar3=' | DATADOG | 1  | 6  | true ",
    "w3c tilde vs w3c tilde sub          | 'bar4~s' | W3C     | 0  | 5  | 'tbar4~' | W3C     | 1  | 6  | true ",
    "datadog equals vs w3c tilde sub     | 'sbar5=' | DATADOG | 1  | 6  | 'bar5~t' | W3C     | 0  | 5  | true ",
    "datadog question vs w3c exclaim sub | 'sbar6?' | DATADOG | 1  | 6  | 'bar5!t' | W3C     | 0  | 5  | false",
    "datadog comma vs w3c underscore sub | 'sbar6,' | DATADOG | 1  | 6  | 'bar6_t' | W3C     | 0  | 5  | false"
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
    TagValue tv1 = TagValue.from(enc1, seq1, s1, e1);
    String sub1 = seq1.substring(s1, e1);
    TagValue tv2 = TagValue.from(enc2, seq2, s2, e2);
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
