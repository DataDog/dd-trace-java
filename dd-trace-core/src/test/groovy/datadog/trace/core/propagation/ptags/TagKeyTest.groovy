package datadog.trace.core.propagation.ptags

import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.core.propagation.ptags.TagElement.Encoding.DATADOG
import static datadog.trace.core.propagation.ptags.TagElement.Encoding.W3C

class TagKeyTest extends DDCoreSpecification {


  def 'tag keys should use cached values when appropriate #seq1 #seq2'() {
    when:
    def tk1 = TagKey.from(enc1, seq1)
    def tk2 = TagKey.from(enc2, seq2)

    then:
    tk1.forType(enc1) == seq1
    tk2.forType(enc2) == seq2
    if (same) {
      assert tk1.is(tk2)
      assert tk1.forType(enc2) == seq2
    } else {
      assert !tk1.is(tk2)
    }

    where:
    seq1          | enc1    | seq2    | enc2     | same
    '_dd.p.foo1'  | DATADOG | '_dd.p.foo1'  | DATADOG  | true
    't.foo2'      | W3C     | 't.foo2'      | W3C      | true
    '_dd.p.foo3'  | DATADOG | 't.foo3'      | W3C      | true
    't.foo4'      | W3C     | '_dd.p.foo4'  | DATADOG  | true
    't.foo5~'     | W3C     | '_dd.p.foo5=' | DATADOG  | false
    '_dd.p.foo6~' | DATADOG | 't.foo6~'     | W3C      | true
    '_dd.p.foo7~' | DATADOG | 't.foo7='     | W3C      | false
    '_dd.p.foo81' | DATADOG | 't.foo82'     | W3C      | false
  }

  def 'tag values should use cached values from sub sequences #seq1 #seq2'() {
    when:
    def tv1 = TagKey.from(enc1, seq1, s1, e1)
    def sub1 = seq1.substring(s1, e1)
    def tv2 = TagKey.from(enc2, seq2, s2, e2)
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
    seq1           | enc1    | s1 | e1 | seq2           | enc2     | s2 | e2 | same
    'bb_dd.p.bar1' | DATADOG | 2  | 12 | 'r_dd.p.bar1r' | DATADOG  | 1  | 11 | true
    't.bar2ss'     | W3C     | 0  | 6  | 't_dd.p.bar2t' | DATADOG  | 1  | 11 | true
    't.bar3~s'     | W3C     | 0  | 7  | 't_dd.p.bar3=' | DATADOG  | 1  | 12 | false
    't.bar4~s'     | W3C     | 0  | 7  | 'tt.bar4~'     | W3C      | 1  | 8  | true
    's_dd.p.bar5=' | DATADOG | 1  | 12 | 't.bar5~t'     | W3C      | 0  | 7  | false
  }
}
