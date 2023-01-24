package datadog.trace.bootstrap.instrumentation.ci.source;

import java.lang.reflect.Method;

public interface MethodLinesResolver {

  MethodLines getLines(Method method);

  interface Factory {
    MethodLinesResolver create();
  }

  final class MethodLines {
    public static final MethodLines EMPTY = new MethodLines(Integer.MAX_VALUE, Integer.MIN_VALUE);

    private final int startLineNumber;
    private final int finishLineNumber;

    public MethodLines(int startLineNumber, int finishLineNumber) {
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
  }
}
