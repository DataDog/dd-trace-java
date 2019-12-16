package datadog.trace.instrumentation.aws.v0;

public final class KinesisTracingRequestHandler extends TracingRequestHandler {
  public static final KinesisTracingRequestHandler INSTANCE = new KinesisTracingRequestHandler();

  private KinesisTracingRequestHandler() {
    super(KinesisClientDecorator.DECORATE);
  }
}
