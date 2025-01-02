package datadog.trace.instrumentation.aws.v0;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public final class AwsSdkModule extends InstrumenterModule.Tracing {

  public AwsSdkModule() {
    super("aws-sdk");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AwsSdkClientDecorator",
      packageName + ".GetterAccess",
      packageName + ".GetterAccess$1",
      packageName + ".TracingRequestHandler",
      packageName + ".AwsNameCache",
      packageName + ".OnErrorDecorator",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> map = new java.util.HashMap<>();
    map.put("com.amazonaws.services.sqs.model.ReceiveMessageResult", "java.lang.String");
    map.put(
        "com.amazonaws.AmazonWebServiceRequest",
        "datadog.trace.bootstrap.instrumentation.api.AgentSpan");
    return map;
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new AWSHttpClientInstrumentation(),
        new RequestExecutorInstrumentation(),
        new HandlerChainFactoryInstrumentation());
  }
}
