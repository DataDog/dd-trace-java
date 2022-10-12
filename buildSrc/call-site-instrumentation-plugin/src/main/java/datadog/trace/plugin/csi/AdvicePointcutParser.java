package datadog.trace.plugin.csi;

import datadog.trace.plugin.csi.HasErrors.HasErrorsException;
import datadog.trace.plugin.csi.util.MethodType;
import java.util.Collection;
import javax.annotation.Nonnull;

/**
 * Implementors of this interface will parse pointcut expressions (e.g. {@code
 * java.lang.StringBuilder java.lang.StringBuilder.append(java.lang.String)}) and return the related
 * {@link MethodType} instance.
 */
public interface AdvicePointcutParser {

  @Nonnull
  MethodType parse(@Nonnull String signature);

  class SignatureParsingError extends HasErrorsException {

    public SignatureParsingError(@Nonnull final HasErrors errors) {
      super(errors);
    }

    public SignatureParsingError(@Nonnull final Collection<Failure> errors) {
      super(errors);
    }

    public SignatureParsingError(@Nonnull final Failure... errors) {
      super(errors);
    }
  }
}
