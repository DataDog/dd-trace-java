import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.dropwizard.testing.junit.ResourceTestRule
import org.junit.ClassRule
import spock.lang.Shared

import javax.ws.rs.core.Response

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class NestedResourcesTest extends AgentTestRunner {
  @Shared
  @ClassRule
  ResourceTestRule resources = ResourceTestRule.builder()
  .addResource(new KeyCloakResources.AdminRoot())
  .addResource(new KeyCloakResources.RealmsAdminResource())
  .addResource(new KeyCloakResources.RealmAdminResource())
  .addResource(new KeyCloakResources.UsersResource())
  .addResource(new KeyCloakResources.UserResource())
  .build()

  def getClient() {
    resources.client()
  }

  def "test nested calls"() {
    when:
    Response response
    runUnderTrace("test.span") {
      response = getClient().target("/admin/realms/realm1/users/53c82214-ca89-423b-a1f3-6a7784e61cf6").request().get()
    }

    then:
    response.readEntity(String) == "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><userRepresentation/>"
    response.getStatus() == 200

    assertTraces(1) {
      sortSpansByStart()
      trace(6) {
        span {
          operationName "test.span"
          resourceName "/admin/realms"
          tags {
            "$Tags.COMPONENT" "jax-rs"
            "$Tags.HTTP_ROUTE" "/admin/realms"
            defaultTags()
          }
        }
        span {
          childOf span(0)
          operationName "jax-rs.request"
          resourceName "AdminRoot.getRealmsAdmin"
          spanType DDSpanTypes.HTTP_SERVER
          tags {
            "$Tags.COMPONENT" "jax-rs-controller"
            defaultTags()
          }
        }
        span {
          childOf span(0)
          operationName "jax-rs.request"
          resourceName "RealmsAdminResource.getRealmAdmin"
          spanType DDSpanTypes.HTTP_SERVER
          tags {
            "$Tags.COMPONENT" "jax-rs-controller"
            defaultTags()
          }
        }
        span {
          childOf span(0)
          operationName "jax-rs.request"
          resourceName "RealmAdminResource.users"
          spanType DDSpanTypes.HTTP_SERVER
          tags {
            "$Tags.COMPONENT" "jax-rs-controller"
            defaultTags()
          }
        }
        span {
          childOf span(0)
          operationName "jax-rs.request"
          resourceName "UsersResource.user"
          spanType DDSpanTypes.HTTP_SERVER
          tags {
            "$Tags.COMPONENT" "jax-rs-controller"
            defaultTags()
          }
        }
        span {
          childOf span(0)
          operationName "jax-rs.request"
          resourceName "UserResource.getUser"
          spanType DDSpanTypes.HTTP_SERVER
          tags {
            "$Tags.COMPONENT" "jax-rs-controller"
            defaultTags()
          }
        }
      }
    }
  }
}
