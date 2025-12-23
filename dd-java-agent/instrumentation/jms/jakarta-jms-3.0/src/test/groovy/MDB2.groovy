import jakarta.ejb.MessageDriven
import jakarta.jms.Message
import jakarta.jms.MessageListener

@MessageDriven(mappedName="unusedTopic")
class MDB2 implements MessageListener {

  void onMessage(Message message) {
    if (message == null) {
      throw new Exception("null message")
    }
  }
}

