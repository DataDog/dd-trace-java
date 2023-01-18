package datadog.trace.bootstrap.instrumentation.ci.source;

import datadog.compiler.utils.CompilerUtils;
import javax.annotation.Nullable;

public class CompilerAidedSourcePathResolver implements SourcePathResolver {

  @Nullable
  @Override
  public String getSourcePath(Class<?> c) {
    try {
      return CompilerUtils.getSourcePath(c);
    } catch (Exception e) {
      return null;
    }
  }
}
