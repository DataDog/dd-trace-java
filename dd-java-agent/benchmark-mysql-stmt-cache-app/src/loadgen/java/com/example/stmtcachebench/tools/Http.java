package com.example.stmtcachebench.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

final class Http {
  private Http() {}

  static int get(URL url, byte[] buf, int connectTimeoutMs, int readTimeoutMs) throws IOException {
    HttpURLConnection c = (HttpURLConnection) url.openConnection();
    c.setConnectTimeout(connectTimeoutMs);
    c.setReadTimeout(readTimeoutMs);
    int code = c.getResponseCode();
    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
    if (in != null) {
      drain(in, buf);
      in.close();
    }
    return code;
  }

  static void drain(InputStream in, byte[] buf) throws IOException {
    while (in.read(buf) >= 0) {}
  }

  static String getBody(URL url) throws IOException {
    HttpURLConnection c = (HttpURLConnection) url.openConnection();
    c.setConnectTimeout(2000);
    c.setReadTimeout(10000);
    try (InputStream in = c.getInputStream()) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) >= 0) {
        out.write(buf, 0, n);
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  static Map<String, Long> stats(String base) throws IOException {
    String body = getBody(new URL(base + "/admin/stats"));
    Map<String, Long> m = new HashMap<>();
    int start = 0;
    while (start < body.length()) {
      int nl = body.indexOf('\n', start);
      int end = nl < 0 ? body.length() : nl;
      int eq = body.indexOf('=', start);
      if (eq > start && eq < end) {
        m.put(body.substring(start, eq), Long.parseLong(body.substring(eq + 1, end).trim()));
      }
      start = end + 1;
    }
    return m;
  }

  static boolean ready(URL url) {
    try {
      return get(url, new byte[1024], 200, 5000) == 200;
    } catch (IOException e) {
      return false;
    }
  }
}
