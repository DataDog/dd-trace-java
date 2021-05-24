package com.datadog.appsec.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum Action {
  @JsonProperty("block")
  BLOCK,

  @JsonProperty("monitor")
  MONITOR,

  @JsonProperty("disabled")
  DISABLED
}
