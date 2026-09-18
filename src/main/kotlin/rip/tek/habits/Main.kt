package rip.tek.habits

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
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
                call.respondHtml { board(db, kiosk = call.request.queryParameters.contains("kiosk")) }
            }

            post("/tick/{slug}") {
                val habit = db.habits().find { it.slug == call.parameters["slug"] }
                if (habit == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return@post
                }
                val day = call.receiveParameters()["day"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                if (day == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }
                // Wrapping past target back to zero is the only way to undo a
                // mistap: there is no separate clear button on the display.
                val next = (db.valueOn(habit.id, day) + 1) % (habit.target + 1)
                db.set(habit.id, day, next)
                call.respondRedirect(if (call.request.queryParameters.contains("kiosk")) "/?kiosk" else "/")
            }

            get("/icons.woff2") {
                val bytes = checkNotNull(Db::class.java.getResourceAsStream("/icons.woff2")).readBytes()
                call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
                call.respondBytes(bytes, ContentType("font", "woff2"))
            }
        }
    }.start(wait = true)
}

// How far back a run is allowed to be counted. Nothing on the display depends
// on older days, so this is the whole history the page needs to load.
private const val DAYS = 365

// A bar day is wider than a grid day (10px + 3px gap) on purpose: the bar has a
// single row to fill, so the same run has to read as a longer object there.
private const val PITCH = 17

private fun HTML.board(db: Db, kiosk: Boolean) {
    val habits = db.habits()
    val today = LocalDate.now()
    val values = db.valuesSince(today.minusDays((DAYS - 1).toLong()))
    val todayRow = today.dayOfWeek.value - 1

    head {
        title("habits")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        // The display reloads itself so that ticks made from a phone show up on
        // the wall without anyone walking over to it.
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
                val run = runOf(values[habit.id] ?: emptyMap(), habit.target, today)
                form(
                    action = "/tick/${habit.slug}" + if (kiosk) "?kiosk" else "",
                    method = FormMethod.post,
                    classes = "habit",
                ) {
                    style = "--c: ${habit.colour}"
                    div("head") {
                        span("icon" + if (run.length == 0) " dead" else "") { +glyph(habit.icon) }
                        button(name = "day", classes = "tile ${run.state}") {
                            value = today.toString()
                            +run.length.toString()
                        }
                    }
                    // Both views are always rendered; the belt slides whichever
                    // one is off-screen, so nothing left of the pane can move.
                    div("pane") {
                        div("view") { grid(run, todayRow) }
                        div("view") { bar(run) }
                    }
                }
            }
        }
    }
}

private fun DIV.grid(run: Run, todayRow: Int) {
    div("grid") {
        val last = run.length - 1 + run.offset
        // Only as many week columns as the run actually reaches, so a broken run
        // leaves the grid genuinely empty instead of drawing a field of blanks.
        val cols = if (run.length == 0) 0 else ((6 - todayRow) + last) / 7 + 1
        for (c in 0 until cols) {
            for (r in 0..6) {
                val ago = c * 7 + todayRow - r
                div(
                    when {
                        run.pending && ago == 0 -> "cell open"
                        ago >= run.offset && ago <= last -> "cell on"
                        else -> "cell"
                    }
                )
            }
        }
    }
}

private fun DIV.bar(run: Run) {
    if (run.length == 0) return
    div(if (run.pending) "bar open" else "bar") {
        style = "width: ${(run.length + run.offset) * PITCH - 3}px"
    }
}

/**
 * The current run only, snapstreak style: a miss resets it to zero and takes the
 * history with it. [pending] means the run reaches yesterday and today has not
 * been ticked yet, which is not a miss until the day is over.
 */
private data class Run(val length: Int, val pending: Boolean) {
    val offset = if (pending) 1 else 0
    val state = if (length == 0) "cold" else if (pending) "pending" else "hot"
}

