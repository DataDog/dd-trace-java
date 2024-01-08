import jakarta.jms.Message
import jakarta.jms.MessageListener

// Not a valid MDB because it only implements MessageListener.
public class MDBBad implements MessageListener {

  public void onMessage(Message message) {
    if (message == null) {
      throw new Exception("null message")
    }
  }

}


