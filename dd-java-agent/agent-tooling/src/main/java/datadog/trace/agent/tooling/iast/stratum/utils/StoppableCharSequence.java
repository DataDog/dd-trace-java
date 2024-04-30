package datadog.trace.agent.tooling.iast.stratum.utils;

public class StoppableCharSequence implements CharSequence {

  protected final CharSequence original;

  protected Runnable onPendingFinished;

  int pending;

  protected StoppableCharSequence(final CharSequence original, final int maxIt) {
    this(original, maxIt, null);
  }

  public StoppableCharSequence(
      final CharSequence original, final int maxIt, final Runnable onPendingFinished) {
    this.original = original;
    pending = Math.max(original.length() * 5, maxIt);
    this.onPendingFinished = onPendingFinished;
  }

  @Override
  public char charAt(final int index) {
    if (pending-- == 0) {
      onPendingFinished.run();
    }
    return original.charAt(index);
  }

  @Override
  public int length() {
    return original.length();
  }

  @Override
  public CharSequence subSequence(final int start, final int stop) {
    return original.subSequence(start, stop);
  }

  @Override
  public String toString() {
    return original.toString();
  }
}
