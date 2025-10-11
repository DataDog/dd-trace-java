import javax.jms.Message
import javax.jms.MessageListener

// Not a valid MDB because it only implements MessageListener.
class MDBBad implements MessageListener {

  void onMessage(Message message) {
    if (message == null) {
      throw new Exception("null message")
    }
  }
}


