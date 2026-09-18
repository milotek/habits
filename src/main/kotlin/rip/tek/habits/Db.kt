package rip.tek.habits

import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate

data class Habit(
    val id: Int,
    val slug: String,
    val name: String,
    val icon: String,
    val target: Int,
    /** How many days one tick covers. 1 is daily; 2 is every other day. */
    val cadence: Int,
    val colour: String,
)

// sqlite-jdbc connections are not safe to share across threads, and Netty will
// happily call in from several at once. Traffic here is one person tapping a
// button, so a single guarded connection is simpler than a pool.
class Db(path: String) {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        conn.createStatement().use { it.execute("pragma journal_mode = WAL") }
    }

    fun migrate() = synchronized(conn) {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                create table if not exists habits (
                  id       integer primary key,
                  slug     text    not null unique,
                  name     text    not null,
                  icon     text    not null,
                  target   integer not null default 1,
                  colour   text    not null,
                  position integer not null
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                create table if not exists completions (
                  habit_id integer not null references habits(id),
                  day      text    not null,
                  value    integer not null,
                  primary key (habit_id, day)
                )
                """.trimIndent()
            )
            // `midnight` was renamed to `sleep`. Renaming the row rather than
            // seeding a new slug is what keeps the habit's completions attached.
            st.executeUpdate("update habits set slug = 'sleep' where slug = 'midnight'")
            val columns = st.executeQuery("pragma table_info(habits)").use { rs ->
                buildList { while (rs.next()) add(rs.getString("name")) }
            }
            if ("cadence" !in columns) {
                st.executeUpdate("alter table habits add column cadence integer not null default 1")
            }
        }
        seed()
    }

    private fun seed() {
        // SEED is the source of truth for how a habit looks, so a redeploy restyles
        // rows that already exist. `name` is left alone: it is the one column
        // meant to be edited on the server.
        val sql = """
            insert into habits (slug, name, icon, target, cadence, colour, position) values (?, ?, ?, ?, ?, ?, ?)
            on conflict(slug) do update set
              icon = excluded.icon,
              target = excluded.target,
              cadence = excluded.cadence,
              colour = excluded.colour,
              position = excluded.position
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            SEED.forEachIndexed { i, h ->
                ps.setString(1, h.slug)
                ps.setString(2, h.slug)
                ps.setString(3, h.icon)
                ps.setInt(4, h.target)
                ps.setInt(5, h.cadence)
                ps.setString(6, h.colour)
                ps.setInt(7, i)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        // A habit dropped from SEED is off the board, so take its rows with it
        // instead of leaving an invisible habit and its completions behind. The
        // slugs are source constants, never user input.
        val kept = SEED.joinToString(", ") { "'${it.slug}'" }
        conn.createStatement().use { st ->
            st.executeUpdate("delete from completions where habit_id in (select id from habits where slug not in ($kept))")
            st.executeUpdate("delete from habits where slug not in ($kept)")
        }
    }

    fun habits(): List<Habit> = synchronized(conn) {
        conn.createStatement().use { st ->
            val rs = st.executeQuery("select id, slug, name, icon, target, cadence, colour from habits order by position")
            buildList {
                while (rs.next()) {
                    add(
                        Habit(
                            id = rs.getInt("id"),
                            slug = rs.getString("slug"),
                            name = rs.getString("name"),
                            icon = rs.getString("icon"),
                            target = rs.getInt("target"),
                            cadence = rs.getInt("cadence"),
                            colour = rs.getString("colour"),
                        )
                    )
                }
            }
        }
    }

    /** habit id -> day -> value, for every completion on or after [from]. */
    fun valuesSince(from: LocalDate): Map<Int, Map<LocalDate, Int>> = synchronized(conn) {
        conn.prepareStatement("select habit_id, day, value from completions where day >= ?").use { ps ->
            ps.setString(1, from.toString())
            val rs = ps.executeQuery()
            val out = mutableMapOf<Int, MutableMap<LocalDate, Int>>()
            while (rs.next()) {
                out.getOrPut(rs.getInt("habit_id")) { mutableMapOf() }[LocalDate.parse(rs.getString("day"))] =
                    rs.getInt("value")
            }
            out
        }
    }

    fun set(habitId: Int, day: LocalDate, value: Int) = synchronized(conn) {
        if (value <= 0) {
            conn.prepareStatement("delete from completions where habit_id = ? and day = ?").use { ps ->
                ps.setInt(1, habitId)
                ps.setString(2, day.toString())
                ps.executeUpdate()
            }
        } else {
            conn.prepareStatement(
                "insert into completions (habit_id, day, value) values (?, ?, ?) " +
                    "on conflict(habit_id, day) do update set value = excluded.value"
            ).use { ps ->
                ps.setInt(1, habitId)
                ps.setString(2, day.toString())
                ps.setInt(3, value)
                ps.executeUpdate()
            }
        }
        Unit
    }

    fun valueOn(habitId: Int, day: LocalDate): Int = synchronized(conn) {
        conn.prepareStatement("select value from completions where habit_id = ? and day = ?").use { ps ->
            ps.setInt(1, habitId)
            ps.setString(2, day.toString())
            val rs = ps.executeQuery()
            if (rs.next()) rs.getInt("value") else 0
        }
    }
}

private data class Seed(
    val slug: String,
    val icon: String,
    val target: Int,
    /** How many days one tick covers. 1 is daily; 2 is every other day. */
    val cadence: Int,
    val colour: String,
)

// Deliberately unlabelled: this repo is public, and a readable list here would
// undo the point of showing only icons on the display. Set `name` in the
// database on the server if you ever want labels.
//
// Catppuccin Mocha accents walked in palette order from red to mauve, so the
// board reads top to bottom as a rainbow. target > 1 makes a habit a counter:
// it takes that many taps to cycle a day back to empty. cadence > 1 makes one
// tick last that many days, which is how a habit with rest days keeps a streak:
// 2 is every other day, 7 is weekly, 30 is monthly.
private val SEED = listOf(
    Seed("clean", "block", 1, 1, "#f38ba8"),
    Seed("wake", "light_mode", 1, 1, "#fab387"),
    Seed("sleep", "dark_mode", 1, 1, "#f9e2af"),
    Seed("gym", "fitness_center", 1, 2, "#a6e3a1"),
    Seed("leetcode", "code", 3, 1, "#94e2d5"),
    Seed("cls", "bolt", 2, 1, "#74c7ec"),
    Seed("office", "work", 1, 1, "#89b4fa"),
    Seed("note", "edit", 1, 1, "#cba6f7"),
)
