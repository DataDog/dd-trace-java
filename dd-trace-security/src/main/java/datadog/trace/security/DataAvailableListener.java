package datadog.trace.security;

public interface DataAvailableListener {
    void dataAvailable(Flow flow, DataSource newData, DataSource allData);
}
