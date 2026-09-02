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
        }
        seed()
    }

    private fun seed() {
        val sql = "insert or ignore into habits (slug, name, icon, target, colour, position) values (?, ?, ?, ?, ?, ?)"
        conn.prepareStatement(sql).use { ps ->
            SEED.forEachIndexed { i, h ->
                ps.setString(1, h.slug)
                ps.setString(2, h.slug)
                ps.setString(3, h.icon)
                ps.setInt(4, h.target)
                ps.setString(5, h.colour)
                ps.setInt(6, i)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    fun habits(): List<Habit> = synchronized(conn) {
        conn.createStatement().use { st ->
            val rs = st.executeQuery("select id, slug, name, icon, target, colour from habits order by position")
            buildList {
                while (rs.next()) {
                    add(
                        Habit(
                            id = rs.getInt("id"),
                            slug = rs.getString("slug"),
                            name = rs.getString("name"),
                            icon = rs.getString("icon"),
                            target = rs.getInt("target"),
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
    val colour: String,
)

// Deliberately unlabelled: this repo is public, and a readable list here would
// undo the point of showing only icons on the display. Set `name` in the
// database on the server if you ever want labels.
//
// Catppuccin Mocha, matching the rest of tek.rip. target > 1 makes a habit a
// counter: the square shades by value/target instead of on/off.
private val SEED = listOf(
    Seed("clean", "block", 1, "#cba6f7"),
    Seed("midnight", "dark_mode", 1, "#b4befe"),
    Seed("wake", "light_mode", 1, "#f9e2af"),
    Seed("gym", "change_history", 1, "#fab387"),
    Seed("leetcode", "hexagon", 3, "#a6e3a1"),
    Seed("cls", "diamond", 2, "#74c7ec"),
    Seed("bugs", "asterisk", 2, "#f38ba8"),
    Seed("note", "edit", 1, "#94e2d5"),
)
