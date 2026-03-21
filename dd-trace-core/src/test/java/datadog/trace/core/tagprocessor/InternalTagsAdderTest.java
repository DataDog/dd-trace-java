package datadog.trace.core.tagprocessor;

import static datadog.trace.bootstrap.instrumentation.api.Tags.VERSION;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
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
class InternalTagsAdderTest {

  @Mock DDSpanContext spanContext;

  @TableTest({
    "scenario                         | serviceName | expectedBaseService",
    "service differs to ddService     | anotherOne  | test               ",
    "service equals ddService         | test        | null               ",
    "service equals ddService (mixed) | TeSt        | null               "
  })
  @ParameterizedTest(name = "{0}")
  void shouldAddDdBaseServiceWhenServiceDiffersToDdService(
      String scenario, String serviceName, String expectedBaseService) {
    InternalTagsAdder calculator = new InternalTagsAdder("test", null);
    when(spanContext.getServiceName()).thenReturn(serviceName);

    Map<String, Object> enrichedTags =
        calculator.processTags(
            new HashMap<String, Object>(),
            spanContext,
            Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    if ("null".equals(expectedBaseService)) {
      assertFalse(enrichedTags.containsKey("_dd.base_service"));
    } else {
      assertEquals(
          UTF8BytesString.create(expectedBaseService), enrichedTags.get("_dd.base_service"));
    }
  }

  @TableTest({
    "scenario                                      | serviceName | ddVersion | hasVersionTag | versionTagValue | expected",
    "same service, no ddVersion, no tag            | same        | null      | false         | null            | null    ",
    "different service, ddVersion=1.0, no tag      | different   | 1.0       | false         | null            | null    ",
    "different service, ddVersion=1.0, version tag | different   | 1.0       | true          | 2.0             | 2.0     ",
    "same service, no ddVersion, version tag       | same        | null      | true          | 2.0             | 2.0     ",
    "same service, ddVersion=1.0, version tag      | same        | 1.0       | true          | 2.0             | 2.0     ",
    "same service, ddVersion=1.0, no tag           | same        | 1.0       | false         | null            | 1.0     "
  })
  @ParameterizedTest(name = "{0}")
  void shouldAddVersionWhenDdServiceAndVersionConfigured(
      String scenario,
      String serviceName,
      String ddVersion,
      boolean hasVersionTag,
      String versionTagValue,
      String expected) {
    String resolvedDdVersion = "null".equals(ddVersion) ? null : ddVersion;
    InternalTagsAdder calculator = new InternalTagsAdder("same", resolvedDdVersion);
    when(spanContext.getServiceName()).thenReturn(serviceName);

    Map<String, Object> tags = new HashMap<>();
    if (hasVersionTag) {
      tags.put("version", versionTagValue);
    }

    Map<String, Object> enrichedTags =
        calculator.processTags(
            tags,
            spanContext,
            Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    String resolvedExpected = "null".equals(expected) ? null : expected;
    Object versionValue = enrichedTags != null ? enrichedTags.get(VERSION) : null;
    String versionStr = versionValue != null ? versionValue.toString() : null;
    assertEquals(resolvedExpected, versionStr);
  }
}
