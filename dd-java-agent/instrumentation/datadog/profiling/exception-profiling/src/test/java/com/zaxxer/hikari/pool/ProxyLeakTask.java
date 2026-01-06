package com.zaxxer.hikari.pool;

public class ProxyLeakTask extends Exception {
  private static final long serialVersionUID = 1L;

  public ProxyLeakTask() {
    super("Proxy leak detected");
  }

  public ProxyLeakTask(String message) {
    super(message);
  }

  public ProxyLeakTask(String message, Throwable cause) {
    super(message, cause);
  }

  public ProxyLeakTask(Throwable cause) {
    super(cause);
  }
}
