import com.sun.jersey.core.spi.component.ComponentContext;
import com.sun.jersey.core.spi.component.ComponentScope;
import com.sun.jersey.spi.inject.Injectable;
import com.sun.jersey.spi.inject.InjectableProvider;
import java.lang.reflect.Type;
import javax.ws.rs.core.Context;
import javax.ws.rs.ext.Provider;

@Provider
public class FakeProvider implements InjectableProvider<Context, Type> {

  @Override
  public ComponentScope getScope() {
    return ComponentScope.PerRequest;
  }

  @Override
  public Injectable getInjectable(ComponentContext ic, Context context, Type type) {
    if (DummyContext.class.equals(type)) {
      return () -> new DummyContext();
    }
    return null;
  }
}
