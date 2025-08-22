package datadog.trace.instrumentation.aws.v0;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Groups the instrumentations for AWS SDK 1.11.0+. */
@AutoService(InstrumenterModule.class)
public class AwsSdkModule extends InstrumenterModule.Tracing {
  private final String namespace;

  public AwsSdkModule() {
    this("com.amazonaws", "aws-sdk");
  }

  protected AwsSdkModule(String namespace, String instrumentationName) {
    super(instrumentationName);
    this.namespace = namespace;
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
    map.put(namespace + ".services.sqs.model.ReceiveMessageResult", "java.lang.String");
    map.put(namespace + ".AmazonWebServiceRequest", "datadog.context.Context");
    return map;
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new AWSHttpClientInstrumentation(namespace),
        new RequestExecutorInstrumentation(namespace),
        new HandlerChainFactoryInstrumentation(namespace));
  }
}
