import dd.trace.instrumentation.springwebflux.server.SpringWebFluxTestApplication
import org.springframework.boot.test.context.SpringBootTest

/**
 * Run all Webflux tests under netty event loop having only 1 thread.
 * Some of the bugs are better visible in this setup because same thread is reused
 * for different requests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [SpringWebFluxTestApplication, ForceNettyAutoConfiguration])
class SingleThreadedSpringWebfluxTest extends SpringWebfluxTest {

  def setup() {
    System.setProperty("reactor.netty.ioWorkerCount", "1")
  }

  def cleanup() {
    System.clearProperty("reactor.netty.ioWorkerCount")
  }
}
