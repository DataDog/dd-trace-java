package smoketest.resteasy;

import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;

import java.util.HashSet;
import java.util.Set;
import javax.ws.rs.core.Application;

public class App extends Application {

  private Set<Object> singletons = new HashSet<Object>();

  public App() {
    singletons.add(new Resource());
    singletons.add(org.jboss.resteasy.plugins.providers.StringTextStar.class); // Writer for String
    singletons.add(new JacksonJsonProvider()); // Writer for json
  }

  @Override
  public Set<Object> getSingletons() {
    return singletons;
  }
}
