package datadog.trace.test.util

import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.utility.JavaModule

class ConfigInstrumentationFailedListener extends AgentBuilder.Listener.Adapter {
  @Override
  void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
    if (DDSpecification.CONFIG == typeName) {
      DDSpecification.configModificationFailed = true
    }
  }
}
