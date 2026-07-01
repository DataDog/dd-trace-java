package com.datadog.debugger.symbol;

import com.squareup.moshi.Json;
import java.util.List;

public class ServiceVersion {
  private final String service;

  private final String env;
  private final String version;
  private final String language;
  private final List<Scope> scopes;

  @Json(name = "upload_id")
  private final String uploadId;

  @Json(name = "batch_num")
  private final long batchNum;

  @Json(name = "final")
  private final boolean isFinal;

  public ServiceVersion(
      String service,
      String env,
      String version,
      String language,
      List<Scope> scopes,
      String uploadId,
      long batchNum,
      boolean isFinal) {
    this.service = service;
    this.env = env;
    this.version = version;
    this.language = language;
    this.scopes = scopes;
    this.uploadId = uploadId;
    this.batchNum = batchNum;
    this.isFinal = isFinal;
  }

  public List<Scope> getScopes() {
    return scopes;
  }
}
