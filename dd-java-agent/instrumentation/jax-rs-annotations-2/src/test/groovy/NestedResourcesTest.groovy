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
    response.statusInfo.getStatusCode() == 200

    assertTraces(1) {
      sortSpansByStart()
      trace(2) {
        span {
          operationName "test.span"
          resourceName "GET /"
          tags {
            "$Tags.COMPONENT" "jax-rs"
            "$Tags.HTTP_ROUTE" "/"
            defaultTags()
          }
        }
        span {
          childOf span(0)
          operationName "jax-rs.request"
          resourceName ""
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
