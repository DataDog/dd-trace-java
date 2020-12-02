package datadog.trace.instrumentation.datastax.cassandra;

import com.datastax.oss.driver.api.core.session.Session;
import java.util.concurrent.CompletionStage;
import net.bytebuddy.asm.Advice;

public class CassandraClientAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void injectTracingSession(
      @Advice.Return(readOnly = false) CompletionStage<Session> completionStage) {

    // Change CompletingStage<Session> to CompletingStage<TracingSession>
    // The TracingSession wrapper includes span start/stop
    completionStage = completionStage.thenApply(TracingSession::new);
  }
}
