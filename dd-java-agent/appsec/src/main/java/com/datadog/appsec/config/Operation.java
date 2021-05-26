package com.datadog.appsec.config;

public enum Operation {
  MATCH_REGEX,
  NOT_MATCH,
  EQUAL,
  MATCH_CIDR,
  HAS_SQLI_PATTERN,
  HAS_XSS_PATTERN
}
