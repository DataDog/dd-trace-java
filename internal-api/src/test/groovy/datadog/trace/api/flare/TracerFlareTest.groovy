package datadog.trace.api.flare

import datadog.trace.test.util.DDSpecification

import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

import static java.nio.charset.StandardCharsets.UTF_8

class TracerFlareTest extends DDSpecification {

  // give each mock its own type - we use that to disambiguate reporters
  interface Reporter1 extends TracerFlare.Reporter {}
  interface Reporter2 extends TracerFlare.Reporter {}

  def "test tracer flare"() {
    setup:
    TracerFlare.addReporter {} // exercises default methods
    def textReporter = Mock(Reporter1)
    TracerFlare.addReporter(textReporter)
    def binaryReporter = Mock(Reporter2)
    TracerFlare.addReporter(binaryReporter)

    when:
    def entries = buildAndExtractZip()

    then:
    1 * textReporter.prepareForFlare()
    1 * binaryReporter.prepareForFlare()

    then:
    1 * textReporter.addReportToFlare(_) >> { ZipOutputStream zos ->
      TracerFlare.addText(zos, "test.txt", "example text")
      throw new IllegalStateException("txt (expected)") // should not stop flare
    }
    1 * binaryReporter.addReportToFlare(_) >> { ZipOutputStream zos ->
      TracerFlare.addBinary(zos, "test.bin", [0, 1, 2, 3, 4, 5, 6, 7] as byte[])
      throw new IllegalStateException("bin (expected)") // should not stop flare
    }

    then:
    1 * textReporter.cleanupAfterFlare()
    1 * binaryReporter.cleanupAfterFlare()
    0 * _

    and:
    entries.size() == 3
    entries["test.txt"] == "example text"
    entries["test.bin"] == [0, 1, 2, 3, 4, 5, 6, 7] as byte[]
    entries["flare_errors.txt"] =~
      /^(java.lang.IllegalStateException: (bin|txt) \(expected\)\n){2}$/
  }

  def "test getReporter finds reporter by class name"() {
    setup:
    def reporter1 = Mock(Reporter1)
    def reporter2 = Mock(Reporter2)
    TracerFlare.addReporter(reporter1)
    TracerFlare.addReporter(reporter2)

    when:
    def found1 = TracerFlare.getReporter(reporter1.getClass().getName())
    def found2 = TracerFlare.getReporter(reporter2.getClass().getName())

    then:
    found1 == reporter1
    found2 == reporter2
  }

  def "test getReporter returns null for non-existent reporter"() {
    setup:
    def reporter = Mock(Reporter1)
    TracerFlare.addReporter(reporter)

    when:
    def found = TracerFlare.getReporter("com.example.NonExistentReporter")

    then:
    found == null
  }

  def buildAndExtractZip() {
    TracerFlare.prepareForFlare()
    def out = new ByteArrayOutputStream()
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      TracerFlare.addReportsToFlare(zip)
    } finally {
      TracerFlare.cleanupAfterFlare()
    }

    def entries = [:]

    def zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))
    def entry
    while (entry = zip.nextEntry) {
      def bytes = new ByteArrayOutputStream()
      bytes << zip
      entries.put(entry.name, entry.name.endsWith(".bin")
      ? bytes.toByteArray() : new String(bytes.toByteArray(), UTF_8))
    }

    return entries
  }
}
