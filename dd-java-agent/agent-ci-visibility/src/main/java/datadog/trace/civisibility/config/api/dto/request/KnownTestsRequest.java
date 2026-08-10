package datadog.trace.civisibility.config.api.dto.request;

import com.squareup.moshi.Json;
import datadog.trace.civisibility.config.api.dto.PageInfo;
import javax.annotation.Nullable;

public final class KnownTestsRequest {
  @Json(name = "repository_url")
  public final String repositoryUrl;

  public final String service;
  public final String env;

  @Json(name = "page_info")
  public final PageInfo.Request pageInfo;

  public KnownTestsRequest(TracerEnvironment tracerEnvironment, @Nullable String pageState) {
    this.repositoryUrl = tracerEnvironment.getRepositoryUrl();
    this.service = tracerEnvironment.getService();
    this.env = tracerEnvironment.getEnv();
    this.pageInfo = new PageInfo.Request(pageState);
  }
}
