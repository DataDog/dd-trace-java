package stackstate.example.dropwizard;

import io.dropwizard.Application;
import io.dropwizard.Configuration;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import stackstate.example.dropwizard.resources.SimpleCrudResource;

public class BookApplication extends Application<Configuration> {
  public static void main(final String[] args) throws Exception {
    new BookApplication().run(args);
  }

  @Override
  public String getName() {
    return "hello-world";
  }

  @Override
  public void initialize(final Bootstrap<Configuration> bootstrap) {
    // nothing to do yet
  }

  @Override
  public void run(final Configuration configuration, final Environment environment) {

    environment.jersey().register(new SimpleCrudResource());
  }
}
