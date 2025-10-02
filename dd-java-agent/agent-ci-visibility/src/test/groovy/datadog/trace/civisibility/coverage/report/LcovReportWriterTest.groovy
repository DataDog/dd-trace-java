package datadog.trace.civisibility.coverage.report

import spock.lang.Specification

class LcovReportWriterTest extends Specification {

  def "empty map produces empty output"() {
    expect:
    LcovReportWriter.toString(Collections.emptyMap()) == ""
  }

  def "single file with executable and covered lines"() {
    given:
    def lc = new LinesCoverage()
    lc.executableLines.set(1); lc.executableLines.set(2); lc.executableLines.set(3)
    lc.coveredLines.set(1); lc.coveredLines.set(3)
    def m = Collections.singletonMap("src/Foo.java", lc)

    expect:
    LcovReportWriter.toString(m) == """SF:src/Foo.java
DA:1,1
DA:2,0
DA:3,1
LH:2
LF:3
end_of_record
"""
  }

  def "ignores bit 0 in both sets and handles very large lines"() {
    given:
    def lc = new LinesCoverage()
    lc.executableLines.set(0); lc.executableLines.set(2); lc.executableLines.set(10000)
    lc.coveredLines.set(0); lc.coveredLines.set(2); lc.coveredLines.set(10000)
    def m = Collections.singletonMap("a.java", lc)

    when:
    def out = LcovReportWriter.toString(m)

    then:
    out.contains("SF:a.java\n")
    out.contains("DA:2,1\n")
    out.contains("DA:10000,1\n")
    !out.contains("DA:0,")
    out.contains("LH:2\n")
    out.contains("LF:2\n")
  }

  def "multiple files are sorted and lines within a file are sorted"() {
    given:
    def a = new LinesCoverage()
    a.executableLines.set(2); a.executableLines.set(5)
    a.coveredLines.set(5)

    def z = new LinesCoverage()
    z.executableLines.set(1)
    z.coveredLines.set(1)

    def m = new LinkedHashMap<String, LinesCoverage>()
    m.put("z/FileZ.java", z)
    m.put("a/FileA.java", a)

    when:
    def out = LcovReportWriter.toString(m)

    then: "files sorted: a/... then z/..."
    def idxA = out.indexOf("SF:a/FileA.java\n")
    def idxZ = out.indexOf("SF:z/FileZ.java\n")
    idxA >= 0 && idxZ > idxA

    and: "lines sorted within a/FileA.java (2 then 5) with correct hit counts"
    def blockA = out.substring(idxA, idxZ)
    blockA.indexOf("DA:2,0\n") >= 0 &&
      blockA.indexOf("DA:5,1\n") > blockA.indexOf("DA:2,0\n")
  }

  def "null LinesCoverage is treated as empty"() {
    given:
    def m = new LinkedHashMap<String, LinesCoverage>()
    m.put("empty.java", null)

    expect:
    LcovReportWriter.toString(m) == """SF:empty.java
LH:0
LF:0
end_of_record
"""
  }

  def "skips empty or null path entries"() {
    given:
    def lc = new LinesCoverage()
    lc.executableLines.set(1); lc.coveredLines.set(1)
    def m = new LinkedHashMap<String, LinesCoverage>()
    m.put("", lc)
    m.put(null, lc)

    expect:
    LcovReportWriter.toString(m) == ""
  }

  def "covered lines outside executable set are ignored in DA and LH"() {
    given:
    def lc = new LinesCoverage()
    lc.executableLines.set(1); lc.executableLines.set(2)
    lc.coveredLines.set(3) // not executable -> ignored
    def m = Collections.singletonMap("X.java", lc)

    expect:
    LcovReportWriter.toString(m) == """SF:X.java
DA:1,0
DA:2,0
LH:0
LF:2
end_of_record
"""
  }

  def "no executable lines even if covered bits exist -> no DA, LF=LH=0"() {
    given:
    def lc = new LinesCoverage()
    lc.coveredLines.set(1); lc.coveredLines.set(2)
    def m = Collections.singletonMap("Y.java", lc)

    expect:
    LcovReportWriter.toString(m) == """SF:Y.java
LH:0
LF:0
end_of_record
"""
  }
}
