import datadog.trace.agent.test.base.HttpServer
import org.glassfish.grizzly.IOStrategy
import org.glassfish.grizzly.strategies.LeaderFollowerNIOStrategy
import org.glassfish.grizzly.strategies.SameThreadIOStrategy
import org.glassfish.grizzly.strategies.SimpleDynamicNIOStrategy

abstract class GrizzlyIOStrategyTest extends GrizzlyTest {
  @Override
  HttpServer server() {
    def server = super.server() as GrizzlyServer
    server.server.getListener("grizzly").getTransport().setIOStrategy(strategy())
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
