package datadog.trace.api.iast.securitycontrol;

import datadog.trace.api.iast.VulnerabilityMarks;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class SecurityControl {

  @Nonnull
  private SecurityControlType type;

  private int marks;

  @Nonnull
  private String className ;

  @Nonnull
  private String method;

  @Nullable
  private String[] parameterTypes;

  @Nullable
  private int[] parametersToMark;


  public SecurityControl(
      @Nonnull SecurityControlType type,
      int marks,
      @Nonnull String className,
      @Nonnull String method,
      @Nullable String[] parameterTypes,
      @Nullable int[] parametersToMark) {
    this.type = type;
    this.marks = marks;
    this.className = className;
    this.method = method;
    this.parameterTypes = parameterTypes;
    this.parametersToMark = parametersToMark;
  }

  @Nonnull
  public SecurityControlType getType() {
    return type;
  }

  @Nonnull
  public int getMarks() {
    return marks;
  }

  @Nonnull
  public String getClassName() {
    return className;
  }

  @Nonnull
  public String getMethod() {
    return method;
  }

  @Nullable
  public String[] getParameterTypes() {
    return parameterTypes;
  }

  @Nullable
  public int[] getParametersToMark() {
    return parametersToMark;
  }

}
