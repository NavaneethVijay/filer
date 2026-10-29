package com.navaneeth.filer.data.search

import com.navaneeth.filer.data.places.Gazetteer
import com.navaneeth.filer.data.places.PlaceQuery
import com.navaneeth.filer.ui.model.FileCategory
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Which of a file's dates a time phrase is about. ANY matches added, edited or taken. */
enum class DateField { ANY, ADDED, MODIFIED, TAKEN }

/**
 * A time range in epoch millis. [phrase] is the user's own words ("2 days ago"), shown back as the
 * filter's label. [around] is a slightly wider range to fall back to when nothing matches exactly.
 */
data class DateFilter(val start: Long, val end: Long, val phrase: String, val around: Pair<Long, Long>? = null)

/** A folder or app the files came from. [topLevel]: a folder at the root of storage ("Download"). */
data class FolderFilter(val label: String, val needle: String, val topLevel: Boolean)

data class SizeFilter(val minBytes: Long?, val maxBytes: Long?, val phrase: String)

/** [TAG] comes from "#tag" words, read before the rest of the query (see AiText.extractTags). */
enum class FilterKind { TYPE, DATE, FOLDER, SIZE, PLACE, TAG }

/**
 * A search turned into filters plus the words left over. "pdf files downloaded 2 days ago" is
 * type PDF, date added 2 days ago, no words: answered from Android's media index, no model needed.
 */
data class ParsedQuery(
    val terms: List<String>,
    val categories: Set<FileCategory> = emptySet(),
    val extensions: Set<String> = emptySet(),
    val dateField: DateField = DateField.ANY,
    val date: DateFilter? = null,
    val folders: List<FolderFilter> = emptyList(),
    val size: SizeFilter? = null,
    val place: PlaceQuery? = null,
    /** The place was a guess (a small town that might be an ordinary word); drop it if no photo matches. */
    val placeIsWeak: Boolean = false
) {
    val text: String get() = terms.joinToString(" ")
    val hasType: Boolean get() = categories.isNotEmpty() || extensions.isNotEmpty()

    val kinds: List<FilterKind>
        get() = buildList {
            if (hasType) add(FilterKind.TYPE)
            if (date != null) add(FilterKind.DATE)
            if (folders.isNotEmpty()) add(FilterKind.FOLDER)
            if (this@ParsedQuery.size != null) add(FilterKind.SIZE)
            if (place != null) add(FilterKind.PLACE)
        }

    /** The same query with some filters switched off by the user (their words are dropped, not searched). */
    fun without(removed: Set<FilterKind>): ParsedQuery = copy(
        categories = if (FilterKind.TYPE in removed) emptySet() else categories,
        extensions = if (FilterKind.TYPE in removed) emptySet() else extensions,
        date = if (FilterKind.DATE in removed) null else date,
        folders = if (FilterKind.FOLDER in removed) emptyList() else folders,
        size = if (FilterKind.SIZE in removed) null else size,
        place = if (FilterKind.PLACE in removed) null else place
    )
}

/**
 * Rule-based reading of natural searches: types ("pdfs", "photos", "docx"), dates ("yesterday",
 * "2 days ago", "last week", "in march", "2023", "since monday"), which date ("downloaded",
 * "taken", "edited"), apps and folders ("whatsapp", "downloads", "screenshots", "work folder"),
 * size ("over 10 mb", "large") and, for photos, places ("from goa", "paris photos").
 */
object QueryParser {

    fun parse(query: String, gazetteer: Gazetteer?, today: LocalDate = LocalDate.now(), allowWeakPlace: Boolean = true): ParsedQuery {
        val p = Parse(normalize(query), today)
        p.size()
        p.dateField()
        p.dates()
        p.folders()
        if (gazetteer != null) p.places(gazetteer, allowWeakPlace)
        p.types()
        p.sizeWords()
        return p.result()
    }

