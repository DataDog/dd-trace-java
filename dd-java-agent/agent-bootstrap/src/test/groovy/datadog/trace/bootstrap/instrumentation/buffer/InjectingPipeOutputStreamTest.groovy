package datadog.trace.bootstrap.instrumentation.buffer

import datadog.trace.test.util.DDSpecification

class InjectingPipeOutputStreamTest extends DDSpecification {
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

  def 'should filter a buffer and inject if found #found'() {
    setup:
    def downstream = new ByteArrayOutputStream()
    def piped = new OutputStreamWriter(new InjectingPipeOutputStream(downstream, marker.getBytes("UTF-8"), contentToInject.getBytes("UTF-8"), null),
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
    def piped = new InjectingPipeOutputStream(downstream, marker.getBytes("UTF-8"), contentToInject.getBytes("UTF-8"), null)
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
}
