package datadog.trace.bootstrap.instrumentation.jdbc;

import java.util.Objects;
import datadog.trace.util.HashingUtils;

public final class DBInfo {
  public static final DBInfo DEFAULT = new Builder().type("database").build();

  private final String type;
  private final String subtype;
  private final boolean fullPropagationSupport;
  private final String url;
  private final String user;
  private final String instance;
  private final String db;
  private final String host;
  private final Integer port;
  private final String warehouse;
  private final String schema;
  private volatile String poolName;

  DBInfo(
      String type,
      String subtype,
      boolean fullPropagationSupport,
      String url,
      String user,
      String instance,
      String db,
      String host,
      Integer port,
      String warehouse,
      String schema,
      String poolName) {
    this.type = type;
    this.subtype = subtype;
    this.fullPropagationSupport = fullPropagationSupport;
    this.url = url;
    this.user = user;
    this.instance = instance;
    this.db = db;
    this.host = host;
    this.port = port;
    this.warehouse = warehouse;
    this.schema = schema;
    this.poolName = poolName;
  }

  public static final class Builder {
    private String type;
    private String subtype;
    // most DBs do support full propagation (inserting trace ID in query comments), so we default to
    // true. See https://docs.datadoghq.com/database_monitoring/connect_dbm_and_apm
    private boolean fullPropagationSupport = true;
    private String url;
    private String user;
    private String instance;
    private String db;
    private String warehouse;
    private String schema;
    private String host;
    private Integer port;
    private String poolName;

    Builder() {}

    Builder(
        String type,
        String subtype,
        boolean fullPropagationSupport,
        String url,
        String user,
        String instance,
        String db,
        String host,
        Integer port,
        String warehouse,
        String schema,
        String poolName) {
      this.type = type;
      this.subtype = subtype;
      this.fullPropagationSupport = fullPropagationSupport;
      this.url = url;
      this.user = user;
      this.instance = instance;
      this.db = db;
      this.host = host;
      this.port = port;
      this.warehouse = warehouse;
      this.schema = schema;
      this.poolName = poolName;
    }

    public Builder type(String type) {
      this.type = type;
      return this;
    }

    public Builder subtype(String subtype) {
      this.subtype = subtype;
      return this;
    }

    public Builder fullPropagationSupport(boolean fullPropagationSupport) {
      this.fullPropagationSupport = fullPropagationSupport;
      return this;
    }

    public Builder url(String url) {
      this.url = url;
      return this;
    }

    public Builder user(String user) {
      this.user = user;
      return this;
    }

    public Builder instance(String instance) {
      this.instance = instance;
      return this;
    }

    public Builder db(String db) {
      this.db = db;
      return this;
    }

    public Builder warehouse(String warehouse) {
      this.warehouse = warehouse;
      return this;
    }

    public Builder schema(String schema) {
      this.schema = schema;
      return this;
    }

    public Builder host(String host) {
      this.host = host;
      return this;
    }

    public Builder port(Integer port) {
      this.port = port;
      return this;
    }

    public Builder poolName(String poolName) {
      this.poolName = poolName;
      return this;
    }

    public DBInfo build() {
      return new DBInfo(
          type,
          subtype,
          fullPropagationSupport,
          url,
          user,
          instance,
          db,
          host,
          port,
          warehouse,
          schema,
          poolName);
    }
  }

  public String getType() {
    return type;
  }

  public String getSubtype() {
    return subtype;
  }

  public boolean getFullPropagationSupport() {
    return fullPropagationSupport;
  }

  public String getUrl() {
    return url;
  }

  public String getUser() {
    return user;
  }

  public String getInstance() {
    return instance;
  }

  public String getDb() {
    return db;
  }

  public String getHost() {
    return host;
  }

  public Integer getPort() {
    return port;
  }

  public String getWarehouse() {
    return warehouse;
  }

  public String getSchema() {
    return schema;
  }

  public String getPoolName() {
    return poolName;
  }

  public void setPoolName(String poolname) {
    this.poolName = poolname;
  }

  public Builder toBuilder() {
    return new Builder(
        type,
        subtype,
        fullPropagationSupport,
        url,
        user,
        instance,
        db,
        host,
        port,
        warehouse,
        schema,
        poolName);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    DBInfo dbInfo = (DBInfo) o;
    return Objects.equals(type, dbInfo.type)
        && Objects.equals(subtype, dbInfo.subtype)
        && fullPropagationSupport == dbInfo.fullPropagationSupport
        && Objects.equals(url, dbInfo.url)
        && Objects.equals(user, dbInfo.user)
        && Objects.equals(instance, dbInfo.instance)
        && Objects.equals(db, dbInfo.db)
        && Objects.equals(host, dbInfo.host)
        && Objects.equals(port, dbInfo.port)
        && Objects.equals(warehouse, dbInfo.warehouse)
        && Objects.equals(schema, dbInfo.schema)
        && Objects.equals(poolName, dbInfo.poolName);
  }

  @Override
  public int hashCode() {
    return HashingUtils.hash(
        type,
        subtype,
        fullPropagationSupport,
        url,
        user,
        instance,
        db,
        host,
        port,
        warehouse,
        schema);
  }
}
