package datadog.trace.core.sqreen;

public interface DataAvailableListener {
    void dataAvailable(Flow flow, DataSource newData, DataSource allData);
}