    private fun normalize(query: String): String = " " + query.lowercase()
        .replace(Regex("[’']s\\b"), "")
        .replace(Regex("[^\\p{L}\\p{N}.\\s-]"), " ")
        .replace(Regex("(\\d)(kb|mb|gb|k|m|g)\\b"), "$1 $2")
        .replace(Regex("\\s+"), " ")
        .trim() + " "

    private class Parse(var s: String, val today: LocalDate) {
        val categories = mutableSetOf<FileCategory>()
        val extensions = mutableSetOf<String>()
        var field = DateField.ANY
        var date: DateFilter? = null
        val folders = mutableListOf<FolderFilter>()
        var size: SizeFilter? = null
        var sizeWord: String? = null
        var place: PlaceQuery? = null
        var placeIsWeak = false

        /** Finds [pattern] (word-bounded); if [use] accepts the match, removes it from the text. */
        fun take(pattern: String, use: (MatchResult) -> Boolean) = takeReplacing(pattern) { if (use(it)) "" else null }

        /**
         * Finds [pattern] (word-bounded) and replaces each match with what [use] returns (words to
         * keep searching), or leaves it when [use] returns null. The only place the text changes.
         */
        fun takeReplacing(pattern: String, use: (MatchResult) -> String?) {
            val regex = Regex("(?<=\\s)(?:$pattern)(?=\\s)")
            var from = 0
            while (from < s.length) {
                val m = regex.find(s, from) ?: return
                val replacement = use(m)
                if (replacement != null) {
                    s = (s.substring(0, m.range.first) + " $replacement " + s.substring(m.range.last + 1)).replace(Regex("\\s+"), " ")
                    if (!s.startsWith(" ")) s = " $s"
                    if (!s.endsWith(" ")) s = "$s "
                    from = 0
                } else {
                    from = m.range.last + 1
                }
            }
        }

        // Size

        fun size() {
            val unit = "(kb|mb|gb|k|m|g)"
            take("(?:over|above|more than|bigger than|larger than|greater than|at least|>)\\s*(\\d+(?:\\.\\d+)?)\\s*$unit") { m ->
                size = SizeFilter(bytes(m.groupValues[1], m.groupValues[2]), size?.maxBytes, m.value.trim()); true
            }
            take("(?:under|below|less than|smaller than|at most|<)\\s*(\\d+(?:\\.\\d+)?)\\s*$unit") { m ->
                size = SizeFilter(size?.minBytes, bytes(m.groupValues[1], m.groupValues[2]), m.value.trim()); true
            }
            take("(large|big|huge|heavy|biggest|largest|small|tiny|smallest)") { m -> sizeWord = m.groupValues[1]; true }
        }

        /** "large" depends on the type: a large photo is smaller than a large video. */
        fun sizeWords() {
            val word = sizeWord ?: return
            if (size != null) return
            val large = word in setOf("large", "big", "huge", "heavy", "biggest", "largest")
            val mb = 1024L * 1024
            size = when {
                large && FileCategory.VIDEOS in categories -> SizeFilter(200 * mb, null, word)
                large && FileCategory.IMAGES in categories -> SizeFilter(5 * mb, null, word)
                large -> SizeFilter(20 * mb, null, word)
                FileCategory.IMAGES in categories -> SizeFilter(null, 200 * 1024L, word)
                else -> SizeFilter(null, mb, word)
            }
        }

        private fun bytes(n: String, unit: String): Long {
            val k = 1024.0
            val mult = when (unit.first()) {
                'k' -> k
                'm' -> k * k
                else -> k * k * k
            }
            return (n.toDouble() * mult).toLong()
        }

        // Dates

        fun dateField() {
            take("(downloaded|received|saved|got|added|created|shared with me|sent to me|sent me)") { field = DateField.ADDED; true }
            take("(taken|shot|clicked|captured|snapped|recorded)") { field = DateField.TAKEN; true }
            take("(modified|edited|changed|updated)") { field = DateField.MODIFIED; true }
        }

