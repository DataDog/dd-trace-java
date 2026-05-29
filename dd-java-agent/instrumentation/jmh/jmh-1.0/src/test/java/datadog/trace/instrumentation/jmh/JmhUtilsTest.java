package datadog.trace.instrumentation.jmh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class JmhUtilsTest {

  @Test
  void splitBenchmarkName_simple() {
    String[] parts = JmhUtils.splitBenchmarkName("com.example.MyBenchmark.myMethod");
    assertArrayEquals(new String[] {"com.example.MyBenchmark", "myMethod"}, parts);
  }

  @Test
  void splitBenchmarkName_withParams() {
    String[] parts =
        JmhUtils.splitBenchmarkName("com.example.MyBenchmark.myMethod:size=1000,threads=4");
    assertArrayEquals(new String[] {"com.example.MyBenchmark", "myMethod"}, parts);
  }

  @Test
  void splitBenchmarkName_noPackage() {
    String[] parts = JmhUtils.splitBenchmarkName("MyBenchmark.myMethod");
    assertArrayEquals(new String[] {"MyBenchmark", "myMethod"}, parts);
  }

  @Test
  void splitBenchmarkName_noDot() {
    String[] parts = JmhUtils.splitBenchmarkName("noDot");
    assertArrayEquals(new String[] {"", "noDot"}, parts);
  }

  @Test
  void testParameters_noParams() {
    assertNull(JmhUtils.testParameters("com.example.MyBenchmark.myMethod"));
  }

  @Test
  void testParameters_withParams() {
    String result = JmhUtils.testParameters("com.example.MyBenchmark.myMethod:size=1000,threads=4");
    assertEquals("{\"metadata\":{\"test_name\":\"myMethod:size=1000,threads=4\"}}", result);
  }

  @Test
  void testParameters_escapesQuotes() {
    String result = JmhUtils.testParameters("com.example.MyBenchmark.myMethod:key=\"value\"");
    assertEquals("{\"metadata\":{\"test_name\":\"myMethod:key=\\\"value\\\"\"}}", result);
  }
}
