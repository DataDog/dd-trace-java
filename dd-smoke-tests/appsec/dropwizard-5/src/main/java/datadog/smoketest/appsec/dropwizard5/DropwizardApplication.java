package datadog.smoketest.appsec.dropwizard5;

import io.dropwizard.configuration.ResourceConfigurationSourceProvider;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;

/** Dropwizard 5 application reproducing a Jetty 12 ee10 + Jersey 3 stack. */
public class DropwizardApplication extends Application<AppConfiguration> {

  public static void main(final String[] args) throws Exception {
    new DropwizardApplication().run("server", "smoke-config.yml");
  }

  @Override
  public void initialize(final Bootstrap<AppConfiguration> bootstrap) {
    // Load the configuration from the classpath so the fat jar is self-contained
    bootstrap.setConfigurationSourceProvider(new ResourceConfigurationSourceProvider());
  }

  @Override
  public void run(final AppConfiguration configuration, final Environment environment) {
    environment.jersey().register(new AppResource());
  }
}
