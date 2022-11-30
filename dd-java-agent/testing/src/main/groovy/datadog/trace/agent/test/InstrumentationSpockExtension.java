package datadog.trace.agent.test;

import org.spockframework.runtime.AbstractRunListener;
import org.spockframework.runtime.extension.AbstractGlobalExtension;
import org.spockframework.runtime.model.SpecInfo;

public class InstrumentationSpockExtension extends AbstractGlobalExtension {

  @Override
  public void visitSpec(final SpecInfo spec) {}

  private static class ContextClassLoaderListener extends AbstractRunListener {

    private final ClassLoader originalLoader;
    private final ClassLoader customLoader;

    public ContextClassLoaderListener() {}

    @Override
    public void beforeSpec(final SpecInfo spec) {}

    @Override
    public void afterSpec(SpecInfo spec) {}
  }
}
