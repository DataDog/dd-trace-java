import datadog.trace.agent.test.InstrumentationSpecification
import org.glassfish.jersey.client.JerseyClientBuilder
import spock.lang.AutoCleanup
import spock.lang.Shared

import javax.ws.rs.client.Client
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class JaxMultithreadedClientTest extends InstrumentationSpecification {

  @Shared
  ExecutorService executor = Executors.newFixedThreadPool(10)

  @AutoCleanup
  @Shared
  def server = httpServer {
    handlers {
      prefix("success") {
        String msg = "Hello."
        response.status(200).send(msg)
      }
    }
  }

  def cleanupSpec() {
    executor.shutdownNow()
  }

  def "multiple threads using the same builder works"() {
    given:
    def uri = server.address.resolve("/success")
    def builder = new JerseyClientBuilder()

    // Start 10 tasks of 50 requests
    when:
    List<Callable<Boolean>> tasks = new ArrayList<>(10)
    (1..10).each {
      tasks.add({
        (1..50).any {
          try {
            Client client = builder.build()
            client.target(uri).request().get()
          } catch (ConnectException ce) {
            System.err.println("server overwhelmed, ignoring failure: " + ce.class.name)
            return true
          } catch (Throwable e) {
            e.printStackTrace(System.err)
            return false
          }
          return true
        }
      })
    }
    List<Future<Boolean>> futures = executor.invokeAll(tasks)
    boolean ok = true
    for (Future<Boolean> future : futures) {
      ok &= future.get(10, TimeUnit.SECONDS)
    }
    then:
    ok
  }
}
