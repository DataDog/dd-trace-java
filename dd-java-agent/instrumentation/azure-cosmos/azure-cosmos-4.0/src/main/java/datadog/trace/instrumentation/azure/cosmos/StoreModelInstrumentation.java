package datadog.trace.instrumentation.azure.cosmos;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.azure.cosmos.implementation.ResourceType;
import com.azure.cosmos.implementation.RxDocumentServiceRequest;
import com.azure.cosmos.implementation.RxDocumentServiceResponse;
import com.azure.cosmos.implementation.RxStoreModel;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import reactor.core.publisher.Mono;

/**
 * Wraps the response of {@code RxStoreModel.processMessage}, the single entry point for every
 * request the SDK sends, whatever the connection mode. The SDK client-level retry policies call it
 * again for each attempt, so each attempt gets its own span.
 *
 * <p>{@code ThinClientStoreModel} (recent versions) extends {@code RxGatewayStoreModel} and calls
 * {@code super.processMessage}, so it is covered without being matched itself.
 */
public final class StoreModelInstrumentation
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "com.azure.cosmos.implementation.RxGatewayStoreModel",
      "com.azure.cosmos.implementation.directconnectivity.ServerStoreModel"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("processMessage"))
            .and(takesArguments(1))
            .and(
                takesArgument(0, named("com.azure.cosmos.implementation.RxDocumentServiceRequest")))
            .and(returns(named("reactor.core.publisher.Mono"))),
        getClass().getName() + "$ProcessMessageAdvice");
  }

  public static final class ProcessMessageAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void afterProcessMessage(
        @Advice.This RxStoreModel storeModel,
        @Advice.Argument(0) RxDocumentServiceRequest request,
        @Advice.Return(readOnly = false) Mono<RxDocumentServiceResponse> response) {
      if (response != null
          && request != null
          && request.getResourceType() != ResourceType.DatabaseAccount) {
        response =
            new TracingCosmosMono(
                response,
                request,
                InstrumentationContext.get(RxStoreModel.class, CosmosClientInfo.class)
                    .get(storeModel));
      }
    }
  }
}
