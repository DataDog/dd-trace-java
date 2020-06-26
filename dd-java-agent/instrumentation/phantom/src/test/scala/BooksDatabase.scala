import java.util.UUID

import com.outworkers.phantom.Table
import com.outworkers.phantom.connectors.{CassandraConnection, ContactPoint}
import com.outworkers.phantom.database.{Database, DatabaseProvider}
import com.outworkers.phantom.dsl._

case class Book(
               id: UUID,
               title: String,
               author: String,
               status: String,
               inventory: Int
               )

abstract class Books extends Table[Books, Book] {
  object id extends UUIDColumn with PartitionKey
  object title extends StringColumn
  object author extends StringColumn
  object status extends StringColumn
  object inventory extends IntColumn
}

class BooksDatabase(override val connector: CassandraConnection) extends Database[BooksDatabase](connector) {
  object Books extends Books with Connector
}

class EmbeddedBooksDatabase(port: Int) extends BooksDatabase(ContactPoint.apply(port).keySpace("books"))

class BooksDatabaseUtils(db: BooksDatabase) extends DatabaseProvider[BooksDatabase] {

  import scala.concurrent.duration._

  override def database: BooksDatabase = db

  def create: Unit = database.create(5.seconds)(scala.concurrent.ExecutionContext.Implicits.global)

}
