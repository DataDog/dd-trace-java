package com.datadog.openfeature.internal.delivery;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.zip.GZIPInputStream;

/** Gzip decoding, as the JDK HTTP client does not handle content encoding. */
final class Gzip {
  private Gzip() {}

  /**
   * Decodes a response body according to its {@code Content-Encoding} header.
   *
   * @param response the response to decode the body of.
   * @return the decoded body.
   * @throws IOException if the body is not valid gzip content.
   */
  static byte[] decode(final HttpResponse<byte[]> response) throws IOException {
    final boolean gzipped =
        response
            .headers()
            .firstValue("Content-Encoding")
            .map("gzip"::equalsIgnoreCase)
            .orElse(false);
    if (!gzipped) {
      return response.body();
    }
    try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
      return input.readAllBytes();
    }
  }
}
