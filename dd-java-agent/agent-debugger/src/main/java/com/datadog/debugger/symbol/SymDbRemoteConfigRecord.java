package com.datadog.debugger.symbol;

import com.squareup.moshi.Json;

public class SymDbRemoteConfigRecord {
  @Json(name = "upload_symbols")
  private final boolean uploadSymbols;

  public SymDbRemoteConfigRecord(boolean uploadSymbols) {
    this.uploadSymbols = uploadSymbols;
  }

  public boolean isUploadSymbols() {
    return uploadSymbols;
  }
}
