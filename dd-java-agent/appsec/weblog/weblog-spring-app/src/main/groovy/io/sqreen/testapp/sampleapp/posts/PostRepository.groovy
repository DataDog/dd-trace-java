package io.sqreen.testapp.sampleapp.posts

import io.sqreen.testapp.imitation.VulnerableQuery
import org.hibernate.SQLQuery
import org.hibernate.Session
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

import javax.persistence.EntityManager
import java.sql.ResultSet
import java.sql.SQLException

@Component
class PostRepository {

  @Autowired
  private NamedParameterJdbcTemplate jdbcTemplate

  /*
   * When running on GlassFish embedded:
   * No qualifying bean of type 'javax.persistence.EntityManager' available:
   * expected single matching bean but found 3: org.springframework.orm.jpa.SharedEntityManagerCreator#0,
   * org.springframework.orm.jpa.SharedEntityManagerCreator#1,org.springframework.orm.jpa.SharedEntityManagerCreator#2
   *
   * Why this happens, I have no idea. The first reference to the #1 and #2 beans in DBEUG logging is already when
   * they're being instantiated.
   * All 3 bean definitions (in the parent bean factory) name as source
   * JpaBaseConfiguration::entityManagerFactory(), which is called only once
   *
   * So hack around this by injecting all 3 and then picking the first...
   */
  @Autowired
  private List<EntityManager> entityManager

  List<Post> getAll() {
    this.jdbcTemplate.query(
      'SELECT id, author, title, body, created_at FROM posts ORDER BY id ASC',
      PostRowMapper.INSTANCE)
  }

  void save(Post post) {
    def q
    if (post.id) {
      q = '''UPDATE posts SET author = :author, title = :title, body = :body WHERE id = :id'''
    } else {
      q = 'INSERT INTO posts(author, title, body, created_at) VALUES(:author, :title, :body, :createdAt)'
    }

    this.jdbcTemplate.update(q, [*: post.properties, createdAt: new Date()])
  }

  Post find(String id) {
    // variable should be a int, but made string so it can be vulnerable
    def query = VulnerableQuery.build(
      """
                SELECT id, author, title, body, created_at
                FROM posts WHERE """,
      "id =", "$id")
    this.jdbcTemplate.queryForObject(query, [:], PostRowMapper.INSTANCE)
  }

  Post findJpaNative(String id) {
    def query = entityManager.first().createNativeQuery("""
                SELECT id, author, title, body, created_at
                FROM posts WHERE id = $id""", Post)
    if (id.contains(':p')) {
      query.setParameter('p', 1)
    }
    query.singleResult
  }

  Post findHibernateNative(String id) {
    Session session = entityManager.first().unwrap(Session)
    SQLQuery query = session.createSQLQuery("""
                SELECT posts.id, posts.author, posts.title, posts.body, posts.created_at
                FROM posts WHERE id = $id""")
    query.addRoot('posts', Post)
    if (id.contains(':p')) {
      query.setParameter('p', 1)
    }
    query.uniqueResult()
  }

  private static final class PostRowMapper implements RowMapper<Post> {
    private PostRowMapper() {
    }

    static final PostRowMapper INSTANCE = new PostRowMapper()

    @Override
    Post mapRow(ResultSet rs, int rowNum) throws SQLException {
      new Post(
        id: rs.getLong('id'),
        author: rs.getString('author'),
        title: rs.getString('title'),
        body: rs.getString('body'),
        createdAt: rs.getTime('created_at')
        )
    }
  }
}
