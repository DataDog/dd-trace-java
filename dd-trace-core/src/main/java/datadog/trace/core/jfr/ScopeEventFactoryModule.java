package datadog.trace.core.jfr;

import dagger.Module;
import dagger.Provides;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Module
public class ScopeEventFactoryModule {
  @Provides
  public static DDScopeEventFactory eventFactory() {
    try {
      return (DDScopeEventFactory)
          Class.forName("datadog.trace.core.jfr.openjdk.ScopeEventFactory").newInstance();
    } catch (final ClassFormatError | ReflectiveOperationException | NoClassDefFoundError e) {
      log.debug("Profiling of ScopeEvents is not available");
    }
    return new DDNoopScopeEventFactory();
  }
}
