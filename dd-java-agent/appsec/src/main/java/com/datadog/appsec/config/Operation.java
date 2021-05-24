package com.datadog.appsec.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum Operation {
  @JsonProperty("match_regex")
  MATCH_REGEX,

  @JsonProperty("not_match")
  NOT_MATCH,

  @JsonProperty("equal")
  EQUAL,

  @JsonProperty("match_cidr")
  MATCH_CIDR,

  @JsonProperty("has_sqli_pattern")
  HAS_SQLI_PATTERN,

  @JsonProperty("has_xss_pattern")
  HAS_XSS_PATTERN
}
