package executor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;

public class CustomThreadPoolExecutor extends AbstractExecutorService {

  private volatile Boolean running = true;
  private LinkedBlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<Runnable>(10);
  private Runnable worker =
      new Runnable() {
        public void run() {
          try {
            while (getRunning()) {
              Runnable runnable = ((LinkedBlockingQueue<Runnable>) getWorkQueue()).take();
              runnable.run();
            }

          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (Exception e) {
            e.printStackTrace();
          }
        }
      };
  private Thread workerThread = new Thread(worker, "ExecutorTestThread");

  public CustomThreadPoolExecutor() {
    workerThread.start();
  }

  @Override
  public void shutdown() {
    running = false;
    workerThread.interrupt();
  }

  @Override
  public List<Runnable> shutdownNow() {
    running = false;
    workerThread.interrupt();
    return new ArrayList<Runnable>();
  }

  @Override
  public boolean isShutdown() {
    return !running;
  }

  @Override
  public boolean isTerminated() {
    return workerThread.isAlive();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    workerThread.join(unit.toMillis(timeout));
    return true;
  }

  @Override
  public Future<?> submit(Runnable task) {
    RunnableFuture<Object> future = super.newTaskFor(task, null);
    execute(future);
    return ((Future<?>) (future));
  }

  @Override
  public void execute(Runnable command) {
    try {
      workQueue.put(command);
    } catch (Throwable t) {
      throw new RejectedExecutionException(t);
    }
  }

  public Boolean getRunning() {
    return running;
  }

  public void setRunning(Boolean running) {
    this.running = running;
  }

  public LinkedBlockingQueue<Runnable> getWorkQueue() {
    return workQueue;
  }

  public void setWorkQueue(LinkedBlockingQueue<Runnable> workQueue) {
    this.workQueue = workQueue;
  }

  public Runnable getWorker() {
    return worker;
  }

  public void setWorker(Runnable worker) {
    this.worker = worker;
  }

  public Thread getWorkerThread() {
    return workerThread;
  }

  public void setWorkerThread(Thread workerThread) {
    this.workerThread = workerThread;
  }
}
