package gw.internal.xml.ws;

import datadog.trace.api.Trace;

/**
 * Test double for Guidewire's WSI worker; kept in package {@code gw.internal.xml.ws} so the matcher
 * applies.
 */
public class AsyncResponseImpl {

  private final Thread thread;

  public AsyncResponseImpl() {
    this.thread = new WebserviceInvocationThread();
  }

  // The boolean only distinguishes this overload from the no-arg constructor; its value is unused.
  private AsyncResponseImpl(boolean anonymous) {
    this.thread =
        new Thread() {
          @Override
          public void run() {
            soapCall();
          }
        };
  }

  public static AsyncResponseImpl anonymous() {
    return new AsyncResponseImpl(true);
  }

  public void invoke() throws InterruptedException {
    thread.start();
    thread.join();
  }

  public void invokeSync() {
    thread.run();
  }

  @Trace(operationName = "soap.call")
  static void soapCall() {}

  // Chained constructor: two <init> frames make capture fire twice, testing the State CAS dedup.
  public static final class WebserviceInvocationThread extends Thread {
    public WebserviceInvocationThread() {
      this("WSI-Invocation");
    }

    private WebserviceInvocationThread(String name) {
      super(name);
    }

    @Override
    public void run() {
      soapCall();
    }
  }
}
