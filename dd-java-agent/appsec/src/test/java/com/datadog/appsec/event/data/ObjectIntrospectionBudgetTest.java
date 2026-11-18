package com.datadog.appsec.event.data;

import static com.datadog.appsec.ddwaf.WAFModule.MAX_ELEMENTS;
import static com.datadog.appsec.event.data.ObjectIntrospection.convert;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.datadog.appsec.gateway.AppSecRequestContext;
import datadog.trace.api.telemetry.WafMetricCollector;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Inputs far larger than the element budget are converted to the same bounded output, without
 * sizing the output containers by the input.
 */
class ObjectIntrospectionBudgetTest {

  private static final int OVERSIZED = MAX_ELEMENTS * 100;

  private WafMetricCollector originalMetricCollector;
  private AppSecRequestContext ctx;

  @BeforeEach
  void setUp() {
    originalMetricCollector = WafMetricCollector.get();
    WafMetricCollector.INSTANCE = mock(WafMetricCollector.class);
    ctx = mock(AppSecRequestContext.class);
  }

  @AfterEach
  void tearDown() {
    WafMetricCollector.INSTANCE = originalMetricCollector;
  }

  @Test
  void oversizedMapKeepsTheFirstEntriesWithinBudget() {
    Map<String, Object> body = new LinkedHashMap<>();
    for (int i = 0; i < OVERSIZED; i++) {
      body.put("k" + i, "v" + i);
    }

    Object result = convert(body, ctx);

    assertInstanceOf(Map.class, result);
    Map<?, ?> map = (Map<?, ?>) result;
    // one element for the map itself, two for each entry (key and value)
    assertEquals((MAX_ELEMENTS - 1) / 2, map.size());
    assertEquals("v0", map.get("k0"));
  }

  @Test
  void oversizedListKeepsTheFirstElementsWithinBudget() {
    List<Object> body = new ArrayList<>(OVERSIZED);
    for (int i = 0; i < OVERSIZED; i++) {
      body.add(i);
    }

    Object result = convert(body, ctx);

    assertInstanceOf(List.class, result);
    List<?> list = (List<?>) result;
    assertTrue(list.size() < MAX_ELEMENTS);
    assertEquals(0, list.get(0));
  }

  @Test
  void oversizedArrayKeepsTheFirstElementsWithinBudget() {
    String[] body = new String[OVERSIZED];
    for (int i = 0; i < OVERSIZED; i++) {
      body[i] = "s" + i;
    }

    Object result = convert(body, ctx);

    assertInstanceOf(List.class, result);
    List<?> list = (List<?>) result;
    assertTrue(list.size() < MAX_ELEMENTS);
    assertEquals("s0", list.get(0));
  }
}
