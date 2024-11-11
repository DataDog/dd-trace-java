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
  public Lines getMethodLines(@Nonnull Method method) {
    for (LinesResolver delegate : delegates) {
      Lines lines = delegate.getMethodLines(method);
      if (lines.isValid()) {
        return lines;
      }
    }
    return Lines.EMPTY;
  }
}
