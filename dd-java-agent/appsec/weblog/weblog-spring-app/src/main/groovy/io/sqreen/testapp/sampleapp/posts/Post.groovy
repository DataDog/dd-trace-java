package io.sqreen.testapp.sampleapp.posts

import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.Id
import javax.persistence.Table

@Entity
@Table(name = 'posts')
class Post {

  @Id
  @GeneratedValue
  Long id

  String author
  String title
  String body

  Date createdAt
}
