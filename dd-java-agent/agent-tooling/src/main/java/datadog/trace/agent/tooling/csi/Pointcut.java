package datadog.trace.agent.tooling.csi;

public interface Pointcut {
  String type();

  String method();

  String descriptor();
}
