package datadog.trace.plugin.csi.impl;

import static datadog.trace.plugin.csi.util.CallSiteUtils.classNameToDescriptor;
import static datadog.trace.plugin.csi.util.CallSiteUtils.classNameToType;
import static datadog.trace.plugin.csi.util.CallSiteUtils.repeat;

import datadog.trace.plugin.csi.AdvicePointcutParser;
import datadog.trace.plugin.csi.HasErrors;
import datadog.trace.plugin.csi.HasErrors.Failure;
import datadog.trace.plugin.csi.HasErrors.HasErrorsImpl;
import datadog.trace.plugin.csi.util.ErrorCode;
import datadog.trace.plugin.csi.util.MethodType;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import org.objectweb.asm.Type;

/**
 * Implementation of {@link AdvicePointcutParser} using a simple regexp expression to extract the
 * {@link MethodType} of the pointcut
 */
public class RegexpAdvicePointcutParser implements AdvicePointcutParser {

  private static final Pattern ADVICE_SIGNATURE_PATTERN =
      Pattern.compile("^(?<return>\\S*)\\s+(?<type>\\S*)\\.(?<method>\\S*)\\s*\\((?<args>.*)\\)$");
  private static final char ARRAY_DESCRIPTOR = '[';
  private static final Map<String, Type> PRIMITIVE_TYPES = new HashMap<>(9);

  static {
    PRIMITIVE_TYPES.put("byte", Type.BYTE_TYPE);
    PRIMITIVE_TYPES.put("char", Type.CHAR_TYPE);
    PRIMITIVE_TYPES.put("double", Type.DOUBLE_TYPE);
    PRIMITIVE_TYPES.put("float", Type.FLOAT_TYPE);
    PRIMITIVE_TYPES.put("int", Type.INT_TYPE);
    PRIMITIVE_TYPES.put("long", Type.LONG_TYPE);
    PRIMITIVE_TYPES.put("short", Type.SHORT_TYPE);
    PRIMITIVE_TYPES.put("boolean", Type.BOOLEAN_TYPE);
    PRIMITIVE_TYPES.put("void", Type.VOID_TYPE);
  }

  @Override
  @Nonnull
  public MethodType parse(@Nonnull final String signature) {
    final Matcher matcher = ADVICE_SIGNATURE_PATTERN.matcher(signature.trim());
    if (!matcher.matches()) {
      final String pattern = ADVICE_SIGNATURE_PATTERN.pattern();
      throw new SignatureParsingError(
          new Failure(ErrorCode.POINTCUT_SIGNATURE_INVALID, signature, pattern));
    }
    final HasErrors errors = new HasErrorsImpl();
    final Type target = parseTarget(signature, matcher, errors);
    final Type returnType = parseReturn(signature, matcher, errors);
    final Type[] argTypes = parseArguments(signature, matcher, errors);
    if (!errors.isSuccess()) {
      throw new SignatureParsingError(errors);
    }
    final String method = matcher.group("method").trim();
    assert target != null && returnType != null;
    return new MethodType(target, method, Type.getMethodType(returnType, argTypes));
  }

  private static Type parseTarget(
      @Nonnull final String signature,
      @Nonnull final Matcher matcher,
      @Nonnull final HasErrors errors) {
    final String typeName = matcher.group("type");
    try {
      return parseType(typeName);
    } catch (Throwable e) {
      errors.addError(e, ErrorCode.POINTCUT_SIGNATURE_INVALID_TYPE, signature, typeName);
      return null;
    }
  }

  private static Type parseReturn(
      @Nonnull final String signature,
      @Nonnull final Matcher matcher,
      @Nonnull final HasErrors errors) {
    final String returnTypeName = matcher.group("return").trim();
    try {
      return parseType(returnTypeName);
    } catch (Throwable e) {
      errors.addError(e, ErrorCode.POINTCUT_SIGNATURE_INVALID_TYPE, signature, returnTypeName);
      return null;
    }
  }

  private static Type[] parseArguments(
      @Nonnull final String signature,
      @Nonnull final Matcher matcher,
      @Nonnull final HasErrors errors) {
    final String argsGroup = matcher.group("args");
    final String names = argsGroup == null ? "" : argsGroup.trim();
    if (names.isEmpty()) {
      return new Type[0];
    }
    String[] argNames = names.split(",");
    Type[] result = new Type[argNames.length];
    for (int i = 0; i < argNames.length; i++) {
      String argTypeName = argNames[i];
      try {
        result[i] = parseType(argTypeName.trim());
      } catch (Throwable e) {
        errors.addError(e, ErrorCode.POINTCUT_SIGNATURE_INVALID_TYPE, signature, argTypeName);
      }
    }
    return result;
  }

  private static Type parseType(final String name) {
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException("Type name cannot be null or empty");
    }
    int startOfArray = name.indexOf(ARRAY_DESCRIPTOR);
    if (startOfArray >= 0) {
      final Type arrayType = parseType(name.substring(0, startOfArray));
      String arrayDeclaration = name.substring(startOfArray);
      int dimension =
          (int)
              arrayDeclaration
                  .chars()
                  .filter(it -> it == ARRAY_DESCRIPTOR)
                  .count(); // assumes array notation is well-formed
      String elementType =
          arrayType.getSort() == Type.OBJECT
              ? classNameToDescriptor(arrayType.getClassName())
              : arrayType.getInternalName();
      return Type.getType(repeat(ARRAY_DESCRIPTOR, dimension) + elementType);
    }
    return classNameOrPrimitiveToType(name);
  }

  public static Type classNameOrPrimitiveToType(final String name) {
    if (name == null || name.isEmpty()) {
      return null;
    }
    final Type type = PRIMITIVE_TYPES.get(name);
    return type != null ? type : classNameToType(name);
  }
}
