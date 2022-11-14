package datadog.telemetry;

public enum RequestStatus {
  SUCCESS,
  NOTING_TO_SEND,
  NOT_SUPPORTED_ERROR,
  HTTP_ERROR
}
