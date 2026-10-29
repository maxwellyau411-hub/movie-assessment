package moviecatalogue.catalogue.genre

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.springframework.data.jpa.repository.JpaRepository

/** Controlled reference data, seeded by migration V2 and never edited by the application. */
@Entity
@Immutable
@Table(name = "genre")
class Genre(
    @Id
    @Column(length = 40)
    val code: String,
    @Column(nullable = false, length = 60)
    val name: String,
) {
    override fun equals(other: Any?) = other is Genre && other.code == code

    override fun hashCode() = code.hashCode()
}

interface GenreRepository : JpaRepository<Genre, String> {
    fun findAllByOrderByNameAsc(): List<Genre>
}
