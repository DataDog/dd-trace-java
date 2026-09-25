package datadog.trace.instrumentation.aws.v2.sqs;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Groups the instrumentations for AWS SQS SDK 2.0+. */
@AutoService(InstrumenterModule.class)
public final class SqsModule extends InstrumenterModule.Tracing {

  public SqsModule() {
    super("sqs", "aws-sdk");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new java.util.HashMap<>();
    contextStore.put(
        "software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse", "java.lang.String");
    contextStore.put(
        "software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse$BuilderImpl",
        "java.lang.String");
    return contextStore;
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    final List<Instrumenter> ret = new ArrayList<>(6);
    ret.add(new SqsClientInstrumentation());
    ret.add(new SqsReceiveRequestInstrumentation());
    // we don't need to instrument messages when we're doing legacy AWS-SDK tracing
    if (!InstrumenterConfig.get().isLegacyInstrumentationEnabled(false, "aws-sdk")) {
      ret.add(new SqsMd5ChecksumInterceptorInstrumentation());
      ret.add(new SqsReceiveResponseBuilderInstrumentation());
      ret.add(new SqsReceiveResponseBuilderImplInstrumentation());
      ret.add(new SqsReceiveResultInstrumentation());
    }
    return ret;
  }
}
