package datadog.trace.civisibility.source;

import datadog.compiler.utils.CompilerUtils;
import java.lang.reflect.Method;
import javax.annotation.Nonnull;

public class CompilerAidedMethodLinesResolver implements MethodLinesResolver {
  @Nonnull
  @Override
  public MethodLines getLines(@Nonnull Method method) {
    int startLine = CompilerUtils.getStartLine(method);
    if (startLine <= 0) {
      return MethodLines.EMPTY;
    }
    int endLine = CompilerUtils.getEndLine(method);
    if (endLine <= 0) {
      return MethodLines.EMPTY;
    }
    return new MethodLines(startLine, endLine);
  }
}
