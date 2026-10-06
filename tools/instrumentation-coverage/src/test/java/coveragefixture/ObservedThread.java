package coveragefixture;

/** Its stack lookup is itself observable, including when the collector requests a stack. */
public class ObservedThread extends Thread {
  @Override
  public void run() {
    getStackTrace();
  }

  @Override
  public StackTraceElement[] getStackTrace() {
    return super.getStackTrace();
  }
}
