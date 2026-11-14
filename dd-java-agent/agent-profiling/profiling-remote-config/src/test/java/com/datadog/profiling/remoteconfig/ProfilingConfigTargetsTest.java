package com.datadog.profiling.remoteconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class ProfilingConfigTargetsTest {

  @Test
  void invalidTargetsAreReported() {
    List<String> problems = new ArrayList<>();

    int rank =
        ProfilingConfigTargets.rank(
            "{\"targets\":[{\"service\":1},{\"service\":\"my-app\"},\"x\"]}", problems);

    assertEquals(3001, rank);
    assertEquals(2, problems.size());
    assertTrue(problems.get(0).contains("targets[0]"));
    assertTrue(problems.get(1).contains("targets[2]"));
  }

  @Test
  void droppedPayloadsAreReported() {
    List<String> problems = new ArrayList<>();

    assertEquals(
        ProfilingConfigTargets.DROPPED,
        ProfilingConfigTargets.rank("{\"targets\":[{\"service\":1}]}", problems));
    assertTrue(problems.contains("no valid targets"));

    problems.clear();
    assertEquals(
        ProfilingConfigTargets.DROPPED, ProfilingConfigTargets.rank("{\"targets\":{}}", problems));
    assertTrue(problems.contains("targets is not an array"));
  }

  // the specificity is the level of the most specific condition of the most specific target, times
  // 1000, plus its number of conditions
  @TableTest({
    "Scenario                       | Json                                                                         | Expected",
    "no targets                     | '{\"k\":1}'                                                                  | 0       ",
    "empty targets                  | '{\"targets\":[]}'                                                           | 0       ",
    "empty target                   | '{\"targets\":[{}]}'                                                         | 0       ",
    "wildcards                      | '{\"targets\":[{\"env\":\"*\",\"service\":\"*\"}]}'                          | 0       ",
    "env                            | '{\"targets\":[{\"env\":\"staging\"}]}'                                      | 2001    ",
    "service                        | '{\"targets\":[{\"service\":\"my-app\"}]}'                                   | 3001    ",
    "env and service                | '{\"targets\":[{\"env\":\"staging\",\"service\":\"my-app\"}]}'               | 3002    ",
    "wildcard service and env       | '{\"targets\":[{\"env\":\"staging\",\"service\":\"*\"}]}'                    | 2001    ",
    "other tag                      | '{\"targets\":[{\"tags\":[\"team:x\"]}]}'                                    | 1001    ",
    "two other tags                 | '{\"targets\":[{\"tags\":[\"team:x\",\"tier:y\"]}]}'                         | 1002    ",
    "service tag                    | '{\"targets\":[{\"tags\":[\"service:my-app\"]}]}'                            | 1001    ",
    "service tag of another service | '{\"targets\":[{\"tags\":[\"service:other\"]}]}'                             | 1001    ",
    "env tag of another env         | '{\"targets\":[{\"service\":\"my-app\",\"tags\":[\"env:prod\"]}]}'           | 3002    ",
    "workload                       | '{\"targets\":[{\"tags\":[\"kube_deployment:d\"]}]}'                         | 4001    ",
    "workload from the task         | '{\"targets\":[{\"tags\":[\"task_family:t\"]}]}'                             | 4001    ",
    "container name                 | '{\"targets\":[{\"tags\":[\"container_name:c\"]}]}'                          | 5001    ",
    "kubernetes container name      | '{\"targets\":[{\"tags\":[\"kube_container_name:c\"]}]}'                     | 5001    ",
    "pod                            | '{\"targets\":[{\"tags\":[\"pod_name:p\"]}]}'                                | 6001    ",
    "task                           | '{\"targets\":[{\"tags\":[\"task_arn:a\"]}]}'                                | 6001    ",
    "container id                   | '{\"targets\":[{\"tags\":[\"container_id:i\"]}]}'                            | 7001    ",
    "tag name is case insensitive   | '{\"targets\":[{\"tags\":[\"POD_NAME:P\"]}]}'                                | 6001    ",
    "tag without a value            | '{\"targets\":[{\"tags\":[\"pod_name\"]}]}'                                  | 6001    ",
    "service and container id       | '{\"targets\":[{\"service\":\"my-app\",\"tags\":[\"container_id:i\"]}]}'     | 7002    ",
    "the most specific level counts | '{\"targets\":[{\"env\":\"staging\",\"tags\":[\"pod_name:p\",\"team:x\"]}]}' | 6003    ",
    "other service                  | '{\"targets\":[{\"service\":\"other\"}]}'                                    | 3001    ",
    "other env                      | '{\"targets\":[{\"env\":\"prod\",\"service\":\"my-app\"}]}'                  | 3002    ",
    "the most specific target wins  | '{\"targets\":[{\"env\":\"staging\"},{\"service\":\"my-app\"}]}'             | 3001    ",
    "invalid service is skipped     | '{\"targets\":[{\"service\":1},{\"env\":\"staging\"}]}'                      | 2001    ",
    "null service is invalid        | '{\"targets\":[{\"service\":null}]}'                                         | -1      ",
    "tags not an array              | '{\"targets\":[{\"tags\":\"pod_name:p\"}]}'                                  | -1      ",
    "tag not a string               | '{\"targets\":[{\"tags\":[1]}]}'                                             | -1      ",
    "target not an object           | '{\"targets\":[\"my-app\"]}'                                                 | -1      ",
    "targets not an array           | '{\"targets\":{\"service\":\"my-app\"}}'                                     | -1      ",
    "invalid json                   | '{\"targets\":['                                                             | -1      ",
    "not an object                  | '[]'                                                                         | -1      "
  })
  void rankOfTargets(String json, int expected) {
    assertEquals(expected, ProfilingConfigTargets.rank(json));
  }
}
