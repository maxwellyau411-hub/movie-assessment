package moviecatalogue.catalogue.demo

import moviecatalogue.catalogue.credit.CreatorRole
import moviecatalogue.catalogue.person.PersonCategory
import moviecatalogue.catalogue.person.PersonCategory.CAST
import moviecatalogue.catalogue.person.PersonCategory.CREATOR
import java.time.LocalDate
import java.util.UUID

/**
 * Fictional demonstration catalogue. All titles, people and biographies are invented. IDs are
 * name-based UUIDs, so they are identical on every machine and every run.
 *
 * Deliberate edge cases: a person in both lists (Ilse Varnholt), a creator credit without a role,
 * a creator with no credits yet (Oskar Fennimore), unknown birth/release dates, a future release,
 * and duplicate titles/names that are allowed by design.
 */
object DemoCatalogue {
    data class DemoPerson(
        val key: String,
        val name: String,
        val dateOfBirth: LocalDate?,
        val categories: Set<PersonCategory>,
        val biography: String,
    ) {
        val id: UUID = demoId("person", key)
    }

    data class DemoMovie(
        val key: String,
        val title: String,
        val releaseDate: LocalDate?,
        val runtimeMinutes: Int?,
        val genres: List<String>,
        val synopsis: String?,
        val cast: List<Pair<String, String?>> = emptyList(),
        val creators: List<Pair<String, CreatorRole?>> = emptyList(),
    ) {
        val id: UUID = demoId("movie", key)
    }

    fun demoId(
        kind: String,
        key: String,
    ): UUID = UUID.nameUUIDFromBytes("movie-catalogue-demo:$kind:$key".toByteArray())

    private fun date(text: String) = LocalDate.parse(text)

    val people =
        listOf(
            DemoPerson(
                "ilse-varnholt",
                "Ilse Varnholt",
                date("1978-04-12"),
                setOf(CAST, CREATOR),
                "Stage actor turned director, known for slow-burning mysteries.",
            ),
            DemoPerson(
                "teodor-quill",
                "Teodor Quill",
                date("1985-11-03"),
                setOf(CAST),
                "Character actor who rarely plays the same accent twice.",
            ),
            DemoPerson(
                "mira-castellane",
                "Mira Castellane",
                date("1990-07-21"),
                setOf(CAST),
                "Former competitive rower; now a leading dramatic actor.",
            ),
            DemoPerson("joaquin-ferreol", "Joaquin Ferreol", null, setOf(CAST), "Prefers to keep personal details private."),
            DemoPerson(
                "celestine-ardour",
                "Celestine Ardour",
                date("2001-12-30"),
                setOf(CAST),
                "Voice and screen actor with an animation background.",
            ),
            DemoPerson("pell-duarte", "Pell Duarte", date("1982-05-17"), setOf(CAST), "Comic actor who started in radio sketches."),
            DemoPerson(
                "hana-ostrowe",
                "Hana Ostrowe",
                date("1969-02-28"),
                setOf(CREATOR),
                "Writer-producer of character-driven ensemble films.",
            ),
            DemoPerson(
                "bram-lindqvar",
                "Bram Lindqvar",
                date("1974-09-09"),
                setOf(CREATOR),
                "Composer who records with found-object percussion.",
            ),
            DemoPerson("noor-vashti", "Noor Vashti", null, setOf(CREATOR), "Credited across departments; role often unlisted."),
            DemoPerson(
                "oskar-fennimore",
                "Oskar Fennimore",
                date("1993-03-03"),
                setOf(CREATOR),
                "First-time director with a project in development.",
            ),
            DemoPerson("sam-arden-1960", "Sam Arden", date("1960-01-15"), setOf(CAST), "Veteran screen actor."),
            DemoPerson(
                "sam-arden-1995",
                "Sam Arden",
                date("1995-08-02"),
                setOf(CAST),
                "Newcomer who happens to share a name with a veteran.",
            ),
        )

