package com.datadog.profiling.uploader;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

enum CompressionType {
  /** No compression */
  OFF,
  /** Default compression */
  ON,
  /** Lower compression ratio with less CPU overhead * */
  LZ4,
  /** Better compression ratio for the price of higher CPU usage * */
  GZIP,
  /** High compression ratio with reasonable CPU usage * */
  ZSTD;

  private static final Logger log = LoggerFactory.getLogger(CompressionType.class);

  static CompressionType of(String type) {
    if (type == null) {
      type = "";
    }

    switch (type.toLowerCase(Locale.ROOT)) {
      case "off":
        return OFF;
      case "on":
        return ON;
      case "lz4":
        return LZ4;
      case "gzip":
        return GZIP;
      case "zstd":
        return ZSTD;
      default:
        log.warn("Unrecognizable compression type: {}. Defaulting to '{}'.", type, ZSTD);
        return ON;
    }
  }
}
