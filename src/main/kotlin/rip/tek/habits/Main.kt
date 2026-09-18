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
import java.security.MessageDigest
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

            get(ICONS_PATH) {
                call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
                call.respondBytes(ICONS, ContentType("font", "woff2"))
            }
        }
    }.start(wait = true)
}

private val ICONS: ByteArray =
    checkNotNull(Db::class.java.getResourceAsStream("/icons.woff2")).readBytes()

// The font is served under a hash of its own bytes so that caching it for a
// year is actually safe. Re-subsetting it moves the URL, which is what stops a
// kiosk that has been open for weeks from drawing blanks where the new glyphs
// should be: it holds the old file, and the old file has no such codepoints.
private val ICONS_PATH: String = "/icons." +
    MessageDigest.getInstance("SHA-256").digest(ICONS).take(6).joinToString("") { "%02x".format(it) } +
    ".woff2"

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
                val days = values[habit.id] ?: emptyMap()
                val run = runOf(days, habit.cadence, today)
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
                        div("view") { grid(days, habit, run, today, todayRow) }
                        div("view") { bar(run) }
                    }
                }
            }
        }
    }
}

private fun DIV.grid(days: Map<LocalDate, Int>, habit: Habit, run: Run, today: LocalDate, todayRow: Int) {
    div("grid") {
        val first = run.offset
        val last = first + run.span - 1
        // Only as many week columns as the run actually reaches, so a dead habit
        // leaves the grid genuinely empty rather than drawing a field of blanks.
        val cols = if (run.span == 0) 0 else ((6 - todayRow) + last) / 7 + 1
        for (c in 0 until cols) {
            for (r in 0..6) {
                val ago = c * 7 + todayRow - r
                when {
                    run.pending && ago == 0 -> div("cell open")
                    ago in first..last -> {
                        val value = coverOn(days, habit.cadence, today.minusDays(ago.toLong()))
                        div("cell") { style = "background: ${shade(habit.colour, value, habit.target)}" }
                    }
                    else -> div("cell")
                }
            }
        }
    }
}

private fun DIV.bar(run: Run) {
    if (run.span == 0) return
    div("strip") {
        // The run is contiguous by construction - that is what makes it a run -
        // so it is one pill however many rest days its cadence let through.
        if (run.pending) div("bar open") { style = "width: ${PITCH - 3}px" }
        div("bar run") { style = "width: ${run.span * PITCH - 3}px" }
    }
}

/**
 * What a day is worth once cadence is taken into account: the value of the most
 * recent tick still covering it, or zero if the habit had lapsed by then.
 */
private fun coverOn(days: Map<LocalDate, Int>, cadence: Int, day: LocalDate): Int {
    for (back in 0 until cadence) {
        val value = days[day.minusDays(back.toLong())] ?: 0
        if (value > 0) return value
    }
    return 0
}

// Four steps rather than a continuous ramp: adjacent days have to be tellable
// apart at a glance from across a room, which a smooth gradient does not manage.
private val STEPS = listOf("40", "73", "b3", "ff")

private fun shade(colour: String, value: Int, target: Int): String {
    if (value <= 0) return "var(--empty)"
    val ratio = (value.toDouble() / target).coerceIn(0.0, 1.0)
    return colour + STEPS[(ceil(ratio * STEPS.size).toInt() - 1).coerceIn(0, STEPS.lastIndex)]
}

/**
 * The current run only, snapstreak style: a miss resets it to zero and takes the
 * history with it. [length] counts ticks rather than days, so a habit with rest
 * days and a daily one both read as the number of times the habit came round and
 * was kept, while [span] is the stretch of days those ticks cover between them.
 * [pending] means the run is alive but today is not covered yet.
 */
private data class Run(val length: Int, val span: Int, val pending: Boolean) {
    val offset = if (pending) 1 else 0
    val state = if (length == 0) "cold" else if (pending) "pending" else "hot"
}

private fun runOf(days: Map<LocalDate, Int>, cadence: Int, today: LocalDate): Run {
    val ticks = days.filterValues { it > 0 }.keys.filter { it <= today }.sortedDescending()
    val last = ticks.firstOrNull() ?: return Run(0, 0, false)

    // Coverage runs forward from a tick, so the run survives while it still
    // reaches yesterday: today is the day the habit comes due, not the day it is
    // lost. At cadence 1 that is the ordinary "ticked today or yesterday".
    if (last < today.minusDays(cadence.toLong())) return Run(0, 0, false)

    var length = 1
    for (i in 1 until ticks.size) {
        if (ticks[i - 1].toEpochDay() - ticks[i].toEpochDay() > cadence) break
        length++
    }
    val pending = last < today.minusDays((cadence - 1).toLong())
    val oldest = today.toEpochDay() - ticks[length - 1].toEpochDay()
    return Run(length, (oldest - (if (pending) 1 else 0) + 1).toInt(), pending)
}

// Material Symbols glyphs live in the private use area, and icons.woff2 is
// subset to exactly these eight. Addressing them by codepoint rather than by
// ligature is what lets the subset drop its layout tables.
private val GLYPHS = mapOf(
    "block" to "",
    "light_mode" to "",
    "dark_mode" to "",
    "fitness_center" to "",
    "code" to "",
    "bolt" to "",
    "work" to "",
    "edit" to "",
)

private fun glyph(name: String) = GLYPHS[name] ?: "?"

private val CSS = """
    @font-face {
      font-family: 'Material Symbols Outlined';
      src: url('$ICONS_PATH') format('woff2');
      font-display: block;
    }
    :root { --bg: #11111b; --fg: #cdd6f4; --empty: #313244; --gone: #585b70; }
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
    .tile.cold { background: var(--empty); border: 1px solid transparent; color: var(--gone); }
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
    .cell.open { background: transparent; border: 1px dashed color-mix(in srgb, var(--c) 70%, transparent); box-sizing: border-box; }
    /* The same run as one pill, so its length is a length rather than a number. */
    .strip { display: flex; align-items: center; gap: 3px; height: 88px; }
    .bar { height: 88px; border-radius: 6px; flex: none; }
    .bar.run { background: var(--c); }
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
