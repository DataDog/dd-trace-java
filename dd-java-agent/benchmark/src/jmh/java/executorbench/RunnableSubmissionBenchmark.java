package executorbench;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Phaser;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures context propagation through standard and subclassed {@link ThreadPoolExecutor}s.
 *
 * <p>The reusable, fresh, and root-context cases include a worker handoff. The overlapping case
 * blocks the worker outside the measured interval so both submissions contend for the same task's
 * ownership slot; its score covers two submissions. Allocation can be inspected with {@code -prof
 * gc} for all cases except the overlapping benchmark, whose invocation-level fixture allocation is
 * included in the profiler result.
 *
 * <p>Run from {@code dd-java-agent/benchmark} so the relative agent path resolves:
 *
 * <pre>{@code
 * java -jar build/libs/benchmark-*-jmh.jar 'RunnableSubmissionBenchmark.*' -prof gc
 * }</pre>
 *
 * <p>To compare commits with the same harness, replace {@code build/agent/dd-java-agent.jar} with
 * each commit's agent before a run. Do not rebuild the JMH jar between arms.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {
      "-javaagent:build/agent/dd-java-agent.jar",
      "-Ddd.profiling.enabled=false",
      "-Ddd.profiling.queueing.time.enabled=false",
      "-Ddd.instrumentation.telemetry.enabled=false",
      "-Ddd.remote_config.enabled=false",
      "-Ddd.jmxfetch.enabled=false"
    })
public class RunnableSubmissionBenchmark {

  @Benchmark
  public void activeReusableTask(ExecutorState state) throws InterruptedException {
    state.submitActive(state.reusableTask);
  }

  @Benchmark
  public void activeFreshTask(ExecutorState state) throws InterruptedException {
    state.submitActive(new CompletionTask(state, state.completion));
  }

  @Benchmark
  public void rootReusableTask(ExecutorState state) throws InterruptedException {
    state.submitRoot(state.reusableTask);
  }

  @Benchmark
  public void activeLambda(ExecutorState state) throws InterruptedException {
    state.submitActiveLambda();
  }

  @Benchmark
  public void overlappingReusableTask(CollisionState state) {
    state.submitOverlapping();
  }

  @State(Scope.Thread)
  public static class ExecutorState {
    @Param({"base", "delegating", "nondelegating"})
    public String executorType;

    private ThreadPoolExecutor executor;
    private Phaser completion;
    private CompletionTask reusableTask;
    private AgentSpan span;
    private volatile boolean propagationFailed;

    @Setup(Level.Trial)
    public void setup() {
      executor = newExecutor(executorType);
      executor.prestartAllCoreThreads();
      completion = new Phaser(1);
      reusableTask = new CompletionTask(this, completion);
      span = startSpan("benchmark", "runnable-submission");
    }

    @TearDown(Level.Trial)
    public void tearDown() throws InterruptedException {
      closeExecutor(executor);
      span.finish();
      if (propagationFailed) {
        throw new AssertionError("task did not receive the submitted span");
      }
    }

    private void submitActive(CompletionTask task) throws InterruptedException {
      int phase = completion.getPhase();
      task.expected = span;
      try (AgentScope ignored = activateSpan(span)) {
        executor.execute(task);
      }
      completion.awaitAdvanceInterruptibly(phase);
    }

    private void submitRoot(CompletionTask task) throws InterruptedException {
      int phase = completion.getPhase();
      task.expected = null;
      executor.execute(task);
      completion.awaitAdvanceInterruptibly(phase);
    }

    private void submitActiveLambda() throws InterruptedException {
      int phase = completion.getPhase();
      try (AgentScope ignored = activateSpan(span)) {
        executor.execute(
            () -> {
              if (activeSpan() != span) {
                propagationFailed = true;
              }
              completion.arrive();
            });
      }
      completion.awaitAdvanceInterruptibly(phase);
    }
  }

  @State(Scope.Thread)
  public static class CollisionState {
    @Param({"base", "delegating"})
    public String executorType;

