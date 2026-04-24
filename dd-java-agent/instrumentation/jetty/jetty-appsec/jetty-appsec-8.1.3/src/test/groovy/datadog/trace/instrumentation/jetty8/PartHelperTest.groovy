package datadog.trace.instrumentation.jetty8

import javax.servlet.http.Part
import spock.lang.Specification

/** Minimal stand-in for {@code MultiPartInputStream}: exposes a {@code getParts()} method. */
class FakeMpi {
  private final Collection parts

  FakeMpi(Collection parts) {
    this.parts = parts
  }

  Collection getParts() {
    parts
  }
}

class PartHelperTest extends Specification {

  // ── extractFilenames ────────────────────────────────────────────────────────

  def "extractFilenames returns empty list for null collection"() {
    expect:
    PartHelper.extractFilenames(null) == []
  }

  def "extractFilenames returns empty list for empty collection"() {
    expect:
    PartHelper.extractFilenames([]) == []
  }

  def "extractFilenames returns empty list when no parts have a filename"() {
    given:
    def parts = [field('a', 'x'), field('b', 'y')]

    expect:
    PartHelper.extractFilenames(parts) == []
  }

  def "extractFilenames extracts filename from a single file part"() {
    given:
    def parts = [filePart('photo.jpg')]

    expect:
    PartHelper.extractFilenames(parts) == ['photo.jpg']
  }

  def "extractFilenames extracts filenames from multiple file parts"() {
    given:
    def parts = [filePart('a.jpg'), filePart('b.png'), filePart('c.pdf')]

    expect:
    PartHelper.extractFilenames(parts) == ['a.jpg', 'b.png', 'c.pdf']
  }

  def "extractFilenames skips form-field parts and keeps file parts"() {
    given:
    def parts = [field('x', 'v'), filePart('upload.zip'), field('y', 'w')]

    expect:
    PartHelper.extractFilenames(parts) == ['upload.zip']
  }

  def "extractFilenames preserves filenames with spaces and special characters"() {
    given:
    def parts = [filePart('my file.tar.gz'), filePart('résumé.pdf')]

    expect:
    PartHelper.extractFilenames(parts) == ['my file.tar.gz', 'résumé.pdf']
  }

  // ── filenameFromPart ────────────────────────────────────────────────────────

  def "filenameFromPart returns null when Content-Disposition header is absent"() {
    given:
    Part p = Stub(Part) { getHeader('Content-Disposition') >> null }

    expect:
    PartHelper.filenameFromPart(p) == null
  }

  def "filenameFromPart returns null when there is no filename parameter"() {
    given:
    Part p = Stub(Part) { getHeader('Content-Disposition') >> 'form-data; name="field"' }

    expect:
    PartHelper.filenameFromPart(p) == null
  }

  def "filenameFromPart extracts unquoted filename"() {
    given:
    Part p = Stub(Part) { getHeader('Content-Disposition') >> 'form-data; name="file"; filename=photo.jpg' }

    expect:
    PartHelper.filenameFromPart(p) == 'photo.jpg'
  }

  def "filenameFromPart strips quotes from filename"() {
    given:
    Part p = Stub(Part) { getHeader('Content-Disposition') >> 'form-data; name="file"; filename="photo.jpg"' }

    expect:
    PartHelper.filenameFromPart(p) == 'photo.jpg'
  }

  def "filenameFromPart returns empty string for empty quoted filename"() {
    given:
    Part p = Stub(Part) { getHeader('Content-Disposition') >> 'form-data; name="file"; filename=""' }

    expect:
    PartHelper.filenameFromPart(p) == ''
  }

  def "filenameFromPart returns empty string for empty unquoted filename"() {
    given:
    Part p = Stub(Part) { getHeader('Content-Disposition') >> 'form-data; name="file"; filename=' }

    expect:
    PartHelper.filenameFromPart(p) == ''
  }

  def "extractFormFields skips part with empty filename"() {
    given:
    def parts = [emptyFilenamePart('field')]

    expect:
    PartHelper.extractFormFields(parts) == [:]
  }

  def "extractFilenames skips empty filename"() {
    given:
    def parts = [emptyFilenamePart('field')]

    expect:
    PartHelper.extractFilenames(parts) == []
  }

  def "filenameFromPart preserves semicolons inside a quoted filename"() {
    given:
    Part p = Stub(Part) { getHeader('Content-Disposition') >> 'form-data; name="file"; filename="shell;evil.php"' }

    expect:
    PartHelper.filenameFromPart(p) == 'shell;evil.php'
  }

  def "filenameFromPart handles escaped quote inside filename"() {
    given:
    Part p = Stub(Part) { getHeader('Content-Disposition') >> 'form-data; name="file"; filename="file\\"name.txt"' }

    expect:
    PartHelper.filenameFromPart(p) == 'file"name.txt'
  }

  def "filenameFromPart handles filename before other parameters"() {
    given:
    Part p = Stub(Part) { getHeader('Content-Disposition') >> 'form-data; filename="first.txt"; name="file"' }

    expect:
    PartHelper.filenameFromPart(p) == 'first.txt'
  }

  // ── charsetFromContentType ──────────────────────────────────────────────────

  def "charsetFromContentType returns UTF-8 for null"() {
    expect:
    PartHelper.charsetFromContentType(null) == java.nio.charset.StandardCharsets.UTF_8
  }

