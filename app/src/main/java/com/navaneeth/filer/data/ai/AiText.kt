package com.navaneeth.filer.data.ai

import java.time.LocalDate

/** Pure text rules for tags and suggested names. Unit-tested. */
object AiText {

    const val MAX_TAGS = 4
    private const val MAX_TAG_LENGTH = 24
    private const val MAX_NAME_LENGTH = 60

    /**
     * Names that say nothing about the file: camera, chat-app and scanner defaults, screenshots,
     * downloads that got a number. Only these get a name suggestion.
     */
    private val unclearPatterns = listOf(
        Regex("^(img|pxl|dsc|dscn|dcim|mvimg|vid|mov|pic|photo|image|picture)[-_ ]?\\d.*"),
        Regex(".*[-_]wa\\d{3,}.*"),
        Regex("^(scan|scanned|doc|document|file|untitled|new document|cam ?scanner)[-_ ]*(\\(\\d+\\)|\\d+)?([-_ ].*\\d.*)?$"),
        Regex("^(screenshot|screen ?shot|screen[-_]recording|screenrecorder)[-_ ]?.*"),
        Regex("^(download|downloaded file|attachment|unnamed|image)[-_ ]*(\\(\\d+\\)|\\d+)?$"),
        Regex("^\\d{6,}[-_]?\\d*$"),
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-.*"),
        Regex("^(rec|recording|voice|audio|ptt)[-_ ]?\\d.*")
    )

    fun isUnclearName(fileName: String): Boolean {
        val base = fileName.substringBeforeLast('.', fileName).trim().lowercase()
        if (base.isEmpty()) return true
        return unclearPatterns.any { it.matches(base) }
    }

