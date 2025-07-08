package datadog.trace.bootstrap.instrumentation.buffer

import datadog.trace.test.util.DDSpecification
import spock.lang.Specification

class InjectingPipeOutputStreamTest extends DDSpecification {

  static class ExceptionControlledOutputStream extends FilterOutputStream {

    boolean failWrite = false

    ExceptionControlledOutputStream(OutputStream out) {
      super(out)
    }

    @Override
    void write(int b) throws IOException {
      if (failWrite) {
        throw new IOException("Failed")
      }
      super.write(b)
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

  def 'should maintain the state in case of IOException for epilogue #epilogue'() {
    setup:
    def baos = new ByteArrayOutputStream()
    def downstream = new ExceptionControlledOutputStream(baos)
    def piped = new OutputStreamWriter(new InjectingPipeOutputStream(downstream, "</head>".getBytes("UTF-8"), "<injected/>".getBytes("UTF-8"), null),
    "UTF-8")
    when:
    piped.write(prologue)
    piped.flush()
    downstream.failWrite = true
    piped.write(epilogue)
    piped.flush()
    then:
    thrown IOException
    when:
    downstream.failWrite = false
    piped.write(epilogue)
    piped.close()
    then:
    def expected = prologue + epilogue
    assert expected == new String(baos.toByteArray(), "UTF-8")
    where:
    prologue                | epilogue
    "<html><head><script/>" | "</head></html>"
    "<html><head><script/>" | "</html>"
  }
}
