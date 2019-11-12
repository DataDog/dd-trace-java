import org.glassfish.grizzly.IOStrategy
import org.glassfish.grizzly.http.server.HttpServer
import org.glassfish.grizzly.strategies.LeaderFollowerNIOStrategy
import org.glassfish.grizzly.strategies.SameThreadIOStrategy
import org.glassfish.grizzly.strategies.SimpleDynamicNIOStrategy
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory
import org.glassfish.jersey.server.ResourceConfig

abstract class GrizzlyIOStrategyTest extends GrizzlyTest {
  @Override
  HttpServer startServer(int port) {
    ResourceConfig rc = new ResourceConfig()
    rc.register(SimpleExceptionMapper)
    rc.register(ServiceResource)
    def server = GrizzlyHttpServerFactory.createHttpServer(new URI("http://localhost:$port"), rc)
    server.getListener("grizzly").getTransport().setIOStrategy(strategy())
    // Default in NIOTransportBuilder is WorkerThreadIOStrategy, so don't need to retest that.s
    return server
  }

  abstract IOStrategy strategy()
}

class LeaderFollowerTest extends GrizzlyIOStrategyTest {

  @Override
  IOStrategy strategy() {
    return LeaderFollowerNIOStrategy.instance
  }
}

class SameThreadTest extends GrizzlyIOStrategyTest {

  @Override
  IOStrategy strategy() {
    return SameThreadIOStrategy.instance
  }
}

class SimpleDynamicTest extends GrizzlyIOStrategyTest {

  @Override
  IOStrategy strategy() {
    return SimpleDynamicNIOStrategy.instance
  }
}
