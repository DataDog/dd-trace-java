package datadog.trace.instrumentation.datastax.cassandra4;

import com.datastax.oss.driver.api.core.metadata.EndPoint;
import com.datastax.oss.driver.api.core.session.Session;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.bytebuddy.asm.Advice;

public class CassandraClientAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void injectTracingSession(
      @Advice.Argument(1) final Set<EndPoint> contactPoints,
      @Advice.Return(readOnly = false) CompletionStage<Session> completionStage) {

    // Change CompletingStage<Session> to CompletingStage<TracingSession>
    // The TracingSession wrapper includes span start/stop
    completionStage =
        completionStage.thenCombine(
            CompletableFuture.completedFuture(ContactPointsUtil.fromEndPointSet(contactPoints)),
            TracingSession::new);
  }
}
