package datadog.trace.instrumentation.kotlin.coroutines;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.trace.api.GenericClassValue;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTraceCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.AbstractCoroutine;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.ThreadContextElement;
import kotlinx.coroutines.internal.ScopeCoroutine;

/** Manages the Datadog context for coroutines, switching contexts as coroutines switch threads. */
public final class DatadogThreadContextElement implements ThreadContextElement<Context> {
  private static final AtomicReferenceFieldUpdater<DatadogThreadContextElement, ContextContinuation>
      CONTINUATION =
          AtomicReferenceFieldUpdater.newUpdater(
              DatadogThreadContextElement.class, ContextContinuation.class, "continuation");

  private static final CoroutineContext.Key<DatadogThreadContextElement> DATADOG_KEY =
      new CoroutineContext.Key<DatadogThreadContextElement>() {};

  private static final boolean LEGACY_CONTEXT_MANAGER =
      InstrumenterConfig.get().isLegacyContextManagerEnabled();

  /** Coroutines built by coroutineScope, withContext, etc. that their caller waits for. */
  private static final ClassValue<Boolean> SCOPED_COROUTINE =
      GenericClassValue.of(
          type ->
              ScopeCoroutine.class.isAssignableFrom(type)
                  // older timeout coroutines are scoped but don't extend ScopeCoroutine
                  || (AbstractCoroutine.class.isAssignableFrom(type)
                      && "kotlinx.coroutines.TimeoutCoroutine".equals(type.getName())));

  /** Gives each new coroutine its own element, so coroutines never share Datadog context. */
  public static CoroutineContext addDatadogElement(CoroutineContext coroutineContext) {
    DatadogThreadContextElement existing = coroutineContext.get(DATADOG_KEY);
    if (existing == null) {
      return coroutineContext.plus(new DatadogThreadContextElement(null, null));
    }
    // a context passed from another coroutine starts from that coroutine's current context
    DatadogThreadContextElement fresh =
        new DatadogThreadContextElement(
            existing.context, existing.traceKeptOpenFor(coroutineContext.get(Job.Key)));
    // keep the element's position, so elements added after it can still update scopes on top
    return coroutineContext.<CoroutineContext>fold(
        EmptyCoroutineContext.INSTANCE,
        (replaced, element) -> replaced.plus(element == existing ? fresh : element));
  }

  // only needed until the initial context is captured; cleared so a long-lived child doesn't keep
  // its parent's scope stack and trace alive
  @Nullable private Context seed;
  @Nullable private AgentTraceCollector traceKeptOpenByParent;
  private volatile Context context;
  private volatile ContextContinuation continuation;

  /** The coroutine this element was created for. */
  @Nullable private volatile Job owner;

  /** The trace kept open until this coroutine completes, by its own continuation or its parent. */
  @Nullable private volatile AgentTraceCollector traceKeptOpen;

  private DatadogThreadContextElement(
      @Nullable Context seed, @Nullable AgentTraceCollector traceKeptOpenByParent) {
    this.seed = seed;
    this.traceKeptOpenByParent = traceKeptOpenByParent;
  }

  /** Returns the trace this coroutine keeps open, if coroutines of this job are its children. */
  @Nullable
  private AgentTraceCollector traceKeptOpenFor(@Nullable Job job) {
    AgentTraceCollector trace = traceKeptOpen;
    // structured children complete before their parent, which keeps its trace open until then
    return trace != null && job != null && (job == owner || isScopedBy(job)) ? trace : null;
  }

  /** Scoped coroutines share their caller's element and complete before the caller does. */
  private boolean isScopedBy(Job job) {
    return SCOPED_COROUTINE.get(job.getClass())
        && ((AbstractCoroutine<?>) job).getContext().get(DATADOG_KEY) == this;
  }

  @Nonnull
  @Override
  public Key<?> getKey() {
    return DATADOG_KEY;
  }

  public static void captureDatadogContext(@Nonnull AbstractCoroutine<?> coroutine) {
    DatadogThreadContextElement datadog = coroutine.getContext().get(DATADOG_KEY);
    if (datadog != null) {
      if (datadog.owner == null) {
        // scoped coroutines built later share this element, but aren't its owner
        datadog.owner = coroutine;
      }
      if (datadog.context == null) {
        datadog.captureInitialContext();
      }
    }
  }

  private synchronized void captureInitialContext() {
    if (context == null) {
      Context initial = seed != null ? seed : Context.current();
      if (LEGACY_CONTEXT_MANAGER) {
        // workers share this coroutine's scope stack, rather than copying it between them
        initial = AgentTracer.get().sharedContextStack(initial);
      }
      // this coroutine isn't running yet, so its context can't change under us
      AgentTraceCollector trace = traceOf(initial);
      if (trace != null) {
        if (trace == traceKeptOpenByParent) {
          // the parent keeps this trace open until after this child completes
          traceKeptOpen = trace;
        } else {
          // stop enclosing trace from finishing early
          ContextContinuation captured = initial.capture();
          continuation = captured;
          if (captured.context() != Context.root()) {
            // only when the capture was accepted, e.g. async propagation is enabled
            traceKeptOpen = trace;
          }
        }
      }
      context = initial;
      seed = null;
      traceKeptOpenByParent = null;
    }
  }

  @Nullable
  private static AgentTraceCollector traceOf(Context context) {
    AgentSpan span = AgentSpan.fromContext(context);
    return span != null ? span.spanContext().getTraceCollector() : null;
  }

  public static void cancelDatadogContext(@Nonnull AbstractCoroutine<?> coroutine) {
    DatadogThreadContextElement datadog = coroutine.getContext().get(DATADOG_KEY);
    ContextContinuation continuation =
        datadog == null ? null : CONTINUATION.getAndSet(datadog, null);
    if (continuation != null) {
      // release enclosing trace now the coroutine has completed
      continuation.release();
    }
  }

  @Override
  public Context updateThreadContext(@Nonnull CoroutineContext coroutineContext) {
    Context current = context;
    if (current == null) {
      captureInitialContext();
      current = context;
    }
    return current.swap();
  }

  @Override
  public void restoreThreadContext(
      @Nonnull CoroutineContext coroutineContext, Context originalContext) {
    Context coroutineContextAfter = originalContext.swap();
    // legacy workers share the coroutine's scope stack, so it never needs saving; writing it back
    // would usually store that same stack, but after an unbalanced swap on this thread it would
    // rebind the coroutine to the wrong stack
    if (!LEGACY_CONTEXT_MANAGER) {
      context = coroutineContextAfter;
    }
  }

  @Nonnull
  @Override
  public CoroutineContext plus(@Nonnull CoroutineContext coroutineContext) {
    return CoroutineContext.DefaultImpls.plus(this, coroutineContext);
  }

  @Override
  public <R> R fold(
      R initial, @Nonnull Function2<? super R, ? super Element, ? extends R> operation) {
    return CoroutineContext.Element.DefaultImpls.fold(this, initial, operation);
  }

  @Nullable
  @Override
  public <E extends Element> E get(@Nonnull Key<E> key) {
    return CoroutineContext.Element.DefaultImpls.get(this, key);
  }

  @Nonnull
  @Override
  public CoroutineContext minusKey(@Nonnull Key<?> key) {
    return CoroutineContext.Element.DefaultImpls.minusKey(this, key);
  }
}
