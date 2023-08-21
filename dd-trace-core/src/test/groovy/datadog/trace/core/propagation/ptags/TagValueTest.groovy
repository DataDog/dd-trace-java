package datadog.trace.core.propagation.ptags

import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.core.propagation.ptags.TagElement.Encoding.DATADOG
import static datadog.trace.core.propagation.ptags.TagElement.Encoding.W3C

class TagValueTest extends DDCoreSpecification {


  def 'tag values should use cached values when appropriate #seq1 #seq2'() {
    when:
    def tv1 = TagValue.from(enc1, seq1)
    def tv2 = TagValue.from(enc2, seq2)

    then:
    tv1.forType(enc1) == seq1
    tv2.forType(enc2) == seq2
    if (same) {
      assert tv1.is(tv2)
      assert tv1.forType(enc2) == seq2
    } else {
      assert !tv1.is(tv2)
    }

    where:
    seq1    | enc1    | seq2    | enc2     | same
    'foo1'  | DATADOG | 'foo1'  | DATADOG  | true
    'foo2'  | W3C     | 'foo2'  | W3C      | true
    'foo3'  | DATADOG | 'foo3'  | W3C      | true
    'foo4'  | W3C     | 'foo4'  | DATADOG  | true
    'foo5~' | W3C     | 'foo5=' | DATADOG  | true
    'foo6=' | DATADOG | 'foo6~' | W3C      | true
    'foo7~' | DATADOG | 'foo7~' | W3C      | false
    'foo81' | DATADOG | 'foo82' | W3C      | false
    'foo9;' | DATADOG | 'foo9_' | W3C      | false
  }

  def 'tag values should use cached values from sub sequences #seq1 #seq2'() {
    when:
    def tv1 = TagValue.from(enc1, seq1, s1, e1)
    def sub1 = seq1.substring(s1, e1)
    def tv2 = TagValue.from(enc2, seq2, s2, e2)
    def sub2 = seq2.substring(s2, e2)

    then:
    tv1.forType(enc1) == sub1
    tv2.forType(enc2) == sub2
    if (same) {
      assert tv1.is(tv2)
      assert tv1.forType(enc2) == sub2
    } else {
      assert !tv1.is(tv2)
    }

    where:
    seq1     | enc1    | s1 | e1 | seq2     | enc2     | s2 | e2 | same
    'bbbar1' | DATADOG | 2  | 6  | 'rbar1r' | DATADOG  | 1  | 5  | true
    'bar2ss' | W3C     | 0  | 4  | 'tbar2t' | DATADOG  | 1  | 5  | true
    'bar3~s' | W3C     | 0  | 5  | 'tbar3=' | DATADOG  | 1  | 6  | true
    'bar4~s' | W3C     | 0  | 5  | 'tbar4~' | W3C      | 1  | 6  | true
    'sbar5=' | DATADOG | 1  | 6  | 'bar5~t' | W3C      | 0  | 5  | true
    'sbar6?' | DATADOG | 1  | 6  | 'bar5!t' | W3C      | 0  | 5  | false
    'sbar6,' | DATADOG | 1  | 6  | 'bar6_t' | W3C      | 0  | 5  | false
  }
}
