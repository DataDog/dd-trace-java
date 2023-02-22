package datadog.trace.plugin.csi;

import datadog.trace.plugin.csi.ValidationContext.BaseValidationContext;
import datadog.trace.plugin.csi.impl.CallSiteSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AdviceSpecification;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
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
    private final List<AdviceResult> advices = new ArrayList<>();

    public CallSiteResult(@Nonnull final CallSiteSpecification specification) {
      this.specification = specification;
    }

    @Override
    public boolean isSuccess() {
      return super.isSuccess() && getAdvices().allMatch(AdviceResult::isSuccess);
    }

    public Stream<AdviceResult> getAdvices() {
      return advices.stream();
    }

    public void addAdvice(final AdviceResult advice) {
      this.advices.add(advice);
    }

    public CallSiteSpecification getSpecification() {
      return specification;
    }
  }

  final class AdviceResult extends BaseValidationContext {

    private final AdviceSpecification specification;
    private final File file;

    public AdviceResult(
        @Nonnull final AdviceSpecification specification, @Nonnull final File file) {
      this.specification = specification;
      this.file = file;
    }

    public AdviceSpecification getSpecification() {
      return specification;
    }

    public File getFile() {
      return file;
    }
  }
}
