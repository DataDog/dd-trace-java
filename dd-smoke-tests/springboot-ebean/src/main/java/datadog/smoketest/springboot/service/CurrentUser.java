package datadog.smoketest.springboot.service;

import io.ebean.config.CurrentUserProvider;
import org.springframework.stereotype.Component;

/** Provides the current user to EbeanServer. */
@Component
public class CurrentUser implements CurrentUserProvider {

  @Override
  public Object currentUser() {
    return "test";
  }
}
