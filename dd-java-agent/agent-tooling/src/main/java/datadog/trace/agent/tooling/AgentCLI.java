package datadog.trace.agent.tooling;

import com.datadog.crashtracking.CrashUploader;
import datadog.trace.agent.tooling.bytebuddy.SharedTypePools;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import datadog.trace.bootstrap.Agent;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** CLI methods, used when running the agent as a sample application with -jar. */
public final class AgentCLI {

  private static final Logger log = LoggerFactory.getLogger(AgentCLI.class);

  static {
    SharedTypePools.registerIfAbsent(SharedTypePools.simpleCache());
    HierarchyMatchers.registerIfAbsent(HierarchyMatchers.simpleChecks());
  }

  /** Prints all known integrations in alphabetical order. */
  public static void printIntegrationNames() {
    Set<String> names = new TreeSet<>();
    for (Instrumenter instrumenter : Instrumenters.load(Instrumenter.class.getClassLoader())) {
      if (instrumenter instanceof Instrumenter.Default) {
        names.add(((Instrumenter.Default) instrumenter).name());
      }
    }
    for (String name : names) {
      System.out.println(name);
    }
  }

  /**
   * Sends sample traces at a regular interval for diagnostic purposes.
   *
   * @param count how many traces to send, negative means send forever
   * @param interval the interval (in seconds) to wait for each trace
   */
  public static void sendSampleTraces(final int count, final double interval) {
    Agent.start(null, Agent.class.getProtectionDomain().getCodeSource().getLocation());

    int numTraces = 0;
    while (++numTraces <= count || count < 0) {
      AgentSpan span = AgentTracer.startSpan("sample");
      try {
        Thread.sleep(Math.max((long) (1000.0 * interval), 1L));
      } catch (InterruptedException ignore) {
      } finally {
        span.finish();
      }
      if (count < 0) {
        System.out.print("... completed " + numTraces + (numTraces < 2 ? " trace\r" : " traces\r"));
      } else {
        System.out.print("... completed " + numTraces + "/" + count + " traces\r");
      }
    }
  }

  public static void uploadCrash(final String[] args) throws Exception {
    List<InputStream> files = new ArrayList<>();
    for (String arg : args) {
      try {
        files.add(new FileInputStream(arg));
      } catch (FileNotFoundException | SecurityException e) {
        log.error("Failed to open {}", arg, e);
      }
    }

    new CrashUploader().upload(files);
  }
}
