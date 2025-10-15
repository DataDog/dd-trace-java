package datadog.trace.agent.tooling;

import static datadog.crashtracking.ConfigManager.readConfig;

import datadog.crashtracking.ConfigManager;
import datadog.crashtracking.CrashUploader;
import datadog.crashtracking.OOMENotifier;
import datadog.trace.agent.tooling.bytebuddy.SharedTypePools;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import datadog.trace.agent.tooling.profiler.EnvironmentChecker;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.Agent;
import datadog.trace.bootstrap.InitializationTelemetry;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
  @SuppressForbidden
  public static void printIntegrationNames() {
    Set<String> names = new TreeSet<>();
    for (InstrumenterModule module : InstrumenterIndex.readIndex().modules()) {
      names.add(module.name());
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
  @SuppressForbidden
  public static void sendSampleTraces(final int count, final double interval) throws Exception {
    Agent.startDatadogTracer(InitializationTelemetry.noOpInstance());

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

  public static void uploadCrash(final String configFile, final String... files) throws Exception {
    String error = null;
    ConfigManager.StoredConfig storedConfig = null;
    if (configFile != null) {
      Path configPath = Paths.get(configFile);
      if (!Files.exists(configPath)) {
        log.error("Config file {} does not exist", configFile);
        error = "Config file does not exist";
      }
      storedConfig = readConfig(Config.get(), configPath);
      if (storedConfig == null) {
        log.error("Unable to parse config file {}", configFile);
        error += "Unable to parse config file";
      }
    }
    if (storedConfig == null) {
      // if the PID is not provided, the config file will be null
      storedConfig = new ConfigManager.StoredConfig.Builder(Config.get()).build();
    }

    final CrashUploader crashUploader = new CrashUploader(storedConfig);
    // send the crash ping
    crashUploader.notifyCrashStarted(error);

    if (error != null) {
      System.exit(1);
    }

    List<Path> paths = new ArrayList<>(files.length);
    for (String file : files) {
      final Path path = Paths.get(file);
      if (!Files.exists(path)) {
        log.error("Crash log {} does not exist", file);
        System.exit(1);
      }
      paths.add(path);
    }
    crashUploader.upload(paths);
  }

  public static void sendOomeEvent(String taglist) throws Exception {
    OOMENotifier.sendOomeEvent(taglist);
  }

  @SuppressForbidden
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

  public static void checkProfilerEnv(String temp) {
    if (!EnvironmentChecker.checkEnvironment(temp)) {
      System.exit(1);
    }
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
                      log.error("Error deleting temp file: {}", temp.getAbsolutePath());
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
