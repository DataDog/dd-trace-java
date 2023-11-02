import datadog.trace.agent.test.AgentTestRunner
import io.netty.channel.nio.NioEventLoopGroup

import java.util.concurrent.TimeUnit

class RegistrationTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.profiling.enabled", "true")
    super.configurePreAgent()
  }

  def "test NioEventLoop updates thread filter"() {
    setup:
    NioEventLoopGroup nioEventLoopGroup = new NioEventLoopGroup(1)
    boolean shutdownGracefully = false

    when:
    nioEventLoopGroup.execute {}
    shutdownGracefully = nioEventLoopGroup.shutdownGracefully().await(5, TimeUnit.SECONDS)

    then:
    shutdownGracefully
    TEST_PROFILING_CONTEXT_INTEGRATION.attachments.get() == 1
    TEST_PROFILING_CONTEXT_INTEGRATION.detachments.get() == 1
  }
}
