package datadog.trace.instrumentation.junit5;

import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.function.BiFunction;
import javax.annotation.Nullable;
import org.junit.platform.commons.util.ClassLoaderUtils;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;

public class ExecutionRequestFactory {

  private static final MethodHandles METHOD_HANDLES =
      new MethodHandles(ClassLoaderUtils.getDefaultClassLoader());

  /*
   * From 5.13.0 onwards ExecutionRequest requires two additional arguments on creation.
   * - OutputDirectoryProvider outputDirectoryProvider
   * - NamespacedHierarchicalStore<Namespace> requestLevelStore
   */
  private static final MethodHandle GET_OUTPUT_DIRECTORY_PROVIDER =
      METHOD_HANDLES.method(ExecutionRequest.class, "getOutputDirectoryProvider");
  private static final MethodHandle GET_STORE =
      METHOD_HANDLES.method(ExecutionRequest.class, "getStore");
  /*
   * From 6.0.0 onwards CancellationToken is also required.
   */
  private static final MethodHandle GET_CANCELLATION_TOKEN =
      METHOD_HANDLES.method(ExecutionRequest.class, "getCancellationToken");
  /*
   * From 6.0.0 onwards (also applicable for 5.14.0 onwards) OutputDirectoryProvider is deprecated in favor of OutputDirectoryCreator
   */
  private static final MethodHandle GET_OUTPUT_DIRECTORY_CREATOR =
      METHOD_HANDLES.method(ExecutionRequest.class, "getOutputDirectoryCreator");

  private static final String[] PARAMETERS_JUNIT6 =
      new String[] {
        "org.junit.platform.engine.TestDescriptor",
        "org.junit.platform.engine.EngineExecutionListener",
        "org.junit.platform.engine.ConfigurationParameters",
        "org.junit.platform.engine.OutputDirectoryCreator",
        "org.junit.platform.engine.support.store.NamespacedHierarchicalStore",
        "org.junit.platform.engine.CancellationToken"
      };

  private static final String[] PARAMETERS_JUNIT514 =
      new String[] {
        "org.junit.platform.engine.TestDescriptor",
        "org.junit.platform.engine.EngineExecutionListener",
        "org.junit.platform.engine.ConfigurationParameters",
        "org.junit.platform.engine.OutputDirectoryCreator",
        "org.junit.platform.engine.support.store.NamespacedHierarchicalStore",
      };

  private static final String[] PARAMETERS_JUNIT513 =
      new String[] {
        "org.junit.platform.engine.TestDescriptor",
        "org.junit.platform.engine.EngineExecutionListener",
        "org.junit.platform.engine.ConfigurationParameters",
        "org.junit.platform.engine.reporting.OutputDirectoryProvider",
        "org.junit.platform.engine.support.store.NamespacedHierarchicalStore",
      };

  private static final String[] PARAMETERS_FALLBACK =
      new String[] {
        "org.junit.platform.engine.TestDescriptor",
        "org.junit.platform.engine.EngineExecutionListener",
        "org.junit.platform.engine.ConfigurationParameters",
      };

  private static final BiFunction<ExecutionRequest, EngineExecutionListener, ExecutionRequest>
      EXECUTION_REQUEST_CREATE = createExecutionRequestHandle();

  private static BiFunction<ExecutionRequest, EngineExecutionListener, ExecutionRequest>
      createExecutionRequestHandle() {
    BiFunction<ExecutionRequest, EngineExecutionListener, ExecutionRequest> factory;

    factory = junit6Factory();
    if (factory != null) {
      return factory;
    }

    factory = junit514Factory();
    if (factory != null) {
      return factory;
    }

    factory = junit513Factory();
    if (factory != null) {
      return factory;
    }

    return fallbackFactory();
  }

  private static BiFunction<ExecutionRequest, EngineExecutionListener, ExecutionRequest>
      junit6Factory() {
    if (GET_OUTPUT_DIRECTORY_CREATOR == null
        || GET_STORE == null
        || GET_CANCELLATION_TOKEN == null) {
      return null;
    }

    MethodHandle createMethod = findCreateMethod(PARAMETERS_JUNIT6);
    if (createMethod == null) {
      return null;
    }

    return (request, listener) -> {
      Object creator = METHOD_HANDLES.invoke(GET_OUTPUT_DIRECTORY_CREATOR, request);
      Object store = METHOD_HANDLES.invoke(GET_STORE, request);
      Object cancellationToken = METHOD_HANDLES.invoke(GET_CANCELLATION_TOKEN, request);
      return METHOD_HANDLES.invoke(
          createMethod,
          request.getRootTestDescriptor(),
          listener,
          request.getConfigurationParameters(),
          creator,
          store,
          cancellationToken);
    };
  }

  private static BiFunction<ExecutionRequest, EngineExecutionListener, ExecutionRequest>
      junit514Factory() {
    if (GET_OUTPUT_DIRECTORY_CREATOR == null || GET_STORE == null) {
      return null;
    }

    MethodHandle createMethod = findCreateMethod(PARAMETERS_JUNIT514);
    if (createMethod == null) {
      return null;
    }

    return (request, listener) -> {
      Object creator = METHOD_HANDLES.invoke(GET_OUTPUT_DIRECTORY_CREATOR, request);
      Object store = METHOD_HANDLES.invoke(GET_STORE, request);
      return METHOD_HANDLES.invoke(
          createMethod,
          request.getRootTestDescriptor(),
          listener,
          request.getConfigurationParameters(),
          creator,
          store);
    };
  }

  private static BiFunction<ExecutionRequest, EngineExecutionListener, ExecutionRequest>
      junit513Factory() {
    if (GET_OUTPUT_DIRECTORY_PROVIDER == null || GET_STORE == null) {
      return null;
    }

    MethodHandle createMethod = findCreateMethod(PARAMETERS_JUNIT513);
    if (createMethod == null) {
      return null;
    }

    return (request, listener) -> {
      Object provider = METHOD_HANDLES.invoke(GET_OUTPUT_DIRECTORY_PROVIDER, request);
      Object store = METHOD_HANDLES.invoke(GET_STORE, request);
      return METHOD_HANDLES.invoke(
          createMethod,
          request.getRootTestDescriptor(),
          listener,
          request.getConfigurationParameters(),
          provider,
          store);
    };
  }

  private static BiFunction<ExecutionRequest, EngineExecutionListener, ExecutionRequest>
      fallbackFactory() {
    MethodHandle constructor =
        METHOD_HANDLES.constructor(
            ExecutionRequest.class,
            TestDescriptor.class,
            EngineExecutionListener.class,
            ConfigurationParameters.class);

    return (request, listener) ->
        METHOD_HANDLES.invoke(
            constructor,
            request.getRootTestDescriptor(),
            listener,
            request.getConfigurationParameters());
  }

  @Nullable
  private static MethodHandle findCreateMethod(String... parameterTypes) {
    return METHOD_HANDLES.method(
        ExecutionRequest.class,
        m ->
            "create".equals(m.getName())
                && m.getParameterCount() == parameterTypes.length
                && Arrays.equals(
                    Arrays.stream(m.getParameterTypes()).map(Class::getName).toArray(),
                    parameterTypes));
  }

  public static ExecutionRequest createExecutionRequest(
      ExecutionRequest request, EngineExecutionListener listener) {
    return EXECUTION_REQUEST_CREATE.apply(request, listener);
  }
}
