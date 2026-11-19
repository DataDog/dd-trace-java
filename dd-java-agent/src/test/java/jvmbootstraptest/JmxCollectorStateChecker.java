package jvmbootstraptest;

/** Prints whether the dd-jmx-collector thread was started once the agent is loaded. */
public class JmxCollectorStateChecker {
  public static void main(final String[] args) throws Exception {
    AgentLoadedChecker.main(args);

    boolean jmxStarted = false;
    for (Thread t : Thread.getAllStackTraces().keySet()) {
      if ("dd-jmx-collector".equals(t.getName())) {
        jmxStarted = true;
        break;
      }
    }

    System.out.println("JMX_COLLECTOR_STARTED=" + jmxStarted);
    System.out.close();
  }
}
