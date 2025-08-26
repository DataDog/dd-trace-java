package datadog.trace.civisibility.coverage.report

import spock.lang.Specification

class LcovReportWriterTest extends Specification {

  def "empty map produces empty output"() {
    given:
    Map<String, BitSet> m = Collections.emptyMap()

    expect:
    LcovReportWriter.toString(m) == ""
  }

  def "single file simple lines"() {
    given:
    def bs = new BitSet()
    bs.set(1)
    bs.set(3)
    Map<String, BitSet> m = Collections.singletonMap("src/Foo.java", bs)

    when:
    def out = LcovReportWriter.toString(m)

    then:
    out == """SF:src/Foo.java
DA:1,1
DA:3,1
LH:2
LF:3
end_of_record
"""
  }

  def "ignores bit 0 and handles large lines"() {
    given:
    def bs = new BitSet()
    bs.set(0)        // ignored
    bs.set(2)
    bs.set(10000)
    Map<String, BitSet> m = Collections.singletonMap("a.java", bs)

    when:
    def out = LcovReportWriter.toString(m)

    then:
    out.contains("SF:a.java\n")
    out.contains("DA:2,1\n")
    out.contains("DA:10000,1\n")
    !out.contains("DA:0,1\n")
    out.contains("LH:2\n")
    out.contains("LF:10000\n") // length()=10001 -> LF=10000
  }

  def "multiple files are sorted and lines within a file are sorted"() {
    given:
    def b1 = new BitSet(); b1.set(5); b1.set(2)
    def b2 = new BitSet(); b2.set(1)
    def m = new LinkedHashMap<String, BitSet>()
    m.put("z/FileZ.java", b2)
    m.put("a/FileA.java", b1)

    when:
    def out = LcovReportWriter.toString(m)

    then: "files sorted: a/... then z/..."
    def idxA = out.indexOf("SF:a/FileA.java\n")
    def idxZ = out.indexOf("SF:z/FileZ.java\n")
    idxA >= 0 && idxZ > idxA

    and: "lines sorted within a/FileA.java (2 then 5)"
    def blockA = out.substring(idxA, idxZ)
    blockA.indexOf("DA:2,1\n") >= 0 &&
      blockA.indexOf("DA:5,1\n") > blockA.indexOf("DA:2,1\n")
  }

  def "null BitSet is treated as empty"() {
    given:
    def m = new LinkedHashMap<String, BitSet>()
    m.put("empty.java", null)

    when:
    def out = LcovReportWriter.toString(m)

    then:
    out == """SF:empty.java
LH:0
LF:0
end_of_record
"""
  }

  def "skips empty or null path entries"() {
    given:
    def bs = new BitSet()
    bs.set(1)

    def m = new LinkedHashMap<String, BitSet>()
    m.put("", bs)
    m.put(null, bs)

    expect:
    LcovReportWriter.toString(m) == ""
  }
}
