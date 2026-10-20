package datadog.trace.instrumentation.kotlin.coroutines;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.trace.api.GenericClassValue;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
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
public final class DatadogThreadContextElement
    implements ThreadContextElement<Context>,
        Function2<CoroutineContext, CoroutineContext.Element, CoroutineContext> {
  private static final AtomicReferenceFieldUpdater<DatadogThreadContextElement, ContextContinuation>
      CONTINUATION =
          AtomicReferenceFieldUpdater.newUpdater(
              DatadogThreadContextElement.class, ContextContinuation.class, "continuation");

  private static final CoroutineContext.Key<DatadogThreadContextElement> DATADOG_KEY =
      new CoroutineContext.Key<DatadogThreadContextElement>() {};

  private static final Object COMPLETED = new Object();

  /** Coroutines built by coroutineScope, withContext, etc. that their caller waits for. */
  private static final ClassValue<Boolean> SCOPED_COROUTINE =
      GenericClassValue.of(
          type ->
              ScopeCoroutine.class.isAssignableFrom(type)
                  // older timeout coroutines are scoped but don't extend ScopeCoroutine
                  || (AbstractCoroutine.class.isAssignableFrom(type)
                      && "kotlinx.coroutines.TimeoutCoroutine".equals(type.getName())));

  /** Adds a fresh Datadog element, replacing any inherited element in place. */
  public static CoroutineContext addDatadogElement(CoroutineContext coroutineContext) {
    DatadogThreadContextElement existing = coroutineContext.get(DATADOG_KEY);
    if (existing == null) {
      return coroutineContext.plus(new DatadogThreadContextElement(null));
    }
    DatadogThreadContextElement fresh =
        new DatadogThreadContextElement(existing.localRootFor(coroutineContext.get(Job.Key)));
    // keep the element's position, so elements added after it can still update scopes on top
    return coroutineContext.fold(EmptyCoroutineContext.INSTANCE, fresh);
  }

  /** Rebuilds the context using this replacement element, avoiding a capturing fold lambda. */
  @Override
  public CoroutineContext invoke(CoroutineContext rebuilt, CoroutineContext.Element element) {
    return rebuilt.plus(element.getKey() == DATADOG_KEY ? this : element);
  }

  @Nullable private AgentSpan parentLocalRootSpan;
  private volatile Context context;
  private volatile ContextContinuation continuation;

  /** The owning Job, null before initialization, or COMPLETED after releasing the owner. */
  @Nullable private volatile Object owner;

  /** The local root of the trace kept open by this coroutine or its parent. */
  @Nullable private volatile AgentSpan localRootSpan;

  private DatadogThreadContextElement(@Nullable AgentSpan parentLocalRootSpan) {
    this.parentLocalRootSpan = parentLocalRootSpan;
  }

  /** Returns the local root kept open for structured children launched with this job. */
  @Nullable
  private AgentSpan localRootFor(@Nullable Job job) {
    AgentSpan rootSpan = localRootSpan;
    // structured children complete before their parent, which keeps its trace open until then
    return rootSpan != null && job != null && (job == owner || isScopedBy(job)) ? rootSpan : null;
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
      Context initial = Context.current();
      // this coroutine isn't running yet, so its context can't change under us
      AgentSpan rootSpan = localRootOf(initial);
      if (rootSpan != null) {
        if (rootSpan == parentLocalRootSpan) {
          // the parent keeps this trace open until after this child completes
          localRootSpan = rootSpan;
        } else {
          // stop enclosing trace from finishing early
          ContextContinuation captured = initial.capture();
          continuation = captured;
          if (captured.context() != Context.root()) {
            // only when the capture was accepted, e.g. async propagation is enabled
            localRootSpan = rootSpan;
          }
        }
      }
      context = AgentTracer.get().forkContext(initial);
      parentLocalRootSpan = null;
    }
  }

  @Nullable
  private static AgentSpan localRootOf(Context context) {
    AgentSpan span = AgentSpan.fromContext(context);
    return span != null ? span.getLocalRootSpan() : null;
  }

  public static void cancelDatadogContext(@Nonnull AbstractCoroutine<?> coroutine) {
    DatadogThreadContextElement datadog = coroutine.getContext().get(DATADOG_KEY);
    if (datadog == null || datadog.owner != coroutine) {
      // scoped coroutines share their caller's element, but must not release its trace hold
      return;
    }
    datadog.owner = COMPLETED;
    datadog.localRootSpan = null;
    ContextContinuation continuation = CONTINUATION.getAndSet(datadog, null);
    if (continuation != null) {
      // release enclosing trace now the coroutine has completed
      continuation.release();
    }
  }

  @Override
  public Context updateThreadContext(@Nonnull CoroutineContext coroutineContext) {
    Context current = context;
    if (current == null) {
      if (owner == null) {
        // an already-cancelled coroutine can skip constructor capture but still install context
        owner = coroutineContext.get(Job.Key);
      }
      captureInitialContext();
      current = context;
    }
    return current.swap();
  }

  @Override
  public void restoreThreadContext(
      @Nonnull CoroutineContext coroutineContext, Context originalContext) {
    // The next worker may already be executing against the coroutine's stack.
    originalContext.swap();
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
