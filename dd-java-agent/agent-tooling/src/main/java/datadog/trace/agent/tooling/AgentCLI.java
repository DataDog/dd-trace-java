package datadog.trace.agent.tooling;

import com.datadog.crashtracking.CrashUploader;
import datadog.trace.agent.tooling.bytebuddy.SharedTypePools;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import datadog.trace.bootstrap.Agent;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.jar.JarFile;
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
  public static void sendSampleTraces(final int count, final double interval) throws Exception {
    Agent.startDatadogTracer();

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
        new CrashUploader().upload(files);
      } catch (FileNotFoundException | SecurityException e) {
        log.error("Failed to open {}", arg, e);
        System.exit(1);
      }
    }
  }

  public static void scanDependencies(final String[] args) throws Exception {
    Class depClass =
        Class.forName(
            "datadog.telemetry.dependency.DependencyService",
            true,
            AgentCLI.class.getClassLoader());
    Object depService = depClass.getConstructor().newInstance();
    Method addUrlMethod = depService.getClass().getMethod("addURL", URL.class);
    Method resolveOne = depService.getClass().getMethod("resolveOneDependency");

    Consumer<File> invoker =
        (file) -> {
          try {
            addUrlMethod.invoke(depService, file.toURI().toURL());
            resolveOne.invoke(depService);
          } catch (Exception e) {
            log.error("Error invoking dependencies service", e);
          }
        };
    File origin = new File(args[0]);
    if (origin.isFile()) {
      recursiveDependencySearch(invoker, origin);
    } else if (origin.isDirectory()) {
      File[] files = origin.listFiles();
      for (File file : files) {
        recursiveDependencySearch(invoker, file);
      }
    } else {
      System.err.println("Invalid path found:" + origin.getAbsolutePath());
    }

    System.out.println("Scan finished");
  }

  private static void recursiveDependencySearch(Consumer<File> invoker, File origin)
      throws IOException {
    invoker.accept(origin);
    unzipJar(invoker, origin);
  }

  private static void unzipJar(Consumer<File> invoker, File file) throws IOException {
    try (JarFile jar = new JarFile(file)) {
      log.debug("Finding entries in file: {}", file.getName());

      jar.stream()
          .forEach(
              e -> {
                if (e.getName().endsWith(".jar") || e.getName().endsWith(".war")) {
                  try {
                    log.debug("Jar entry found in file: {} entry: {}", file.getName(), e.getName());
                    File temp = File.createTempFile("internal", ".jar");
                    try (InputStream is = jar.getInputStream(e);
                        OutputStream out = new FileOutputStream(temp)) {
                      int read;
                      while ((read = is.read()) != -1) {
                        out.write(read);
                      }
                    }
                    log.debug("Adding new jar: {}", temp.getAbsolutePath());
                    recursiveDependencySearch(invoker, temp);
                    if (!temp.delete()) {
                      log.error("Error deleting temp file:{}", temp.getAbsolutePath());
                    }
                  } catch (Exception ex) {
                    log.error("Error unzipping file", ex);
                  }
                } else {
                  log.debug("Entry: {} ignored in file: {}", e.getName(), file.getAbsolutePath());
                }
              });
    }
  }
}
