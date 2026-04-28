package datadog.trace.bootstrap.instrumentation.java.net;

import java.util.concurrent.atomic.AtomicBoolean;

public class JpmsInetAddressHelper {
  public static final AtomicBoolean OPENED = new AtomicBoolean(false);

  private JpmsInetAddressHelper() {}
}
