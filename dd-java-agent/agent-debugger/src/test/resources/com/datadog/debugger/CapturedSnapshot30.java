package com.datadog.debugger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

public class CapturedSnapshot30 {

  private InetAddress inetAddress;

  public CapturedSnapshot30() throws UnknownHostException {
    this.inetAddress = Inet4Address.getLocalHost();
  }

  public static int main(String arg) throws Exception {
    if ("static".equals(arg)) {
      return new MyHttpURLConnection().process();
    }
    return new MyObjectInputStream().process();
  }

  private static class MyObjectInputStream extends ObjectInputStream {
    private final int intField = 42;

    public MyObjectInputStream() throws IOException {
      super();
    }

    public int process() {
      return intField;
    }
  }

  private static class MyHttpURLConnection extends HttpURLConnection {
    private final int intField = 42;

    public MyHttpURLConnection() {
      super(null);
    }

    @Override
    public void connect() throws IOException {

    }

    @Override
    public void disconnect() {

    }

    @Override
    public boolean usingProxy() {
      return false;
    }

    public int process() {
      return intField;
    }
  }
}
