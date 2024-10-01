package datadog.trace.instrumentation.springsecurity5;

import java.util.function.Supplier;
import org.springframework.security.core.context.SecurityContext;

public class AppSecDeferredContext implements Supplier<SecurityContext> {

  private final Supplier<SecurityContext> delegate;

  public AppSecDeferredContext(final Supplier<SecurityContext> delegate) {
    this.delegate = delegate;
  }

  @Override
  public SecurityContext get() {
    SecurityContext context = delegate.get();
    if (context != null) {
      SpringSecurityUserEventDecorator.DECORATE.onUser(context.getAuthentication());
    }
    return context;
  }
}
