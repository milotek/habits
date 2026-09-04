package rip.tek.habits

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import java.time.LocalDate
import kotlin.math.ceil

fun main() {
    val port = System.getenv("HABITS_PORT")?.toInt() ?: 8095
    val db = Db(System.getenv("HABITS_DB") ?: "habits.db").apply { migrate() }

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        routing {
            get("/") {
                call.respondHtml { board(db, kiosk = call.request.queryParameters.contains("kiosk")) }
            }

            post("/tick/{slug}") {
                val habit = db.habits().find { it.slug == call.parameters["slug"] }
                if (habit == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    val today = LocalDate.now()
                    // Wrapping past target back to zero is the only way to undo a
                    // mistap: there is no separate clear button on a wall display.
                    val next = (db.valueOn(habit.id, today) + 1) % (habit.target + 1)
                    db.set(habit.id, today, next)
                    call.respondRedirect("/")
                }
            }

            get("/icons.woff2") {
                val bytes = checkNotNull(Db::class.java.getResourceAsStream("/icons.woff2")).readBytes()
                call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
                call.respondBytes(bytes, ContentType("font", "woff2"))
            }
        }
    }.start(wait = true)
}

private const val DAYS = 365

private fun HTML.board(db: Db, kiosk: Boolean) {
    val habits = db.habits()
    val today = LocalDate.now()
    val start = today.minusDays((DAYS - 1).toLong())
    val values = db.valuesSince(start)

    head {
        title("habits")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        // The wall display is never touched, so it reloads itself to pick up
        // ticks made from a phone.
        if (kiosk) {
            meta {
                httpEquiv = "refresh"
                content = "60"
            }
        }
        style { unsafe { +CSS } }
    }
    body {
        div("board") {
            habits.forEach { habit ->
                val days = values[habit.id] ?: emptyMap()
                div("habit") {
                    if (kiosk) {
                        span("icon") {
                            style = "color: ${habit.colour}"
                            +glyph(habit.icon)
                        }
                    } else {
                        form(action = "/tick/${habit.slug}", method = FormMethod.post) {
                            button(classes = "icon") {
                                style = "color: ${habit.colour}"
                                +glyph(habit.icon)
                            }
                        }
                    }
                    div("grid") {
                        // Pad to the weekday of the first day so that every column
                        // is one calendar week, Monday at the top.
                        repeat(start.dayOfWeek.value - 1) { div("cell pad") }
                        for (i in 0 until DAYS) {
                            val day = start.plusDays(i.toLong())
                            div(if (day == today) "cell today" else "cell") {
                                style = "background: ${shade(habit.colour, days[day] ?: 0, habit.target)}"
                            }
                        }
                    }
                }
            }
        }
    }
}

// Four steps rather than a continuous ramp: adjacent days have to be tellable
// apart at a glance from across a room, which a smooth gradient does not manage.
private val STEPS = listOf("40", "73", "b3", "ff")

private fun shade(colour: String, value: Int, target: Int): String {
    if (value <= 0) return "var(--empty)"
    val ratio = (value.toDouble() / target).coerceIn(0.0, 1.0)
    return colour + STEPS[(ceil(ratio * STEPS.size).toInt() - 1).coerceIn(0, STEPS.lastIndex)]
}

// Material Symbols glyphs live in the private use area, and icons.woff2 is
// subset to exactly these eight. Addressing them by codepoint rather than by
// ligature is what lets the subset drop its layout tables.
private val GLYPHS = mapOf(
    "block" to "",
    "dark_mode" to "",
    "light_mode" to "",
    "change_history" to "",
    "hexagon" to "",
    "diamond" to "",
    "asterisk" to "",
    "edit" to "",
)

private fun glyph(name: String) = GLYPHS[name] ?: "?"

private val CSS = """
    @font-face {
      font-family: 'Material Symbols Outlined';
      src: url('/icons.woff2') format('woff2');
      font-display: block;
    }
    :root { --bg: #11111b; --fg: #cdd6f4; --dim: #45475a; --empty: #313244; }
    body {
      background: var(--bg);
      color: var(--fg);
      font: 14px ui-monospace, monospace;
      margin: 0;
      padding: 2rem;
    }
    /* 53 columns do not fit a phone, and this is ticked from a phone. The grid
       scrolls; the icon stays pinned so the row being ticked stays identifiable. */
    .board { overflow-x: auto; display: flex; flex-direction: column; gap: 0.9rem; }
    .habit { display: flex; align-items: center; gap: 0.9rem; width: max-content; }
    .icon {
      font-family: 'Material Symbols Outlined';
      font-variation-settings: 'FILL' 0, 'wght' 300, 'GRAD' 0, 'opsz' 24;
      font-size: 1.5rem;
      line-height: 1;
      width: 1.6rem;
      text-align: center;
      position: sticky;
      left: 0;
      background: var(--bg);
    }
    button.icon {
      border: 0;
      padding: 0;
      cursor: pointer;
      font-family: 'Material Symbols Outlined';
      font-size: 1.5rem;
    }
    .grid {
      display: grid;
      grid-auto-flow: column;
      grid-template-rows: repeat(7, 10px);
      grid-auto-columns: 10px;
      gap: 3px;
    }
    .cell { border-radius: 2px; background: var(--empty); }
    .pad { background: transparent; }
    .today { outline: 1px solid var(--dim); outline-offset: 1px; }
""".trimIndent()
