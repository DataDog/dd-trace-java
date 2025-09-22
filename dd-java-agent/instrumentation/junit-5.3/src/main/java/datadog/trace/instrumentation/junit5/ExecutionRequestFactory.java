package datadog.trace.instrumentation.junit5;

import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.function.BiFunction;
import org.junit.platform.commons.util.ClassLoaderUtils;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;

public class ExecutionRequestFactory {

  private static final MethodHandles METHOD_HANDLES =
      new MethodHandles(ClassLoaderUtils.getDefaultClassLoader());

  /*
   * From 5.13.0-RC1 onwards ExecutionRequest requires two additional arguments on creation.
   * - OutputDirectoryProvider outputDirectoryProvider
   * - NamespacedHierarchicalStore<Namespace> requestLevelStore
   */
  private static final MethodHandle GET_OUTPUT_DIRECTORY_PROVIDER =
      METHOD_HANDLES.method(ExecutionRequest.class, "getOutputDirectoryProvider");
  private static final MethodHandle GET_STORE =
      METHOD_HANDLES.method(ExecutionRequest.class, "getStore");
  /*
   * From 6.0.0-M2 onwards CancellationToken is also required.
   */
  private static final MethodHandle GET_CANCELLATION_TOKEN =
      METHOD_HANDLES.method(ExecutionRequest.class, "getCancellationToken");

  private static final String[] CREATE_PARAMETER_TYPES =
      new String[] {
        "org.junit.platform.engine.TestDescriptor",
        "org.junit.platform.engine.EngineExecutionListener",
        "org.junit.platform.engine.ConfigurationParameters",
        "org.junit.platform.engine.reporting.OutputDirectoryProvider",
        "org.junit.platform.engine.support.store.NamespacedHierarchicalStore",
        "org.junit.platform.engine.CancellationToken"
      };

  private static final BiFunction<ExecutionRequest, EngineExecutionListener, ExecutionRequest>
      EXECUTION_REQUEST_CREATE = createExecutionRequestHandle();

  private static BiFunction<ExecutionRequest, EngineExecutionListener, ExecutionRequest>
      createExecutionRequestHandle() {
    // 6.0.0-M2 and later
    if (GET_CANCELLATION_TOKEN != null) {
      MethodHandle createMethod =
          METHOD_HANDLES.method(
              ExecutionRequest.class,
              m ->
                  "create".equals(m.getName())
                      && m.getParameterCount() == 6
                      && Arrays.equals(
                          Arrays.stream(m.getParameterTypes()).map(Class::getName).toArray(),
                          CREATE_PARAMETER_TYPES));

      return (request, listener) -> {
        Object provider = METHOD_HANDLES.invoke(GET_OUTPUT_DIRECTORY_PROVIDER, request);
        Object store = METHOD_HANDLES.invoke(GET_STORE, request);
        Object cancellationToken = METHOD_HANDLES.invoke(GET_CANCELLATION_TOKEN, request);
        return METHOD_HANDLES.invoke(
            createMethod,
            request.getRootTestDescriptor(),
            listener,
            request.getConfigurationParameters(),
            provider,
            store,
            cancellationToken);
      };
    }

    // 5.13.0-RC1 and later
    if (GET_STORE != null && GET_OUTPUT_DIRECTORY_PROVIDER != null) {
      MethodHandle createMethod =
          METHOD_HANDLES.method(
              ExecutionRequest.class,
              m ->
                  "create".equals(m.getName())
                      && m.getParameterCount() == 5
                      && Arrays.equals(
                          Arrays.stream(m.getParameterTypes()).map(Class::getName).toArray(),
                          Arrays.copyOf(CREATE_PARAMETER_TYPES, 5)));

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

  public static ExecutionRequest createExecutionRequest(
      ExecutionRequest request, EngineExecutionListener listener) {
    return EXECUTION_REQUEST_CREATE.apply(request, listener);
  }
}
