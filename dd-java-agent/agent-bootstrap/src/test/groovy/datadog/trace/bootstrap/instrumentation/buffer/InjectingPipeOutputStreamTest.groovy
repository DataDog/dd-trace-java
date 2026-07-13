package datadog.trace.bootstrap.instrumentation.buffer

import datadog.trace.test.util.DDSpecification
import java.util.function.LongConsumer

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
    def piped = new InjectingPipeOutputStream(downstream, MARKER_BYTES, CONTEXT_BYTES, null, { long bytes -> counter.incr(bytes) }, null)

    when:
    piped.write(testBytes)
    piped.close()

    then:
    counter.value == testBytes.length
    downstream.toByteArray() == testBytes
  }

  def 'should count bytes correctly when writing bytes individually'() {
    setup:
    def testBytes = "test".getBytes("UTF-8")
    def downstream = new ByteArrayOutputStream()
    def counter = new Counter()
    def piped = new InjectingPipeOutputStream(downstream, MARKER_BYTES, CONTEXT_BYTES, null, { long bytes -> counter.incr(bytes) }, null)

    when:
    for (int i = 0; i < testBytes.length; i++) {
      piped.write((int) testBytes[i])
    }
    piped.close()

    then:
    counter.value == testBytes.length
    downstream.toByteArray() == testBytes
  }

  def 'should count bytes correctly with multiple writes'() {
    setup:
    def testBytes = "test content"
    def downstream = new ByteArrayOutputStream()
    def counter = new Counter()
    def piped = new InjectingPipeOutputStream(downstream, MARKER_BYTES, CONTEXT_BYTES, null, { long bytes -> counter.incr(bytes) }, null)

    when:
    piped.write(testBytes[0..4].getBytes("UTF-8"))
    piped.write(testBytes[5..5].getBytes("UTF-8"))
    piped.write(testBytes[6..-1].getBytes("UTF-8"))
    piped.close()

    then:
    counter.value == testBytes.length()
    downstream.toByteArray() == testBytes.getBytes("UTF-8")
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

  def 'should call timing callback when injection happens'() {
    setup:
    def downstream = Mock(OutputStream) {
      write(_) >> { args ->
        Thread.sleep(1) // simulate slow write
      }
    }
    def timingCallback = Mock(LongConsumer)
    def piped = new InjectingPipeOutputStream(downstream, MARKER_BYTES, CONTEXT_BYTES, null, null, timingCallback)

    when:
    piped.write("<html><head></head><body></body></html>".getBytes("UTF-8"))
    piped.close()

    then:
    1 * timingCallback.accept({ it > 0 })
  }

  def 'should not call timing callback when no injection happens'() {
    setup:
    def downstream = new ByteArrayOutputStream()
    def timingCallback = Mock(LongConsumer)
    def piped = new InjectingPipeOutputStream(downstream, MARKER_BYTES, CONTEXT_BYTES, null, null, timingCallback)

    when:
    piped.write("no marker here".getBytes("UTF-8"))
    piped.close()

    then:
    0 * timingCallback.accept(_)
  }

  def 'should be resilient to exceptions when timing callback is null'() {
    setup:
    def downstream = new ByteArrayOutputStream()
    def piped = new InjectingPipeOutputStream(downstream, MARKER_BYTES, CONTEXT_BYTES, null, null, null)

    when:
    piped.write("<html><head></head><body></body></html>".getBytes("UTF-8"))
    piped.close()

    then:
    noExceptionThrown()
  }

  def streamingPipe(OutputStream downstream, String content) {
    return new InjectingPipeOutputStream(downstream, content.getBytes("UTF-8"), new HtmlByteMatcher(), null, null, null)
  }

  def 'streaming parser should inject before the real </head> for #description'() {
    setup:
    def downstream = new ByteArrayOutputStream()
    def piped = streamingPipe(downstream, content)
    when:
    try (def closeme = piped) {
      piped.write(body.getBytes("UTF-8"))
    }
    then:
    new String(downstream.toByteArray(), "UTF-8") == expected
    where:
    description                     | body                                                         | content   | expected
    'plain'                         | "<html><head><foo/></head><body/></html>"                    | "<i></i>" | "<html><head><foo/><i></i></head><body/></html>"
    'uppercase tag'                 | "<html><HEAD></HEAD></html>"                                 | "<i></i>" | "<html><HEAD><i></i></HEAD></html>"
    'mixed case tag'                | "<head></HeAd>"                                              | "<i></i>" | "<head><i></i></HeAd>"
    'trailing whitespace'           | "<head></head >"                                             | "<i></i>" | "<head><i></i></head >"
    'newline before gt'            | "<head></head\n></html>"                                     | "<i></i>" | "<head><i></i></head\n></html>"
    'ignores </head> in comment'    | "<head><!-- </head> --></head>"                              | "<i></i>" | "<head><!-- </head> --><i></i></head>"
    'ignores </head> in script'     | "<head><script>var x='</head>';</script></head>"            | "<i></i>" | "<head><script>var x='</head>';</script><i></i></head>"
    'ignores </head> in document.write' | '<head><script>document.write("<head></head>");</script></head>' | "<i></i>" | '<head><script>document.write("<head></head>");</script><i></i></head>'
    'ignores bare </head> in script' | "<head><script>if (a</head>b) {}</script></head>"           | "<i></i>" | "<head><script>if (a</head>b) {}</script><i></i></head>"
    'ignores uppercase </HEAD> in script' | "<head><script>x='</HEAD>'</script></head>"            | "<i></i>" | "<head><script>x='</HEAD>'</script><i></i></head>"
    'ignores </head> in style'      | "<head><style>/* </head> */</style></head>"                  | "<i></i>" | "<head><style>/* </head> */</style><i></i></head>"
    'ignores </head> in script src attr' | '<head><script src="/a?x=</head>"></script></head>'    | "<i></i>" | '<head><script src="/a?x=</head>"></script><i></i></head>'
    'does not match </header>'      | "<head></header></head>"                                     | "<i></i>" | "<head></header><i></i></head>"
    'no head means no injection'    | "<html><body/></html>"                                       | "<i></i>" | "<html><body/></html>"
    'only injects once'             | "<head></head></head>"                                       | "<i></i>" | "<head><i></i></head></head>"
    'after doctype'                 | "<!DOCTYPE html><head></head>"                               | "<i></i>" | "<!DOCTYPE html><head><i></i></head>"
  }

  def 'streaming parser should give identical output writing byte by byte'() {
    setup:
    def body = "<html><head><!-- </head> --><script>'</head>'</script></head><body/></html>"
    def bulk = new ByteArrayOutputStream()
    def single = new ByteArrayOutputStream()
    def bulkPipe = streamingPipe(bulk, "<i></i>")
    def singlePipe = streamingPipe(single, "<i></i>")
    when:
    bulkPipe.write(body.getBytes("UTF-8"))
    bulkPipe.close()
    for (byte b : body.getBytes("UTF-8")) {
      singlePipe.write((int) b)
    }
    singlePipe.close()
    then:
    def expected = "<html><head><!-- </head> --><script>'</head>'</script><i></i></head><body/></html>"
    new String(bulk.toByteArray(), "UTF-8") == expected
    new String(single.toByteArray(), "UTF-8") == expected
  }

  def 'streaming parser should count original bytes and fire injection callbacks'() {
    setup:
    def body = "<html><head></head></html>"
    def downstream = new ByteArrayOutputStream()
    def counter = new Counter()
    def injected = new Counter()
    def piped = new InjectingPipeOutputStream(downstream, "<i></i>".getBytes("UTF-8"),
    new HtmlByteMatcher(), { -> injected.value++ }, { long bytes -> counter.incr(bytes) }, null)
    when:
    piped.write(body.getBytes("UTF-8"))
    piped.close()
    then:
    // callback reports the original response size, not counting the injected content
    counter.value == body.length()
    injected.value == 1
    new String(downstream.toByteArray(), "UTF-8") == "<html><head><i></i></head></html>"
  }
}
