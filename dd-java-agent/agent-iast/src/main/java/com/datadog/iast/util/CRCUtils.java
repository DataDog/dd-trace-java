package com.datadog.iast.util;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

public abstract class CRCUtils {

  private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

  private CRCUtils() {}

  public static void update(final CRC32 crc, final String value) {
    update(crc, value, DEFAULT_CHARSET);
  }

  public static void update(final CRC32 crc, final String value, final Charset charset) {
    final byte[] bytes = value.getBytes(charset);
    update(crc, bytes);
  }

  public static void update(final CRC32 crc, final byte[] bytes) {
    crc.update(bytes, 0, bytes.length);
  }
}
