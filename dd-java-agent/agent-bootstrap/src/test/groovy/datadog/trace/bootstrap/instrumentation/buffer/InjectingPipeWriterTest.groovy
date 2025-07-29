package datadog.trace.bootstrap.instrumentation.buffer

import datadog.trace.test.util.DDSpecification

class InjectingPipeWriterTest extends DDSpecification {
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
}
