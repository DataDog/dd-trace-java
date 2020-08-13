package datadog.trace.bootstrap.instrumentation.api;

import lombok.EqualsAndHashCode;

@EqualsAndHashCode
public final class Pair<T, U> {

  public static <T, U> Pair<T, U> of(final T left, final U right) {
    return new Pair<>(left, right);
  }

  private final T left;
  private final U right;

  Pair(final T left, final U right) {
    this.left = left;
    this.right = right;
  }

  public T getLeft() {
    return left;
  }

  public U getRight() {
    return right;
  }

  public boolean hasLeft() {
    return null != left;
  }

  public boolean hasRight() {
    return null != right;
  }

  @Override
  public String toString() {
    return "Pair<" + left + "," + right + ">";
  }
}
