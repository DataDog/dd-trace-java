package datadog.trace.plugin.csi.util;

import static datadog.trace.plugin.csi.util.CallSiteConstants.CONSTRUCTOR_METHOD;

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.objectweb.asm.Type;

/** Description of a method including its declaring type, name and descriptor. */
public class MethodType {

  private final Type owner;
  private final String methodName;
  private final Type methodType;
  private final boolean constructor;

  public MethodType(
      @Nonnull final Type owner, @Nonnull final String methodName, @Nonnull final Type methodType) {
    if (owner.getSort() == Type.METHOD) {
      throw new IllegalArgumentException("Owner should not be a method " + owner);
    }
    if (methodType.getSort() != Type.METHOD) {
      throw new IllegalArgumentException("Type should be a method " + methodType);
    }
    this.owner = owner;
    this.methodName = methodName;
    this.methodType = methodType;
    constructor = CONSTRUCTOR_METHOD.equals(methodName);
  }

  public Type getOwner() {
    return owner;
  }

  public String getMethodName() {
    return methodName;
  }

  public Type getMethodType() {
    return methodType;
  }

  public boolean isConstructor() {
    return constructor;
  }

  public boolean isVoidReturn() {
    return Type.VOID_TYPE.equals(methodType.getReturnType());
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    MethodType that = (MethodType) o;
    return Objects.equals(owner, that.owner)
        && Objects.equals(methodName, that.methodName)
        && Objects.equals(methodType, that.methodType);
  }

  @Override
  public int hashCode() {
    return Objects.hash(owner, methodName, methodType);
  }

  @Override
  public String toString() {
    return String.format(
        "%s %s.%s(%s)",
        methodType.getReturnType().getClassName(),
        owner.getClassName(),
        methodName,
        Arrays.stream(methodType.getArgumentTypes())
            .map(Type::getClassName)
            .collect(Collectors.joining(", ")));
  }
}
