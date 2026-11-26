package weblogic.workarea;

public interface WorkContextMap {
  WorkContext get(String key);

  WorkContext put(String key, WorkContext context, int propagationMode);

  WorkContext remove(String key);
}
