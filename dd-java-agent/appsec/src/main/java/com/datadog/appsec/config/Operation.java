package com.datadog.appsec.config;

public enum Operation {
  MATCH_REGEX,
  PHRASE_MATCH,
  MATCH_STRING,
  HAS_SQLI_PATTERN,
  HAS_XSS_PATTERN
}
