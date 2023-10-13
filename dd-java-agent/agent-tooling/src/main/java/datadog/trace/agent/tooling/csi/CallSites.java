package datadog.trace.agent.tooling.csi;

import java.util.function.Consumer;

public interface CallSites extends Consumer<CallSites.Container> {

  interface Container {
    default void addAdvice(
        final String type,
        final String method,
        final String descriptor,
        final InvokeAdvice advice) {
      addAdvice(type, method, descriptor, (CallSiteAdvice) advice);
    }

    default void addAdvice(
        final String type,
        final String method,
        final String descriptor,
        final InvokeDynamicAdvice advice) {
      addAdvice(type, method, descriptor, (CallSiteAdvice) advice);
    }

    void addAdvice(String type, String method, String descriptor, CallSiteAdvice advice);

    void addHelpers(String... helperClassNames);
  }

  interface HasEnabledProperty {
    boolean isEnabled();
  }
}
