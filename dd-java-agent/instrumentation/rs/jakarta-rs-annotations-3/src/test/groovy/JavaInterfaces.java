import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

public class JavaInterfaces {

  interface Jakarta {

    void call();
  }

  @Path("interface")
  interface InterfaceWithClassMethodPath extends Jakarta {

    @Override
    @GET
    @Path("invoke")
    void call();
  }

  @Path("abstract")
  abstract class AbstractClassOnInterfaceWithClassPath implements InterfaceWithClassMethodPath {

    @GET
    @Path("call")
    @Override
    public void call() {
      // do nothing
    }

    abstract void actual();
  }

  @Path("child")
  class ChildClassOnInterface extends AbstractClassOnInterfaceWithClassPath {

    @Override
    void actual() {
      // do nothing
    }
  }

  // TODO: uncomment when we drop support for Java 7
  //  @Path("interface")
  //  interface DefaultInterfaceWithClassMethodPath extends Jakarta {
  //
  //    @GET
  //    @Path("invoke")
  //    default void call() {
  //      actual();
  //    }
  //
  //    void actual();
  //  }
  //
  //  @Path("child")
  //  class DefaultChildClassOnInterface implements DefaultInterfaceWithClassMethodPath {
  //
  //    @Override
  //    public void actual() {
  //      // do nothing
  //    }
  //  }
}
