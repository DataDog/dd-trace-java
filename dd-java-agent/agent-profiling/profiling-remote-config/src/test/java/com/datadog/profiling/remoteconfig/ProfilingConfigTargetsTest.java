package com.datadog.profiling.remoteconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.tabletest.junit.TableTest;

class ProfilingConfigTargetsTest {

  @TableTest({
    "Scenario              | Json                                                                                        | Expected",
    "env and service match | '{\"targets\":[{\"env\":\"staging\",\"service\":\"my-app\"}]}'                              | true    ",
    "service only matches  | '{\"targets\":[{\"service\":\"my-app\"}]}'                                                  | true    ",
    "env only matches      | '{\"targets\":[{\"env\":\"staging\"}]}'                                                     | true    ",
    "any target matches    | '{\"targets\":[{\"service\":\"other\"},{\"env\":\"staging\",\"service\":\"my-app\"}]}'      | true    ",
    "wildcards             | '{\"targets\":[{\"env\":\"*\",\"service\":\"*\"}]}'                                         | true    ",
    "empty target          | '{\"targets\":[{}]}'                                                                        | true    ",
    "targets after others  | '{\"profilers\":{\"cpuTime\":true},\"settings\":{},\"targets\":[{\"service\":\"my-app\"}]}' | true    ",
    "env differs           | '{\"targets\":[{\"env\":\"prod\",\"service\":\"my-app\"}]}'                                 | false   ",
    "service differs       | '{\"targets\":[{\"env\":\"staging\",\"service\":\"other\"}]}'                               | false   ",
    "case sensitive        | '{\"targets\":[{\"service\":\"My-App\"}]}'                                                  | false   ",
    "no targets            | '{\"quotas\":[{\"tags\":[]}]}'                                                              | false   ",
    "empty targets         | '{\"targets\":[]}'                                                                          | false   ",
    "targets not an array  | '{\"targets\":{\"service\":\"my-app\"}}'                                                    | false   ",
    "target not an object  | '{\"targets\":[\"my-app\"]}'                                                                | false   ",
    "invalid json          | '{\"targets\":['                                                                            | false   ",
    "not an object         | '[]'                                                                                        | false   "
  })
  void matchesTargets(String json, boolean expected) {
    assertEquals(expected, ProfilingConfigTargets.matches(json, "staging", "my-app"));
  }
}
