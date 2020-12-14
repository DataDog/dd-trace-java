package datadog.trace.common.writer.ddagent;

public class StubTraceAPI implements TraceAPI {
  @Override
  public TraceMapper selectTraceMapper() {
    return new TraceMapperV0_4();
  }

  @Override
  public Response sendSerializedTraces(Payload payload) {
    // artificial side effect
    System.err.println(payload.sizeInBytes());
    return Response.success(200);
  }

  @Override
  public void addResponseListener(DDAgentResponseListener listener) {}
}
