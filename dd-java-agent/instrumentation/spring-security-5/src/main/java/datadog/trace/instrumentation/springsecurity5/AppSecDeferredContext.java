package datadog.trace.instrumentation.springsecurity5;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContext;

public class AppSecDeferredContext implements Supplier<SecurityContext> {

  private static final Logger LOGGER = LoggerFactory.getLogger(AppSecDeferredContext.class);

  private final Supplier<SecurityContext> delegate;

  public AppSecDeferredContext(final Supplier<SecurityContext> delegate) {
    this.delegate = delegate;
  }

  @Override
  public SecurityContext get() {
    SecurityContext context = delegate.get();
    if (context != null) {
      try {
        SpringSecurityUserEventDecorator.DECORATE.onUser(context.getAuthentication());
      } catch (Throwable e) {
        LOGGER.debug("Error handling user event", e);
      }
    }
    return context;
  }
}
