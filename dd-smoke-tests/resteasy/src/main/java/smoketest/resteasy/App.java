package smoketest.resteasy;

import java.util.HashSet;
import java.util.Set;
import javax.ws.rs.core.Application;

public class App extends Application {

  private Set<Object> singletons = new HashSet<Object>();

  public App() {
    singletons.add(new Resource());
  }

  @Override
  public Set<Object> getSingletons() {
    return singletons;
  }
}
