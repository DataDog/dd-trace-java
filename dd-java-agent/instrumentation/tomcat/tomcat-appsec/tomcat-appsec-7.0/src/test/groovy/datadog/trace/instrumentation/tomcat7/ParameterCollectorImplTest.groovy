package datadog.trace.instrumentation.tomcat7

import datadog.trace.api.Config
import spock.lang.Specification

class ParameterCollectorImplTest extends Specification {

  void 'getContents returns empty list when no parts added'() {
    expect:
    new ParameterCollector.ParameterCollectorImpl(true).getContents().isEmpty()
  }

  void 'addPart skips content when filename is null'() {
    given:
    def collector = new ParameterCollector.ParameterCollectorImpl(true)

    when:
    collector.addPart(new TestPart(null, 'some body'))

    then:
    collector.getContents().isEmpty()
    collector.getFilenames().isEmpty()
  }

  void 'addPart reads content but skips filename when filename is empty string'() {
    given:
    def collector = new ParameterCollector.ParameterCollectorImpl(true)

    when:
    collector.addPart(new TestPart('', 'some body'))

    then:
    collector.getContents() == ['some body']
    collector.getFilenames().isEmpty()
  }

  void 'addPart reads content for file part with filename'() {
    given:
    def collector = new ParameterCollector.ParameterCollectorImpl(true)

    when:
    collector.addPart(new TestPart('file.txt', 'hello world'))

    then:
    collector.getContents() == ['hello world']
    collector.getFilenames() == ['file.txt']
  }

  void 'addPart reads content for multiple files'() {
    given:
    def collector = new ParameterCollector.ParameterCollectorImpl(true)

    when:
    collector.addPart(new TestPart('a.txt', 'content-a'))
    collector.addPart(new TestPart('b.txt', 'content-b'))

    then:
    collector.getContents() == ['content-a', 'content-b']
    collector.getFilenames() == ['a.txt', 'b.txt']
  }

  void 'addPart truncates content at MAX_CONTENT_BYTES'() {
    given:
    def maxBytes = Config.get().getAppSecMaxFileContentBytes()
    def collector = new ParameterCollector.ParameterCollectorImpl(true)
    def longContent = 'X' * (maxBytes + 500)

    when:
    collector.addPart(new TestPart('big.bin', longContent))

    then:
    collector.getContents()[0].length() == maxBytes
  }

  void 'addPart reads exactly MAX_CONTENT_BYTES when content is exactly that size'() {
    given:
    def maxBytes = Config.get().getAppSecMaxFileContentBytes()
    def collector = new ParameterCollector.ParameterCollectorImpl(true)
    def content = 'Y' * maxBytes

    when:
    collector.addPart(new TestPart('exact.bin', content))

    then:
    collector.getContents()[0].length() == maxBytes
  }

  void 'addPart stops collecting content after MAX_FILES_TO_INSPECT files but still collects filenames'() {
    given:
    def maxFiles = Config.get().getAppSecMaxFileContentCount()
    def collector = new ParameterCollector.ParameterCollectorImpl(true)

    when:
    (1..maxFiles + 1).each { i -> collector.addPart(new TestPart("file${i}.txt", "content${i}")) }

    then:
    collector.getContents().size() == maxFiles
    collector.getFilenames().size() == maxFiles + 1
  }

  void 'addPart adds empty string when getInputStream throws'() {
    given:
    def collector = new ParameterCollector.ParameterCollectorImpl(true)

    when:
    collector.addPart(new FailingPart('bad.txt'))

    then:
    collector.getContents() == ['']
    collector.getFilenames() == ['bad.txt']
  }

  void 'addPart preserves ISO-8859-1 bytes'() {
    given:
    def collector = new ParameterCollector.ParameterCollectorImpl(true)
    def bytes = (0..255).collect { (byte) it } as byte[]
    def expected = new String(bytes, 'ISO-8859-1')

    when:
    collector.addPart(new RawBytesPart('binary.bin', bytes))

    then:
    collector.getContents()[0] == expected
  }

  void 'addPart skips content when inspectContent is false but still collects filename'() {
    given:
    def collector = new ParameterCollector.ParameterCollectorImpl(false)

    when:
    collector.addPart(new TestPart('file.txt', 'hello world'))

    then:
    collector.getContents().isEmpty()
    collector.getFilenames() == ['file.txt']
  }

  void 'addPart falls back to getFilename() when getSubmittedFileName() is absent (Tomcat 7)'() {
    given:
    def collector = new ParameterCollector.ParameterCollectorImpl(true)

    when:
    collector.addPart(new Tomcat7Part('tomcat7.txt', 'tomcat7 content'))

    then:
    collector.getContents() == ['tomcat7 content']
    collector.getFilenames() == ['tomcat7.txt']
  }

  void 'ParameterCollectorNoop getContents returns empty list'() {
    expect:
    ParameterCollector.ParameterCollectorNoop.INSTANCE.getContents().isEmpty()
  }

  // --- helper classes ---

  static class TestPart {
    private final String filename
    private final String content

    TestPart(String filename, String content) {
      this.filename = filename
      this.content = content
    }

    String getSubmittedFileName() {
      filename
    }

    InputStream getInputStream() {
      new ByteArrayInputStream((content ?: '').getBytes('ISO-8859-1'))
    }
  }

  static class FailingPart {
    private final String filename

    FailingPart(String filename) {
      this.filename = filename
    }

    String getSubmittedFileName() {
      filename
    }

    InputStream getInputStream() {
      throw new IOException('simulated error')
    }
  }

  static class RawBytesPart {
    private final String filename
    private final byte[] bytes

    RawBytesPart(String filename, byte[] bytes) {
      this.filename = filename
      this.bytes = bytes
    }

    String getSubmittedFileName() {
      filename
    }

    InputStream getInputStream() {
      new ByteArrayInputStream(bytes)
    }
  }

  /** Simulates a Tomcat 7 ApplicationPart that only exposes getFilename(), not getSubmittedFileName(). */
  static class Tomcat7Part {
    private final String filename
    private final String content

    Tomcat7Part(String filename, String content) {
      this.filename = filename
      this.content = content
    }

    String getFilename() {
      filename
    }

    InputStream getInputStream() {
      new ByteArrayInputStream((content ?: '').getBytes('ISO-8859-1'))
    }
  }
}
