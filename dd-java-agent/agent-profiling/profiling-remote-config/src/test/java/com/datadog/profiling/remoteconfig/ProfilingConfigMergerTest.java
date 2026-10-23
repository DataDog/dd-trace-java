package com.datadog.profiling.remoteconfig;

import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class ProfilingConfigMergerTest {

  @TableTest({
    "Scenario      | Json                                    | Expected  ",
    "string        | '{\"k\": \"v\"}'                        | v         ",
    "integer       | '{\"k\": 100}'                          | 100       ",
    "decimal       | '{\"k\": 0.5}'                          | 0.5       ",
    "boolean       | '{\"k\": false}'                        | false     ",
    "list          | '{\"k\": [\"a\", 1, true]}'             | 'a,1,true'",
    "list skipping | '{\"k\": [\"a\", null, {\"x\": 1}, 2]}' | 'a,2'     "
  })
  void parsesValues(String json, String expected) throws Exception {
    assertEquals(expected, ProfilingConfigMerger.parse(json).get("k"));
  }

  @Test
  void nullAndNestedValuesAreNotOverrides() throws Exception {
    Map<String, String> values =
        ProfilingConfigMerger.parse("{\"a\": null, \"b\": {\"c\": 1}, \"d\": \"e\"}");

    Map<String, String> expected = new HashMap<>();
    expected.put("d", "e");
    assertEquals(expected, values);
  }

  @Test
  void laterConfigIdWinsAndKeysAreMerged() {
    Map<String, String> configs = new HashMap<>();
    configs.put("b", "{\"profiling.ddprof.cpu.enabled\": true, \"x\": \"from-b\"}");
    configs.put("a", "{\"profiling.ddprof.wall.enabled\": false, \"x\": \"from-a\"}");

    Map<String, String> expected = new HashMap<>();
    expected.put("profiling.ddprof.cpu.enabled", "true");
    expected.put("profiling.ddprof.wall.enabled", "false");
    expected.put("x", "from-b");
    assertEquals(expected, ProfilingConfigMerger.merge(configs));
  }

  @Test
  void invalidConfigIsIgnored() {
    Map<String, String> configs = new HashMap<>();
    configs.put("a", "not json");
    configs.put("b", "[1, 2]");
    configs.put("c", "{\"k\": \"v\"}");

    Map<String, String> expected = new HashMap<>();
    expected.put("k", "v");
    assertEquals(expected, ProfilingConfigMerger.merge(configs));
  }

  @Test
  void noConfigs() {
    assertEquals(emptyMap(), ProfilingConfigMerger.merge(emptyMap()));
  }
}