    /** "#Tax Docs" → "tax-docs"; null when nothing usable is left. */
    fun normalizeTag(raw: String): String? {
        val tag = raw.trim().trimStart('#').lowercase()
            .replace(Regex("[\\s_]+"), "-")
            // \p{M}: vowel signs and other marks Indian scripts need ("बिल" keeps its ि).
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}-]"), "")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(MAX_TAG_LENGTH)
            .trim('-')
        return tag.takeIf { it.length >= 2 }
    }

    /**
     * A file name from the model's suggestion: no path, no illegal characters, keeps the file's own
     * extension. Null when the suggestion is empty or the same as the current name.
     */
    fun cleanSuggestedName(raw: String, currentName: String): String? {
        val ext = currentName.substringAfterLast('.', "").takeIf { it.isNotEmpty() && currentName.contains('.') }
        var base = raw.trim().trim('"', '\'', '`', '*').substringAfterLast('/')
        // The model may repeat an extension; the file's own is put back below.
        if (ext != null && base.endsWith(".$ext", ignoreCase = true)) base = base.dropLast(ext.length + 1)
        base = base.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "")
            .replace(Regex("\\s+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_', '.', ' ', '-')
            .take(MAX_NAME_LENGTH)
            .trim('_', '.', '-')
        if (base.length < 3) return null
        val name = if (ext != null) "$base.$ext" else base
        return name.takeUnless { it.equals(currentName, ignoreCase = true) }
    }

    /**
     * Splits "#receipts coffee #2024" into tags (["receipts", "2024"]) and the rest ("coffee").
     * Only words starting with # are tags.
     */
    fun extractTags(query: String): Pair<List<String>, String> {
        val tags = mutableListOf<String>()
        val rest = query.split(Regex("\\s+")).filter { word ->
            if (word.startsWith("#") && word.length > 1) {
                normalizeTag(word)?.let(tags::add)
                false
            } else {
                true
            }
        }
        return tags.distinct() to rest.joinToString(" ").trim()
    }

    /** A file's text as a few passages to embed for tags (files outside smart search only). */
    fun passagesForTags(text: String): List<String> {
        val squashed = text.replace(Regex("\\s+"), " ").trim()
        if (squashed.length < MIN_TEXT_FOR_TAGS) return emptyList()
        return squashed.chunked(TAG_PASSAGE_CHARS).take(MAX_TAG_PASSAGES)
    }

    // Suggested names (no model: built from what's known about the file)

    /**
     * A clearer name from the file's date and what it is: "2026-08-12_Invoice_Tata_Power.pdf",
     * "2026-08-14_Beach_Goa.jpg". Null unless there's something besides the date (a [type], [title]
     * or [place]): a date alone isn't clearer than the camera's name.
     */
    fun ruleName(currentName: String, date: LocalDate?, type: String?, title: String?, place: String?): String? {
        val words = listOfNotNull(
            type?.takeUnless { t -> title?.contains(t, ignoreCase = true) == true }?.let(::titleCase),
            title?.let(::titleCase),
            place
        )
        if (words.isEmpty()) return null
        val base = (listOfNotNull(date?.toString()) + words).joinToString(" ")
        return cleanSuggestedName(base, currentName)
    }

    /**
     * The document's title: the first of its opening lines that reads like one (2–8 words, mostly
     * letters, not a page number or a date).
     */
    fun titleLine(text: String): String? = text.lineSequence()
        .take(TITLE_LINES)
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .firstOrNull { line ->
            val words = line.split(' ')
            val letters = line.count { it.isLetter() }
            words.size in 2..8 && line.length in 6..60 && letters >= line.length * 0.7 &&
                !line.lowercase().startsWith("page ")
        }

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val isoDate = Regex("\\b(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})\\b")
    private val dayFirst = Regex("\\b(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})\\b")
    private val dayMonthName = Regex("(?i)\\b(\\d{1,2})(?:st|nd|rd|th)?[ -]([a-z]{3,9})[ ,-]+(\\d{4})\\b")
    private val monthNameDay = Regex("(?i)\\b([a-z]{3,9}) (\\d{1,2})(?:st|nd|rd|th)?,? (\\d{4})\\b")

    /** The first real date in [text] ("2026-08-12", "12/08/2026" day first, "12 Aug 2026", "Aug 12, 2026"). */
    fun dateIn(text: String, today: LocalDate = LocalDate.now()): LocalDate? {
        val head = text.take(DATE_SEARCH_CHARS)
        fun make(y: String, m: Int?, d: String): LocalDate? {
            val year = y.toInt()
            if (m == null || year !in 1990..today.year + 1) return null
            return runCatching { LocalDate.of(year, m, d.toInt()) }.getOrNull()
        }
        fun month(name: String) = months.indexOf(name.lowercase().take(3)).takeIf { it >= 0 }?.plus(1)
        return sequenceOf(
            isoDate.findAll(head).mapNotNull { make(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3]) },
            dayFirst.findAll(head).mapNotNull { make(it.groupValues[3], it.groupValues[2].toInt(), it.groupValues[1]) },
            dayMonthName.findAll(head).mapNotNull { make(it.groupValues[3], month(it.groupValues[2]), it.groupValues[1]) },
            monthNameDay.findAll(head).mapNotNull { make(it.groupValues[3], month(it.groupValues[1]), it.groupValues[2]) }
        ).flatten().firstOrNull()
    }

    /** "TAX INVOICE" → "Tax Invoice"; short capitals in a mixed-case title stay ("GST bill" → "GST Bill"). */
    private fun titleCase(text: String): String {
        val allCaps = text.none { it.isLowerCase() }
        return text.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { word ->
            val acronym = !allCaps && word.length in 2..4 && word.none { it.isLowerCase() }
            if (acronym) word else word.lowercase().replaceFirstChar { it.titlecase() }
        }
    }

    private const val MIN_TEXT_FOR_TAGS = 40
    private const val TAG_PASSAGE_CHARS = 1_000
    private const val MAX_TAG_PASSAGES = 6
    private const val TITLE_LINES = 12
    private const val DATE_SEARCH_CHARS = 3_000
}
