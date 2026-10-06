package coveragefixture;

/** Small target for overload, throwing-exit, and inventory controls. */
public class Fixture {
  public int call(int value) {
    return value + 1;
  }

  public String call(String value) {
    return value;
  }

  public void fail(RuntimeException error) {
    throw error;
  }

  public void neverCalled() {}
}
