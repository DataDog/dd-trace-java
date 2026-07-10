package datadog.opentracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import datadog.trace.api.DDTags;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.test.util.DDJavaSpecification;
import io.opentracing.log.Fields;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DefaultLogHandlerTest extends DDJavaSpecification {

  private final ListWriter writer = new ListWriter();
  private final CoreTracer tracer = CoreTracer.builder().writer(writer).build();

  @AfterEach
  void cleanup() throws Exception {
    if (tracer != null) {
      tracer.close();
    }
  }

  @Test
  void handlesCorrectlyTheErrorPassedInTheFields() {
    LogHandler underTest = new DefaultLogHandler();
    DDSpan span = (DDSpan) tracer.buildSpan("datadog", "op name").withServiceName("foo").start();
    String errorMessage = "errorMessage";
    String differentMessage = "differentMessage";
    Throwable error = new Throwable(errorMessage);
    Map<String, Object> fields = new HashMap<>();
    fields.put(Fields.ERROR_OBJECT, error);
    fields.put(Fields.MESSAGE, differentMessage);

    underTest.log(fields, span);

    assertEquals(error.getMessage(), span.getTags().get(DDTags.ERROR_MSG));
    assertEquals(error.getClass().getName(), span.getTags().get(DDTags.ERROR_TYPE));
  }

  @Test
  void handlesCorrectlyTheErrorPassedInTheFieldsWhenCalledWithTimestamp() {
    LogHandler underTest = new DefaultLogHandler();
    DDSpan span = (DDSpan) tracer.buildSpan("datadog", "op name").withServiceName("foo").start();
    String errorMessage = "errorMessage";
    String differentMessage = "differentMessage";
    Throwable error = new Throwable(errorMessage);
    Map<String, Object> fields = new HashMap<>();
    fields.put(Fields.ERROR_OBJECT, error);
    fields.put(Fields.MESSAGE, differentMessage);

    underTest.log(System.currentTimeMillis(), fields, span);

    assertEquals(error.getMessage(), span.getTags().get(DDTags.ERROR_MSG));
    assertEquals(error.getClass().getName(), span.getTags().get(DDTags.ERROR_TYPE));
  }

  @Test
  void handlesCorrectlyTheMessageInTheFieldsButSpanIsNotAnError() {
    LogHandler underTest = new DefaultLogHandler();
    DDSpan span = (DDSpan) tracer.buildSpan("datadog", "op name").withServiceName("foo").start();
    String errorMessage = "errorMessage";
    Map<String, Object> fields = new HashMap<>();
    fields.put(Fields.MESSAGE, errorMessage);

    underTest.log(fields, span);

    assertNull(span.getTags().get(DDTags.ERROR_MSG));
  }

  @Test
  void handlesCorrectlyTheMessageInTheFieldsCalledWithTimestampButSpanIsNotAnError() {
    LogHandler underTest = new DefaultLogHandler();
    DDSpan span = (DDSpan) tracer.buildSpan("datadog", "op name").withServiceName("foo").start();
    String errorMessage = "errorMessage";
    Map<String, Object> fields = new HashMap<>();
    fields.put(Fields.MESSAGE, errorMessage);

    underTest.log(System.currentTimeMillis(), fields, span);

    assertNull(span.getTags().get(DDTags.ERROR_MSG));
  }

  @Test
  void handlesCorrectlyTheMessageInTheFieldsWhenSpanIsError() {
    LogHandler underTest = new DefaultLogHandler();
    DDSpan span = (DDSpan) tracer.buildSpan("datadog", "op name").withServiceName("foo").start();
    String errorMessage = "errorMessage";
    Map<String, Object> fields = new HashMap<>();
    span.setError(true);
    fields.put(Fields.MESSAGE, errorMessage);

    underTest.log(fields, span);

    assertEquals(errorMessage, span.getTags().get(DDTags.ERROR_MSG));
  }

  @Test
  void handlesCorrectlyTheMessageInTheFieldsCalledWithTimestampWhenSpanIsError() {
    LogHandler underTest = new DefaultLogHandler();
    DDSpan span = (DDSpan) tracer.buildSpan("datadog", "op name").withServiceName("foo").start();
    String errorMessage = "errorMessage";
    Map<String, Object> fields = new HashMap<>();
    span.setError(true);
    fields.put(Fields.MESSAGE, errorMessage);

    underTest.log(System.currentTimeMillis(), fields, span);

    assertEquals(errorMessage, span.getTags().get(DDTags.ERROR_MSG));
  }

  @Test
  void handlesCorrectlyTheMessageInTheFieldsWhenEventIsError() {
    LogHandler underTest = new DefaultLogHandler();
    DDSpan span = (DDSpan) tracer.buildSpan("datadog", "op name").withServiceName("foo").start();
    String errorMessage = "errorMessage";
    Map<String, Object> fields = new HashMap<>();
    fields.put(Fields.EVENT, "error");
    fields.put(Fields.MESSAGE, errorMessage);

    underTest.log(fields, span);

    assertEquals(errorMessage, span.getTags().get(DDTags.ERROR_MSG));
  }

  @Test
  void handlesCorrectlyTheMessageInTheFieldsCalledWithTimestampWhenEventIsError() {
    LogHandler underTest = new DefaultLogHandler();
    DDSpan span = (DDSpan) tracer.buildSpan("datadog", "op name").withServiceName("foo").start();
    String errorMessage = "errorMessage";
    Map<String, Object> fields = new HashMap<>();
    fields.put(Fields.EVENT, "error");
    fields.put(Fields.MESSAGE, errorMessage);

    underTest.log(System.currentTimeMillis(), fields, span);

    assertEquals(errorMessage, span.getTags().get(DDTags.ERROR_MSG));
  }

  @Test
  void sanityTestWithLoghandlerNotSet() throws Exception {
    String expectedName = "fakeName";
    String expectedLogEvent = "fakeEvent";
    long timeStamp = System.currentTimeMillis();
    Map<String, String> fieldsMap = new HashMap<>();

    DDTracer loggingTracer = DDTracer.builder().writer(writer).build();
    try {
      io.opentracing.Span span =
          loggingTracer.buildSpan(expectedName).withServiceName("foo").start();

      span.log(expectedLogEvent);
      span.log(timeStamp, expectedLogEvent);
      span.log(fieldsMap);
      span.log(timeStamp, fieldsMap);
      // no exception thrown
    } finally {
      loggingTracer.close();
    }
  }

  @Test
  void sanityTestWhenPassedLogHandlerIsNull() throws Exception {
    String expectedName = "fakeName";
    String expectedLogEvent = "fakeEvent";
    long timeStamp = System.currentTimeMillis();
    Map<String, String> fieldsMap = new HashMap<>();

    DDTracer loggingTracer = DDTracer.builder().writer(writer).logHandler(null).build();
    try {
      io.opentracing.Span span = loggingTracer.buildSpan(expectedName).start();

      span.log(expectedLogEvent);
      span.log(timeStamp, expectedLogEvent);
      span.log(fieldsMap);
      span.log(timeStamp, fieldsMap);
      // no exception thrown
    } finally {
      loggingTracer.close();
    }
  }

  @Test
  void shouldDelegateSimpleLogsToLogHandler() throws Exception {
    LogHandler logHandler = mock(LogHandler.class);
    String expectedName = "fakeName";
    String expectedLogEvent = "fakeEvent";
    long timeStamp = System.currentTimeMillis();

    DDTracer loggingTracer = DDTracer.builder().writer(writer).logHandler(logHandler).build();
    try {
      OTSpan span = (OTSpan) loggingTracer.buildSpan(expectedName).withServiceName("foo").start();

      span.log(timeStamp, expectedLogEvent);

      verify(logHandler).log(timeStamp, expectedLogEvent, span.getDelegate());
    } finally {
      loggingTracer.close();
    }
  }

  @Test
  void shouldDelegateSimpleLogsWithTimestampToLogHandler() throws Exception {
    LogHandler logHandler = mock(LogHandler.class);
    String expectedName = "fakeName";
    String expectedLogEvent = "fakeEvent";

    DDTracer loggingTracer = DDTracer.builder().writer(writer).logHandler(logHandler).build();
    try {
      OTSpan span = (OTSpan) loggingTracer.buildSpan(expectedName).withServiceName("foo").start();

      span.log(expectedLogEvent);

      verify(logHandler).log(expectedLogEvent, span.getDelegate());
    } finally {
      loggingTracer.close();
    }
  }

  @Test
  void shouldDelegateLogsWithFieldsToLogHandler() throws Exception {
    LogHandler logHandler = mock(LogHandler.class);
    String expectedName = "fakeName";
    Map<String, String> fieldsMap = new HashMap<>();

    DDTracer loggingTracer = DDTracer.builder().writer(writer).logHandler(logHandler).build();
    try {
      OTSpan span = (OTSpan) loggingTracer.buildSpan(expectedName).withServiceName("foo").start();

      span.log(fieldsMap);

      verify(logHandler).log(fieldsMap, span.getDelegate());
    } finally {
      loggingTracer.close();
    }
  }

  @Test
  void shouldDelegateLogsWithFieldsAndTimestampToLogHandler() throws Exception {
    LogHandler logHandler = mock(LogHandler.class);
    String expectedName = "fakeName";
    Map<String, String> fieldsMap = new HashMap<>();
    long timeStamp = System.currentTimeMillis();

    DDTracer loggingTracer = DDTracer.builder().writer(writer).logHandler(logHandler).build();
    try {
      OTSpan span = (OTSpan) loggingTracer.buildSpan(expectedName).withServiceName("foo").start();

      span.log(timeStamp, fieldsMap);

      verify(logHandler).log(timeStamp, fieldsMap, span.getDelegate());
    } finally {
      loggingTracer.close();
    }
  }
}