private fun runOf(days: Map<LocalDate, Int>, target: Int, today: LocalDate): Run {
    // A counter habit only counts as a day once it reaches its target; a partial
    // day is a miss, otherwise the target would mean nothing.
    fun done(day: LocalDate) = (days[day] ?: 0) >= target

    val pending = !done(today) && done(today.minusDays(1))
    val end = if (pending) today.minusDays(1) else today
    if (!done(end)) return Run(0, false)

    var length = 0
    while (length < DAYS && done(end.minusDays(length.toLong()))) length++
    return Run(length, pending)
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
    :root { --bg: #11111b; --fg: #cdd6f4; --cold: #313244; --gone: #585b70; }
    body {
      background: var(--bg);
      color: var(--fg);
      font: 14px ui-monospace, monospace;
      margin: 0;
      padding: 2rem;
    }
    /* A long run is wider than a phone, and this is ticked from a phone. The
       views scroll; the icon and the tile stay pinned so the row being ticked
       stays identifiable and reachable. */
    .board { overflow-x: auto; display: flex; flex-direction: column; gap: 14px; }
    .habit { display: flex; align-items: center; gap: 14px; height: 88px; width: max-content; margin: 0; }
    .head { display: flex; align-items: center; gap: 14px; flex: none; position: sticky; left: 0; background: var(--bg); }
    .icon {
      font-family: 'Material Symbols Outlined';
      font-variation-settings: 'FILL' 0, 'wght' 300, 'GRAD' 0, 'opsz' 24;
      font-size: 24px;
      line-height: 1;
      color: var(--c);
      flex: none;
    }
    .dead { opacity: 0.3; }
    .tile {
      width: 88px;
      height: 88px;
      flex: none;
      box-sizing: border-box;
      border-radius: 6px;
      display: flex;
      align-items: center;
      justify-content: center;
      font: 600 34px/1 ui-monospace, monospace;
      letter-spacing: -1px;
      padding: 0;
      cursor: pointer;
    }
    .tile.hot { background: color-mix(in srgb, var(--c) 16%, transparent); border: 1px solid var(--c); color: var(--c); }
    .tile.pending { background: transparent; border: 1px dashed color-mix(in srgb, var(--c) 55%, transparent); color: color-mix(in srgb, var(--c) 72%, transparent); }
    .tile.cold { background: var(--cold); border: 1px solid transparent; color: var(--gone); }
    .tile:hover, .tile:focus-visible { outline: 1px solid var(--fg); outline-offset: 2px; }
    /* Weekday rows and week columns like a contribution graph, but only the live
       run is drawn: newest week on the left, older weeks to the right. */
    .grid {
      display: grid;
      grid-auto-flow: column;
      grid-template-rows: repeat(7, 10px);
      grid-auto-columns: 10px;
      gap: 3px;
      height: 88px;
    }
    .cell { width: 10px; height: 10px; border-radius: 2px; background: transparent; }
    .cell.on { background: var(--c); }
    .cell.open { border: 1px dashed color-mix(in srgb, var(--c) 70%, transparent); box-sizing: border-box; }
    .bar { height: 88px; border-radius: 6px; background: var(--c); flex: none; }
    .bar.open { background: transparent; border: 1px dashed color-mix(in srgb, var(--c) 70%, transparent); box-sizing: border-box; }
    /* The pane is per row rather than around the whole board so that the swap
       moves only the run, never the icon or the tile. */
    .pane { display: grid; height: 88px; overflow: hidden; flex: none; }
    .view { grid-area: 1 / 1; height: 100%; display: flex; align-items: center; }
    /* One belt: a view leaves upward, is teleported below while out of frame,
       and rides back up into place, so the loop only ever travels one way. */
    @keyframes belt {
      0%       { transform: translateY(0); }
      44%      { transform: translateY(0); animation-timing-function: cubic-bezier(.16, 1, .3, 1); }
      50%      { transform: translateY(-100%); }
      50.001%  { transform: translateY(100%); }
      94%      { transform: translateY(100%); animation-timing-function: cubic-bezier(.16, 1, .3, 1); }
      100%     { transform: translateY(0); }
    }
    .view { animation: belt 12s linear infinite; will-change: transform; }
    .view:nth-child(2) { animation-delay: -6s; }
""".trimIndent()
