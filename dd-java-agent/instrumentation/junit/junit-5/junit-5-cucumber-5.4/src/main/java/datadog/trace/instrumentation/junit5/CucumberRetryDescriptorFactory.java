package datadog.trace.instrumentation.junit5;

import datadog.trace.instrumentation.junit5.execution.RetryDescriptorFactory;
import datadog.trace.util.MethodHandles;
import io.cucumber.core.gherkin.Pickle;
import java.lang.invoke.MethodHandle;
import java.util.function.UnaryOperator;
import org.junit.platform.commons.util.ClassLoaderUtils;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;

/**
 * Reconstructs the Cucumber retry descriptor ({@code PickleDescriptor}) through its own constructor
 * with a transformed unique id to avoid final-field mutations (JEP 500).
 */
public final class CucumberRetryDescriptorFactory implements RetryDescriptorFactory {

  private static final MethodHandles METHOD_HANDLES =
      new MethodHandles(ClassLoaderUtils.getDefaultClassLoader());

  private static final String PACKAGE = "io.cucumber.junit.platform.engine.";

  private static final MethodHandle CONSTRUCTOR_7_24 =
      METHOD_HANDLES.constructor(
          PACKAGE + "CucumberTestDescriptor$PickleDescriptor",
          JUnitPlatformUtils.loadClass(PACKAGE + "CucumberConfiguration"),
          UniqueId.class,
          String.class,
          TestSource.class,
          Pickle.class);
  private static final MethodHandle CONSTRUCTOR_7_7 =
      METHOD_HANDLES.constructor(
          PACKAGE + "NodeDescriptor$PickleDescriptor",
          ConfigurationParameters.class,
          UniqueId.class,
          String.class,
          TestSource.class,
          Pickle.class);
  private static final MethodHandle CONSTRUCTOR_6_0 =
      METHOD_HANDLES.constructor(
          PACKAGE + "PickleDescriptor",
          ConfigurationParameters.class,
          UniqueId.class,
          String.class,
          TestSource.class,
          Pickle.class);
  private static final MethodHandle CONSTRUCTOR_5_4 =
      METHOD_HANDLES.constructor(
          PACKAGE + "PickleDescriptor",
          UniqueId.class,
          String.class,
          TestSource.class,
          Pickle.class);

  // 7.24+ stores the configuration on the descriptor, read it back for the reconstruction.
  private static final MethodHandle CONFIGURATION_GETTER =
      METHOD_HANDLES.privateFieldGetter(
          PACKAGE + "CucumberTestDescriptor$PickleDescriptor", "configuration");

  // The Pickle field was renamed pickleEvent -> pickle; resolved lazily off the descriptor's class.
  private volatile MethodHandle pickleGetter;

  @Override
  public TestDescriptor copy(TestDescriptor original, UnaryOperator<UniqueId> idTransform) {
    if (!"PickleDescriptor".equals(original.getClass().getSimpleName())) {
      return null; // only the leaf scenario descriptor is retried; containers are filtered earlier
    }
    Object pickle = readPickle(original);
    if (pickle == null) {
      return null;
    }
    UniqueId newId = idTransform.apply(original.getUniqueId());
    String name = original.getDisplayName();
    TestSource source = original.getSource().orElse(null);

    if (CONSTRUCTOR_7_24 != null) {
      Object configuration = METHOD_HANDLES.invoke(CONFIGURATION_GETTER, original);
      return configuration == null
          ? null
          : METHOD_HANDLES.invoke(CONSTRUCTOR_7_24, configuration, newId, name, source, pickle);
    }
    if (CONSTRUCTOR_7_7 != null) {
      return METHOD_HANDLES.invoke(
          CONSTRUCTOR_7_7, new EmptyConfigurationParameters(), newId, name, source, pickle);
    }
    if (CONSTRUCTOR_6_0 != null) {
      return METHOD_HANDLES.invoke(
          CONSTRUCTOR_6_0, new EmptyConfigurationParameters(), newId, name, source, pickle);
    }
    if (CONSTRUCTOR_5_4 != null) {
      return METHOD_HANDLES.invoke(CONSTRUCTOR_5_4, newId, name, source, pickle);
    }
    return null; // unknown cucumber version -> fall back to the generic clone
  }

  private Object readPickle(TestDescriptor descriptor) {
    MethodHandle getter = pickleGetter;
    if (getter == null) {
      getter = METHOD_HANDLES.privateFieldGetter(descriptor.getClass(), "pickle");
      if (getter == null) {
        getter = METHOD_HANDLES.privateFieldGetter(descriptor.getClass(), "pickleEvent");
      }
      pickleGetter = getter;
    }
    return getter != null ? METHOD_HANDLES.invoke(getter, descriptor) : null;
  }
}
