package datadog.trace.core.tagprocessor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import datadog.trace.core.DDSpanContext;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tabletest.junit.TableTest;

@ExtendWith(MockitoExtension.class)
class IntegrationAdderTest {

  @Mock DDSpanContext spanContext;

  @TableTest({
    "scenario              | isSet",
    "integration name set  | true ",
    "integration name null | false"
  })
  @ParameterizedTest(name = "{0}")
  void shouldAddOrRemoveDdIntegrationWhenSetOnTheSpanContext(String scenario, boolean isSet) {
    IntegrationAdder calculator = new IntegrationAdder();

    when(spanContext.getIntegrationName()).thenReturn(isSet ? "test" : null);

    Map<String, Object> input = new HashMap<>();
    input.put("_dd.integration", "bad");
    Map<String, Object> enrichedTags =
        calculator.processTags(
            input,
            spanContext,
            Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    if (isSet) {
      assertEquals("test", enrichedTags.get("_dd.integration"));
    } else {
      assertFalse(enrichedTags.containsKey("_dd.integration"));
    }
  }
}
