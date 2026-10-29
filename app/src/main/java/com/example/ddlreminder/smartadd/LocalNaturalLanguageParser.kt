package com.example.ddlreminder.smartadd

import com.example.ddlreminder.reminder.ReminderOffset
import java.time.*
import java.time.temporal.TemporalAdjusters

/** 保守离线 fallback：只为有明确日期和事件语义的片段建 Draft，说明句会合并到 notes。 */
class LocalNaturalLanguageParser : NaturalLanguageParser {
    override suspend fun parse(text: String, nowMillis: Long, zoneId: ZoneId): ParseResult {
        val normalized = text.trim()
        if (normalized.isBlank()) return ParseResult(emptyList(), "请输入需要解析的日程")
        if (isPureNoise(normalized) || ("取消" in normalized && !Regex("改到|改为|延期到").containsMatchIn(normalized))) {
            return ParseResult(emptyList(), "未识别到可创建的日程", discardedFragmentCount = 1)
        }
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        val chunks = eventChunks(normalized)
        val drafts = mutableListOf<ScheduleDraft>()
        val leftovers = mutableListOf<String>()
        var inheritedDate: LocalDate? = null
        chunks.forEach { chunk ->
            if (hasEventMeaning(chunk) && (hasDateExpression(chunk) || (hasExplicitTime(chunk) && inheritedDate != null))) {
                val draft = parseEvent(chunk, nowMillis, zoneId, now.toLocalDate(), inheritedDate)
                drafts += draft
                inheritedDate = draft.date ?: inheritedDate
            } else leftovers += chunk
        }
        if (drafts.size == 1) {
            val notes = extractNotes(normalized, drafts.single().title)
            drafts[0] = drafts[0].copy(notes = notes)
        }
        val warning = when {
            drafts.isEmpty() && normalized.length > 40 -> "本地解析无法可靠理解这段文本，建议启用AI智能解析"
            drafts.isEmpty() -> "未识别到可创建的日程"
            else -> null
        }
        return ParseResult(drafts, warning, discardedFragmentCount = leftovers.size)
    }

    private fun eventChunks(text: String): List<String> {
        val sentenceChunks = text.split(Regex("[。；;\\n]+")).map(String::trim).filter(String::isNotBlank)
        val result = mutableListOf<String>()
        var current: StringBuilder? = null
        sentenceChunks.forEach { sentence ->
            val commaChunks = sentence.split(Regex("[，,](?=(?:今天|明天|后天|这周|本周|下周|周[一二三四五六日天]|\\d{1,2}月|(?:上午|下午|晚上|中午|凌晨).{0,6}(?:考试|班会|会议|活动|汇报|pre)))"))
            commaChunks.forEach { chunk ->
                val trimmed = chunk.trim()
                if ((hasDateExpression(trimmed) || hasExplicitTime(trimmed)) && hasEventMeaning(trimmed)) {
                    current?.let { result += it.toString() }
                    current = StringBuilder(trimmed)
                } else if (current != null) {
                    current!!.append('。').append(trimmed)
                } else result += trimmed
            }
        }
        current?.let { result += it.toString() }
        return result
    }

    private fun parseEvent(source: String, nowMillis: Long, zoneId: ZoneId, today: LocalDate, inheritedDate: LocalDate? = null): ScheduleDraft {
        val date = parseDate(source, today) ?: inheritedDate
        val exact = parseExactTime(source)
        val period = parsePeriod(source)
        val precision = when { exact != null -> TimePrecision.EXACT; period != TimePeriod.NONE -> TimePrecision.PERIOD; date != null -> TimePrecision.DATE_ONLY; else -> TimePrecision.UNKNOWN }
        val needsConfirmation = precision == TimePrecision.PERIOD
        val effectiveTime = when { exact != null -> exact; precision == TimePrecision.DATE_ONLY -> LocalTime.of(23, 59); else -> null }
        val deadline = if (date != null && effectiveTime != null) date.atTime(effectiveTime).atZone(zoneId).toInstant().toEpochMilli() else null
        val warnings = mutableListOf<String>()
        if (needsConfirmation) warnings += "请确认具体时间"
        if (precision == TimePrecision.DATE_ONLY) warnings += "未识别具体时间，已默认设置为23:59"
        val tentative = Regex("暂定|拟定|预计").containsMatchIn(source)
        if (tentative) warnings += "安排为暂定，请后续确认"
        if (Regex("改到|改为|延期到").containsMatchIn(source)) warnings += "文本包含改期信息，请确认"
        var canCreate = date != null && effectiveTime != null && !needsConfirmation
        if (deadline != null && deadline <= nowMillis) { canCreate = false; warnings += "识别出的日期时间已经过去，请确认" }
        return ScheduleDraft(
            title = extractTitle(source), deadlineMillis = deadline, reminderOffsets = parseReminders(source),
            remindersExplicit = parseReminders(source).isNotEmpty(), warnings = warnings, usedDefaultTime = precision == TimePrecision.DATE_ONLY,
            ambiguousDate = date == null, sourceText = source, confidence = if (date != null) ParseConfidence.HIGH else ParseConfidence.LOW,
            hasBlockingError = !canCreate, date = date, exactTime = exact, timePeriod = period, timePrecision = precision,
            needsTimeConfirmation = needsConfirmation, canCreate = canCreate, isTentative = tentative,
        )
    }

