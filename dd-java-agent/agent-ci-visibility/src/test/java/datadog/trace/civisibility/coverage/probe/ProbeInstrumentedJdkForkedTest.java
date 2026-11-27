package datadog.trace.civisibility.coverage.probe;

import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.FIXTURES;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.await;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.referenceLinesOfEachWorker;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.ContextScope;
import datadog.trace.api.civisibility.coverage.ProbeRecorder;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.civisibility.domain.TestContextImpl;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.utility.JavaModule;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

/**
 * Probed code that runs from inside JDK methods on a test's threads, as a mocking library's advice
 * does once the library instruments a JDK class. Each case instruments one JDK class so that every
 * method entry on a watched thread runs {@link AdviceDispatch#hook}, which has probes. The recorder
 * must not call itself through that code, and the test's window must get exactly the lines of the
 * code the test ran.
 */
class ProbeInstrumentedJdkForkedTest {

  private static final String DISPATCH = "datadog.trace.civisibility.coverage.probe.AdviceDispatch";
  private static final String TARGET =
      "datadog.trace.civisibility.coverage.probe.fixtures.AdviceTarget";
  private static final String TARGET_SOURCE =
      "datadog/trace/civisibility/coverage/probe/fixtures/AdviceTarget.java";

  /**
   * The deepest nesting of advice calls that the recorder allows: the hook runs from a JDK method,
   * and once more from a JDK method of the owner lookup that the hook's probes start.
   */
  private static final int ALLOWED_DEPTH = 2;

  /** Reference lines of each worker run alone. */
  private static final List<Map<String, BitSet>> WORKER_LINES = new ArrayList<>();

  private static final List<Throwable> FAILURES = new CopyOnWriteArrayList<>();

  @TempDir static File injectionFolder;

  private static Instrumentation instrumentation;
  private static Class<?> dispatch;
  private static AgentTracer.TracerAPI original;
  private static CoreTracer tracer;

  @BeforeAll
  static void setUp() throws Exception {
    System.out.println("java.version " + System.getProperty("java.version"));
    WORKER_LINES.addAll(referenceLinesOfEachWorker());
    original = AgentTracer.get();
    tracer = CoreTracer.builder().writer(new ListWriter()).build();
    AgentTracer.forceRegister(tracer);
    instrumentation = ByteBuddyAgent.install();
    ClassInjector.UsingInstrumentation.of(
            injectionFolder, ClassInjector.UsingInstrumentation.Target.BOOTSTRAP, instrumentation)
        .injectRaw(singletonMap(DISPATCH, classFile(DISPATCH)));
    dispatch = Class.forName(DISPATCH, true, null);
    Object target =
        Class.forName(TARGET, true, new FixtureLoader(null, TARGET)).getConstructor().newInstance();
    dispatch.getField("hook").set(null, target);
    // loads and links what the scenarios run before any JDK class is instrumented
    for (Scenario scenario : Scenario.values()) {
      scenario.run(loadedWorkload());
    }
  }

