public class ComparableAsyncChild implements Runnable, Comparable<ComparableAsyncChild> {

  private final int priority;
  private final Runnable task;

  public ComparableAsyncChild(int priority, Runnable task) {
    this.priority = priority;
    this.task = task;
  }

  @Override
  public int compareTo(ComparableAsyncChild o) {
    return priority - o.priority;
  }

  @Override
  public void run() {
    task.run();
  }
}
