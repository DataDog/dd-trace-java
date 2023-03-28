package datadog.trace.api.datastreams;

/** An interface to Data Streams checkpointer, allowing passing the context manually. */
public interface DataStreamsCheckpointer {
  /**
   * @param type The type of the checkpoint, usually the streaming technology being used. Examples: kafka, kinesis, sns etc.
   * @paren source The source of data. For instance: topic, exchange or stream name.
   * @param setter An interface to the context carrier, from which the context will be extracted. I.e. wrapper around message headers.
   * */
  void setConsumeCheckpoint(String type, String source, DataStreamsContextCarrier setter);

  /**
   * @param type The type of the checkpoint, usually the streaming technology being used. Examples: kafka, kinesis, sns etc.
   * @paren target The destination to which the data is being sent. For instance: topic, exchange or stream name.
   * @param setter An interface to the context carrier, to which the context will be injected. I.e. wrapper around message headers.
   * */
  void setProduceCheckpoint(String type, String target, DataStreamsContextCarrier setter);
}