        fun dates() {
            val prefix = "(?:(since|after|before|from|on|in|during)\\s+)?"
            val count = "(\\d+|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|couple of|a couple of|few|a few)"

            take("day before yesterday") { set(day(today.minusDays(2)), it.value, around(today.minusDays(3), today.minusDays(1))); true }
            take("(yesterday|last night)") { set(day(today.minusDays(1)), it.value, around(today.minusDays(2), today)); true }
            take("(today|this morning|this evening|tonight|this afternoon)") { set(day(today), it.value, around(today.minusDays(1), today)); true }

            take("$prefix$count\\s+(day|week|month|year)s?\\s+(?:ago|back|before)") { m ->
                val n = number(m.groupValues[2])
                val (range, near) = when (m.groupValues[3]) {
                    "day" -> today.minusDays(n).let { day(it) to around(it.minusDays(1), it.plusDays(1)) }
                    "week" -> today.minusWeeks(n).let { range(it.minusDays(3), it.plusDays(3)) to around(it.minusDays(7), it.plusDays(7)) }
                    "month" -> today.minusMonths(n).let { month(it) to range(it.withDayOfMonth(1).minusMonths(1), it.withDayOfMonth(1).plusMonths(2).minusDays(1)) }
                    else -> today.minusYears(n).let { year(it.year) to null }
                }
                set(relative(m.groupValues[1], range), m.value, near)
                true
            }
            take("(?:in |within |during |over |from )?(?:the )?(?:last|past|previous)\\s+$count\\s+(day|week|month|year)s?") { m ->
                val n = number(m.groupValues[1])
                val start = when (m.groupValues[2]) {
                    "day" -> today.minusDays(n - 1)
                    "week" -> today.minusWeeks(n)
                    "month" -> today.minusMonths(n)
                    else -> today.minusYears(n)
                }
                set(range(start, today), m.value, null)
                true
            }
            take("(?:in |during |from )?(?:the )?(this|last|past|previous)\\s+(week|weekend|month|year)") { m ->
                val last = m.groupValues[1] != "this"
                val past = m.groupValues[1] == "past"
                val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                when (m.groupValues[2]) {
                    "week" -> when {
                        past -> set(range(today.minusDays(6), today), m.value, null)
                        last -> set(range(monday.minusWeeks(1), monday.minusDays(1)), m.value, around(monday.minusWeeks(1), today))
                        else -> set(range(monday, today), m.value, around(monday.minusWeeks(1), today))
                    }
                    "weekend" -> {
                        val saturday = today.with(TemporalAdjusters.previous(DayOfWeek.SATURDAY))
                        set(range(saturday, saturday.plusDays(1)), m.value, around(saturday.minusDays(1), saturday.plusDays(2)))
                    }
                    "month" -> {
                        val target = if (last) today.minusMonths(1) else today
                        set(month(target), m.value, if (past) range(today.minusMonths(1), today) else null)
                    }
                    else -> set(year(if (last) today.year - 1 else today.year), m.value, null)
                }
                true
            }

            val months = MONTHS.keys.joinToString("|")
            // "5 march", "march 5th", "march 5 2024"
            take("$prefix(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?($months)(?:\\s+(\\d{4}))?") { m ->
                specificDay(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3], m.groupValues[4], m.value)
            }
            take("$prefix($months)\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:\\s+(\\d{4}))?") { m ->
                specificDay(m.groupValues[1], m.groupValues[3].toInt(), m.groupValues[2], m.groupValues[4], m.value)
            }
            // "in march", "last december", "march 2024"; bare "may" only with a prefix or year (it's also a verb).
            take("(?:(since|after|before|from|in|during|last|this)\\s+)?($months)(?:\\s+(\\d{4}))?") { m ->
                val name = m.groupValues[2]
                if (name == "may" && m.groupValues[1].isEmpty() && m.groupValues[3].isEmpty()) return@take false
                val monthNumber = MONTHS.getValue(name)
                val year = m.groupValues[3].toIntOrNull()
                    ?: if (monthNumber > today.monthValue || (m.groupValues[1] == "last" && monthNumber == today.monthValue)) today.year - 1 else today.year
                val first = LocalDate.of(year, monthNumber, 1)
                set(relative(m.groupValues[1].takeUnless { it == "last" || it == "this" }.orEmpty(), month(first)), m.value, null)
                true
            }
            take("$prefix((?:19|20)\\d{2})") { m ->
                set(relative(m.groupValues[1], year(m.groupValues[2].toInt())), m.value, null); true
            }
            take("(?:(since|after|before|on|last|this)\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)") { m ->
                val dow = DayOfWeek.valueOf(m.groupValues[2].uppercase())
                val d = if (m.groupValues[1] == "last") today.with(TemporalAdjusters.previous(dow)) else today.with(TemporalAdjusters.previousOrSame(dow))
                val kind = m.groupValues[1].takeUnless { it == "last" || it == "this" }.orEmpty()
                set(relative(kind, day(d)), m.value, around(d.minusDays(1), d.plusDays(1)))
                true
            }
            // Not "new": it's part of too many place names (New York, New Delhi).
            take("(recent|recently|lately)") { m ->
                set(range(today.minusDays(6), today), m.value, range(today.minusDays(30), today)); true
            }
            take("(latest|newest)") { true }
        }