  @AfterAll
  static void tearDown() {
    AgentTracer.forceRegister(original);
    tracer.close();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "java.lang.ThreadLocal",
        "java.lang.InheritableThreadLocal",
        "java.lang.Thread",
        "java.lang.ref.Reference",
        "java.util.ArrayList",
        "java.util.Collections",
        "java.util.concurrent.ConcurrentHashMap",
        "java.util.Arrays",
        "java.lang.Long"
      })
  void probedCodeThatJdkMethodsRunNeitherRecursesNorChangesTheLines(String jdkClass)
      throws Exception {
    List<String> transformed = new CopyOnWriteArrayList<>();
    List<Throwable> errors = new CopyOnWriteArrayList<>();
    ResettableClassFileTransformer transformer = instrument(jdkClass, transformed, errors);
    List<Outcome> outcomes = new ArrayList<>();
    try {
      assertEquals(emptyList(), errors);
      assertEquals(singletonList(jdkClass), transformed);
      for (Scenario scenario : Scenario.values()) {
        outcomes.add(check(jdkClass, scenario));
      }
    } finally {
      transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
    }

    assertEquals(emptyList(), FAILURES);
    for (Outcome outcome : outcomes) {
      assertTrue(
          outcome.maxDepth <= ALLOWED_DEPTH,
          outcome + ": the recorder ran its own probes through the instrumented class");
      assertEquals(WORKER_LINES.get(outcome.scenario.worker), outcome.lines, outcome.toString());
    }
    if (jdkClass.equals("java.lang.ThreadLocal")) {
      for (Outcome outcome : outcomes) {
        // every scenario registers a new thread, which reads a thread local
        assertTrue(outcome.hookRuns > 0, outcome + ": the hook never ran");
        assertTrue(outcome.dropped > 0, outcome + ": no entry inside the recorder was dropped");
      }
      // on a thread with its own window, only the recorder reads thread locals
      assertFalse(outcomes.get(0).adviceLines, outcomes.get(0) + ": the hook's lines were kept");
    }
  }

  /**
   * The recorder's work on a busy thread can throw: a JDK method that a mocking library instruments
   * can, and so can a StackOverflowError or an OutOfMemoryError. Each case throws once from the
   * thread local that the recorder reads in such work: when it registers a thread, or when a thread
   * creates another. The thread must not stay busy, and the hits of the code it runs next must
   * count.
   */
  @TableTest({
    "Scenario                                       | Recorder Method    | Error             ",
    "stack overflow while registering a thread      | register           | StackOverflowError",
    "out of memory while registering a thread       | register           | OutOfMemoryError  ",
    "stack overflow while a thread creates a thread | windowForNewThread | StackOverflowError",
    "out of memory while a thread creates a thread  | windowForNewThread | OutOfMemoryError  "
  })
  void anErrorInTheRecordersWorkLeavesTheThreadRecording(String recorderMethod, String error)
      throws Exception {
    Runnable hook = (Runnable) dispatch.getField("hook").get(null);
    ThrowOnce throwOnce = new ThrowOnce(recorderMethod, error, hook);
    ClassLoader loader = loadedWorkload();
    List<String> transformed = new CopyOnWriteArrayList<>();
    List<Throwable> errors = new CopyOnWriteArrayList<>();
    ResettableClassFileTransformer transformer =
        instrument("java.lang.ThreadLocal", transformed, errors);
    Run run;
    try {
      assertEquals(emptyList(), errors);
      assertEquals(singletonList("java.lang.ThreadLocal"), transformed);
      dispatch.getField("hook").set(null, throwOnce);
      run =
          recorderMethod.equals("register")
              ? registrationThrows(loader)
              : threadCreationThrows(loader, hook);
    } finally {
      dispatch.getField("hook").set(null, hook);
      transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
    }

    assertEquals(emptyList(), FAILURES);
    assertEquals(
        1, throwOnce.thrown.get(), error + " thrown inside ProbeRecorder." + recorderMethod);
    Object threadProbes = registeredFor(run.thread);
    assertNotNull(threadProbes, "the thread has no probe arrays");
    assertFalse(call(threadProbes, "isBusy"), "the thread stayed busy");
    assertTrue(call(threadProbes, "isAttached"), "the thread's arrays were not attached to it");
    run.lines.remove(TARGET_SOURCE);
    assertEquals(WORKER_LINES.get(run.worker), run.lines);
  }

  /**
   * A thread created while a window is open runs a worker twice. Registering the thread throws at
   * its first probe, whose hits are lost; the second run has them again.
   */
  private static Run registrationThrows(ClassLoader loader) throws Exception {
    TestWindow window = new TestWindow();
    Thread thread =
        new Thread(() -> {
          ConcurrentWorkloads.run(loader, 1);
          ConcurrentWorkloads.run(loader, 1);
        });
    startWatched(thread);
    finish(thread);
    return new Run(thread, 1, window.closeAndCollect());
  }

  /**
   * A thread with no window runs code for the test whose span it activates. It creates a thread,
   * for which the recorder looks up the window to hand, and the lookup throws. Then it runs a
   * worker.
   */
  private static Run threadCreationThrows(ClassLoader loader, Runnable hook) throws Exception {
    CountDownLatch opened = new CountDownLatch(1);
    AtomicReference<AgentSpan> span = new AtomicReference<>();
    Thread thread =
        new Thread(() -> {
          await(opened);
          try (ContextScope scope = tracer.activateSpan(span.get())) {
            // registers the thread, with lines that are left out
            hook.run();
            new Thread(() -> {});
            ConcurrentWorkloads.run(loader, 2);
          }
        });
    startWatched(thread);
    TestWindow window = new TestWindow();
    span.set(testSpan(new TestContextImpl(window)));
    try {
      opened.countDown();
      finish(thread);
    } finally {
      span.get().finish();
    }
    return new Run(thread, 2, window.closeAndCollect());
  }

  /** The probe arrays registered for a thread, found without registering any. */
  private static Object registeredFor(Thread thread) throws ReflectiveOperationException {
    Method method = ProbeRecorder.class.getDeclaredMethod("registeredFor", long.class);
    method.setAccessible(true);
    return method.invoke(null, thread.getId());
  }

  private static boolean call(Object threadProbes, String methodName)
      throws ReflectiveOperationException {
    Method method = threadProbes.getClass().getDeclaredMethod(methodName);
    method.setAccessible(true);
    return (Boolean) method.invoke(threadProbes);
  }

  private static Outcome check(String jdkClass, Scenario scenario) throws Exception {
    ClassLoader loader = loadedWorkload();
    dispatch.getField("maxDepth").setInt(null, 0);
    dispatch.getField("calls").setInt(null, 0);
    long droppedBefore = ProbeRecorder.getDroppedEntryCount();
    Map<String, BitSet> lines = scenario.run(loader);
    boolean adviceLines = lines.remove(TARGET_SOURCE) != null;
    Outcome outcome =
        new Outcome(
            jdkClass,
            scenario,
            dispatch.getField("maxDepth").getInt(null),
            dispatch.getField("calls").getInt(null),
            ProbeRecorder.getDroppedEntryCount() - droppedBefore,
            adviceLines,
            lines);
    System.out.println(outcome);
    return outcome;
  }

  /** A fixture loader whose workload class is loaded, so that the scenarios load no class. */
  private static ClassLoader loadedWorkload() throws ClassNotFoundException {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    Class.forName(FIXTURES, true, loader);
    return loader;
  }

  private enum Scenario {
    /** A thread opens a window and runs a worker. */
    WINDOW_ON_THE_THREAD(0) {
      @Override
      Map<String, BitSet> run(ClassLoader loader) throws Exception {
        AtomicReference<Map<String, BitSet>> lines = new AtomicReference<>();
        Thread thread =
            new Thread(() -> {
              TestWindow window = new TestWindow();
              ConcurrentWorkloads.run(loader, worker);
              lines.set(window.closeAndCollect());
            });
        startWatched(thread);
        finish(thread);
        return lines.get();
      }
    },

    /** A thread with no window runs a worker for the test whose span it activates. */
    TASK_OF_A_TEST(2) {
      @Override
      Map<String, BitSet> run(ClassLoader loader) throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        AtomicReference<AgentSpan> span = new AtomicReference<>();
        // created before the window opens, so it is handed none
        Thread thread =
            new Thread(() -> {
              await(opened);
              try (ContextScope scope = tracer.activateSpan(span.get())) {
                ConcurrentWorkloads.run(loader, worker);
              }
            });
        startWatched(thread);
        TestWindow window = new TestWindow();
        span.set(testSpan(new TestContextImpl(window)));
        try {
          opened.countDown();
          finish(thread);
        } finally {
          span.get().finish();
        }
        return window.closeAndCollect();
      }
    },

    /** A thread created while a window is open, so handed it, runs a worker. */
    HANDED_WINDOW(1) {
      @Override
      Map<String, BitSet> run(ClassLoader loader) throws Exception {
        TestWindow window = new TestWindow();
        Thread thread = new Thread(() -> ConcurrentWorkloads.run(loader, worker));
        startWatched(thread);
        finish(thread);
        return window.closeAndCollect();
      }
    };

    final int worker;

    Scenario(int worker) {
      this.worker = worker;
    }

    abstract Map<String, BitSet> run(ClassLoader loader) throws Exception;
  }

  /** Makes the advice run the hook on {@code thread} from its start, and starts it. */
  private static void startWatched(Thread thread) throws ReflectiveOperationException {
    thread.setUncaughtExceptionHandler((failed, e) -> FAILURES.add(e));
    dispatch.getField("watched").set(null, new Thread[] {thread});
    thread.start();
  }

  private static void finish(Thread thread) throws Exception {
    try {
      thread.join(TimeUnit.SECONDS.toMillis(30));
    } finally {
      dispatch.getField("watched").set(null, new Thread[0]);
    }
    assertFalse(thread.isAlive(), "the watched thread did not end");
  }

  private static AgentSpan testSpan(TestContextImpl context) {
    return tracer
        .buildSpan("test", "test")
        .ignoreActiveSpan()
        .withRequestContextData(RequestContextSlot.CI_VISIBILITY, context)
        .start();
  }

  /** Makes every method and constructor of a JDK class call {@link AdviceDispatch#enter}. */
  private static ResettableClassFileTransformer instrument(
      String jdkClass, List<String> transformed, List<Throwable> errors) {
    return new AgentBuilder.Default()
        .disableClassFormatChanges()
        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
        .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
        .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
        .with(
            new AgentBuilder.Listener.Adapter() {
              @Override
              public void onTransformation(
                  TypeDescription type,
                  ClassLoader loader,
                  JavaModule module,
                  boolean loaded,
                  DynamicType dynamicType) {
                transformed.add(type.getName());
              }

              @Override
              public void onError(
                  String typeName,
                  ClassLoader loader,
                  JavaModule module,
                  boolean loaded,
                  Throwable throwable) {
                errors.add(throwable);
              }
            })
        .ignore(none())
        .type(named(jdkClass))
        .transform((builder, type, loader, module, domain) ->
            builder.visit(
                Advice.to(EntryAdvice.class)
                    .on(
                        isMethod()
                            .and(not(isAbstract()))
                            .and(not(isNative()))
                            .or(isConstructor()))))
        .installOn(instrumentation);
  }

  /** Read as bytes, so that the application class loader never defines the dispatcher. */
  private static byte[] classFile(String className) throws IOException {
    String resource = className.replace('.', '/') + ".class";
    try (InputStream in =
        ProbeInstrumentedJdkForkedTest.class.getClassLoader().getResourceAsStream(resource)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      for (int read; (read = in.read(buffer)) != -1; ) {
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    }
  }

  /** Runs the hook, but throws an error once from inside a method of the recorder. */
  private static final class ThrowOnce implements Runnable {
    private final String recorderMethod;
    private final String error;
    private final Runnable hook;
    final AtomicInteger thrown = new AtomicInteger();

    ThrowOnce(String recorderMethod, String error, Runnable hook) {
      this.recorderMethod = recorderMethod;
      this.error = error;
      this.hook = hook;
    }

    @Override
    public void run() {
      if (thrown.get() == 0 && inRecorderMethod() && thrown.compareAndSet(0, 1)) {
        throw error.equals("OutOfMemoryError")
            ? new OutOfMemoryError("thrown by the test")
            : new StackOverflowError("thrown by the test");
      }
      hook.run();
    }

    private boolean inRecorderMethod() {
      for (StackTraceElement frame : new Throwable().getStackTrace()) {
        if (frame.getMethodName().equals(recorderMethod)
            && frame.getClassName().equals(ProbeRecorder.class.getName())) {
          return true;
        }
      }
      return false;
    }
  }

  private static final class Run {
    final Thread thread;
    final int worker;
    final Map<String, BitSet> lines;

    Run(Thread thread, int worker, Map<String, BitSet> lines) {
      this.thread = thread;
      this.worker = worker;
      this.lines = lines;
    }
  }

  static final class EntryAdvice {
    @Advice.OnMethodEnter
    static void enter() {
      AdviceDispatch.enter();
    }
  }

  private static final class Outcome {
    final String jdkClass;
    final Scenario scenario;
    final int maxDepth;
    final int hookRuns;
    final long dropped;
    final boolean adviceLines;
    final Map<String, BitSet> lines;

    Outcome(
        String jdkClass,
        Scenario scenario,
        int maxDepth,
        int hookRuns,
        long dropped,
        boolean adviceLines,
        Map<String, BitSet> lines) {
      this.jdkClass = jdkClass;
      this.scenario = scenario;
      this.maxDepth = maxDepth;
      this.hookRuns = hookRuns;
      this.dropped = dropped;
      this.adviceLines = adviceLines;
      this.lines = lines;
    }

    @Override
    public String toString() {
      return jdkClass
          + " "
          + scenario
          + ": depth "
          + maxDepth
          + ", hook runs "
          + hookRuns
          + ", dropped "
          + dropped
          + ", hook lines kept "
          + adviceLines;
    }
  }
}
