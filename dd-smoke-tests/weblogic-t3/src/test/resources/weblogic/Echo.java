package probe;

import javax.ejb.Remote;

@Remote
public interface Echo {
  void prepare(String providerUrl) throws Exception;

  String read();

  String nested();

  String untraced();

  void fail(String traceId);
}
