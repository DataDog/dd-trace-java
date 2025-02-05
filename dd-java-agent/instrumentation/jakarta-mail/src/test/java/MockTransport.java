import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import java.util.Properties;

public class MockTransport extends Transport {
  public MockTransport(Session session, URLName urlname) {
    super(session, urlname);
  }

  public MockTransport() {
    this(Session.getInstance(new Properties()), null);
  }

  public MockTransport(Session session) {
    this(session, null);
  }

  public static Transport newInstance(Session session) {
    return new MockTransport(session, null);
  }

  public void sendMessage(Message msg, Address[] addresses) throws MessagingException {
    for (Address a : addresses) {
      this.notifyTransportListeners(1, addresses, new Address[0], new Address[0], msg);
    }
  }

  @Override
  public void connect() {
    this.setConnected(true);
    this.notifyConnectionListeners(1);
  }

  public synchronized void connect(String host, int port, String user, String password)
      throws MessagingException {}
}
