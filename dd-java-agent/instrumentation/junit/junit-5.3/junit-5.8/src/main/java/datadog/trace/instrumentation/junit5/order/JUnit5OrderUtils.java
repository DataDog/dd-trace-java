package datadog.trace.instrumentation.junit5.order;

import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import org.junit.jupiter.api.ClassDescriptor;
import org.junit.jupiter.api.MethodDescriptor;
import org.junit.platform.commons.util.ClassLoaderUtils;
import org.junit.platform.engine.TestDescriptor;

public class JUnit5OrderUtils {

  private static final MethodHandles METHOD_HANDLES =
      new MethodHandles(ClassLoaderUtils.getDefaultClassLoader());

  private static final MethodHandle GET_TEST_DESCRIPTOR =
      METHOD_HANDLES.privateFieldGetter(
          "org.junit.jupiter.engine.discovery.AbstractAnnotatedDescriptorWrapper",
          "testDescriptor");

  public static TestDescriptor getTestDescriptor(ClassDescriptor classDescriptor) {
    return METHOD_HANDLES.invoke(GET_TEST_DESCRIPTOR, classDescriptor);
  }

  public static TestDescriptor getTestDescriptor(MethodDescriptor methodDescriptor) {
    return METHOD_HANDLES.invoke(GET_TEST_DESCRIPTOR, methodDescriptor);
  }
}
