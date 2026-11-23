package datadog.trace.test.junit.utils.converter;

import static java.lang.invoke.MethodType.methodType;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.converter.ArgumentConverter;

/**
 * Converts a method reference written as a string into a {@link Function}, using method handles.
 *
 * <p>The format is {@code ClassName::method}, where further {@code ::method} segments chain calls
 * on the previous result: {@code GitInfo::getCommit::getSha} is equivalent to {@code gitInfo ->
 * gitInfo.getCommit().getSha()}. Methods must be public and take no argument. A simple class name
 * is resolved in the package of the test class.
 */
public class MethodRefGetterConverter implements ArgumentConverter {

  public static final String METHOD_REF_SEP = "::";

  @Override
  public Object convert(Object source, ParameterContext context)
      throws ArgumentConversionException {
    String reference = (String) source;
    List<String> segments = splitSegments(reference);
    if (segments.size() < 2) {
      throw new ArgumentConversionException("Invalid method reference: " + reference);
    }
    Class<?> type = resolveClass(segments.get(0), context);
    // identity handle, so each segment can be appended with filterReturnValue
    MethodHandle chain = MethodHandles.identity(type);
    for (int index = 1; index < segments.size(); index++) {
      String methodName = segments.get(index);
      try {
        // findVirtual needs the full method type: the return type is not part of the reference
        Class<?> returnType = type.getMethod(methodName).getReturnType();
        MethodHandle getter =
            MethodHandles.publicLookup().findVirtual(type, methodName, methodType(returnType));
        chain = MethodHandles.filterReturnValue(chain, getter);
        type = returnType;
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new ArgumentConversionException(
            "No public no-arg method " + methodName + " on " + type.getName(), e);
      }
    }
    MethodHandle function = chain.asType(methodType(Object.class, Object.class));
    return (Function<Object, Object>)
        target -> {
          try {
            return (Object) function.invokeExact(target);
          } catch (RuntimeException | Error e) {
            throw e;
          } catch (Throwable e) {
            throw new IllegalStateException("Failed to invoke " + reference, e);
          }
        };
  }

  private static List<String> splitSegments(String reference) {
    List<String> segments = new ArrayList<>();
    int start = 0;
    int separator;
    while ((separator = reference.indexOf(METHOD_REF_SEP, start)) >= 0) {
      segments.add(reference.substring(start, separator));
      start = separator + METHOD_REF_SEP.length();
    }
    segments.add(reference.substring(start));
    return segments;
  }

  private static Class<?> resolveClass(String className, ParameterContext context) {
    Class<?> testClass = context.getDeclaringExecutable().getDeclaringClass();
    String qualifiedName =
        className.contains(".") ? className : testClass.getPackage().getName() + "." + className;
    try {
      return Class.forName(qualifiedName, false, testClass.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new ArgumentConversionException("Unknown class " + qualifiedName, e);
    }
  }
}
