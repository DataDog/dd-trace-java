package datadog.smoketest.appsec.springbootjdbcpostgresql.model;

import java.sql.Timestamp;

public class AccessLog {
  private long id;
  private long userId;
  private long dogId;
  private Timestamp accessTimestamp;

  public AccessLog() {}

  public long getId() {
    return id;
  }

  public void setId(long id) {
    this.id = id;
  }

  public long getUserId() {
    return userId;
  }

  public void setUserId(long userId) {
    this.userId = userId;
  }

  public long getDogId() {
    return dogId;
  }

  public void setDogId(long dogId) {
    this.dogId = dogId;
  }

  public Timestamp getAccessTimestamp() {
    return accessTimestamp;
  }

  public void setAccessTimestamp(Timestamp accessTimestamp) {
    this.accessTimestamp = accessTimestamp;
  }

  @Override
  public String toString() {
    final StringBuffer sb = new StringBuffer("AccessLog{");
    sb.append("id=").append(id);
    sb.append(", userId=").append(userId);
    sb.append(", dogId=").append(dogId);
    sb.append(", accessTimestamp=").append(accessTimestamp);
    sb.append('}');
    return sb.toString();
  }
}
