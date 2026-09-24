package datadog.trace.bootstrap;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.context.Context;
import datadog.trace.api.EndpointTracker;
import datadog.trace.api.Stateful;
import datadog.trace.api.profiling.ProfilingContextAttribute;
import datadog.trace.api.profiling.ProfilingScope;
import datadog.trace.api.profiling.Timing;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.util.AgentTaskScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link ProfilingContextIntegration} handed out synchronously during {@code premain} while the
 * real ddprof-based integration is constructed later, off the premain thread, to avoid loading the
 * ddprof native library (and touching {@code java.nio.file}) before {@code main} gets a chance to
 * set its own {@code java.nio.file.spi.DefaultFileSystemProvider}. Delegates to {@link
 * ProfilingContextIntegration.NoOp} until the swap happens; stays a no-op forever if construction
 * fails.
 */
final class DeferredProfilingContextIntegration implements ProfilingContextIntegration {
  private static final Logger log =
      LoggerFactory.getLogger(DeferredProfilingContextIntegration.class);

  /**
   * Delay before the deferred construction runs, giving {@code main} a chance to install its own
   * {@code java.nio.file.spi.DefaultFileSystemProvider} first; not user-tunable since losing the
   * first second of context exposure is not observable.
   */
  private static final long INITIALIZATION_DELAY_MILLIS = 1_000;

  private final String name;
  private final Callable<ProfilingContextIntegration> factory;

  /**
   * Swapped to the real integration once construction succeeds; volatile since scopes may already
   * be running when the swap happens.
   */
  private volatile ProfilingContextIntegration delegate = ProfilingContextIntegration.NoOp.INSTANCE;

  /**
   * Callbacks queued via {@link #whenAvailable(Runnable)} before the swap; guarded by {@code this}
   * together with the {@link #delegate} write so none is run twice or dropped.
   */
  private final List<Runnable> pendingAvailabilityCallbacks = new ArrayList<>(1);

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
   * Schedules the deferred construction to run off this (premain) thread, after {@link
   * #INITIALIZATION_DELAY_MILLIS}.
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
      if (integration == null) {
        return;
      }
      final List<Runnable> callbacks;
      synchronized (this) {
        delegate = integration;
        callbacks = new ArrayList<>(pendingAvailabilityCallbacks);
        pendingAvailabilityCallbacks.clear();
      }
      for (final Runnable callback : callbacks) {
        try {
          callback.run();
        } catch (final Throwable t) {
          log.debug("Availability callback for {} profiling context failed.", name, t);
        }
      }
    } catch (final Throwable t) {
      // toString() because failures here (UnsatisfiedLinkError etc.) often carry no message.
      log.info("Deferred {} profiling context labeling not available. {}", name, t.toString());
    }
  }

  /**
   * Runs {@code callback} once the real integration is swapped in, or immediately if it already is;
   * never runs it if the deferred construction failed.
   */
  @Override
  public void whenAvailable(final Runnable callback) {
    synchronized (this) {
      if (delegate == ProfilingContextIntegration.NoOp.INSTANCE) {
        pendingAvailabilityCallbacks.add(callback);
        return;
      }
    }
    callback.run();
  }

  /**
   * The name of the deferred integration, not of the current delegate: read once at tracer build
   * time, possibly before the deferred construction completes.
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
  public boolean isThreadContextBindingRequired() {
    return delegate.isThreadContextBindingRequired();
  }

  @Override
  public void setContext(final Context context) {
    delegate.setContext(context);
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