        private fun specificDay(kind: String, dayOfMonth: Int, monthName: String, yearText: String, phrase: String): Boolean {
            val monthNumber = MONTHS.getValue(monthName)
            if (dayOfMonth !in 1..31) return false
            val year = yearText.toIntOrNull()
                ?: if (monthNumber > today.monthValue || (monthNumber == today.monthValue && dayOfMonth > today.dayOfMonth)) today.year - 1 else today.year
            val d = runCatching { LocalDate.of(year, monthNumber, dayOfMonth) }.getOrNull() ?: return false
            set(relative(kind, day(d)), phrase, around(d.minusDays(1), d.plusDays(1)))
            return true
        }

        private fun set(range: Pair<Long, Long>, phrase: String, near: Pair<Long, Long>?) {
            if (date == null) date = DateFilter(range.first, range.second, phrase.trim(), near)
        }

        /** "since X" / "after X": from X until now; "before X": everything until X. */
        private fun relative(kind: String, range: Pair<Long, Long>): Pair<Long, Long> = when (kind) {
            "since", "after" -> range.first to endOf(today)
            "before" -> 0L to range.first - 1
            else -> range
        }

        // Folders and apps

        fun folders() {
            take("(?:from |in |on |via |through )?(?:my )?(whatsapp|telegram|instagram|snapchat|signal|messenger|bluetooth|twitter|facebook|discord|slack|teams|gmail)") { m ->
                val app = m.groupValues[1]
                folders += FolderFilter(APP_LABELS[app] ?: app, APP_FOLDERS[app] ?: app, topLevel = false); true
            }
            take("(?:from |in )?(?:the |my )?(downloads|download folder|download)") {
                folders += FolderFilter("Download", "Download", topLevel = true); true
            }
            take("(?:from |in |on )?(?:the |my )?(camera roll|camera|dcim)") {
                folders += FolderFilter("Camera", "DCIM", topLevel = true); true
            }
            take("(screenshots?|screen shots?)") {
                folders += FolderFilter("Screenshots", "Screenshots", topLevel = false)
                categories += FileCategory.IMAGES; true
            }
            take("(screen recordings?|screen records?)") {
                folders += FolderFilter("Screen recordings", "Screen", topLevel = false)
                categories += FileCategory.VIDEOS; true
            }
            take("(voice notes?|voice messages?)") {
                folders += FolderFilter("Voice notes", "Voice Notes", topLevel = false)
                categories += FileCategory.AUDIO; true
            }
            take("(?:in |from |inside )?(?:the |my )?([\\p{L}\\p{N}][\\p{L}\\p{N}._-]{0,30})\\s+folder") { m ->
                folder(m.groupValues[1]); true
            }
            take("folder\\s+(?:named |called )?([\\p{L}\\p{N}][\\p{L}\\p{N}._-]{0,30})") { m ->
                folder(m.groupValues[1]); true
            }
            take("(?:in|from)\\s+(?:the |my )?(documents|pictures|music|movies|podcasts|recordings|audiobooks)") { m ->
                folder(m.groupValues[1]); true
            }
        }

