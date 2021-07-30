import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import dd.trace.instrumentation.springwebflux.server.SpringWebFluxTestApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory
import org.springframework.boot.web.embedded.netty.NettyServerCustomizer
import org.springframework.context.annotation.Bean
import reactor.netty.http.server.HttpServer
import reactor.netty.resources.LoopResources

/**
 * Run all Webflux tests under netty event loop having only 1 thread.
 * Some of the bugs are better visible in this setup because same thread is reused
 * for different requests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [SpringWebFluxTestApplication, ForceSingleThreadedNettyAutoConfiguration])
class SingleThreadedSpringWebfluxTest extends SpringWebfluxTest {

  @TestConfiguration
  static class ForceSingleThreadedNettyAutoConfiguration {
    @Bean
    NettyReactiveWebServerFactory nettyFactory() {
      def factory = new NettyReactiveWebServerFactory()
      NettyServerCustomizer customizer = { HttpServer httpServer -> httpServer.runOn(LoopResources.create("my-http", 1, true)) }
      factory.addServerCustomizers(customizer)
      return factory
    }
  }
}