    private fun parseDate(text: String, today: LocalDate): LocalDate? {
        if ("后天" in text) return today.plusDays(2); if ("明天" in text) return today.plusDays(1); if ("今天" in text) return today
        Regex("(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})[号日]?").find(text)?.let { m ->
            val month = m.groupValues[2].toInt(); val day = m.groupValues[3].toInt(); var year = m.groupValues[1].toIntOrNull() ?: today.year
            var candidate = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
            if (m.groupValues[1].isBlank() && candidate.isBefore(today)) candidate = runCatching { LocalDate.of(++year, month, day) }.getOrNull() ?: return null
            return candidate
        }
        Regex("(下周|这周|本周|周)([一二三四五六日天])").find(text)?.let { m ->
            val day = weekday(m.groupValues[2]); val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return monday.plusWeeks(if (m.groupValues[1] == "下周") 1 else 0).plusDays((day.value - 1).toLong()).let {
                if (m.groupValues[1] == "周" && it.isBefore(today)) it.plusWeeks(1) else it
            }
        }
        if ("月底" in text) return today.with(TemporalAdjusters.lastDayOfMonth())
        return null
    }

    private fun parseExactTime(text: String): LocalTime? {
        Regex("(\\d{1,2}):(\\d{2})").find(text)?.let {
            var hour = it.groupValues[1].toInt()
            val prefix = text.substring(0, it.range.first)
            if ((prefix.endsWith("下午") || prefix.endsWith("晚上")) && hour in 1..11) hour += 12
            if (prefix.endsWith("中午") && hour in 1..10) hour += 12
            return runCatching { LocalTime.of(hour, it.groupValues[2].toInt()) }.getOrNull()
        }
        Regex("(凌晨|早上|上午|中午|下午|晚上)?([零〇一二两三四五六七八九十\\d]{1,3})[点时](半|([零〇一二两三四五六七八九十\\d]{1,2})分?)?")
            .findAll(text).firstOrNull { !text.substring(0, it.range.first).endsWith("提前") }?.let { m ->
                var hour = chineseNumber(m.groupValues[2]) ?: return null; val minute = if (m.groupValues[3] == "半") 30 else chineseNumber(m.groupValues[4]) ?: 0
                if (m.groupValues[1] in listOf("下午", "晚上") && hour in 1..11) hour += 12
                if (m.groupValues[1] == "中午" && hour in 1..10) hour += 12
                return runCatching { LocalTime.of(hour, minute) }.getOrNull()
            }
        return null
    }

    private fun parsePeriod(text: String): TimePeriod = when {
        "凌晨" in text || "夜间" in text -> TimePeriod.NIGHT
        "早上" in text || "上午" in text -> TimePeriod.MORNING
        "中午" in text -> TimePeriod.NOON
        "下午" in text -> TimePeriod.AFTERNOON
        "晚上" in text -> TimePeriod.EVENING
        else -> TimePeriod.NONE
    }

    fun parseReminders(text: String): Set<ReminderOffset> {
        val part = text.substringAfter("提前", ""); val values = mutableListOf<Long>()
        if (Regex("(?:一|1)周").containsMatchIn(part)) values += 10080
        Regex("([零一二两三四五六七八九十\\d]+)天").findAll(part).forEach { chineseNumber(it.groupValues[1])?.let { n -> values += n * 1440L } }
        Regex("([零一二两三四五六七八九十\\d]+)(?:个)?小时").findAll(part).forEach { chineseNumber(it.groupValues[1])?.let { n -> values += n * 60L } }
        Regex("([零一二两三四五六七八九十\\d]+)分钟").findAll(part).forEach { chineseNumber(it.groupValues[1])?.let { n -> values += n.toLong() } }
        if (Regex("事件发生时|当天提醒").containsMatchIn(text)) values += 0
        return ReminderOffset.normalize(values)
    }

