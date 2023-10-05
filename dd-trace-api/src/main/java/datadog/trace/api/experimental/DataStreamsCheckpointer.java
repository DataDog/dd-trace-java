package datadog.trace.api.experimental;

import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Tracer;
import datadog.trace.api.internal.InternalTracer;

/** An interface to Data Streams checkpointer, allowing passing the context manually. */
public interface DataStreamsCheckpointer {
  static DataStreamsCheckpointer get() {
    Tracer tracer = GlobalTracer.get();
    if (tracer instanceof InternalTracer) {
      return ((InternalTracer) tracer).getDataStreamsCheckpointer();
    }

    return NoOp.INSTANCE;
  }

  /**
   * @param type The type of the checkpoint, usually the streaming technology being used. Examples:
   *     kafka, kinesis, sns etc.
   * @paren source The source of data. For instance: topic, exchange or stream name.
   * @param carrier An interface to the context carrier, from which the context will be extracted.
   *     I.e. wrapper around message headers.
   */
  void setConsumeCheckpoint(String type, String source, DataStreamsContextCarrier carrier);

  /**
   * @param type The type of the checkpoint, usually the streaming technology being used. Examples:
   *     kafka, kinesis, sns etc.
   * @paren target The destination to which the data is being sent. For instance: topic, exchange or
   *     stream name.
   * @param carrier An interface to the context carrier, to which the context will be injected. I.e.
   *     wrapper around message headers.
   */
  void setProduceCheckpoint(String type, String target, DataStreamsContextCarrier carrier);

  final class NoOp implements DataStreamsCheckpointer {

    public static final DataStreamsCheckpointer INSTANCE = new NoOp();

    @Override
    public void setConsumeCheckpoint(
        String type, String source, DataStreamsContextCarrier carrier) {}

    @Override
    public void setProduceCheckpoint(
        String type, String target, DataStreamsContextCarrier carrier) {}
  }
}
