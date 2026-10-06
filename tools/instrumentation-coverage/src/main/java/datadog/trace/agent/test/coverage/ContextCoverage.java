package datadog.trace.agent.test.coverage;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import datadog.context.Context;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarFile;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

/** Experimental method-entry observations of generic Context identity. */
public final class ContextCoverage implements AutoCloseable {
  private static final int DEFAULT_MAX_ROWS = 10000;
  private static final ThreadLocal<Scenario> SCENARIO = new ThreadLocal<>();
  private static final ThreadLocal<Boolean> RECORDING = new ThreadLocal<>();
  private static volatile ContextCoverage active;
  private static JarFile bootstrapBridgeJar;
  private static final ElementMatcher.Junction<MethodDescription> ELIGIBLE =
      isMethod()
          .and(not(isAbstract()))
          .and(not(isNative()))
          .and(not(isSynthetic()))
          .and(not(isBridge()));
  private static final String SEMANTICS =
      "Method-entry observations only; NON_ROOT_CONTEXT means Context.current() != Context.root(). "
          + "ROOT_CONTEXT is a review candidate, not a bug: empty and suppressed context are "
          + "indistinguishable. No timing or whole-method coverage. Scenarios are explicit "
          + "thread-local or serialized active-test-window labels, never context propagation.";

  private final Instrumentation instrumentation;
  private final HandoffTracker handoffTracker = new HandoffTracker();
  private final Map<String, Map<String, Observation>> methods = new LinkedHashMap<>();
  private final Set<String> executed = new LinkedHashSet<>();
  private final List<Map<String, Object>> targets = new ArrayList<>();
  private final List<String> errors = new ArrayList<>();
  private final Map<String, Object> execution = new LinkedHashMap<>();
  private final Set<String> transformed = new LinkedHashSet<>();
  private final int maxRows;
  private ResettableClassFileTransformer transformer;
  private BootstrapContextCoverageBridge.Listener bootstrapListener;
  private volatile boolean collecting = true;
  private boolean closed;
  private boolean finalized;
  private final Map<String, Scenario> scenarios = new LinkedHashMap<>();
  private Scenario activeScenario;
  private int rows;
  private long dropped;
  private long contextEntries;
  private long rootEntries;

  private ContextCoverage(Instrumentation instrumentation) {
    this.instrumentation = instrumentation;
    this.maxRows = Integer.getInteger("test.context.coverage.max-rows", DEFAULT_MAX_ROWS);
    if (maxRows <= 0) {
      throw new IllegalArgumentException("test.context.coverage.max-rows must be positive");
    }
  }

