package datadog.trace.core.datastreams;

import java.util.Collection;
import java.util.List;


public interface DatastreamsPayloadWriter {
  void writePayload(Collection<StatsBucket> data);

  void writeTransactionPayload(MsgPackDatastreamsPayloadWriter.TransactionPayload payload);

  void writeCompressedTransactionPayload(List<MsgPackDatastreamsPayloadWriter.TransactionPayload> payloads);

}
