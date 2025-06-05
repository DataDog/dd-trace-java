package com.datadog.profiling.controller.openjdk.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SimpleParserTest {
  private SimpleParser parser;

  int val1 = 3232;
  long val2 = 0xffffffffff600000L;
  String str = "fsdag";

  @BeforeEach
  public void setUp() {
    parser = new SimpleParser(val1 + " " + str + " " + Long.toHexString(val2));
  }

  @Test
  void testNextLongValue() throws Exception {
    assertEquals(val1, parser.nextLongValue(10));
    assertEquals(-1, parser.nextLongValue(10));
    assertEquals(val2, parser.nextLongValue(16));
    assertEquals(-1, parser.nextLongValue(10));
  }

  @Test
  void testStringValue() throws Exception {
    assertEquals(Long.toString(val1), parser.nextStringValue());
    assertEquals(str, parser.nextStringValue());
    assertEquals(Long.toHexString(val2), parser.nextStringValue());
    assertNull(parser.nextStringValue());
  }
}
