package com.datadog.profiling.remoteconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class ProfilingConfigTargetsTest {

  @TableTest({
    "Scenario               | Json                                                                                        | Expected",
    "env and service match  | '{\"targets\":[{\"env\":\"staging\",\"service\":\"my-app\"}]}'                              | true    ",
    "service only matches   | '{\"targets\":[{\"service\":\"my-app\"}]}'                                                  | true    ",
    "env only matches       | '{\"targets\":[{\"env\":\"staging\"}]}'                                                     | true    ",
    "any target matches     | '{\"targets\":[{\"service\":\"other\"},{\"env\":\"staging\",\"service\":\"my-app\"}]}'      | true    ",
    "wildcards              | '{\"targets\":[{\"env\":\"*\",\"service\":\"*\"}]}'                                         | true    ",
    "empty target           | '{\"targets\":[{}]}'                                                                        | true    ",
    "targets after others   | '{\"profilers\":{\"cpuTime\":true},\"settings\":{},\"targets\":[{\"service\":\"my-app\"}]}' | true    ",
    "env differs            | '{\"targets\":[{\"env\":\"prod\",\"service\":\"my-app\"}]}'                                 | false   ",
    "service differs        | '{\"targets\":[{\"env\":\"staging\",\"service\":\"other\"}]}'                               | false   ",
    "service not normalized | '{\"targets\":[{\"service\":\"My-App\"}]}'                                                  | true    ",
    "env and service differ | '{\"targets\":[{\"env\":\"prod\",\"service\":\"other\"}]}'                                  | false   ",
    "only env differs       | '{\"targets\":[{\"env\":\"prod\",\"service\":\"*\"}]}'                                      | false   ",
    "only service differs   | '{\"targets\":[{\"env\":\"*\",\"service\":\"other\"}]}'                                     | false   ",
    "no targets             | '{\"quotas\":[{\"tags\":[]}]}'                                                              | false   ",
    "empty targets          | '{\"targets\":[]}'                                                                          | false   ",
    "targets not an array   | '{\"targets\":{\"service\":\"my-app\"}}'                                                    | false   ",
    "target not an object   | '{\"targets\":[\"my-app\"]}'                                                                | false   ",
    "service not a string   | '{\"targets\":[{\"service\":123,\"env\":\"staging\"}]}'                                     | false   ",
    "service null           | '{\"targets\":[{\"service\":null}]}'                                                        | false   ",
    "env not a string       | '{\"targets\":[{\"env\":true}]}'                                                            | false   ",
    "tags not an array      | '{\"targets\":[{\"service\":\"my-app\",\"tags\":\"x\"}]}'                                   | false   ",
    "tag not a string       | '{\"targets\":[{\"service\":\"my-app\",\"tags\":[1]}]}'                                     | false   ",
    "only invalid targets   | '{\"targets\":[{\"service\":1},\"x\"]}'                                                     | false   ",
    "invalid target skipped | '{\"targets\":[{\"service\":1},{\"service\":\"my-app\"}]}'                                  | true    ",
    "service tag matches    | '{\"targets\":[{\"tags\":[\"service:my-app\"]}]}'                                           | true    ",
    "env tag matches        | '{\"targets\":[{\"tags\":[\"env:staging\"]}]}'                                              | true    ",
    "service tag differs    | '{\"targets\":[{\"tags\":[\"service:other\"]}]}'                                            | false   ",
    "env tag differs        | '{\"targets\":[{\"tags\":[\"env:prod\"]}]}'                                                 | false   ",
    "one tag differs        | '{\"targets\":[{\"tags\":[\"team:x\",\"env:prod\"]}]}'                                      | false   ",
    "tag key any case       | '{\"targets\":[{\"tags\":[\"Service:MY-APP\"]}]}'                                           | true    ",
    "tag value any          | '{\"targets\":[{\"tags\":[\"service:*\"]}]}'                                                | true    ",
    "tag without a value    | '{\"targets\":[{\"tags\":[\"service\"]}]}'                                                  | true    ",
    "other tags assumed     | '{\"targets\":[{\"tags\":[\"pod_name:abc\"]}]}'                                             | true    ",
    "tag differs, other ok  | '{\"targets\":[{\"tags\":[\"env:prod\"]},{\"tags\":[\"env:staging\"]}]}'                    | true    ",
    "field ok, tag differs  | '{\"targets\":[{\"service\":\"my-app\",\"tags\":[\"env:prod\"]}]}'                          | false   ",
    "invalid json           | '{\"targets\":['                                                                            | false   ",
    "not an object          | '[]'                                                                                        | false   "
  })
  void matchesTargets(String json, boolean expected) {
    assertEquals(expected, ProfilingConfigTargets.matches(json, "staging", "my-app"));
  }

  @TableTest({
    "Scenario                        | Env       | Service  | Target env | Target service | Expected",
    "case and punctuation            | 'Prod EU' | 'My App' | prod_eu    | my_app         | true    ",
    "same raw values                 | staging   | my-app   | staging    | my-app         | true    ",
    "service differs once normalized | staging   | 'My App' | staging    | my-app         | false   ",
    "env differs once normalized     | 'Prod EU' | my-app   | prod-eu    | my-app         | false   "
  })
  void valuesAreComparedNormalized(
      String env, String service, String targetEnv, String targetService, boolean expected) {
    String json =
        "{\"targets\":[{\"env\":\"" + targetEnv + "\",\"service\":\"" + targetService + "\"}]}";

    assertEquals(expected, ProfilingConfigTargets.matches(json, env, service));
  }

  @Test
  void applicationWithoutEnvOnlyMatchesTargetsForAnyEnv() {
    String forStaging = "{\"targets\":[{\"env\":\"staging\",\"service\":\"my-app\"}]}";
    String forAnyEnv = "{\"targets\":[{\"env\":\"*\",\"service\":\"my-app\"}]}";
    String noEnv = "{\"targets\":[{\"service\":\"my-app\"}]}";

    assertFalse(ProfilingConfigTargets.matches(forStaging, null, "my-app"));
    assertTrue(ProfilingConfigTargets.matches(forAnyEnv, null, "my-app"));
    assertTrue(ProfilingConfigTargets.matches(noEnv, null, "my-app"));
  }

  // a payload that is rejected must not rank, and one that ranks must not be rejected
  @TableTest({
    "Scenario               | Json                                                           ",
    "this service           | '{\"targets\":[{\"service\":\"my-app\"}]}'                     ",
    "service not normalized | '{\"targets\":[{\"service\":\"My-App\"}]}'                     ",
    "other service          | '{\"targets\":[{\"service\":\"other\"}]}'                      ",
    "other env              | '{\"targets\":[{\"env\":\"prod\",\"service\":\"my-app\"}]}'    ",
    "service not a string   | '{\"targets\":[{\"service\":123,\"env\":\"staging\"}]}'        ",
    "env not a string       | '{\"targets\":[{\"env\":true,\"service\":\"my-app\"}]}'        ",
    "tags not an array      | '{\"targets\":[{\"service\":\"my-app\",\"tags\":\"x\"}]}'      ",
    "invalid target skipped | '{\"targets\":[{\"service\":1},{\"service\":\"my-app\"}]}'     ",
    "only invalid targets   | '{\"targets\":[{\"service\":1},\"x\"]}'                        ",
    "service tag differs    | '{\"targets\":[{\"tags\":[\"service:other\"]}]}'               ",
    "env tag differs        | '{\"targets\":[{\"tags\":[\"env:prod\",\"team:x\"]}]}'         ",
    "service tag matches    | '{\"targets\":[{\"tags\":[\"service:my-app\"]}]}'              ",
    "one consistent target  | '{\"targets\":[{\"service\":\"other\"},{\"env\":\"staging\"}]}'",
    "no targets             | '{\"profilers\":{\"cpuTime\":true}}'                           ",
    "invalid json           | '{\"targets\":['                                               "
  })
  void rejectedPayloadsNeverRank(String json) {
    boolean matches = ProfilingConfigTargets.matches(json, "staging", "my-app");
    int specificity = ProfilingConfigTargets.specificity(json, "staging", "my-app");

    if (!matches) {
      assertEquals(0, specificity, "a rejected payload must not rank");
    }
    if (specificity > 0) {
      assertTrue(matches, "a payload that ranks must not be rejected");
    }
  }

  // the specificity is the level of the most specific condition of the most specific target
  // consistent with the application, times 1000, plus its number of conditions
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
    "service tag of this service    | '{\"targets\":[{\"tags\":[\"service:my-app\"]}]}'                            | 1001    ",
    "service tag of another service | '{\"targets\":[{\"tags\":[\"service:other\"]}]}'                             | 0       ",
    "env tag of another env         | '{\"targets\":[{\"service\":\"my-app\",\"tags\":[\"env:prod\"]}]}'           | 0       ",
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
    "other service                  | '{\"targets\":[{\"service\":\"other\"}]}'                                    | 0       ",
    "other env                      | '{\"targets\":[{\"env\":\"prod\",\"service\":\"my-app\"}]}'                  | 0       ",
    "inconsistent target is skipped | '{\"targets\":[{\"env\":\"prod\"},{\"service\":\"my-app\"}]}'                | 3001    ",
    "the most specific target wins  | '{\"targets\":[{\"env\":\"staging\"},{\"service\":\"my-app\"}]}'             | 3001    ",
    "values are compared normalized | '{\"targets\":[{\"service\":\"MY-APP\"}]}'                                   | 3001    ",
    "invalid service is skipped     | '{\"targets\":[{\"service\":1},{\"env\":\"staging\"}]}'                      | 2001    ",
    "null service is skipped        | '{\"targets\":[{\"service\":null}]}'                                         | 0       ",
    "tags not an array              | '{\"targets\":[{\"tags\":\"pod_name:p\"}]}'                                  | 0       ",
    "tag not a string               | '{\"targets\":[{\"tags\":[1]}]}'                                             | 0       ",
    "target not an object           | '{\"targets\":[\"my-app\"]}'                                                 | 0       ",
    "targets not an array           | '{\"targets\":{\"service\":\"my-app\"}}'                                     | 0       ",
    "invalid json                   | '{\"targets\":['                                                             | 0       ",
    "not an object                  | '[]'                                                                         | 0       "
  })
  void specificityOfTargets(String json, int expected) {
    assertEquals(expected, ProfilingConfigTargets.specificity(json, "staging", "my-app"));
  }
}