        private fun folder(name: String) {
            val label = name.replaceFirstChar { it.uppercase() }
            folders += FolderFilter(label, name, topLevel = false)
        }

        // Places (photos only)

        fun places(gazetteer: Gazetteer, allowWeak: Boolean) {
            takeReplacing("(?:in|at|from|near|around)\\s+(?:the )?([\\p{L}][\\p{L}.'-]*(?:\\s+[\\p{L}][\\p{L}.'-]*){0,3})") { m ->
                if (place != null) return@takeReplacing null
                val words = m.groupValues[1].split(' ')
                // Longest place name first: "new york city" before "new york" before "new".
                for (n in words.size downTo 1) {
                    val phrase = words.take(n).joinToString(" ")
                    if (phrase in NOT_PLACES) continue
                    val found = gazetteer.lookup(phrase) ?: continue
                    val weak = !strong(found)
                    if (weak && !allowWeak) continue
                    place = found
                    placeIsWeak = weak
                    // Only the place words are consumed; the rest goes back into the text.
                    return@takeReplacing words.drop(n).joinToString(" ")
                }
                null
            }
            if (place != null) return
            // "goa photos", "paris pics": only well-known places, since any word can be a small town.
            takeReplacing("((?:[\\p{L}][\\p{L}.'-]*\\s+){0,2}[\\p{L}][\\p{L}.'-]*)\\s+(photos?|pics?|pictures?|images?|selfies?)") { m ->
                if (place != null) return@takeReplacing null
                val words = m.groupValues[1].split(' ')
                for (start in words.indices) {
                    val phrase = words.drop(start).joinToString(" ")
                    if (phrase in NOT_PLACES) continue
                    val found = gazetteer.lookup(phrase)?.takeIf(::strong) ?: continue
                    place = found
                    return@takeReplacing (words.take(start) + m.groupValues[2]).joinToString(" ")
                }
                null
            }
            if (place != null) categories += FileCategory.IMAGES
        }

        /** Countries, states and cities of 100k+ people; anything smaller may be an ordinary word. */
        private fun strong(place: PlaceQuery) = place !is PlaceQuery.City || place.points.any { it.radiusKm >= 12.0 }

        // Types

        fun types() {
            TYPE_WORDS.forEach { (pattern, category) -> take(pattern) { categories += category; true } }
            take("\\.?(${EXTENSIONS.joinToString("|")})s?") { m -> extensions += m.groupValues[1]; true }
        }

        fun result(): ParsedQuery {
            val terms = s.split(' ').map { it.trim('.', '-') }.filter { it.length >= 2 && it !in STOPWORDS }
            return ParsedQuery(
                terms = terms,
                categories = categories,
                extensions = extensions,
                dateField = if (date != null) field else DateField.ANY,
                date = date,
                folders = folders,
                size = size,
                place = place,
                placeIsWeak = placeIsWeak
            )
        }

        private fun number(word: String): Long = word.toLongOrNull() ?: when (word) {
            "a", "an", "one" -> 1
            "two", "couple of", "a couple of" -> 2
            "three", "few", "a few" -> 3
            "four" -> 4
            "five" -> 5
            "six" -> 6
            "seven" -> 7
            "eight" -> 8
            "nine" -> 9
            "ten" -> 10
            "eleven" -> 11
            else -> 12
        }

