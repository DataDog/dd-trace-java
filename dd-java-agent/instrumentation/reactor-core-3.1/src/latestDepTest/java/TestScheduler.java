import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;

/**
 * A test scheduler we know that it's not already instrumented by TPE integrations on java
 * concurrent.
 */
public class TestScheduler implements Scheduler {
  static class Worker implements Scheduler.Worker {

    @Override
    public Disposable schedule(Runnable task) {
      final Thread t = new Thread(task);
      t.start();
      return new ThreadDisposable(t);
    }

    @Override
    public void dispose() {}
  }

  static class ThreadDisposable implements Disposable {
    private final Thread t;

    ThreadDisposable(Thread t) {
      this.t = t;
    }

    @Override
    public boolean isDisposed() {
      return !t.isAlive() && !t.isInterrupted();
    }

    @Override
    public void dispose() {
      t.interrupt();
    }
  }

  @Override
  public Disposable schedule(Runnable task) {
    final Thread t = new Thread(task);
    t.start();
    return new ThreadDisposable(t);
  }

  @Override
  public Worker createWorker() {
    return new Worker();
  }
}
