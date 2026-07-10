package datadog.trace.civisibility.config.api.dto.response;

import com.squareup.moshi.Json;
import datadog.trace.api.civisibility.config.TestFQN;
import datadog.trace.civisibility.config.api.dto.PageInfo;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

public final class KnownTestsResponse {
  public final Map<String, Map<String, List<String>>> tests;

  @Json(name = "page_info")
  public final PageInfo.Response pageInfo;

  public KnownTestsResponse(
      Map<String, Map<String, List<String>>> tests, PageInfo.Response pageInfo) {
    this.tests = tests;
    this.pageInfo = pageInfo;
  }

  public boolean hasNextPage() {
    return pageInfo != null && pageInfo.hasNext;
  }

  @Nullable
  public Integer getPageSize() {
    return pageInfo != null ? pageInfo.size : null;
  }

  @Nullable
  public String getNextPageCursor() {
    return pageInfo != null ? pageInfo.cursor : null;
  }

  /**
   * Flattens a {@code module -> suite -> [testName]} map into {@code module -> Set<TestFQN>}.
   * Returns {@code null} when the input contains no tests so that callers can disable downstream
   * features that rely on known tests being available.
   */
  @Nullable
  public static Map<String, Collection<TestFQN>> toTestFQNsByModule(
      Map<String, Map<String, List<String>>> tests) {
    int totalTests = 0;
    Map<String, Collection<TestFQN>> testsByModule = new HashMap<>();
    for (Map.Entry<String, Map<String, List<String>>> moduleEntry : tests.entrySet()) {
      String moduleName = moduleEntry.getKey();
      for (Map.Entry<String, List<String>> suiteEntry : moduleEntry.getValue().entrySet()) {
        String suiteName = suiteEntry.getKey();
        List<String> testNames = suiteEntry.getValue();
        totalTests += testNames.size();
        for (String testName : testNames) {
          testsByModule
              .computeIfAbsent(moduleName, k -> new HashSet<>())
              .add(new TestFQN(suiteName, testName));
        }
      }
    }
    return totalTests > 0 ? testsByModule : null;
  }
}
