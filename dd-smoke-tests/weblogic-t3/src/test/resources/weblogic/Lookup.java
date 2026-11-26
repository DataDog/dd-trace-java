package probe;

import java.util.Hashtable;
import javax.naming.Context;
import javax.naming.InitialContext;

final class Lookup {
  static Echo echo(String providerUrl) throws Exception {
    Hashtable<String, String> env = new Hashtable<>();
    env.put(Context.INITIAL_CONTEXT_FACTORY, "weblogic.jndi.WLInitialContextFactory");
    env.put(Context.PROVIDER_URL, providerUrl);
    env.put(Context.SECURITY_PRINCIPAL, System.getenv("PROBE_USER"));
    env.put(Context.SECURITY_CREDENTIALS, System.getenv("PROBE_PASS"));
    Context context = new InitialContext(env);
    try {
      return (Echo) context.lookup("t3Probe#probe.Echo");
    } finally {
      context.close();
    }
  }
}