    private ThreadPoolExecutor executor;
    private BlockingTask blocker;
    private CollisionTask task;
    private AgentSpan firstSpan;
    private AgentSpan secondSpan;

    @Setup(Level.Invocation)
    public void setup() throws InterruptedException {
      executor = newExecutor(executorType);
      executor.prestartAllCoreThreads();
      blocker = new BlockingTask();
      executor.execute(blocker);
      blocker.started.await();
      firstSpan = startSpan("benchmark", "first-submission");
      secondSpan = startSpan("benchmark", "second-submission");
      task = new CollisionTask(firstSpan, secondSpan);
    }

    @TearDown(Level.Invocation)
    public void tearDown() throws InterruptedException {
      blocker.release.countDown();
      task.finished.await();
      closeExecutor(executor);
      firstSpan.finish();
      secondSpan.finish();
      if (task.propagationFailed) {
        throw new AssertionError("overlapping submissions did not keep their own spans");
      }
    }

    private void submitOverlapping() {
      try (AgentScope ignored = activateSpan(firstSpan)) {
        executor.execute(task);
      }
      try (AgentScope ignored = activateSpan(secondSpan)) {
        executor.execute(task);
      }
    }
  }

  private static ThreadPoolExecutor newExecutor(String executorType) {
    switch (executorType) {
      case "base":
        return new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(16));
      case "delegating":
        return new DelegatingExecutor();
      case "nondelegating":
        return new NonDelegatingExecutor();
      default:
        throw new IllegalArgumentException("Unknown executor type: " + executorType);
    }
  }

  private static void closeExecutor(ThreadPoolExecutor executor) throws InterruptedException {
    if (executor instanceof NonDelegatingExecutor) {
      ((NonDelegatingExecutor) executor).closeWorker();
    }
    executor.shutdownNow();
    if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
      throw new AssertionError("executor did not terminate");
    }
  }

  private static final class CompletionTask implements Runnable {
    private final ExecutorState owner;
    private final Phaser completion;
    private volatile AgentSpan expected;

    private CompletionTask(ExecutorState owner, Phaser completion) {
      this.owner = owner;
      this.completion = completion;
    }

    @Override
    public void run() {
      if (activeSpan() != expected) {
        owner.propagationFailed = true;
      }
      completion.arrive();
    }
  }

  private static final class CollisionTask implements Runnable {
    private final AgentSpan firstExpected;
    private final AgentSpan secondExpected;
    private final CountDownLatch finished = new CountDownLatch(2);
    private int execution;
    private volatile boolean propagationFailed;

    private CollisionTask(AgentSpan firstExpected, AgentSpan secondExpected) {
      this.firstExpected = firstExpected;
      this.secondExpected = secondExpected;
    }

    @Override
    public void run() {
      AgentSpan expected = execution++ == 0 ? firstExpected : secondExpected;
      if (activeSpan() != expected) {
        propagationFailed = true;
      }
      finished.countDown();
    }
  }

  private static final class BlockingTask implements Runnable {
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public void run() {
      started.countDown();
      try {
        release.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static final class DelegatingExecutor extends ThreadPoolExecutor {
    private DelegatingExecutor() {
      super(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(16));
    }

    @Override
    public void execute(Runnable task) {
      super.execute(task);
    }
  }

  private static final class NonDelegatingExecutor extends ThreadPoolExecutor {
    private static final Runnable STOP = () -> {};

    private final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    private final Thread worker;

    private NonDelegatingExecutor() {
      super(0, 1, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<Runnable>());
      worker = new Thread(this::runTasks, "nondelegating-benchmark-worker");
      worker.setDaemon(true);
      worker.start();
    }

    @Override
    public void execute(Runnable task) {
      tasks.add(task);
    }

    private void closeWorker() throws InterruptedException {
      tasks.add(STOP);
      worker.join(TimeUnit.SECONDS.toMillis(10));
      if (worker.isAlive()) {
        throw new AssertionError("non-delegating worker did not terminate");
      }
    }

    private void runTasks() {
      try {
        while (true) {
          Runnable task = tasks.take();
          if (task == STOP) {
            return;
          }
          task.run();
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