        private val zone = ZoneId.systemDefault()
        private fun startOf(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        private fun endOf(d: LocalDate) = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        fun day(d: LocalDate) = startOf(d) to endOf(d)
        fun range(from: LocalDate, to: LocalDate) = startOf(from) to endOf(minOf(to, today))
        fun around(from: LocalDate, to: LocalDate) = range(from, to)
        fun month(d: LocalDate) = range(d.withDayOfMonth(1), d.withDayOfMonth(d.lengthOfMonth()))
        fun year(y: Int) = range(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31))
    }

    private val MONTHS = mapOf(
        "january" to 1, "jan" to 1, "february" to 2, "feb" to 2, "march" to 3, "mar" to 3, "april" to 4, "apr" to 4,
        "may" to 5, "june" to 6, "jun" to 6, "july" to 7, "jul" to 7, "august" to 8, "aug" to 8,
        "september" to 9, "sept" to 9, "sep" to 9, "october" to 10, "oct" to 10, "november" to 11, "nov" to 11,
        "december" to 12, "dec" to 12
    )

    private val APP_FOLDERS = mapOf("whatsapp" to "WhatsApp", "telegram" to "Telegram", "instagram" to "Instagram",
        "snapchat" to "Snapchat", "signal" to "Signal", "messenger" to "Messenger", "bluetooth" to "Bluetooth",
        "twitter" to "Twitter", "facebook" to "Facebook", "discord" to "Discord", "slack" to "Slack", "teams" to "Teams", "gmail" to "Gmail")
    private val APP_LABELS = APP_FOLDERS

    private val TYPE_WORDS = listOf(
        "pdfs?" to FileCategory.PDFS,
        "(photos?|pictures?|pics?|images?|imgs?|selfies?|wallpapers?|snaps)" to FileCategory.IMAGES,
        "(videos?|movies?|clips?|films?|reels?)" to FileCategory.VIDEOS,
        "(audio|music|songs?|tracks?|recordings?|podcasts?|voice notes?)" to FileCategory.AUDIO,
        "(docs?|documents?|word files?|spreadsheets?|excel|sheets?|presentations?|slides|powerpoint|ebooks?|books?)" to FileCategory.DOCUMENTS,
        "(code|scripts?|source code)" to FileCategory.CODE,
        "(zips?|archives?|compressed|rars?)" to FileCategory.ARCHIVES,
        "(apks?|apps?|installers?)" to FileCategory.APKS
    )

    private val EXTENSIONS = listOf(
        "jpg", "jpeg", "png", "heic", "webp", "gif", "mp4", "mkv", "mov", "mp3", "m4a", "wav", "opus", "docx", "doc",
        "xlsx", "xls", "csv", "pptx", "ppt", "txt", "md", "json", "epub", "odt", "rtf", "html", "xml", "kt", "py", "js", "7z", "tar"
    )

    /** Words that only shape the request ("show me my files from…"). */
    private val STOPWORDS = setOf(
        "show", "me", "find", "search", "get", "give", "list", "open", "look", "looking", "for", "where", "what", "which",
        "whose", "who", "are", "is", "was", "were", "did", "do", "does", "i", "my", "mine", "our", "we", "you", "your",
        "the", "a", "an", "of", "to", "with", "and", "or", "that", "this", "those", "these", "all", "any", "some", "every",
        "from", "in", "on", "at", "by", "about", "files", "file", "stuff", "things", "thing", "ones", "one", "please",
        "can", "could", "would", "have", "had", "has", "been", "there", "here", "related", "regarding", "containing",
        "contains", "called", "named", "titled", "phone", "ago", "it", "them", "had", "kept", "stored", "put", "around",
        "near", "taken", "sent", "folder", "folders", "type", "kind", "between", "during", "since", "after", "before",
        "new", "old", "older"
    )

    /** Phrases that read like places after "in/from" but aren't. */
    private val NOT_PLACES = setOf(
        "my", "the", "a", "an", "it", "this", "that", "phone", "home", "work", "office", "school", "college",
        "holiday", "vacation", "trip", "party", "wedding", "beach", "family", "friends", "storage", "memory", "sd card",
        "internal storage", "chrome", "gallery", "files", "inbox", "email", "mail", "drive", "cloud"
    )
}
