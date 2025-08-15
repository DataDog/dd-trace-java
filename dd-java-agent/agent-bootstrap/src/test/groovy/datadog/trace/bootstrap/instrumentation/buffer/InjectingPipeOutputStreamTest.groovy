package datadog.trace.bootstrap.instrumentation.buffer

import datadog.trace.test.util.DDSpecification

class InjectingPipeOutputStreamTest extends DDSpecification {
  static final byte[] MARKER_BYTES = "</head>".getBytes("UTF-8")
  static final byte[] CONTEXT_BYTES = "<script></script>".getBytes("UTF-8")

  static class GlitchedOutputStream extends FilterOutputStream {
    int glitchesPos
    int count
    final OutputStream out

    GlitchedOutputStream(OutputStream out, int glitchesPos) {
      super(out)
      this.out = out
      this.glitchesPos = glitchesPos
    }

    @Override
    void write(byte[] b, int off, int len) throws IOException {
      count += len
      if (count >= glitchesPos) {
        glitchesPos = Integer.MAX_VALUE
        throw new IOException("Glitched after $count bytes")
      }
      out.write(b, off, len)
    }

    @Override
    void write(int b) throws IOException {
      if (++count == glitchesPos) {
        throw new IOException("Glitched after $glitchesPos bytes")
      }
      out.write(b)
    }
  }

  static class Counter {
    int value = 0

    def incr(long count) {
      this.value += count
    }
  }

  def 'should filter a buffer and inject if found #found'() {
    setup:
    def downstream = new ByteArrayOutputStream()
    def piped = new OutputStreamWriter(new InjectingPipeOutputStream(downstream, marker.getBytes("UTF-8"), contentToInject.getBytes("UTF-8")),
    "UTF-8")
    when:
    try (def closeme = piped) {
      piped.write(body)
    }
    then:
    assert downstream.toByteArray() == expected.getBytes("UTF-8")
    where:
    body                                      | marker            | contentToInject         | found | expected
    "<html><head><foo/></head><body/></html>" | "</head>"         | "<script>true</script>" | true  | "<html><head><foo/><script>true</script></head><body/></html>"
    "<html><body/></html>"                    | "</head>"         | "<something/>"          | false | "<html><body/></html>"
    "<foo/>"                                  | "<longerThanFoo>" | "<nothing>"             | false | "<foo/>"
  }

  def 'should be resilient to exceptions when writing #body'() {
    setup:
    def baos = new ByteArrayOutputStream()
    def downstream = new GlitchedOutputStream(baos, glichesAt)
    def piped = new InjectingPipeOutputStream(downstream, marker.getBytes("UTF-8"), contentToInject.getBytes("UTF-8"))
    when:
    try {
      for (String line : body) {
        final bytes = line.getBytes("UTF-8")
        try {
          piped.write(bytes)
        } catch (IOException ioe) {
          ioe.printStackTrace()
          piped.write(bytes)
        }
      }
    } finally {
      // it can throw when draining at close
      try {
        piped.close()
      } catch (IOException ignored) {
      }
    }
    then:
    assert baos.toByteArray() == expected.getBytes("UTF-8")
    where:
    body                                                            | marker            | contentToInject         | glichesAt | expected
    // write fails after the content has been injected
    ["<html>", "<head>", "<foo/>", "</head>", "<body/>", "</html>"] | "</head>"         | "<script>true</script>" | 60        | "<html><head><foo/><script>true</script></head><body/></html>"
    // write fails before the content has been injected
    ["<html>", "<head>", "<foo/>", "</head>", "<body/>", "</html>"] | "</head>"         | "<script>true</script>" | 20        | "<html><head><foo/></head><body/></html>"
    // write fails after having filled the buffer. The last line is written twice
    ["<html>", "<body/>", "</html>"]                                | "</head>"         | "<something/>"          | 10        | "<html><body/></h</html>"
    // expected broken since the real write happens at close (drain) being the content smaller than the buffer. And retry on close is not a common practice. Hence, we suppose loosing content
    ["<foo/>"]                                                      | "<longerThanFoo>" | "<nothing>"             | 3         | "<f"
  }

  def 'should count bytes correctly when writing byte arrays'() {
    setup:
    def testBytes = "test content".getBytes("UTF-8")
    def downstream = new ByteArrayOutputStream()
    def counter = new Counter()
    def piped = new InjectingPipeOutputStream(downstream, MARKER_BYTES, CONTEXT_BYTES, null, { long bytes -> counter.incr(bytes) })

    when:
    piped.write(testBytes)
    piped.close()

    then:
    counter.value == 12
    downstream.toByteArray() == testBytes
  }

  def 'should count bytes correctly when writing bytes individually'() {
    setup:
    def testBytes = "test".getBytes("UTF-8")
    def downstream = new ByteArrayOutputStream()
    def counter = new Counter()
    def piped = new InjectingPipeOutputStream(downstream, MARKER_BYTES, CONTEXT_BYTES, null, { long bytes -> counter.incr(bytes) })

    when:
    for (int i = 0; i < testBytes.length; i++) {
      piped.write((int) testBytes[i])
    }
    piped.close()

    then:
    counter.value == 4
    downstream.toByteArray() == testBytes
  }

  def 'should count bytes correctly with multiple writes'() {
    setup:
    def part1 = "test".getBytes("UTF-8")
    def part2 = " ".getBytes("UTF-8")
    def part3 = "content".getBytes("UTF-8")
    def testBytes = "test content".getBytes("UTF-8")
    def downstream = new ByteArrayOutputStream()
    def counter = new Counter()
    def piped = new InjectingPipeOutputStream(downstream, MARKER_BYTES, CONTEXT_BYTES, null, { long bytes -> counter.incr(bytes) })

    when:
    piped.write(part1)
    piped.write(part2)
    piped.write(part3)
    piped.close()

    then:
    counter.value == 12
    downstream.toByteArray() == testBytes
  }

  def 'should be resilient to exceptions when onBytesWritten callback is null'() {
    setup:
    def testBytes = "test content".getBytes("UTF-8")
    def downstream = new ByteArrayOutputStream()
    def piped = new InjectingPipeOutputStream(downstream, MARKER_BYTES, CONTEXT_BYTES)

    when:
    piped.write(testBytes)
    piped.close()

    then:
    noExceptionThrown()
    downstream.toByteArray() == testBytes
  }
}
