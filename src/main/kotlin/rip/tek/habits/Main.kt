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

fun main() {
    val port = System.getenv("HABITS_PORT")?.toInt() ?: 8095
    val db = Db(System.getenv("HABITS_DB") ?: "habits.db").apply { migrate() }

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        routing {
            get("/") {
                val habits = db.habits()
                val today = LocalDate.now()
                call.respondHtml {
                    head {
                        title("habits")
                        meta(name = "viewport", content = "width=device-width, initial-scale=1")
                        style { unsafe { +CSS } }
                    }
                    body {
                        habits.forEach { habit ->
                            div("row") {
                                span("icon") {
                                    style = "color: ${habit.colour}"
                                    +glyph(habit.icon)
                                }
                                span("val") { +"${db.valueOn(habit.id, today)} / ${habit.target}" }
                            }
                        }
                    }
                }
            }

            get("/icons.woff2") {
                val bytes = checkNotNull(Db::class.java.getResourceAsStream("/icons.woff2")).readBytes()
                call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
                call.respondBytes(bytes, ContentType("font", "woff2"))
            }

            // TODO(you): GET /?kiosk -> same grid, no buttons, meta-refresh so the
            // wall display picks up ticks made from your phone.

            // TODO(you): POST /tick/{slug} -> db.set(...), then redirect back to /.
            // Binary habits toggle 0 <-> 1; counters increment and wrap at target.

            // TODO(you): the year grid. db.valuesSince(today.minusDays(364)) gives you
            // habit id -> day -> value. Lay each habit out as 53 columns x 7 rows with
            // `grid-auto-flow: column`, shading by value/target.
        }
    }.start(wait = true)
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
    :root { --bg: #11111b; --fg: #cdd6f4; --dim: #45475a; }
    body {
      background: var(--bg);
      color: var(--fg);
      font: 14px ui-monospace, monospace;
      margin: 0;
      padding: 2rem;
    }
    .row { display: flex; align-items: center; gap: 1rem; padding: 0.35rem 0; }
    .icon {
      font-family: 'Material Symbols Outlined';
      font-variation-settings: 'FILL' 0, 'wght' 300, 'GRAD' 0, 'opsz' 24;
      font-size: 1.5rem;
      line-height: 1;
      width: 1.6rem;
      text-align: center;
    }
    .val { color: var(--dim); }
""".trimIndent()