  /** Observes exact, already-loaded classes using the Byte Buddy premain agent. */
  public static synchronized ContextCoverage observe(Class<?>... targetClasses) {
    if (active != null) {
      throw new IllegalStateException("Only one ContextCoverage session may be active");
    }
    Objects.requireNonNull(targetClasses, "targets");
    if (targetClasses.length == 0) {
      throw new IllegalArgumentException("Supply at least one target class");
    }
    Instrumentation instrumentation = ByteBuddyAgent.getInstrumentation();
    if (!instrumentation.isRetransformClassesSupported()) {
      throw new IllegalStateException("Retransformation is unavailable");
    }
    ContextCoverage session = new ContextCoverage(instrumentation);
    Set<String> names = new LinkedHashSet<>();
    boolean observesBootstrap = false;
    for (Class<?> target : targetClasses) {
      Objects.requireNonNull(target, "target class");
      ClassLoader targetLoader = target.getClassLoader();
      if ((targetLoader != null && targetLoader != ContextCoverage.class.getClassLoader())
          || target.getName().startsWith("datadog.context.")
          || target.getName().startsWith(ContextCoverage.class.getPackage().getName() + ".")
          || !instrumentation.isModifiableClass(target)) {
        throw new IllegalArgumentException(
            "Target must be modifiable, outside datadog.context, and use either bootstrap or the "
                + "collector's defining loader: "
                + target.getName());
      }
      observesBootstrap |= targetLoader == null;
      if (!names.add(target.getName())) {
        continue;
      }
      CodeSource source = target.getProtectionDomain().getCodeSource();
      session.targets.add(
          map(
              "class",
              target.getName(),
              "artifact",
              source == null ? "unknown" : source.getLocation().toExternalForm(),
              "classLoader",
              targetLoader == null ? "bootstrap" : targetLoader.toString()));
      for (MethodDescription method :
          new TypeDescription.ForLoadedType(target).getDeclaredMethods()) {
        if (ELIGIBLE.matches(method)) {
          session.methods.put(
              target.getName() + "." + method.getInternalName() + method.getDescriptor(),
              new LinkedHashMap<>());
        }
      }
    }
    try {
      if (observesBootstrap) {
        session.installBootstrapBridge(targetClasses);
      }
      new AgentBuilder.Default()
          .disableClassFormatChanges()
          .ignore(none())
          .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
          .with(
              new AgentBuilder.InstallationListener.Adapter() {
                @Override
                public void onBeforeInstall(
                    Instrumentation ignored, ResettableClassFileTransformer installed) {
                  session.transformer = installed;
                }
              })
          .with(
              new AgentBuilder.Listener.Adapter() {
                @Override
                public void onTransformation(
                    TypeDescription type,
                    ClassLoader loader,
                    JavaModule module,
                    boolean loaded,
                    DynamicType dynamicType) {
                  synchronized (session) {
                    session.transformed.add(type.getName());
                  }
                }

                @Override
                public void onError(
                    String type,
                    ClassLoader loader,
                    JavaModule module,
                    boolean loaded,
                    Throwable error) {
                  session.recordError(type + ": " + error);
                }
              })
          .type(
              namedOneOf(names.toArray(new String[0])),
              loader -> loader == null || loader == ContextCoverage.class.getClassLoader())
          .transform(
              (builder, type, loader, module, domain) ->
                  builder.visit(
                      Advice.to(loader == null ? BootstrapEntryAdvice.class : EntryAdvice.class)
                          .on(ELIGIBLE)))
          .installOn(instrumentation);
      if (!session.errors.isEmpty() || !session.transformed.containsAll(names)) {
        throw new IllegalStateException("Incomplete transformation: " + session.errors);
      }
      active = session;
      return session;
    } catch (RuntimeException | Error failure) {
      try {
        session.close();
      } catch (RuntimeException | Error cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  private void installBootstrapBridge(Class<?>[] targets) {
    String bridgeJar = System.getProperty("test.context.coverage.bootstrap-jar", "").trim();
    if (bridgeJar.isEmpty()) {
      throw new IllegalStateException("Bootstrap observation requires the coverage bridge jar");
    }
    if (bootstrapBridgeJar == null) {
      try {
        bootstrapBridgeJar = new JarFile(Paths.get(bridgeJar).toFile());
        instrumentation.appendToBootstrapClassLoaderSearch(bootstrapBridgeJar);
        Class<?> bridge = Class.forName(BootstrapContextCoverageBridge.class.getName(), true, null);
        if (bridge.getClassLoader() != null) {
          throw new IllegalStateException("Coverage bridge was not loaded by bootstrap");
        }
      } catch (IOException | ClassNotFoundException failure) {
        throw new IllegalStateException("Coverage bridge is unavailable to bootstrap", failure);
      }
    }
    bootstrapListener = ContextCoverage::observeEntry;
    BootstrapContextCoverageBridge.install(bootstrapListener);
    if (JavaModule.isSupported()) {
      Module bridgeModule = BootstrapContextCoverageBridge.class.getModule();
      for (Class<?> target : targets) {
        Module targetModule = target.getModule();
        if (target.getClassLoader() == null
            && targetModule.isNamed()
            && !targetModule.canRead(bridgeModule)) {
          instrumentation.redefineModule(
              targetModule, Set.of(bridgeModule), Map.of(), Map.of(), Set.of(), Map.of());
        }
      }
    }
  }

  /** Labels only this thread and restores its previous label even when work throws. */
  public static void inScenario(String name, Runnable work) {
    inScenario(name, name, work);
  }

  /** Labels this thread with a stable scenario identity and a separate human-readable name. */
  public static void inScenario(String id, String name, Runnable work) {
    Objects.requireNonNull(id, "scenario id");
    Objects.requireNonNull(name, "scenario name");
    Objects.requireNonNull(work, "work");
    Scenario previous = SCENARIO.get();
    SCENARIO.set(new Scenario(id, name));
    try {
      work.run();
    } finally {
      if (previous == null) {
        SCENARIO.remove();
      } else {
        SCENARIO.set(previous);
      }
    }
  }

  /** Excludes harness setup and cleanup without removing probes. */
  public synchronized void pause() {
    collecting = false;
    activeScenario = null;
  }

  /** Starts a test execution window without installing Context or task attribution. */
  public synchronized void resume() {
    if (closed) {
      throw new IllegalStateException("Context coverage collection has closed");
    }
    activeScenario = null;
    collecting = true;
  }

  /** Starts a serialized test window used only to attribute otherwise-unlabeled worker entries. */
  public synchronized void resume(String scenario) {
    resume(scenario, scenario);
  }

  /** Starts a serialized test window with stable identity and a separate display name. */
  public synchronized void resume(String scenarioId, String scenarioName) {
    if (closed) {
      throw new IllegalStateException("Context coverage collection has closed");
    }
    activeScenario = registerScenario(new Scenario(scenarioId, scenarioName));
    collecting = true;
  }

  /** Records the harness and agent state used for this report. */
  public synchronized void metadata(String name, Object value) {
    execution.put(name, value);
  }

  /**
   * Wraps a one-shot test task with independent attribution; never captures or attaches Context.
   */
  public Runnable handoff(String boundary, String scenario, Runnable work) {
    return handoffTracker.observe(boundary, scenario, work);
  }

  /** Declares an intentionally paired workload, with propagation changed only in the fixture. */
  public void compareHandoff(String name, String baselineScenario, String interventionScenario) {
    handoffTracker.compare(name, baselineScenario, interventionScenario);
  }

  /** Public helper called by transformed code; no Context is retained or attached. */
  public static void observeEntry(String method) {
    ContextCoverage session = active;
    if (session != null && session.collecting && !Boolean.TRUE.equals(RECORDING.get())) {
      RECORDING.set(Boolean.TRUE);
      try {
        session.record(method, Context.current() != Context.root());
      } catch (Throwable failure) {
        session.recordError("Observation failed: " + failure);
      } finally {
        RECORDING.remove();
      }
    }
  }

  private synchronized void recordError(String error) {
    if (errors.size() < 100) {
      errors.add(error);
    }
  }

  private synchronized void record(String method, boolean nonRoot) {
    if (closed || !collecting) {
      return;
    }
    Map<String, Observation> observations = methods.get(method);
    if (observations == null) {
      recordError("Unexpected method: " + method);
      return;
    }
    executed.add(method);
    if (nonRoot) {
      contextEntries++;
    } else {
      rootEntries++;
    }
    Scenario scenario = SCENARIO.get();
    String attribution = "THREAD_LOCAL";
    String attributionConfidence = "EXACT";
    if (scenario == null) {
      if (activeScenario == null) {
        scenario = new Scenario("<unattributed>", "<unattributed>");
        attribution = "UNATTRIBUTED";
        attributionConfidence = "NONE";
      } else {
        scenario = activeScenario;
        attribution = "ACTIVE_TEST_WINDOW";
        attributionConfidence = "TEMPORAL";
      }
    }
    scenario = registerScenario(scenario);
    String state = nonRoot ? "NON_ROOT_CONTEXT" : "ROOT_CONTEXT";
    String handoffId = HandoffTracker.currentId();
    String key =
        handoffId
            + "|"
            + state
            + ":"
            + scenario.id
            + ":"
            + attribution
            + ":"
            + attributionConfidence;
    Observation observation = observations.get(key);
    if (observation == null) {
      if (rows >= maxRows) {
        dropped++;
        return;
      }
      observation = new Observation(scenario, state, handoffId, attribution, attributionConfidence);
      observations.put(key, observation);
      rows++;
    }
    observation.count++;
  }

  /** Writes a coherent snapshot. Call after close for a final, reset-verified report. */
  public synchronized void writeReport(Path directory) throws IOException {
    if (!errors.isEmpty()) {
      throw new IllegalStateException("Cannot publish successful coverage: " + errors);
    }
    List<Map<String, Object>> methodRows = new ArrayList<>();
    StringBuilder markdown =
        new StringBuilder("# Context entry coverage\n\n")
            .append(SEMANTICS)
            .append("\n\nMethods exercised: ")
            .append(executed.size())
            .append(" / ")
            .append(methods.size())
            .append("; non-root context entries: ")
            .append(contextEntries)
            .append("; root context entries: ")
            .append(rootEntries)
            .append("\n\nDropped observations: ")
            .append(dropped)
            .append(
                "\nCounts in the table exclude dropped observations; summary totals include them.\n")
            .append("\n| Method | Entries | Non-root | Root | Status |\n")
            .append("| --- | ---: | ---: | ---: | --- |\n");
    if ("instrumentation-test".equals(execution.get("mode"))) {
      markdown.insert(
          markdown.indexOf("\n\n") + 2,
          "Execution: existing instrumentation test `"
              + escape(String.valueOf(execution.get("specName")))
              + "`; agent verified: "
              + execution.get("agentInstalled")
              + "; Context loader: "
              + execution.get("contextClassLoader")
              + ". No synthetic propagation intervention.\n\n");
    } else {
      markdown.insert(
          markdown.indexOf("\n\n") + 2,
          "Synthetic collector validation without the Datadog agent; not an instrumentation finding.\n\n");
    }
    StringBuilder findings = new StringBuilder();
    for (Map.Entry<String, Map<String, Observation>> method : methods.entrySet()) {
      List<Observation> observations = new ArrayList<>(method.getValue().values());
      methodRows.add(map("method", method.getKey(), "observations", observations));
      long context = 0;
      long root = 0;
      for (Observation observation : observations) {
        if (observation.state.equals("NON_ROOT_CONTEXT")) {
          context += observation.count;
        } else {
          root += observation.count;
          findings
              .append("\n### Root-context candidate: ")
              .append(escape(method.getKey()))
              .append("\n\nScenario: ")
              .append(escape(observation.scenario))
              .append("; entries: ")
              .append(observation.count)
              .append("; representative thread: ")
              .append(observation.threadId)
              .append(" (")
              .append(escape(observation.threadName))
              .append(")\n\n");
          for (String frame : observation.stack) {
            findings.append("    ").append(frame).append('\n');
          }
        }
      }
      markdown
          .append("| ")
          .append(escape(method.getKey()))
          .append(" | ")
          .append(context + root)
          .append(" | ")
          .append(context)
          .append(" | ")
          .append(root)
          .append(" | ")
          .append(executed.contains(method.getKey()) ? "OBSERVED" : "NEVER_OBSERVED")
          .append(" |\n");
    }
    Map<String, Object> report =
        map(
            "schemaVersion",
            4,
            "finalized",
            finalized,
            "semantics",
            SEMANTICS,
            "javaVersion",
            System.getProperty("java.version"),
            "targets",
            targets,
            "execution",
            new LinkedHashMap<>(execution),
            "scenarios",
            new ArrayList<>(scenarios.values()),
            "health",
            map(
                "errors",
                new ArrayList<>(errors),
                "droppedObservations",
                dropped,
                "maxDistinctObservations",
                maxRows),
            "summary",
            map(
                "eligibleMethods",
                methods.size(),
                "executedMethods",
                executed.size(),
                "contextEntries",
                contextEntries,
                "rootEntries",
                rootEntries),
            "methods",
            methodRows);
    handoffTracker.addTo(report);
    Files.createDirectories(directory);
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    JsonObject document = gson.toJsonTree(report).getAsJsonObject();
    HandoffAnalysis.analyze(document);
    String json = gson.toJson(document);
    Files.write(directory.resolve("report.json"), json.getBytes(StandardCharsets.UTF_8));
    // Gson versions on instrumentation test classpaths can predate JsonArray.isEmpty().
    if (document.getAsJsonArray("opportunities").size() > 0) {
      markdown.append("\n## Propagation opportunities\n\n");
      for (JsonElement entry : document.getAsJsonArray("opportunities")) {
        JsonObject opportunity = entry.getAsJsonObject();
        markdown
            .append("- ")
            .append(escape(opportunity.get("name").getAsString()))
            .append(": ")
            .append(opportunity.get("status").getAsString())
            .append("; downstream methods: ")
            .append(opportunity.get("affectedMethodCount").getAsInt())
            .append("; fully recovered methods: ")
            .append(opportunity.get("recoveredMethodCount").getAsInt())
            .append("; recovered entries: ")
            .append(opportunity.get("recoveredEntries").getAsLong())
            .append(". ")
            .append(opportunity.get("reason").getAsString())
            .append('\n');
      }
    }
    Files.write(
        directory.resolve("report.md"),
        markdown.append(findings).toString().getBytes(StandardCharsets.UTF_8));
    HtmlReport.write(directory.resolve("report.html"), json);
  }

  @Override
  public void close() {
    synchronized (ContextCoverage.class) {
      synchronized (this) {
        if (closed) {
          return;
        }
        closed = true;
        collecting = false;
        handoffTracker.seal();
      }
      try {
        if (transformer != null
            && !transformer.reset(
                instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)) {
          throw new IllegalStateException("Could not reset ContextCoverage transformer");
        }
        synchronized (this) {
          if (!errors.isEmpty()) {
            throw new IllegalStateException("Unhealthy ContextCoverage session: " + errors);
          }
          finalized = true;
        }
      } catch (RuntimeException | Error failure) {
        recordError("Reset failed: " + failure);
        throw failure;
      } finally {
        if (bootstrapListener != null) {
          BootstrapContextCoverageBridge.clear(bootstrapListener);
          bootstrapListener = null;
        }
        if (active == this) {
          active = null;
        }
      }
    }
  }

  private static String escape(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("|", "&#124;")
        .replace("\n", " ")
        .replace("\r", " ");
  }

  private static Map<String, Object> map(Object... entries) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < entries.length; i += 2) {
      result.put((String) entries[i], entries[i + 1]);
    }
    return result;
  }

  private Scenario registerScenario(Scenario scenario) {
    Scenario existing = scenarios.get(scenario.id);
    if (existing != null) {
      if (!existing.name.equals(scenario.name)) {
        throw new IllegalArgumentException(
            "Scenario id "
                + scenario.id
                + " has multiple names: "
                + existing.name
                + ", "
                + scenario.name);
      }
      return existing;
    }
    scenarios.put(scenario.id, scenario);
    return scenario;
  }

  private static final class Scenario {
    final String id;
    final String name;

    Scenario(String id, String name) {
      this.id = Objects.requireNonNull(id, "scenario id");
      this.name = Objects.requireNonNull(name, "scenario name");
    }
  }

  private static final class Observation {
    final String scenarioId;
    final String scenario;
    final String state;
    final String handoffId;
    final String attribution;
    final String attributionConfidence;
    long count;
    final long threadId;
    final String threadName;
    final List<String> stack = new ArrayList<>();

    Observation(
        Scenario scenario,
        String state,
        String handoffId,
        String attribution,
        String attributionConfidence) {
      this.scenarioId = scenario.id;
      this.scenario = scenario.name;
      this.handoffId = handoffId;
      this.state = state;
      this.attribution = attribution;
      this.attributionConfidence = attributionConfidence;
      Thread thread = Thread.currentThread();
      threadId = thread.getId();
      threadName = thread.getName();
      for (StackTraceElement frame : thread.getStackTrace()) {
        if (!frame.getClassName().equals(ContextCoverage.class.getName())
            && !frame.getClassName().startsWith(ContextCoverage.class.getName() + "$")
            && !frame.getClassName().equals(Thread.class.getName())) {
          stack.add(frame.toString());
          if (stack.size() == 12) {
            break;
          }
        }
      }
    }
  }

  /** Advice contains only a reference to the public, same-loader collector helper. */
  public static final class EntryAdvice {
    @Advice.OnMethodEnter
    public static void enter(@Advice.Origin("#t.#m#d") String method) {
      ContextCoverage.observeEntry(method);
    }
  }

  /** Advice for bootstrap targets; the inlined bytecode references only the bootstrap bridge. */
  public static final class BootstrapEntryAdvice {
    @Advice.OnMethodEnter
    public static void enter(@Advice.Origin("#t.#m#d") String method) {
      BootstrapContextCoverageBridge.observeEntry(method);
    }
  }
}
