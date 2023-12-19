package datadog.trace.api.flare

import datadog.trace.test.util.DDSpecification

import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

import static java.nio.charset.StandardCharsets.UTF_8

class TracerFlareTest extends DDSpecification {

  def "test tracer flare"() {
    when:
    TracerFlare.addReporter(new TracerFlare.Reporter() {
        @Override
        void addReport(ZipOutputStream zip) {
          TracerFlare.addText(zip, "test.txt", "example text")
          throw new IllegalStateException("txt (expected)")
        }
      })
    TracerFlare.addReporter(new TracerFlare.Reporter() {
        @Override
        void addReport(ZipOutputStream zip) {
          TracerFlare.addBinary(zip, "test.bin", [0, 1, 2, 3, 4, 5, 6, 7] as byte[])
          throw new IllegalStateException("bin (expected)")
        }
      })

    def zip = buildAndExtractZip()
    then:
    def entries = [:]
    def entry
    while (entry = zip.nextEntry) {
      def bytes = new ByteArrayOutputStream()
      bytes << zip
      entries.put(entry.name, entry.name.endsWith(".bin")
        ? bytes.toByteArray() : new String(bytes.toByteArray(), UTF_8))
    }
    entries.size() == 3
    entries["test.txt"] == "example text"
    entries["test.bin"] == [0, 1, 2, 3, 4, 5, 6, 7] as byte[]
    entries["flare_errors.txt"] =~
      /^(java.lang.IllegalStateException: (bin|txt) \(expected\)\n){2}$/
  }

  def buildAndExtractZip() {
    def out = new ByteArrayOutputStream()
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      TracerFlare.buildFlare(zip)
    }
    return new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))
  }
}
