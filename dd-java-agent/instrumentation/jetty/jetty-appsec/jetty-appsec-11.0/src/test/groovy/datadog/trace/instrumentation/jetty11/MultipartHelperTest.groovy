package datadog.trace.instrumentation.jetty11

import jakarta.servlet.http.Part
import spock.lang.Specification

class MultipartHelperTest extends Specification {

  def "returns empty list for null collection"() {
    expect:
    MultipartHelper.extractFilenames(null) == []
  }

  def "returns empty list for empty collection"() {
    expect:
    MultipartHelper.extractFilenames([]) == []
  }

  def "returns empty list when all parts have null filename"() {
    given:
    def parts = [part(null), part(null)]

    expect:
    MultipartHelper.extractFilenames(parts) == []
  }

  def "returns empty list when all parts have empty filename"() {
    given:
    def parts = [part(''), part('')]

    expect:
    MultipartHelper.extractFilenames(parts) == []
  }

  def "extracts filename from single part"() {
    given:
    def parts = [part('photo.jpg')]

    expect:
    MultipartHelper.extractFilenames(parts) == ['photo.jpg']
  }

  def "extracts filenames from multiple parts"() {
    given:
    def parts = [part('a.jpg'), part('b.png'), part('c.pdf')]

    expect:
    MultipartHelper.extractFilenames(parts) == ['a.jpg', 'b.png', 'c.pdf']
  }

  def "skips parts with null or empty filename and keeps valid ones"() {
    given:
    def parts = [part(null), part('valid.txt'), part(''), part('other.zip')]

    expect:
    MultipartHelper.extractFilenames(parts) == ['valid.txt', 'other.zip']
  }

  def "preserves filenames with spaces and special characters"() {
    given:
    def parts = [part('my file.tar.gz'), part('résumé.pdf')]

    expect:
    MultipartHelper.extractFilenames(parts) == ['my file.tar.gz', 'résumé.pdf']
  }

  private Part part(String submittedFileName) {
    Part p = Stub(Part)
    p.getSubmittedFileName() >> submittedFileName
    return p
  }
}
