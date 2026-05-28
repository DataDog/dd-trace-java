package datadog.trace.civisibility.config.api.dto.response;

import com.squareup.moshi.Json;
import datadog.trace.civisibility.config.api.dto.PageInfo;
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
}