    val movies =
        listOf(
            DemoMovie(
                "lantern-keeper",
                "The Lantern Keeper",
                date("2019-03-14"),
                112,
                listOf("DRAMA", "MYSTERY"),
                "A lighthouse keeper's logbook begins predicting ships that have not yet sailed.",
                cast = listOf("ilse-varnholt" to "Agnes Holm", "teodor-quill" to "The Harbourmaster"),
                creators =
                    listOf(
                        "ilse-varnholt" to CreatorRole.DIRECTOR,
                        "hana-ostrowe" to CreatorRole.WRITER,
                        "bram-lindqvar" to CreatorRole.COMPOSER,
                    ),
            ),
            DemoMovie(
                "orbit-of-glass",
                "Orbit of Glass",
                date("2022-09-30"),
                128,
                listOf("SCIENCE_FICTION", "ADVENTURE"),
                "A salvage crew finds a station built entirely from transparent alloy, and someone still aboard.",
                cast = listOf("mira-castellane" to "Cmdr. Ivy Sorel", "joaquin-ferreol" to null, "sam-arden-1960" to "Dr. Lund"),
                creators = listOf("hana-ostrowe" to CreatorRole.PRODUCER, "hana-ostrowe" to CreatorRole.WRITER, "noor-vashti" to null),
            ),
            DemoMovie(
                "paper-harbour-2016",
                "Paper Harbour",
                date("2016-06-02"),
                97,
                listOf("ROMANCE", "DRAMA"),
                "Two rival origami makers share a market stall for one summer.",
                cast = listOf("mira-castellane" to "June", "pell-duarte" to "Osk"),
                creators = listOf("ilse-varnholt" to CreatorRole.DIRECTOR),
            ),
            DemoMovie(
                "paper-harbour-2024",
                "Paper Harbour",
                date("2024-02-14"),
                101,
                listOf("ROMANCE"),
                "A remake set in a floating city; same title, different film.",
                cast = listOf("celestine-ardour" to "June", "sam-arden-1995" to "Osk"),
                creators = listOf("noor-vashti" to CreatorRole.DIRECTOR),
            ),
            DemoMovie(
                "kestrel-station",
                "Midnight at Kestrel Station",
                date("2021-11-19"),
                104,
                listOf("THRILLER", "CRIME"),
                "The last train is delayed, and every passenger has a reason to miss it.",
                cast = listOf("teodor-quill" to "Inspector Vale", "pell-duarte" to "Porter / Conductor"),
                creators = listOf("bram-lindqvar" to CreatorRole.COMPOSER, "hana-ostrowe" to CreatorRole.DIRECTOR),
            ),
            DemoMovie(
                "quiet-cartographer",
                "The Quiet Cartographer",
                date("2018-01-26"),
                89,
                listOf("ANIMATION", "FAMILY", "ADVENTURE"),
                "A mapmaker's apprentice charts a forest that rearranges itself every night.",
                cast = listOf("celestine-ardour" to "Pip (voice)"),
                creators = listOf("noor-vashti" to CreatorRole.DIRECTOR, "bram-lindqvar" to CreatorRole.COMPOSER),
            ),
            DemoMovie(
                "saltwind",
                "Saltwind",
                date("2014-08-08"),
                121,
                listOf("WESTERN", "DRAMA"),
                "A salt-flat homestead stands between a railway and the only spring for miles.",
                cast = listOf("sam-arden-1960" to "Elias Croft", "joaquin-ferreol" to "Rider"),
                creators = listOf("hana-ostrowe" to CreatorRole.WRITER),
            ),
            DemoMovie(
                "marrow-lane",
                "Echoes of Marrow Lane",
                date("2027-05-21"),
                null,
                listOf("HORROR", "MYSTERY"),
                "Announced: a street where every house repeats the same night.",
                cast = listOf("ilse-varnholt" to null),
                creators = listOf("ilse-varnholt" to CreatorRole.DIRECTOR),
            ),
            DemoMovie(
                "fern-project",
                "Untitled Fern Project",
                null,
                null,
                listOf("COMEDY"),
                null,
            ),
        )
}
