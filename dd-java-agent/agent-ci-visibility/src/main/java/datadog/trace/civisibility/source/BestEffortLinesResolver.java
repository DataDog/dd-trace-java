package datadog.trace.civisibility.source;

import java.lang.reflect.Method;
import javax.annotation.Nonnull;

public class BestEffortLinesResolver implements LinesResolver {

  private final LinesResolver[] delegates;

  public BestEffortLinesResolver(LinesResolver... delegates) {
    this.delegates = delegates;
  }

  @Nonnull
  @Override
  public MethodLines getMethodLines(@Nonnull Method method) {
    for (LinesResolver delegate : delegates) {
      MethodLines lines = delegate.getMethodLines(method);
      if (lines.isValid()) {
        return lines;
      }
    }
    return MethodLines.EMPTY;
  }
}
