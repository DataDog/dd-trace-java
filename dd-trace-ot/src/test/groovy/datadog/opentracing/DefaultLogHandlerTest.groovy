package datadog.opentracing

import datadog.trace.api.DDTags
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import datadog.trace.test.util.DDSpecification
import io.opentracing.Span
import io.opentracing.log.Fields

class DefaultLogHandlerTest extends DDSpecification {
  def writer = new ListWriter()
  def tracer = CoreTracer.builder().writer(writer).build()

  def cleanup() {
    tracer?.close()
  }

  def "handles correctly the error passed in the fields"() {
    setup:
    final LogHandler underTest = new DefaultLogHandler()
    final DDSpan span = tracer.buildSpan("op name").withServiceName("foo").start()
    final String errorMessage = "errorMessage"
    final String differentMessage = "differentMessage"
    final Throwable throwable = new Throwable(errorMessage)
    final Map<String, ?> fields = new HashMap<>()
    fields.put(Fields.ERROR_OBJECT, throwable)
    fields.put(Fields.MESSAGE, differentMessage)

    when:
    underTest.log(fields, span)

    then:
    span.getTags().get(DDTags.ERROR_MSG) == throwable.getMessage()
    span.getTags().get(DDTags.ERROR_TYPE) == throwable.getClass().getName()
  }

  def "handles correctly the error passed in the fields when called with timestamp"() {
    setup:
    final LogHandler underTest = new DefaultLogHandler()
    final DDSpan span = tracer.buildSpan("op name").withServiceName("foo").start()
    final String errorMessage = "errorMessage"
    final String differentMessage = "differentMessage"
    final Throwable throwable = new Throwable(errorMessage)
    final Map<String, ?> fields = new HashMap<>()
    fields.put(Fields.ERROR_OBJECT, throwable)
    fields.put(Fields.MESSAGE, differentMessage)

    when:
    underTest.log(System.currentTimeMillis(), fields, span)

    then:
    span.getTags().get(DDTags.ERROR_MSG) == throwable.getMessage()
    span.getTags().get(DDTags.ERROR_TYPE) == throwable.getClass().getName()
  }

  def "handles correctly the message passed in the fields but the span is not an error"() {
    setup:
    final LogHandler underTest = new DefaultLogHandler()
    final DDSpan span = tracer.buildSpan("op name").withServiceName("foo").start()
    final String errorMessage = "errorMessage"
    final Map<String, ?> fields = new HashMap<>()
    fields.put(Fields.MESSAGE, errorMessage)

    when:
    underTest.log(fields, span)

    then:
    span.getTags().get(DDTags.ERROR_MSG) is null
  }

  def "handles correctly the message passed in the fields when called with timestamp but the span is not an error"() {
    setup:
    final LogHandler underTest = new DefaultLogHandler()
    final DDSpan span = tracer.buildSpan("op name").withServiceName("foo").start()
    final String errorMessage = "errorMessage"
    final Map<String, ?> fields = new HashMap<>()
    fields.put(Fields.MESSAGE, errorMessage)

    when:
    underTest.log(System.currentTimeMillis(), fields, span)

    then:
    span.getTags().get(DDTags.ERROR_MSG) is null
  }

  def "handles correctly the message passed in the fields when the span is error"() {
    setup:
    final LogHandler underTest = new DefaultLogHandler()
    final DDSpan span = tracer.buildSpan("op name").withServiceName("foo").start()
    final String errorMessage = "errorMessage"
    final Map<String, ?> fields = new HashMap<>()
    span.setError(true)
    fields.put(Fields.MESSAGE, errorMessage)

    when:
    underTest.log(fields, span)

    then:
    span.getTags().get(DDTags.ERROR_MSG) == errorMessage
  }

  def "handles correctly the message passed in the fields when called with timestamp when the span is error"() {
    setup:
    final LogHandler underTest = new DefaultLogHandler()
    final DDSpan span = tracer.buildSpan("op name").withServiceName("foo").start()
    final String errorMessage = "errorMessage"
    final Map<String, ?> fields = new HashMap<>()
    span.setError(true)
    fields.put(Fields.MESSAGE, errorMessage)

    when:
    underTest.log(System.currentTimeMillis(), fields, span)

    then:
    span.getTags().get(DDTags.ERROR_MSG) == errorMessage
  }

  def "handles correctly the message passed in the fields when the event is error"() {
    setup:
    final LogHandler underTest = new DefaultLogHandler()
    final DDSpan span = tracer.buildSpan("op name").withServiceName("foo").start()
    final String errorMessage = "errorMessage"
    final Map<String, ?> fields = new HashMap<>()
    fields.put(Fields.EVENT, "error")
    fields.put(Fields.MESSAGE, errorMessage)

    when:
    underTest.log(fields, span)

    then:
    span.getTags().get(DDTags.ERROR_MSG) == errorMessage
  }

