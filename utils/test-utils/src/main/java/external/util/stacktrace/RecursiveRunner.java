package external.util.stacktrace;

/** This class is used to generate stacks not filtered by StackWalker in jmh benchmarks */
public class RecursiveRunner {

  private final int deep;

  private final Runnable runnable;

  public RecursiveRunner(int deep, final Runnable runnable) {
    this.deep = deep;
    this.runnable = runnable;
  }

  public void run() {
    recursiveRun(0);
  }

  private void recursiveRun(int reps) {
    if (reps > deep) {
      runnable.run();
    } else {
      recursiveRun(++reps);
    }
  }
}
