package datadog.trace.bootstrap.instrumentation.buffer

import datadog.trace.test.util.DDSpecification

class InjectingPipeWriterTest extends DDSpecification {
  static final char[] MARKER_CHARS = "</head>".toCharArray()
  static final char[] CONTEXT_CHARS = "<script></script>".toCharArray()

  static class GlitchedWriter extends FilterWriter {
    int glitchesPos
    int count
    final Writer out

    GlitchedWriter(Writer out, int glitchesPos) {
      super(out)
      this.out = out
      this.glitchesPos = glitchesPos
    }

    @Override
    void write(char[] c, int off, int len) throws IOException {
      count += len
      if (count >= glitchesPos) {
        glitchesPos = Integer.MAX_VALUE
        throw new IOException("Glitched after $count bytes")
      }
      out.write(c, off, len)
    }

    @Override
    void write(int c) throws IOException {
      if (++count == glitchesPos) {
        throw new IOException("Glitched after $glitchesPos bytes")
      }
      out.write(c)
    }
  }

  static class Counter {
    int value = 0

    def incr(long count) {
      this.value += count
    }
  }

  def 'should filter a buffer and inject if found #found using write'() {
    setup:
    def downstream = new StringWriter()
    def piped = new PrintWriter(new InjectingPipeWriter(downstream, marker.toCharArray(), contentToInject.toCharArray(), null))
    when:
    try (def closeme = piped) {
      piped.write(body)
    }
    then:
    assert downstream.toString() == expected
    where:
    body                                      | marker            | contentToInject         | found | expected
    "<html><head><foo/></head><body/></html>" | "</head>"         | "<script>true</script>" | true  | "<html><head><foo/><script>true</script></head><body/></html>"
    "<html><body/></html>"                    | "</head>"         | "<something/>"          | false | "<html><body/></html>"
    "<foo/>"                                  | "<longerThanFoo>" | "<nothing>"             | false | "<foo/>"
  }

  def 'should filter a buffer and inject if found #found using append'() {
    setup:
    def downstream = new StringWriter()
    def piped = new PrintWriter(new InjectingPipeWriter(downstream, marker.toCharArray(), contentToInject.toCharArray(), null))
    when:
    try (def closeme = piped) {
      piped.append(body)
    }
    then:
    assert downstream.toString() == expected
    where:
    body                                      | marker            | contentToInject         | found | expected
    "<html><head><foo/></head><body/></html>" | "</head>"         | "<script>true</script>" | true  | "<html><head><foo/><script>true</script></head><body/></html>"
    "<html><body/></html>"                    | "</head>"         | "<something/>"          | false | "<html><body/></html>"
    "<foo/>"                                  | "<longerThanFoo>" | "<nothing>"             | false | "<foo/>"
  }

  def 'should be resilient to exceptions when writing #body'() {
    setup:
    def writer = new StringWriter()
    def downstream = new GlitchedWriter(writer, glichesAt)
    def piped = new InjectingPipeWriter(downstream, marker.toCharArray(), contentToInject.toCharArray(), null, null)
    when:
    try {
      for (String line : body) {
        final chars = line.toCharArray()
        try {
          piped.write(chars)
        } catch (IOException ioe) {
          ioe.printStackTrace()
          piped.write(chars)
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
    assert writer.toString() == expected
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

  def 'should count bytes correctly when writing characters'() {
    setup:
    def downstream = new StringWriter()
    def counter = new Counter()
    def piped = new InjectingPipeWriter(downstream, MARKER_CHARS, CONTEXT_CHARS, null, { long bytes -> counter.incr(bytes) })

    when:
    piped.write("test content".toCharArray())
    piped.close()

    then:
    counter.value == 12
    downstream.toString() == "test content"
  }

  def 'should count bytes correctly when writing characters individually'() {
    setup:
    def downstream = new StringWriter()
    def counter = new Counter()
    def piped = new InjectingPipeWriter(downstream, MARKER_CHARS, CONTEXT_CHARS, null, { long bytes -> counter.incr(bytes) })

    when:
    def content = "test"
    for (int i = 0; i < content.length(); i++) {
      piped.write((int) content.charAt(i))
    }
    piped.close()

    then:
    counter.value == 4
    downstream.toString() == "test"
  }

  def 'should count bytes correctly with multiple writes'() {
    setup:
    def downstream = new StringWriter()
    def counter = new Counter()
    def piped = new InjectingPipeWriter(downstream, MARKER_CHARS, CONTEXT_CHARS, null, { long bytes -> counter.incr(bytes) })

    when:
    piped.write("test".toCharArray())
    piped.write(" ".toCharArray())
    piped.write("content".toCharArray())
    piped.close()

    then:
    counter.value == 12
    downstream.toString() == "test content"
  }

  def 'should be resilient to exceptions when onBytesWritten callback is null'() {
    setup:
    def downstream = new StringWriter()
    def piped = new InjectingPipeWriter(downstream, MARKER_CHARS, CONTEXT_CHARS, null, null)

    when:
    piped.write("test content".toCharArray())
    piped.close()

    then:
    noExceptionThrown()
    downstream.toString() == "test content"
  }
}
