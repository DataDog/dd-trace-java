package datadog.trace.api.http;

public interface StoredBodyListener {
  void onBodyStart(StoredBodySupplier storedByteBody);

  void onBodyEnd(StoredBodySupplier storedByteBody);
}
