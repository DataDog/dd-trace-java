package datadog.trace.core.tagprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.TagMap;
import datadog.trace.core.DDSpanContext;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.Collections;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IntegrationAdderTest extends DDJavaSpecification {

  @ValueSource(booleans = {true, false})
  @ParameterizedTest(
      name = "should add or remove _dd.integration when set ({0}) on the span context")
  void shouldAddOrRemoveDdIntegrationWhenSetOnTheSpanContext(boolean isSet) {
    IntegrationAdder calculator = new IntegrationAdder();
    DDSpanContext spanContext = mock(DDSpanContext.class);
    when(spanContext.getIntegrationName()).thenReturn(isSet ? "test" : null);

    TagMap unsafeTags = TagMap.fromMap(Collections.singletonMap("_dd.integration", "bad"));
    calculator.processTags(unsafeTags, spanContext, link -> {});

    verify(spanContext, times(1)).getIntegrationName();

    if (isSet) {
      assertEquals(Collections.singletonMap("_dd.integration", "test"), unsafeTags);
    } else {
      assertTrue(unsafeTags.isEmpty());
    }
  }
}
