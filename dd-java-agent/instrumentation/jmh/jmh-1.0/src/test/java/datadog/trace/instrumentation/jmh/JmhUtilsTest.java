package datadog.trace.instrumentation.jmh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JmhUtilsTest {

  @Test
  void splitBenchmarkName_simple() {
    assertArrayEquals(
        new String[] {"com.example.MyBenchmark", "myMethod"},
        JmhUtils.splitBenchmarkName("com.example.MyBenchmark.myMethod"));
  }

  @Test
  void splitBenchmarkName_noPackage() {
    assertArrayEquals(
        new String[] {"MyBenchmark", "myMethod"},
        JmhUtils.splitBenchmarkName("MyBenchmark.myMethod"));
  }

  @Test
  void splitBenchmarkName_noDot() {
    assertArrayEquals(new String[] {"", "noDot"}, JmhUtils.splitBenchmarkName("noDot"));
  }

  @Test
  void paramsToJson_singleParam() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("size", "1000");
    assertEquals("{\"metadata\":{\"test_name\":\"size=1000\"}}", JmhUtils.paramsToJson(params));
  }

  @Test
  void paramsToJson_multipleParams() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("size", "1000");
    params.put("threads", "4");
    assertEquals(
        "{\"metadata\":{\"test_name\":\"size=1000, threads=4\"}}", JmhUtils.paramsToJson(params));
  }

  @Test
  void paramsToJson_escapesQuotes() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("key", "\"value\"");
    assertEquals(
        "{\"metadata\":{\"test_name\":\"key=\\\"value\\\"\"}}", JmhUtils.paramsToJson(params));
  }
}
