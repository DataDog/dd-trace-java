package datadog.trace.civisibility.source;

import java.lang.reflect.Method;
import javax.annotation.Nonnull;
import datadog.trace.util.HashingUtils;

public interface LinesResolver {

  @Nonnull
  Lines getMethodLines(@Nonnull Method method);

  @Nonnull
  Lines getClassLines(@Nonnull Class<?> clazz);

  final class Lines {
    public static final Lines EMPTY = new Lines(Integer.MAX_VALUE, Integer.MIN_VALUE);

    private final int startLineNumber;
    private final int endLineNumber;

    public Lines(int startLineNumber, int endLineNumber) {
      this.startLineNumber = startLineNumber;
      this.endLineNumber = endLineNumber;
    }

    public int getStartLineNumber() {
      return startLineNumber;
    }

    public int getEndLineNumber() {
      return endLineNumber;
    }

    public boolean isValid() {
      return startLineNumber <= endLineNumber;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Lines that = (Lines) o;
      return startLineNumber == that.startLineNumber && endLineNumber == that.endLineNumber;
    }

    @Override
    public int hashCode() {
      return HashingUtils.hash(startLineNumber, endLineNumber);
    }
  }
}