    private fun extractTitle(source: String): String {
        // 提取标题前移除简单句开头的日期和时间。
        val text = source.replace(Regex("^(?:今天|明天|后天|(?:下周|这周|本周|周)[一二三四五六日天]|(?:\\d{4}年)?\\d{1,2}月\\d{1,2}[号日]?)(?:(?:凌晨|早上|上午|中午|下午|晚上)?(?:\\d{1,2}:\\d{2}|[零〇一二两三四五六七八九十\\d]{1,3}点(?:半|[零〇一二两三四五六七八九十\\d]{1,2}分)?))?"), "")
        Regex("([\\p{L}\\d]{0,16}(?:考试|体测|测验))").find(text)?.let { return it.groupValues[1].replace(Regex("^(?:原定|暂定|安排|本次)"), "").takeLast(20) }
        Regex("(?:交|提交)([^，。；]{0,20}?(?:作业|报告|论文|材料|申请))").find(text)?.let { return it.groupValues[1] }
        Regex("(?:开|参加)?([^，。；]{0,12}(?:班会|会议|组会|活动|汇报|答辩|比赛|竞赛|生日|报名|pre|Pre|PRE))").find(text)?.let { return it.groupValues[1].ifBlank { "日程" } }
        return text.replace(Regex("今天|明天|后天|(?:下周|这周|本周|周)[一二三四五六日天]|(?:\\d{4}年)?\\d{1,2}月\\d{1,2}[号日]?|上午|下午|晚上|中午|凌晨|\\d{1,2}:\\d{2}|[零一二三四五六七八九十\\d]+点半?"), "")
            .replace(Regex("暂定于|改到|改为|延期到|举行|进行|请"), "").substringBefore('。').trim('，', ',', ' ').take(30)
    }

    private fun extractNotes(text: String, title: String): String? {
        val cue = Regex("地点|形式|闭卷|开卷|时长|题型|材料|携带|要求|A[、/]B卷|提前\\d+分钟到|分为")
        val fragments = mutableListOf<String>()
        text.split(Regex("[。；;]+")).map { it.trim() }.filter { it.isNotBlank() }.forEach { sentence ->
            if (isPureNoise(sentence) || sentence == "请同学们做好准备") return@forEach
            if (sentence.contains(title) && hasDateExpression(sentence)) {
                sentence.split(Regex("[，,]")).drop(1).map(String::trim).filter { cue.containsMatchIn(it) }.forEach(fragments::add)
            } else if (cue.containsMatchIn(sentence)) fragments += sentence
        }
        return fragments.joinToString("；")
            .replace("A、B卷", "A/B卷").replace("A、B", "A/B")
            .replace(Regex("^本次考试"), "考试").takeIf { it.isNotBlank() }
    }

    private fun hasDateExpression(text: String) = Regex("今天|明天|后天|月底|(?:下周|这周|本周|周)[一二三四五六日天]|(?:\\d{4}年)?\\d{1,2}月\\d{1,2}[号日]?").containsMatchIn(text)
    private fun hasEventMeaning(text: String) = Regex("交|提交|考试|体测|测验|开会|班会|会议|组会|活动|汇报|答辩|比赛|竞赛|生日|报名|pre", RegexOption.IGNORE_CASE).containsMatchIn(text)
    private fun hasExplicitTime(text: String) = Regex("凌晨|早上|上午|中午|下午|晚上|\\d{1,2}:\\d{2}|[零一二三四五六七八九十\\d]+点").containsMatchIn(text)
    private fun isPureNoise(text: String) = text.replace(Regex("[@＠]所有人|请大家|请同学们|注意|查收|做好准备|收到请回复|[。！!，,\\s]"), "").isBlank()
    private fun weekday(value: String) = when (value) { "一" -> DayOfWeek.MONDAY; "二" -> DayOfWeek.TUESDAY; "三" -> DayOfWeek.WEDNESDAY; "四" -> DayOfWeek.THURSDAY; "五" -> DayOfWeek.FRIDAY; "六" -> DayOfWeek.SATURDAY; else -> DayOfWeek.SUNDAY }
    private fun chineseNumber(raw: String): Int? {
        if (raw.isBlank()) return null; raw.toIntOrNull()?.let { return it }
        val d = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if ('十' in raw) { val p = raw.split('十'); return (p[0].firstOrNull()?.let { d[it] } ?: 1) * 10 + (p.getOrNull(1)?.firstOrNull()?.let { d[it] } ?: 0) }
        return raw.map { d[it] ?: return null }.joinToString("").toIntOrNull()
    }
}
