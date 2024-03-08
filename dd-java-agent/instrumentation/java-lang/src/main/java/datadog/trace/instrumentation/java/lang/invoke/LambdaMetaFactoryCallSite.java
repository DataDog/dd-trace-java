package datadog.trace.instrumentation.java.lang.invoke;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.Propagation;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.LambdaConversionException;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressForbidden
@Propagation
@CallSite(spi = IastCallSites.class)
public class LambdaMetaFactoryCallSite {

  private static final Logger LOGGER = LoggerFactory.getLogger(LambdaMetaFactoryCallSite.class);

  private static final MethodHandle LAMBDA_PROXY = initLambdaProxy();

  private static MethodHandle initLambdaProxy() {
    try {
      return MethodHandles.lookup()
          .findStatic(
              LambdaMetaFactoryCallSite.class,
              "lambdaInvokeProxy",
              MethodType.methodType(
                  Object.class, MutableCallSite.class, MethodHandle.class, Object[].class));
    } catch (final Throwable e) {
      LOGGER.debug("Cannot fetch lambdaInvokeProxy method", e);
      return null;
    }
  }

  @CallSite.Around(
      value =
          "java.lang.invoke.CallSite java.lang.invoke.LambdaMetafactory.metafactory(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.invoke.MethodType, java.lang.invoke.MethodHandle, java.lang.invoke.MethodType)",
      invokeDynamic = true)
  public static java.lang.invoke.CallSite aroundMetafactory(
      @CallSite.Argument final MethodHandles.Lookup caller,
      @CallSite.Argument final String invokedName,
      @CallSite.Argument final MethodType invokedType,
      @CallSite.Argument final MethodType samMethodType,
      @CallSite.Argument final MethodHandle implMethod,
      @CallSite.Argument final MethodType instantiatedMethodType)
      throws LambdaConversionException {
    java.lang.invoke.CallSite site =
        LambdaMetafactory.metafactory(
            caller, invokedName, invokedType, samMethodType, implMethod, instantiatedMethodType);
    return instrumentLambda(site);
  }

  @CallSite.Around(
      value =
          "java.lang.invoke.CallSite java.lang.invoke.LambdaMetafactory.altMetafactory(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.Object[])",
      invokeDynamic = true)
  public static java.lang.invoke.CallSite aroundAltMetafactory(
      @CallSite.Argument final MethodHandles.Lookup caller,
      @CallSite.Argument final String invokedName,
      @CallSite.Argument final MethodType invokedType,
      @CallSite.Argument final Object... args)
      throws LambdaConversionException {
    java.lang.invoke.CallSite site =
        LambdaMetafactory.altMetafactory(caller, invokedName, invokedType, args);
    return instrumentLambda(site);
  }

  private static java.lang.invoke.CallSite instrumentLambda(
      final java.lang.invoke.CallSite callSite) {
    try {
      if (callSite instanceof ConstantCallSite) {
        final MethodHandle handle = callSite.getTarget();
        if (handle.type().parameterCount() == 0) {
          // we can fetch the actual singleton when the lambda is non-capturing
          final Object lambda = handle.invokeWithArguments();
          if (lambda != null) {
            retransformLambda(lambda.getClass());
          }
        } else if (LAMBDA_PROXY != null) {
          final MutableCallSite site = new MutableCallSite(handle.type());
          MethodHandle proxy = LAMBDA_PROXY;
          proxy = MethodHandles.insertArguments(proxy, 0, site, handle);
          proxy = proxy.asVarargsCollector(Object[].class);
          proxy = proxy.asType(handle.type());
          site.setTarget(proxy);
          return site;
        }
      }
    } catch (Throwable e) {
      LOGGER.debug(SEND_TELEMETRY, "Failed to instrument lambda", e);
    }
    return callSite;
  }

  private static Object lambdaInvokeProxy(
      final MutableCallSite callSite, final MethodHandle original, final Object[] arguments)
      throws Throwable {
    final Object lambda = original.invokeWithArguments(arguments);
    if (lambda != null) {
      retransformLambda(lambda.getClass());
    }
    callSite.setTarget(
        original); // rollback original call site as the lambda has already been instrumented
    return lambda;
  }

  /**
   * This method is in fact a no-op as byte buddy is configured to not instrument lambdas
   *
   * @see net.bytebuddy.agent.builder.AgentBuilder.LambdaInstrumentationStrategy#DISABLED
   */
  private static void retransformLambda(final Class<?> lambda) throws Exception {
    // Utils.getInstrumentation().retransformClasses(lambda);
  }
}
