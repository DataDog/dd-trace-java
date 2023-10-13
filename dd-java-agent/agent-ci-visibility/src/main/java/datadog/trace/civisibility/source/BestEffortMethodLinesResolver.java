package datadog.trace.civisibility.source;

import java.lang.reflect.Method;
import javax.annotation.Nonnull;

public class BestEffortMethodLinesResolver implements MethodLinesResolver {

  private final MethodLinesResolver[] delegates;

  public BestEffortMethodLinesResolver(MethodLinesResolver... delegates) {
    this.delegates = delegates;
  }

  @Nonnull
  @Override
  public MethodLines getLines(@Nonnull Method method) {
    for (MethodLinesResolver delegate : delegates) {
      MethodLines lines = delegate.getLines(method);
      if (lines.isValid()) {
        return lines;
      }
    }
    return MethodLines.EMPTY;
  }
}
