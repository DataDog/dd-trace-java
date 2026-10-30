package com.example.cryptobench.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

final class Http {
  private Http() {}

  private static final SSLSocketFactory TLS = trustAll();

  static int get(URL url, byte[] buf, int connectTimeoutMs, int readTimeoutMs) throws IOException {
    HttpURLConnection c = (HttpURLConnection) url.openConnection();
    c.setConnectTimeout(connectTimeoutMs);
    c.setReadTimeout(readTimeoutMs);
    int code = c.getResponseCode();
    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
    if (in != null) {
      while (in.read(buf) >= 0) {
      }
      in.close();
    }
    return code;
  }

  private static final AtomicLong PEER_IDS = new AtomicLong();

  static String httpsGetNewConnection(URL url, String protocol, int timeoutMs) throws IOException {
    Socket plain = new Socket();
    plain.connect(new InetSocketAddress(url.getHost(), url.getPort()), timeoutMs);
    plain.setSoTimeout(timeoutMs);
    plain.setTcpNoDelay(true);
    String uniquePeer = "peer" + PEER_IDS.incrementAndGet() + ".bench.invalid";
    try (SSLSocket s = (SSLSocket) TLS.createSocket(plain, uniquePeer, url.getPort(), true)) {
      s.setEnabledProtocols(new String[] {protocol});
      s.startHandshake();
      OutputStream out = s.getOutputStream();
      out.write(
          ("GET "
                  + url.getFile()
                  + " HTTP/1.1\r\nHost: "
                  + url.getHost()
                  + "\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.flush();
      return readAll(s.getInputStream());
    }
  }

  static int httpsStatus(String response) {
    if (response.length() < 12 || !response.startsWith("HTTP/1.")) {
      return -1;
    }
    return Integer.parseInt(response.substring(9, 12));
  }

  static String httpsBody(String response) {
    int i = response.indexOf("\r\n\r\n");
    if (i < 0) {
      return "";
    }
    String body = response.substring(i + 4);
    if (response.toLowerCase().contains("transfer-encoding: chunked")) {
      StringBuilder sb = new StringBuilder();
      int pos = 0;
      while (pos < body.length()) {
        int nl = body.indexOf("\r\n", pos);
        if (nl < 0) {
          break;
        }
        int len = Integer.parseInt(body.substring(pos, nl).trim(), 16);
        if (len == 0) {
          break;
        }
        sb.append(body, nl + 2, nl + 2 + len);
        pos = nl + 2 + len + 2;
      }
      return sb.toString();
    }
    return body;
  }

  static String getBody(URL url) throws IOException {
    HttpURLConnection c = (HttpURLConnection) url.openConnection();
    c.setConnectTimeout(2000);
    c.setReadTimeout(10000);
    try (InputStream in = c.getInputStream()) {
      return readAll(in);
    }
  }

  private static String readAll(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int n;
    while ((n = in.read(buf)) >= 0) {
      out.write(buf, 0, n);
    }
    return new String(out.toByteArray(), StandardCharsets.UTF_8);
  }

  static Map<String, String> keyValues(String body) {
    Map<String, String> m = new HashMap<>();
    for (String line : body.split("\n")) {
      int eq = line.indexOf('=');
      if (eq > 0) {
        m.put(line.substring(0, eq), line.substring(eq + 1).trim());
      }
    }
    return m;
  }

  static Map<String, Long> stats(String base) throws IOException {
    Map<String, Long> m = new HashMap<>();
    for (Map.Entry<String, String> e : keyValues(getBody(new URL(base + "/admin/stats"))).entrySet()) {
      m.put(e.getKey(), Long.parseLong(e.getValue()));
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

  private static SSLSocketFactory trustAll() {
    try {
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(
          null,
          new TrustManager[] {
            new X509TrustManager() {
              @Override
              public void checkClientTrusted(X509Certificate[] chain, String authType) {}

              @Override
              public void checkServerTrusted(X509Certificate[] chain, String authType) {}

              @Override
              public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
              }
            }
          },
          null);
      ctx.getClientSessionContext().setSessionCacheSize(256);
      return ctx.getSocketFactory();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
