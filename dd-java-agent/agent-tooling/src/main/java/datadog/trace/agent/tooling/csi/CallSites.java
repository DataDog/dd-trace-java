package datadog.trace.agent.tooling.csi;

import java.util.function.Consumer;

public interface CallSites extends Consumer<CallSites.Container> {

  interface Container {
    default void addAdvice(
        final byte type,
        final String owner,
        final String method,
        final String descriptor,
        final InvokeAdvice advice) {
      addAdvice(type, owner, method, descriptor, (CallSiteAdvice) advice);
    }

    default void addAdvice(
        final byte type,
        final String owner,
        final String method,
        final String descriptor,
        final InvokeDynamicAdvice advice) {
      addAdvice(type, owner, method, descriptor, (CallSiteAdvice) advice);
    }

    void addAdvice(
        byte kind, String owner, String method, String descriptor, CallSiteAdvice advice);

    void addHelpers(String... helperClassNames);
  }

  interface HasEnabledProperty {
    boolean isEnabled();
  }
}
