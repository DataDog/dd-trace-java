package datadog.trace.instrumentation.java.concurrent;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.RunnableWrapper;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@Slf4j
@AutoService(Instrumenter.class)
/**
 * Disable instrumentation for executors that cannot take our runnable wrappers.
 *
 * <p>FIXME: We should remove this once https://github.com/raphw/byte-buddy/issues/558 is fixed
 */
public class ThreadPoolExecutorInstrumentation extends Instrumenter.Default {

  public ThreadPoolExecutorInstrumentation() {
    super(AbstractExecutorInstrumentation.EXEC_NAME);
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("java.util.concurrent.ThreadPoolExecutor");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".ThreadPoolExecutorInstrumentation$BlockingQueueWrapper"};
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isConstructor()
            .and(takesArgument(4, named("java.util.concurrent.BlockingQueue")))
            .and(takesArguments(7)),
        ThreadPoolExecutorInstrumentation.class.getName() + "$ThreadPoolExecutorAdvice");
  }

  public static class ThreadPoolExecutorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void disableIfQueueWrongType(
        @Advice.This final ThreadPoolExecutor executor,
        @Advice.Argument(value = 4, readOnly = false) BlockingQueue<Runnable> queue) {
      queue = new BlockingQueueWrapper(queue);
    }
  }

  public static class BlockingQueueWrapper implements BlockingQueue<Runnable> {
    private final BlockingQueue<Runnable> delegate;

    private final HashMap<Runnable, RunnableWrapper> runnableWrappers = new HashMap<>();

    public BlockingQueueWrapper(final BlockingQueue<Runnable> delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean add(final Runnable runnable) {
      try {
        return delegate.add(unwrap(runnable));
      } catch (final Exception e) {
        runnableWrappers.remove(unwrap(runnable));
        throw e;
      }
    }

    @Override
    public boolean offer(final Runnable runnable) {
      try {
        return delegate.offer(unwrap(runnable));
      } catch (final Exception e) {
        runnableWrappers.remove(unwrap(runnable));
        throw e;
      }
    }

    @Override
    public void put(final Runnable runnable) throws InterruptedException {
      try {
        delegate.put(unwrap(runnable));
      } catch (final Exception e) {
        runnableWrappers.remove(unwrap(runnable));
        throw e;
      }
    }

    @Override
    public boolean offer(final Runnable runnable, final long timeout, final TimeUnit unit)
        throws InterruptedException {
      try {
        return delegate.offer(unwrap(runnable), timeout, unit);
      } catch (final Exception e) {
        runnableWrappers.remove(unwrap(runnable));
        throw e;
      }
    }

    @Override
    public boolean addAll(final Collection<? extends Runnable> c) {
      boolean modified = false;
      for (final Runnable runnable : c) {
        if (add(runnable)) {
          modified = true;
        }
      }
      return modified;
    }

    private Runnable unwrap(final Runnable runnable) {
      if (runnable instanceof RunnableWrapper) {
        runnableWrappers.put(((RunnableWrapper) runnable).unwrap(), (RunnableWrapper) runnable);
        return ((RunnableWrapper) runnable).unwrap();
      }
      return runnable;
    }

    @Override
    public Runnable remove() {
      final Runnable runnable = delegate.remove();
      if (runnableWrappers.containsKey(runnable)) {
        return runnableWrappers.get(runnable);
      }
      return runnable;
    }

    @Override
    public Runnable poll() {
      final Runnable runnable = delegate.poll();
      if (runnableWrappers.containsKey(runnable)) {
        return runnableWrappers.get(runnable);
      }
      return runnable;
    }

    @Override
    public Runnable element() {
      final Runnable runnable = delegate.element();
      if (runnableWrappers.containsKey(runnable)) {
        return runnableWrappers.get(runnable);
      }
      return runnable;
    }

    @Override
    public Runnable peek() {
      final Runnable runnable = delegate.peek();
      if (runnableWrappers.containsKey(runnable)) {
        return runnableWrappers.get(runnable);
      }
      return runnable;
    }

    @Override
    public Runnable take() throws InterruptedException {
      final Runnable runnable = delegate.take();
      if (runnableWrappers.containsKey(runnable)) {
        return runnableWrappers.get(runnable);
      }
      return runnable;
    }

    @Override
    public Runnable poll(final long timeout, final TimeUnit unit) throws InterruptedException {
      final Runnable runnable = delegate.poll(timeout, unit);
      if (runnableWrappers.containsKey(runnable)) {
        return runnableWrappers.get(runnable);
      }
      return runnable;
    }

    @Override
    public int remainingCapacity() {
      return delegate.remainingCapacity();
    }

    @Override
    public boolean remove(final Object o) {
      runnableWrappers.remove(o);
      return delegate.remove(o);
    }

    @Override
    public boolean containsAll(final Collection<?> c) {
      return delegate.containsAll(c);
    }

    @Override
    public boolean removeAll(final Collection<?> c) {
      for (final Object o : c) {
        runnableWrappers.remove(o);
      }
      return delegate.removeAll(c);
    }

    @Override
    public boolean retainAll(final Collection<?> c) {
      return delegate.retainAll(c);
    }

    @Override
    public void clear() {
      delegate.clear();
    }

    @Override
    public int size() {
      return delegate.size();
    }

    @Override
    public boolean isEmpty() {
      return delegate.isEmpty();
    }

    @Override
    public boolean contains(final Object o) {
      return delegate.contains(o);
    }

    @Override
    public Iterator<Runnable> iterator() {
      return delegate.iterator();
    }

    @Override
    public Object[] toArray() {
      return delegate.toArray();
    }

    @Override
    public <T> T[] toArray(final T[] a) {
      return delegate.toArray(a);
    }

    @Override
    public int drainTo(final Collection<? super Runnable> c) {
      return delegate.drainTo(c);
    }

    @Override
    public int drainTo(final Collection<? super Runnable> c, final int maxElements) {
      return delegate.drainTo(c, maxElements);
    }
  }
}
