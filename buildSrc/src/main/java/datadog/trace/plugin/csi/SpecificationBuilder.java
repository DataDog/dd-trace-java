package datadog.trace.plugin.csi;

import datadog.trace.plugin.csi.impl.CallSiteSpecification;
import java.io.File;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Implementors of this interface will take a Java class file and build the related {@link
 * CallSiteSpecification}
 *
 * <p>If the class is not annotated with {@link datadog.trace.agent.tooling.csi.CallSite}
 * implementors should return an empty optional
 */
public interface SpecificationBuilder {

  @Nonnull
  Optional<CallSiteSpecification> build(@Nonnull File classFile);
}
