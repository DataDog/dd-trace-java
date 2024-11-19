package datadog.trace.civisibility.source;

import datadog.compiler.utils.CompilerUtils;
import java.lang.reflect.Method;
import javax.annotation.Nonnull;

public class CompilerAidedLinesResolver implements LinesResolver {
  @Nonnull
  @Override
  public Lines getMethodLines(@Nonnull Method method) {
    int startLine = CompilerUtils.getStartLine(method);
    if (startLine <= 0) {
      return Lines.EMPTY;
    }
    int endLine = CompilerUtils.getEndLine(method);
    if (endLine <= 0) {
      return Lines.EMPTY;
    }
    return new Lines(startLine, endLine);
  }

  @Nonnull
  @Override
  public Lines getClassLines(@Nonnull Class<?> clazz) {
    int startLine = CompilerUtils.getStartLine(clazz);
    if (startLine <= 0) {
      return Lines.EMPTY;
    }
    int endLine = CompilerUtils.getEndLine(clazz);
    if (endLine <= 0) {
      return Lines.EMPTY;
    }
    return new Lines(startLine, endLine);
  }
}
