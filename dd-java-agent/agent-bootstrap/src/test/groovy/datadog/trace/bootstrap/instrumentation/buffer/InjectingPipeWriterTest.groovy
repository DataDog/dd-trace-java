package datadog.trace.bootstrap.instrumentation.buffer

import datadog.trace.test.util.DDSpecification
import java.util.function.LongConsumer

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
    def piped = new PrintWriter(new InjectingPipeWriter(downstream, marker.toCharArray(), contentToInject.toCharArray()))
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
    def piped = new PrintWriter(new InjectingPipeWriter(downstream, marker.toCharArray(), contentToInject.toCharArray()))
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
    def piped = new InjectingPipeWriter(downstream, marker.toCharArray(), contentToInject.toCharArray())
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
    def piped = new InjectingPipeWriter(downstream, MARKER_CHARS, CONTEXT_CHARS, null, { long bytes -> counter.incr(bytes) }, null)
    def testBytes = "test content"

    when:
    piped.write(testBytes.toCharArray())
    piped.close()

    then:
    counter.value == testBytes.length()
    downstream.toString() == testBytes
  }

  def 'should count bytes correctly when writing characters individually'() {
    setup:
    def downstream = new StringWriter()
    def counter = new Counter()
    def piped = new InjectingPipeWriter(downstream, MARKER_CHARS, CONTEXT_CHARS, null, { long bytes -> counter.incr(bytes) }, null)
    def testBytes = "test"

    when:
    for (int i = 0; i < testBytes.length(); i++) {
      piped.write((int) testBytes.charAt(i))
    }
    piped.close()

    then:
    counter.value == testBytes.length()
    downstream.toString() == testBytes
  }

  def 'should count bytes correctly with multiple writes'() {
    setup:
    def downstream = new StringWriter()
    def counter = new Counter()
    def piped = new InjectingPipeWriter(downstream, MARKER_CHARS, CONTEXT_CHARS, null, { long bytes -> counter.incr(bytes) }, null)
    def testBytes = "test content"

    when:
    piped.write(testBytes[0..4].toCharArray())
    piped.write(testBytes[5..5].toCharArray())
    piped.write(testBytes[6..-1].toCharArray())
    piped.close()

    then:
    counter.value == testBytes.length()
    downstream.toString() == testBytes
  }

  def 'should be resilient to exceptions when onBytesWritten callback is null'() {
    setup:
    def downstream = new StringWriter()
    def piped = new InjectingPipeWriter(downstream, MARKER_CHARS, CONTEXT_CHARS)
    def testBytes = "test content"

    when:
    piped.write(testBytes.toCharArray())
    piped.close()

    then:
    noExceptionThrown()
    downstream.toString() == testBytes
  }

  def 'should call timing callback when injection happens'() {
    setup:
    def downstream = Mock(Writer) {
      write(_) >> { args ->
        Thread.sleep(1) // simulate slow write
      }
    }
    def timingCallback = Mock(LongConsumer)
    def piped = new InjectingPipeWriter(downstream, MARKER_CHARS, CONTEXT_CHARS, null, null, timingCallback)

    when:
    piped.write("<html><head></head><body></body></html>".toCharArray())
    piped.close()

    then:
    1 * timingCallback.accept({ it > 0 })
  }

  def 'should not call timing callback when no injection happens'() {
    setup:
    def downstream = new StringWriter()
    def timingCallback = Mock(LongConsumer)
    def piped = new InjectingPipeWriter(downstream, MARKER_CHARS, CONTEXT_CHARS, null, null, timingCallback)

    when:
    piped.write("no marker here".toCharArray())
    piped.close()

    then:
    0 * timingCallback.accept(_)
  }

  def 'should be resilient to exceptions when timing callback is null'() {
    setup:
    def downstream = new StringWriter()
    def piped = new InjectingPipeWriter(downstream, MARKER_CHARS, CONTEXT_CHARS, null, null, null)

    when:
    piped.write("<html><head></head><body></body></html>".toCharArray())
    piped.close()

    then:
    noExceptionThrown()
  }

  def streamingPipe(Writer downstream, String content) {
    return new InjectingPipeWriter(downstream, content.toCharArray(), new HtmlCharMatcher(), null, null, null)
  }

  def 'streaming parser should inject before the real </head> for #description'() {
    setup:
    def downstream = new StringWriter()
    def piped = streamingPipe(downstream, content)
    when:
    try (def closeme = piped) {
      piped.write(body.toCharArray())
    }
    then:
    downstream.toString() == expected
    where:
    description                     | body                                                         | content   | expected
    'plain'                         | "<html><head><foo/></head><body/></html>"                    | "<i></i>" | "<html><head><foo/><i></i></head><body/></html>"
    'uppercase tag'                 | "<html><HEAD></HEAD></html>"                                 | "<i></i>" | "<html><HEAD><i></i></HEAD></html>"
    'trailing whitespace'           | "<head></head >"                                             | "<i></i>" | "<head><i></i></head >"
    'ignores </head> in comment'    | "<head><!-- </head> --></head>"                              | "<i></i>" | "<head><!-- </head> --><i></i></head>"
    'ignores </head> in script'     | "<head><script>var x='</head>';</script></head>"            | "<i></i>" | "<head><script>var x='</head>';</script><i></i></head>"
    'does not match </header>'      | "<head></header></head>"                                     | "<i></i>" | "<head></header><i></i></head>"
    'no head means no injection'    | "<html><body/></html>"                                       | "<i></i>" | "<html><body/></html>"
  }

  def 'streaming parser should give identical output writing char by char'() {
    setup:
    def body = "<html><head><!-- </head> --><script>'</head>'</script></head><body/></html>"
    def bulk = new StringWriter()
    def single = new StringWriter()
    def bulkPipe = streamingPipe(bulk, "<i></i>")
    def singlePipe = streamingPipe(single, "<i></i>")
    when:
    bulkPipe.write(body.toCharArray())
    bulkPipe.close()
    for (char c : body.toCharArray()) {
      singlePipe.write((int) c)
    }
    singlePipe.close()
    then:
    def expected = "<html><head><!-- </head> --><script>'</head>'</script><i></i></head><body/></html>"
    bulk.toString() == expected
    single.toString() == expected
  }
}
