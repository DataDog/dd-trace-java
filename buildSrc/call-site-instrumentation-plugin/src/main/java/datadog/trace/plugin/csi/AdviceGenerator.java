package datadog.trace.plugin.csi;

import datadog.trace.plugin.csi.ValidationContext.BaseValidationContext;
import datadog.trace.plugin.csi.impl.CallSiteSpecification;
import java.io.File;
import javax.annotation.Nonnull;

/**
 * Implementors of this interface will build the final Java source code files implementing the
 * {@link datadog.trace.agent.tooling.csi.CallSiteAdvice} interface
 */
public interface AdviceGenerator {

  @Nonnull
  CallSiteResult generate(@Nonnull CallSiteSpecification callSite);

  final class CallSiteResult extends BaseValidationContext {

    private final CallSiteSpecification specification;

    private final File file;

    public CallSiteResult(@Nonnull final CallSiteSpecification specification, final File file) {
      this.specification = specification;
      this.file = file;
    }

    public CallSiteSpecification getSpecification() {
      return specification;
    }

    public File getFile() {
      return file;
    }
  }
}
