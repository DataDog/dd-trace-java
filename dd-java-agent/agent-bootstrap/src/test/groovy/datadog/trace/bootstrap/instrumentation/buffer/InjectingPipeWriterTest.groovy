package datadog.trace.bootstrap.instrumentation.buffer

import datadog.trace.test.util.DDSpecification
import org.apache.commons.io.IOUtils

class InjectingPipeWriterTest extends DDSpecification {
  static class GlitchedWriter extends FilterWriter {
    int glitchesPos
    int count

    GlitchedWriter(Writer out, int glitchesPos) {
      super(out)
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
    def piped = new InjectingPipeWriter(downstream, marker.toCharArray(), contentToInject.toCharArray(), null)
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
      IOUtils.closeQuietly(piped) // it can throw when draining at close
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
}
