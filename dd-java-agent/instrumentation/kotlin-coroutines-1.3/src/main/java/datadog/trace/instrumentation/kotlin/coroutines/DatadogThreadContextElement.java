package datadog.trace.instrumentation.kotlin.coroutines;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.trace.api.GenericClassValue;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
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

  /** null before the Datadog element, false after it, true once a later hook is found. */
  private static final Function2<Boolean, CoroutineContext.Element, Boolean> HAS_LATER_HOOK =
      (seen, element) -> {
        if (element.getKey() == DATADOG_KEY) {
          return Boolean.FALSE;
        }
        return seen != null && element instanceof ThreadContextElement ? Boolean.TRUE : seen;
      };

  /** Adds a fresh Datadog element, retaining its order relative to other thread context hooks. */
  public static CoroutineContext addDatadogElement(CoroutineContext coroutineContext) {
    DatadogThreadContextElement existing = coroutineContext.get(DATADOG_KEY);
    if (existing == null) {
      return coroutineContext.plus(new DatadogThreadContextElement(null));
    }
    return replaceDatadogElement(
        coroutineContext, existing.localRootFor(coroutineContext.get(Job.Key)));
  }

  private static CoroutineContext replaceDatadogElement(
      CoroutineContext coroutineContext, @Nullable AgentSpan parentLocalRootSpan) {
    DatadogThreadContextElement fresh = new DatadogThreadContextElement(parentLocalRootSpan);
    if (!Boolean.TRUE.equals(coroutineContext.fold(null, HAS_LATER_HOOK))) {
      return coroutineContext.plus(fresh);
    }
    // Rebuild only when appending would move us past another thread context hook.
    return coroutineContext.fold(EmptyCoroutineContext.INSTANCE, fresh);
  }

  /** Preserves Kotlin's identity fast path and native copy/merge semantics. */
  public static CoroutineContext forContextMerge(
      CoroutineContext merged, CoroutineContext original) {
    if (merged == original) {
      return merged;
    }
    DatadogThreadContextElement caller = original.get(DATADOG_KEY);
    // Scoped coroutines can borrow the caller's hold even when the requested context replaces Job.
    // Flow merges may install this element without constructing a coroutine to own it.
    return replaceDatadogElement(
        merged, caller == null ? null : caller.localRootFor(original.get(Job.Key)));
  }

  /** Separates upstream flow state while retaining the caller's active context for downstream. */
  public static CoroutineContext forFlowCollection(
      CoroutineContext upstream, CoroutineContext downstream) {
    DatadogThreadContextElement caller = downstream.get(DATADOG_KEY);
    if (caller != null) {
      // Downstream reinstalls the caller's element without constructing a coroutine.
      caller.context = Context.current();
    }
    DatadogThreadContextElement element = upstream.get(DATADOG_KEY);
    if (element == null || element == caller) {
      // Before 1.7.2, flowOn merges with plus and shares this mutable element with its caller.
      return replaceDatadogElement(upstream, null);
    }
    // Newer releases already received a fresh element from the context merge advice.
    return upstream;
  }

  /** Ignores tracing element identity only while Kotlin validates the emission context. */
  public static CoroutineContext forFlowContextCheck(
      CoroutineContext emitted, CoroutineContext collected) {
    DatadogThreadContextElement expected = collected.get(DATADOG_KEY);
    if (emitted.get(DATADOG_KEY) == expected) {
      return emitted;
    }
    return expected == null ? emitted.minusKey(DATADOG_KEY) : emitted.plus(expected);
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
        datadog.captureInitialContext(true);
      } else if (datadog.owner != coroutine && datadog.isScopedBy(coroutine)) {
        // scoped coroutines (coroutineScope, withTimeout, or withContext before 1.6.1) share their
        // caller's element, so refresh it with the active context
        datadog.context = Context.current();
      }
    }
  }

  private synchronized void captureInitialContext(boolean owned) {
    if (context == null) {
      Context initial = Context.current();
      // Initialize before the first context installation; later swaps update the stored context.
      AgentSpan rootSpan = localRootOf(initial);
      if (rootSpan != null) {
        if (rootSpan == parentLocalRootSpan) {
          // the parent keeps this trace open until after this child completes
          localRootSpan = rootSpan;
        } else if (owned) {
          // stop enclosing trace from finishing early
          ContextContinuation captured = initial.capture();
          continuation = captured;
          if (captured.context() != Context.root()) {
            // only when the capture was accepted, e.g. async propagation is enabled
            localRootSpan = rootSpan;
          }
        }
      }
      context = initial;
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
        Job job = coroutineContext.get(Job.Key);
        // Only the coroutine whose context contains this element can release its continuation.
        // flowOn can install a merged context while retaining the collector's original Job.
        if (job instanceof AbstractCoroutine
            && ((AbstractCoroutine<?>) job).getContext().get(DATADOG_KEY) == this) {
          owner = job;
        }
      }
      // An already-cancelled owner can skip constructor capture but still install its context.
      captureInitialContext(owner instanceof AbstractCoroutine);
      current = context;
    }
    return current.swap();
  }

  @Override
  public void restoreThreadContext(
      @Nonnull CoroutineContext coroutineContext, Context originalContext) {
    context = originalContext.swap();
  }

  /** Tracing state does not change the logical coroutine context used by flow equality checks. */
  @Override
  public boolean equals(Object other) {
    return other instanceof DatadogThreadContextElement;
  }

  @Override
  public int hashCode() {
    return DATADOG_KEY.hashCode();
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
