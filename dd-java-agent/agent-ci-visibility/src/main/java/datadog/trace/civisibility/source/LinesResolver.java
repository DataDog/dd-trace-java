package datadog.trace.civisibility.source;

import java.lang.reflect.Method;
import java.util.Objects;
import javax.annotation.Nonnull;

public interface LinesResolver {

  @Nonnull
  Lines getMethodLines(@Nonnull Method method);

  final class Lines {
    public static final Lines EMPTY = new Lines(Integer.MAX_VALUE, Integer.MIN_VALUE);

    private final int startLineNumber;
    private final int finishLineNumber;

    public Lines(int startLineNumber, int finishLineNumber) {
      this.startLineNumber = startLineNumber;
      this.finishLineNumber = finishLineNumber;
    }

    public int getStartLineNumber() {
      return startLineNumber;
    }

    public int getFinishLineNumber() {
      return finishLineNumber;
    }

    public boolean isValid() {
      return startLineNumber <= finishLineNumber;
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
      return startLineNumber == that.startLineNumber && finishLineNumber == that.finishLineNumber;
    }

    @Override
    public int hashCode() {
      return Objects.hash(startLineNumber, finishLineNumber);
    }
  }
}
