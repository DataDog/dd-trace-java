package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class SplitByTagsPrioritiesTest {

  @TableTest({
    "scenario   | tag         | expectedPriority",
    "span kind  | 'span.kind' | 1               ",
    "language   | 'language'  | 2               ",
    "component  | 'component' | 3               ",
    "custom tag | 'my.tag'    | 127             "
  })
  void priorityOf(String tag, byte expectedPriority) {
    assertEquals(expectedPriority, SplitByTagsPriorities.of(tag));
  }

  @Test
  void componentOutranksLanguageOutranksSpanKind() {
    assertTrue(SplitByTagsPriorities.UNSET < SplitByTagsPriorities.SPAN_KIND);
    assertTrue(SplitByTagsPriorities.SPAN_KIND < SplitByTagsPriorities.LANGUAGE);
    assertTrue(SplitByTagsPriorities.LANGUAGE < SplitByTagsPriorities.COMPONENT);
    assertTrue(SplitByTagsPriorities.COMPONENT < SplitByTagsPriorities.OTHER);
  }
}
