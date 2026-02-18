import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.xml.bind.annotation.XmlRootElement;

public class KeyCloakResources {
  @Path("/admin")
  public static class AdminRoot {

    @Path("realms")
    public Object getRealmsAdmin(@Context final HttpHeaders headers) {
      return new RealmsAdminResource();
    }
  }

  public static class RealmsAdminResource {

    @Path("{realm}")
    public RealmAdminResource getRealmAdmin(
        @Context final HttpHeaders headers, @PathParam("realm") final String name) {
      return new RealmAdminResource();
    }
  }

  public static class RealmAdminResource {
    @Path("users")
    public UsersResource users() {
      return new UsersResource();
    }
  }

  public static class UsersResource {

    @Path("{id}")
    public UserResource user(final @PathParam("id") String id) {
      return new UserResource();
    }
  }

  public static class UserResource {
    @GET
    @Produces(MediaType.APPLICATION_XML)
    public UserRepresentation getUser() {
      return new UserRepresentation();
    }

    /* A method annotated with @Path is required in order for JaxRsAnnotationsInstrumentation.typeMatcher to intercept */
    @Path("self")
    public UserResource requiredMethod() {
      return null;
    }
  }

  @XmlRootElement
  public static class UserRepresentation {}
}
