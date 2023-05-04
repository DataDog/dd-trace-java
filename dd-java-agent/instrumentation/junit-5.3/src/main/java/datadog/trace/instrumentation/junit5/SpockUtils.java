package datadog.trace.instrumentation.junit5;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import org.junit.platform.engine.support.descriptor.MethodSource;

public class SpockUtils {

  public static Method getTestMethod(MethodSource methodSource) {
    String methodName = methodSource.getMethodName();
    if (methodName == null) {
      return null;
    }

    Class<?> testClass = JUnit5Utils.getTestClass(methodSource);
    if (testClass == null) {
      return null;
    }

    try {
      // annotation class has to be loaded like this since at runtime
      // it is absent from the classloader that loads instrumentation code
      Class<Annotation> featureMetadataClass =
          (Class<Annotation>)
              testClass
                  .getClassLoader()
                  .loadClass("org.spockframework.runtime.model.FeatureMetadata");
      Method nameMethod = featureMetadataClass.getDeclaredMethod("name");

      for (Method declaredMethod : testClass.getDeclaredMethods()) {
        Annotation featureMetadata = declaredMethod.getAnnotation(featureMetadataClass);
        if (featureMetadata == null) {
          continue;
        }

        String annotatedName = (String) nameMethod.invoke(featureMetadata);
        if (methodName.equals(annotatedName)) {
          return declaredMethod;
        }
      }

    } catch (Exception e) {
      // ignore
    }

    return null;
  }
}
