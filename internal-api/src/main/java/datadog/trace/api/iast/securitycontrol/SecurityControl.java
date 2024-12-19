package datadog.trace.api.iast.securitycontrol;

import java.util.BitSet;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class SecurityControl {

  @Nonnull private SecurityControlType type;

  private int marks;

  @Nonnull private String className;

  @Nonnull private String method;

  @Nullable private List<String> parameterTypes;

  @Nullable private BitSet parametersToMark;

  public SecurityControl(
      @Nonnull SecurityControlType type,
      int marks,
      @Nonnull String className,
      @Nonnull String method,
      @Nullable List<String> parameterTypes,
      @Nullable BitSet parametersToMark) {
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
  public List<String> getParameterTypes() {
    return parameterTypes;
  }

  @Nullable
  public BitSet getParametersToMark() {
    return parametersToMark;
  }

  @Override
  public String toString() {
    return "SecurityControl{"
        + "type="
        + type
        + ", marks="
        + marks
        + ", className='"
        + className
        + '\''
        + ", method='"
        + method
        + '\''
        + ", parameterTypes="
        + parameterTypes
        + ", parametersToMark="
        + parametersToMark
        + '}';
  }
}
