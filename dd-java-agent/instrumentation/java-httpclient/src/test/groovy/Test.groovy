import datadog.trace.agent.test.AgentTestRunner
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass
import static net.bytebuddy.matcher.ElementMatchers.isMethod
import static net.bytebuddy.matcher.ElementMatchers.isPublic
import static net.bytebuddy.matcher.ElementMatchers.named
import static net.bytebuddy.matcher.ElementMatchers.takesArgument
import static net.bytebuddy.matcher.ElementMatchers.takesArguments

class Test extends AgentTestRunner {

  @Shared
  @AutoCleanup
  def server = httpServer {
    handlers {
      all {
        String msg = "Hello."
        response.status(200).send(msg)
      }
    }
  }

  def "see it should work"() {
    setup:
    def client = HttpClient.newHttpClient()
    def clientType = new TypeDescription.ForLoadedType(client.getClass())

    def typeMatcher = extendsClass(named("java.net.http.HttpClient"))
    assert typeMatcher.matches(clientType)

    def elementMatcher = isMethod()
      .and(named("send"))
      .and(isPublic())
      .and(takesArguments(2))
      .and(takesArgument(0, named("java.net.http.HttpRequest")))
    def clientSendMethod = new MethodDescription.ForLoadedMethod(client.getClass()
      .getMethod("send", HttpRequest.class, HttpResponse.BodyHandler.class))
    assert elementMatcher.matches(clientSendMethod)
  }

  def "why you no work"() {
    setup:
    def client = HttpClient.newHttpClient()
    def request = HttpRequest.newBuilder(server.address)
      .header("kill", "me")
      .GET()
      .build()

    when:
    client.send(request, HttpResponse.BodyHandlers.discarding())

    then:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "test.span"
          resourceName "failing"
        }
      }
    }
  }
}