  def "charsetFromContentType returns UTF-8 when no charset parameter"() {
    expect:
    PartHelper.charsetFromContentType('text/plain') == java.nio.charset.StandardCharsets.UTF_8
  }

  def "charsetFromContentType parses unquoted charset"() {
    expect:
    PartHelper.charsetFromContentType('text/plain; charset=ISO-8859-1') == java.nio.charset.Charset.forName('ISO-8859-1')
  }

  def "charsetFromContentType parses quoted charset"() {
    expect:
    PartHelper.charsetFromContentType('text/plain; charset="ISO-8859-1"') == java.nio.charset.Charset.forName('ISO-8859-1')
  }

  def "charsetFromContentType is case-insensitive"() {
    expect:
    PartHelper.charsetFromContentType('text/plain; CHARSET=UTF-16') == java.nio.charset.StandardCharsets.UTF_16
  }

  def "charsetFromContentType returns UTF-8 for unknown charset"() {
    expect:
    PartHelper.charsetFromContentType('text/plain; charset=not-a-real-charset') == java.nio.charset.StandardCharsets.UTF_8
  }

  // ── extractFormFields ───────────────────────────────────────────────────────

  def "extractFormFields returns empty map for null collection"() {
    expect:
    PartHelper.extractFormFields(null) == [:]
  }

  def "extractFormFields returns empty map for empty collection"() {
    expect:
    PartHelper.extractFormFields([]) == [:]
  }

  def "extractFormFields skips file-upload parts"() {
    given:
    def parts = [filePart('evil.php')]

    expect:
    PartHelper.extractFormFields(parts) == [:]
  }

  def "extractFormFields extracts single form field"() {
    given:
    def parts = [field('username', 'alice')]

    expect:
    PartHelper.extractFormFields(parts) == [username: ['alice']]
  }

  def "extractFormFields groups multiple values under the same name"() {
    given:
    def parts = [field('tag', 'foo'), field('tag', 'bar')]

    expect:
    PartHelper.extractFormFields(parts) == [tag: ['foo', 'bar']]
  }

  def "extractFormFields mixes fields and skips files"() {
    given:
    def parts = [field('a', 'x'), filePart('upload.bin'), field('b', 'y')]

    expect:
    PartHelper.extractFormFields(parts) == [a: ['x'], b: ['y']]
  }

  def "extractFormFields decodes field using Content-Type charset instead of hard-coded UTF-8"() {
    given:
    // "café" encoded as ISO-8859-1: 'é' = 0xE9. Decoded as UTF-8 it would be mojibake.
    byte[] iso88591Bytes = 'café'.getBytes('ISO-8859-1')
    def parts = [fieldWithContentType('drink', iso88591Bytes, 'text/plain; charset=ISO-8859-1')]

    expect:
    PartHelper.extractFormFields(parts) == [drink: ['café']]
  }

  // ── getAllParts ─────────────────────────────────────────────────────────────

  def "getAllParts returns empty list when multiPartInputStream is null and singlePart is null"() {
    expect:
    PartHelper.getAllParts(null, null) == []
  }

  def "getAllParts falls back to singleton when multiPartInputStream is null"() {
    given:
    def part = filePart('evil.php')

    expect:
    PartHelper.getAllParts(null, part) == [part]
  }

  def "getAllParts returns all parts from MultiPartInputStream.getParts()"() {
    given:
    def file = filePart('evil.php')
    def text = field('name', 'val')
    def mpi = new FakeMpi([file, text])

    expect:
    PartHelper.getAllParts(mpi, null) == [file, text]
  }

  def "getAllParts prefers full collection over singleton even when singlePart is provided"() {
    given:
    def file = filePart('evil.php')
    def other = field('name', 'val')
    def mpi = new FakeMpi([file, other])

    expect:
    PartHelper.getAllParts(mpi, file) == [file, other]
  }

  def "getAllParts falls back to singleton when getParts() throws"() {
    given:
    def part = filePart('fallback.jpg')
    def mpi = new Object() {
        Collection getParts() {
          throw new IOException("simulated failure")
        }
      }

    expect:
    PartHelper.getAllParts(mpi, part) == [part]
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  /** Creates a stub Part that looks like a plain form field (no filename). */
  private Part field(String name, String value) {
    Part p = Stub(Part)
    p.getHeader('Content-Disposition') >> "form-data; name=\"${name}\""
    p.getName() >> name
    p.getInputStream() >> new ByteArrayInputStream(value.getBytes('UTF-8'))
    return p
  }

  /** Creates a stub Part with a specific Content-Type (for charset testing). */
  private Part fieldWithContentType(String name, byte[] rawValue, String contentType) {
    Part p = Stub(Part)
    p.getHeader('Content-Disposition') >> "form-data; name=\"${name}\""
    p.getName() >> name
    p.getContentType() >> contentType
    p.getInputStream() >> new ByteArrayInputStream(rawValue)
    return p
  }

  /** Creates a stub Part that looks like a file upload with the given filename. */
  private Part filePart(String filename) {
    Part p = Stub(Part)
    p.getHeader('Content-Disposition') >> "form-data; name=\"file\"; filename=\"${filename}\""
    return p
  }

  /** Creates a stub Part that has filename="" — a file input submitted with no file chosen. */
  private Part emptyFilenamePart(String name) {
    Part p = Stub(Part)
    p.getHeader('Content-Disposition') >> "form-data; name=\"${name}\"; filename=\"\""
    return p
  }
}
