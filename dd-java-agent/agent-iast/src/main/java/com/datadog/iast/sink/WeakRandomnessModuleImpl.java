package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.sink.WeakRandomnessModule;
import java.util.Locale;
import javax.annotation.Nonnull;

public class WeakRandomnessModuleImpl extends SinkModuleBase implements WeakRandomnessModule {

  public WeakRandomnessModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onWeakRandom(@Nonnull final Class<?> instance) {
    if (isSecuredInstance(instance)) {
      return;
    }
    report(VulnerabilityType.WEAK_RANDOMNESS, new Evidence(instance.getName()));
  }

  /**
   * Skip vulnerabilities on {@link java.security.SecureRandom} or any impl that contains secure in
   * the name
   */
  private boolean isSecuredInstance(@Nonnull final Class<?> instance) {
    return instance.getSimpleName().toLowerCase(Locale.ROOT).contains("secure");
  }
}
