package datadog.trace.bootstrap.instrumentation.java.concurrent;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class BlockingQueueWrapper implements BlockingQueue<Runnable> {
  private final BlockingQueue<Runnable> delegate;

  private final BlockingQueue<RunnableWrapper> runnableWrappers = new LinkedBlockingQueue<>();

  public BlockingQueueWrapper(final BlockingQueue<Runnable> delegate) {
    this.delegate = delegate;
  }

  @Override
  public boolean add(final Runnable runnable) {
    if (runnable instanceof RunnableWrapper) {
      synchronized (runnableWrappers) {
        final boolean added = delegate.add(((RunnableWrapper) runnable).unwrap());
        runnableWrappers.add((RunnableWrapper) runnable);
        return added;
      }
    } else {
      return delegate.add(runnable);
    }
  }

  @Override
  public boolean offer(final Runnable runnable) {
    if (runnable instanceof RunnableWrapper) {
      synchronized (runnableWrappers) {
        final boolean added = delegate.offer(((RunnableWrapper) runnable).unwrap());
        runnableWrappers.offer((RunnableWrapper) runnable);
        return added;
      }
    } else {
      return delegate.offer(runnable);
    }
  }

  @Override
  public void put(final Runnable runnable) throws InterruptedException {
    if (runnable instanceof RunnableWrapper) {
      synchronized (runnableWrappers) {
        delegate.put(((RunnableWrapper) runnable).unwrap());
        runnableWrappers.put((RunnableWrapper) runnable);
      }
    } else {
      delegate.put(runnable);
    }
  }

  @Override
  public boolean offer(final Runnable runnable, final long timeout, final TimeUnit unit)
      throws InterruptedException {
    if (runnable instanceof RunnableWrapper) {
      synchronized (runnableWrappers) {
        final boolean added = delegate.offer(((RunnableWrapper) runnable).unwrap(), timeout, unit);
        runnableWrappers.offer((RunnableWrapper) runnable);
        return added;
      }
    } else {
      return delegate.offer(runnable, timeout, unit);
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

  @Override
  public Runnable remove() {
    final Runnable runnable = delegate.remove();
    if (runnableWrappers.peek() != null && runnableWrappers.peek().unwrap() == runnable) {
      return runnableWrappers.remove();
    }
    return runnable;
  }

  @Override
  public Runnable poll() {
    final Runnable runnable = delegate.poll();
    if (runnableWrappers.peek() != null && runnableWrappers.peek().unwrap() == runnable) {
      return runnableWrappers.poll();
    }
    return runnable;
  }

  @Override
  public Runnable element() {
    final Runnable runnable = delegate.element();
    if (runnableWrappers.peek() != null && runnableWrappers.peek().unwrap() == runnable) {
      return runnableWrappers.element();
    }
    return runnable;
  }

  @Override
  public Runnable peek() {
    final Runnable runnable = delegate.peek();
    if (runnableWrappers.peek() != null && runnableWrappers.peek().unwrap() == runnable) {
      return runnableWrappers.peek();
    }
    return runnable;
  }

  @Override
  public Runnable take() throws InterruptedException {
    final Runnable runnable = delegate.take();
    if (runnableWrappers.peek() != null && runnableWrappers.peek().unwrap() == runnable) {
      return runnableWrappers.take();
    }
    return runnable;
  }

  @Override
  public Runnable poll(final long timeout, final TimeUnit unit) throws InterruptedException {
    final Runnable runnable = delegate.poll(timeout, unit);
    if (runnableWrappers.peek() != null && runnableWrappers.peek().unwrap() == runnable) {
      return runnableWrappers.poll();
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
    synchronized (runnableWrappers) {
      delegate.clear();
      runnableWrappers.clear();
    }
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
