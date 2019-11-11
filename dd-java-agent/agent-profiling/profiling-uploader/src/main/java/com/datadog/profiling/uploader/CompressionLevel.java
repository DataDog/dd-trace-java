package com.datadog.profiling.uploader;

enum CompressionLevel {
  /** No compression */
  OFF,
  /** Default compression level */
  ON,
  /** Unknown compression level */
  UNKNOWN;

  static CompressionLevel of(String level) {
    if (level == null) {
      return UNKNOWN;
    }

    switch (level.toLowerCase()) {
      case "off":
        return OFF;
      case "on":
        return ON;
      default:
        return UNKNOWN;
    }
  }
}
