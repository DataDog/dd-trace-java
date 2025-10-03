package runnable;

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
  public boolean equals(Object o) {
    try {
      return this.compareTo((ComparableAsyncChild) o) == 0;
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public int hashCode() {
    assert false : "hashCode not designed";
    return 0;
  }

  @Override
  public void run() {
    task.run();
  }
}
