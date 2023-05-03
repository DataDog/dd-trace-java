package datadog.trace.plugin.csi.impl;

import datadog.trace.plugin.csi.AdviceGenerator;
import datadog.trace.plugin.csi.AdvicePointcutParser;
import datadog.trace.plugin.csi.SpecificationBuilder;
import datadog.trace.plugin.csi.TypeResolver;
import java.io.File;
import javax.annotation.Nonnull;

public abstract class CallSiteFactory {

  private CallSiteFactory() {}

  public static AdviceGenerator adviceGenerator(final File targetFolder) {
    return adviceGenerator(targetFolder, typeResolver());
  }

  public static AdviceGenerator adviceGenerator(
      @Nonnull final File targetFolder, @Nonnull final TypeResolver typeResolver) {
    return new AdviceGeneratorImpl(targetFolder, pointcutParser(), typeResolver);
  }

  public static SpecificationBuilder specificationBuilder() {
    return new AsmSpecificationBuilder();
  }

  public static AdvicePointcutParser pointcutParser() {
    return new RegexpAdvicePointcutParser();
  }

  public static TypeResolver typeResolver() {
    return typeResolver(Thread.currentThread().getContextClassLoader());
  }

  public static TypeResolver typeResolver(@Nonnull final ClassLoader... classpath) {
    return new TypeResolverPool(classpath);
  }
}
