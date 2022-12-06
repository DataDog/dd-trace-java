package jvmbootstraptest;

public class JmxStartedChecker {
  public static void main(final String[] args) throws Exception {
    AgentLoadedChecker.main(args);

    boolean jmxStarted = false;
    for (Thread t : Thread.getAllStackTraces().keySet()) {
      if ("dd-jmx-collector".equals(t.getName())) {
        jmxStarted = true;
        break;
      }
    }

    if (!jmxStarted) {
      System.out.println("ERROR: dd-jmx-collector did not start");
      System.exit(1);
    }

    System.out.println("READY");
    System.out.close();

    // Give time for the test to finish if needed
    if (args.length > 0) {
      Thread.sleep(Long.parseLong(args[0]));
    }
  }
}
