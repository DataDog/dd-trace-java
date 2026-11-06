package datadog.trace.instrumentation.azure.cosmos;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.azure.cosmos.implementation.RxDocumentClientImpl;
import com.azure.cosmos.implementation.RxStoreModel;
import com.azure.cosmos.implementation.UserAgentContainer;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;

/**
 * Records the client-level details (account endpoint, connection mode, user agent) on each store
 * model the client creates, so that request spans can be tagged with them.
 */
public final class DocumentClientInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "com.azure.cosmos.implementation.RxDocumentClientImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // gateway store model, and the thin-client gateway store model in recent versions
    transformer.applyAdvice(
        isMethod()
            .and(namedOneOf("createRxGatewayProxy", "createThinProxy"))
            .and(
                returns(
                    namedOneOf(
                        "com.azure.cosmos.implementation.RxGatewayStoreModel",
                        "com.azure.cosmos.implementation.ThinClientStoreModel"))),
        getClass().getName() + "$GatewayStoreModelAdvice");
    // direct mode store model
    transformer.applyAdvice(
        isMethod().and(named("createStoreModel")).and(takesArguments(1)),
        getClass().getName() + "$DirectStoreModelAdvice");
  }

  public static final class GatewayStoreModelAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void afterCreate(
        @Advice.This RxDocumentClientImpl client,
        @Advice.FieldValue("userAgentContainer") UserAgentContainer userAgentContainer,
        @Advice.Return RxStoreModel storeModel) {
      if (storeModel != null) {
        InstrumentationContext.get(RxStoreModel.class, CosmosClientInfo.class)
            .put(storeModel, CosmosClientInfo.create(client, userAgentContainer));
      }
    }
  }

  public static final class DirectStoreModelAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void afterCreate(
        @Advice.This RxDocumentClientImpl client,
        @Advice.FieldValue("userAgentContainer") UserAgentContainer userAgentContainer,
        @Advice.FieldValue("storeModel") RxStoreModel storeModel) {
      if (storeModel != null) {
        InstrumentationContext.get(RxStoreModel.class, CosmosClientInfo.class)
            .put(storeModel, CosmosClientInfo.create(client, userAgentContainer));
      }
    }
  }
}