  def "handles correctly the message passed in the fields when called with timestampwhen the event is error"() {
    setup:
    final LogHandler underTest = new DefaultLogHandler()
    final DDSpan span = tracer.buildSpan("op name").withServiceName("foo").start()
    final String errorMessage = "errorMessage"
    final Map<String, ?> fields = new HashMap<>()
    fields.put(Fields.EVENT, "error")
    fields.put(Fields.MESSAGE, errorMessage)

    when:
    underTest.log(System.currentTimeMillis(), fields, span)

    then:
    span.getTags().get(DDTags.ERROR_MSG) == errorMessage
  }

  def "sanity test with loghandler not set"() {
    setup:
    final String expectedName = "fakeName"
    final String expectedLogEvent = "fakeEvent"
    final timeStamp = System.currentTimeMillis()
    final Map<String, String> fieldsMap = new HashMap<>()

    when:
    def loggingTracer = DDTracer.builder().writer(writer).build()
    final Span span = loggingTracer
      .buildSpan(expectedName)
      .withServiceName("foo")
      .start()

    span.log(expectedLogEvent)
    span.log(timeStamp, expectedLogEvent)
    span.log(fieldsMap)
    span.log(timeStamp, fieldsMap)

    then:
    noExceptionThrown()

    cleanup:
    loggingTracer.close()
  }

  def "sanity test when passed log handler is null"() {
    setup:
    final String expectedName = "fakeName"
    final String expectedLogEvent = "fakeEvent"
    final timeStamp = System.currentTimeMillis()
    final Map<String, String> fieldsMap = new HashMap<>()

    when:
    def loggingTracer = DDTracer.builder().writer(writer).logHandler(null).build()
    final Span span = loggingTracer
      .buildSpan(expectedName)
      .start()

    span.log(expectedLogEvent)
    span.log(timeStamp, expectedLogEvent)
    span.log(fieldsMap)
    span.log(timeStamp, fieldsMap)

    then:
    noExceptionThrown()

    cleanup:
    loggingTracer.close()
  }

  def "should delegate simple logs to logHandler"() {
    setup:
    def logHandler = Mock(LogHandler)
    final String expectedName = "fakeName"
    final String expectedLogEvent = "fakeEvent"
    final timeStamp = System.currentTimeMillis()

    def loggingTracer = DDTracer.builder().writer(writer).logHandler(logHandler).build()
    def span = loggingTracer
      .buildSpan(expectedName)
      .withServiceName("foo")
      .start()

    when:
    span.log(timeStamp, expectedLogEvent)

    then:
    1 * logHandler.log(timeStamp, expectedLogEvent, span.delegate)

    cleanup:
    loggingTracer.close()
  }

  def "should delegate simple logs with timestamp to logHandler"() {
    setup:
    def logHandler = Mock(LogHandler)
    final String expectedName = "fakeName"
    final String expectedLogEvent = "fakeEvent"

    def loggingTracer = DDTracer.builder().writer(writer).logHandler(logHandler).build()
    def span = loggingTracer
      .buildSpan(expectedName)
      .withServiceName("foo")
      .start()

    when:
    span.log(expectedLogEvent)

    then:
    1 * logHandler.log(expectedLogEvent, span.delegate)

    cleanup:
    loggingTracer.close()
  }

  def "should delegate logs with fields to logHandler"() {
    setup:
    def logHandler = Mock(LogHandler)
    final String expectedName = "fakeName"
    final Map<String, String> fieldsMap = new HashMap<>()

    def loggingTracer = DDTracer.builder().writer(writer).logHandler(logHandler).build()
    def span = loggingTracer
      .buildSpan(expectedName)
      .withServiceName("foo")
      .start()

    when:
    span.log(fieldsMap)

    then:
    1 * logHandler.log(fieldsMap, span.delegate)

    cleanup:
    loggingTracer.close()
  }

  def "should delegate logs with fields and timestamp to logHandler"() {
    setup:
    def logHandler = Mock(LogHandler)
    final String expectedName = "fakeName"
    final Map<String, String> fieldsMap = new HashMap<>()
    final timeStamp = System.currentTimeMillis()

    def loggingTracer = DDTracer.builder().writer(writer).logHandler(logHandler).build()
    def span = loggingTracer
      .buildSpan(expectedName)
      .withServiceName("foo")
      .start()

    when:
    span.log(timeStamp, fieldsMap)

    then:
    1 * logHandler.log(timeStamp, fieldsMap, span.delegate)

    cleanup:
    loggingTracer.close()
  }
}
