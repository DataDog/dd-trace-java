package datadog.trace.instrumentation.jersey2_21;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.glassfish.jersey.server.spi.internal.ParamValueFactoryWithSource;

public class ExtractPathParamsHelper {
  private static final MethodHandle PARAMETER_FUNCTION_FIELD_GETTER;
  private static final Class<?> SEGMENT_VALUE_SUPPLIER_CLASS;
  private static final MethodHandle NAME_FIELD_GETTER;
  private static final MethodHandle SOURCE_GETTER;

  static {
    MethodHandles.Lookup lookup = MethodHandles.lookup();
    try {
      Field parameterFunction;
      try {
        parameterFunction = ParamValueFactoryWithSource.class.getDeclaredField("parameterFunction");
      } catch (NoSuchFieldException nsfe) {
        parameterFunction = ParamValueFactoryWithSource.class.getDeclaredField("factory");
      }
      parameterFunction.setAccessible(true);
      PARAMETER_FUNCTION_FIELD_GETTER = lookup.unreflectGetter(parameterFunction);

      Method getSource = ParamValueFactoryWithSource.class.getDeclaredMethod("getSource");
      SOURCE_GETTER = lookup.unreflect(getSource);

      Class<?> factoryClass;
      try {
        factoryClass =
            Class.forName(
                "org.glassfish.jersey.server.internal.inject.PathParamValueParamProvider$PathParamValueProvider");
      } catch (ClassNotFoundException cnfe) {
        // older versions
        factoryClass =
            Class.forName(
                "org.glassfish.jersey.server.internal.inject.PathParamValueFactoryProvider$PathParamValueFactory");
      }
      SEGMENT_VALUE_SUPPLIER_CLASS = factoryClass;

      Field extractor = factoryClass.getDeclaredField("extractor");
      extractor.setAccessible(true);
      MethodHandle extractorGetter = lookup.unreflectGetter(extractor);
      Class<?> extractorClass =
          Class.forName(
              "org.glassfish.jersey.server.internal.inject.MultivaluedParameterExtractor");
      MethodHandle getName =
          lookup.findVirtual(extractorClass, "getName", MethodType.methodType(String.class));
      NAME_FIELD_GETTER = MethodHandles.filterReturnValue(extractorGetter, getName);
    } catch (NoSuchFieldException
        | IllegalAccessException
        | ClassNotFoundException
        | NoSuchMethodException e) {
      throw new UndeclaredThrowableException(e);
    }
  }

  public static Map<String, String> buildParamMap(
      final Object[] values, List<ParamValueFactoryWithSource<?>> params) {
    Map<String, String> map = new HashMap<>();
    int idx = 0;
    for (ParamValueFactoryWithSource<?> p : params) {
      Object value = values[idx++];
      if (value == null) {
        continue;
      }
      try {
        if (!((Enum) SOURCE_GETTER.invoke(p)).name().equals("PATH")) {
          continue;
        }
        Object f = PARAMETER_FUNCTION_FIELD_GETTER.invoke(p);
        if (SEGMENT_VALUE_SUPPLIER_CLASS.isAssignableFrom(f.getClass())) {
          String name = (String) NAME_FIELD_GETTER.invoke(f);
          map.put(name, value.toString());
        }
      } catch (Throwable throwable) {
        if (throwable instanceof RuntimeException) {
          throw (RuntimeException) throwable;
        }
        throw new UndeclaredThrowableException(throwable);
      }
    }

    return map;
  }
}
