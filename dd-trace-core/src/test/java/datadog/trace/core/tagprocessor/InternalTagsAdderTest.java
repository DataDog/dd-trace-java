package datadog.trace.core.tagprocessor;

import static datadog.trace.bootstrap.instrumentation.api.Tags.VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.DDSpanContext;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.Collections;
import java.util.Objects;
import org.tabletest.junit.TableTest;

class InternalTagsAdderTest extends DDJavaSpecification {

  @TableTest({
    "scenario          | serviceName | expectsBaseService",
    "different service | anotherOne  | true              ",
    "exact match       | test        | false             ",
    "case insensitive  | TeSt        | false             "
  })
  void shouldAddBaseServiceWhenServiceDiffersToDdService(
      String serviceName, boolean expectsBaseService) {
    InternalTagsAdder calculator = new InternalTagsAdder("test", null);
    DDSpanContext spanContext = mock(DDSpanContext.class);
    when(spanContext.getServiceName()).thenReturn(serviceName);

    TagMap unsafeTags = TagMap.fromMap(Collections.emptyMap());
    calculator.processTags(unsafeTags, spanContext, link -> {});

    verify(spanContext, times(1)).getServiceName();

    if (expectsBaseService) {
      assertEquals(UTF8BytesString.create("test"), unsafeTags.get("_dd.base_service"));
    } else {
      assertTrue(unsafeTags.isEmpty());
    }
  }

  @TableTest({
    "scenario                          | serviceName | ddVersion | initialVersion | expected",
    "same service, no version          | same        |           |                |         ",
    "different service with ddVersion  | different   | 1.0       |                |         ",
    "different service, manual version | different   | 1.0       | 2.0            | 2.0     ",
    "same service, no ddVersion        | same        |           | 2.0            | 2.0     ",
    "same service, both versions       | same        | 1.0       | 2.0            | 2.0     ",
    "same service, only ddVersion      | same        | 1.0       |                | 1.0     "
  })
  void shouldAddVersionWhenDdServiceEqualsServiceNameAndVersionSet(
      String serviceName, String ddVersion, String initialVersion, String expected) {
    InternalTagsAdder calculator = new InternalTagsAdder("same", ddVersion);
    DDSpanContext spanContext = mock(DDSpanContext.class);
    when(spanContext.getServiceName()).thenReturn(serviceName);

    TagMap unsafeTags =
        TagMap.fromMap(
            initialVersion != null
                ? Collections.singletonMap("version", initialVersion)
                : Collections.emptyMap());
    calculator.processTags(unsafeTags, spanContext, link -> {});

    verify(spanContext, times(1)).getServiceName();
    assertEquals(expected, Objects.toString(unsafeTags.get(VERSION), null));
  }
}
