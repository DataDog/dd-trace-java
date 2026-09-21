package datadog.trace.bootstrap;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.trace.api.EndpointTracker;
import datadog.trace.api.Stateful;
import datadog.trace.api.profiling.ProfilingContextAttribute;
import datadog.trace.api.profiling.ProfilingScope;
import datadog.trace.api.profiling.Timing;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.util.AgentTaskScheduler;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link ProfilingContextIntegration} that can be handed out synchronously during {@code premain}
 * while the real integration is constructed later, off the premain thread.
 *
 * <p>Constructing the ddprof-based integration loads the ddprof native library and touches {@code
 * java.nio.file} (through {@code TempLocationManager}), which must not happen on the JVM's
 * primordial premain thread: it can lock in the default filesystem provider before the application
 * has a chance to configure one in {@code main}. This wrapper keeps the premain thread free of that
 * work by delegating to {@link ProfilingContextIntegration.NoOp} until the deferred construction
 * completes, then swapping in the real integration.
 *
 * <p>Moving the work to another thread is not enough on its own, because that thread would still
 * run concurrently with the rest of {@code premain}, i.e. still before {@code main} gets to set
 * {@code java.nio.file.spi.DefaultFileSystemProvider}. The construction is therefore also delayed
 * by {@link #INITIALIZATION_DELAY_MILLIS}. That delay is a mitigation, not a guarantee: the JVM
 * offers no hook for "the application has entered {@code main}", so an application whose start-up
 * is slower than the delay can still be racing with it.
 *
 * <p>Scope events happening before the swap are silently dropped. That is acceptable for context
 * <em>exposure</em> (eBPF/CWS reading the current span off a thread), but not for profiling
 * accuracy, so users with the Datadog profiler actually enabled keep the synchronous construction
 * path.
 */
final class DeferredProfilingContextIntegration implements ProfilingContextIntegration {
  private static final Logger log =
      LoggerFactory.getLogger(DeferredProfilingContextIntegration.class);

  /**
   * How long to wait before running the deferred construction, so that the rest of {@code premain}
   * has returned and the application has had a chance to run the top of {@code main} (where an
   * application that cares about it installs its own {@code
   * java.nio.file.spi.DefaultFileSystemProvider}).
   *
   * <p>Same magnitude as the longest delay {@code Agent} already applies for the analogous "let the
   * application get there first" problem with OkHttp and a custom log manager, and hardcoded for
   * the same reason: this is context <em>exposure</em> for eBPF/CWS consumers, where losing the
   * first second of thread context is not observable, so there is nothing for a user to tune.
   */
  private static final long INITIALIZATION_DELAY_MILLIS = 1_000;

  private final String name;
  private final Callable<ProfilingContextIntegration> factory;

  /**
   * Swapped from {@link ProfilingContextIntegration.NoOp} to the real integration once the deferred
   * construction succeeds. Volatile because application threads may already be running scopes when
   * the swap happens.
   */
  private volatile ProfilingContextIntegration delegate = ProfilingContextIntegration.NoOp.INSTANCE;

  /**
   * @param name the name reported by {@link #name()}, i.e. the name of the integration being
   *     deferred.
   * @param factory creates the real integration; invoked at most once, off the premain thread.
   */
  DeferredProfilingContextIntegration(
      final String name, final Callable<ProfilingContextIntegration> factory) {
    this.name = name;
    this.factory = factory;
  }

  /**
   * Schedules the deferred construction so that it runs off the calling (premain) thread, after
   * {@link #INITIALIZATION_DELAY_MILLIS}.
   *
   * <p>The delay is the same order of magnitude as the one {@code Agent} already uses to let the
   * application reach a given point before starting OkHttp when a custom log manager is in play. It
   * is a heuristic, not a handshake: nothing here observes {@code main} actually starting.
   */
  void scheduleInitialization() {
    AgentTaskScheduler.get().schedule(this::initialize, INITIALIZATION_DELAY_MILLIS, MILLISECONDS);
  }

  /**
   * Runs the deferred construction. On failure this instance keeps behaving as {@link
   * ProfilingContextIntegration.NoOp} forever; a background failure must never propagate.
   */
  void initialize() {
    try {
      final ProfilingContextIntegration integration = factory.call();
      if (integration != null) {
        delegate = integration;
      }
    } catch (final Throwable t) {
      log.debug("Deferred {} profiling context labeling not available. {}", name, t.getMessage());
    }
  }

  /**
   * The name of the deferred integration, not of the current delegate: it is read once when the
   * tracer is built, which may happen before the deferred construction completes, and it must
   * describe the integration that is being installed.
   */
  @Override
  public String name() {
    return name;
  }

  @Override
  public void onStart() {
    delegate.onStart();
  }

  @Override
  public void onAttach() {
    delegate.onAttach();
  }

  @Override
  public void onDetach() {
    delegate.onDetach();
  }

  @Override
  public Stateful newScopeState(final ProfilerContext profilerContext) {
    return delegate.newScopeState(profilerContext);
  }

  @Override
  public int encode(final CharSequence constant) {
    return delegate.encode(constant);
  }

  @Override
  public int encodeOperationName(final CharSequence constant) {
    return delegate.encodeOperationName(constant);
  }

  @Override
  public int encodeResourceName(final CharSequence constant) {
    return delegate.encodeResourceName(constant);
  }

  @Override
  public ProfilingContextAttribute createContextAttribute(final String attribute) {
    return delegate.createContextAttribute(attribute);
  }

  @Override
  public ProfilingScope newScope() {
    return delegate.newScope();
  }

  @Override
  public void onRootSpanFinished(final AgentSpan rootSpan, final EndpointTracker tracker) {
    delegate.onRootSpanFinished(rootSpan, tracker);
  }

  @Override
  public EndpointTracker onRootSpanStarted(final AgentSpan rootSpan) {
    return delegate.onRootSpanStarted(rootSpan);
  }

  @Override
  public Timing start(final TimerType type) {
    return delegate.start(type);
  }
}
