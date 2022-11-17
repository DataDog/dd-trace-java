package datadog.telemetry;

public enum RequestStatus {
  SUCCESS,
  NOTHING_TO_SEND,
  NOT_SUPPORTED_ERROR,
  HTTP_ERROR
}
