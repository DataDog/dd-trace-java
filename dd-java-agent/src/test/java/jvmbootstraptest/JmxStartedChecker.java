package jvmbootstraptest;

public class JmxStartedChecker {
  public static void main(final String[] args) throws Exception {
    AgentLoadedChecker.main(args);

    boolean jmxStarted = false;
    for (Thread t : Thread.getAllStackTraces().keySet()) {
      if ("dd-jmx-collector".equals(t.getName())) {
        jmxStarted = true;
      }
    }

    if (!jmxStarted) {
      throw new IllegalStateException("JMXFetch did not start");
    }
  }
}
