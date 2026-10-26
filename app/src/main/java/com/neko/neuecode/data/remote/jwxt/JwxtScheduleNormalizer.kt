package com.neko.neuecode.data.remote.jwxt

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.neko.neuecode.domain.jwxt.JwxtCourse
import com.neko.neuecode.domain.jwxt.JwxtNamedCode
import com.neko.neuecode.domain.jwxt.JwxtScheduleDocument
import com.neko.neuecode.domain.jwxt.JwxtScheduleEvent
import com.neko.neuecode.domain.jwxt.JwxtScheduleSummary
import com.neko.neuecode.domain.jwxt.JwxtSection
import com.neko.neuecode.domain.jwxt.JwxtSections
import com.neko.neuecode.domain.jwxt.JwxtTimeRange
import java.security.MessageDigest

object JwxtScheduleNormalizer {

    private val weekdayNames = mapOf(
        1 to "星期一",
        2 to "星期二",
        3 to "星期三",
        4 to "星期四",
        5 to "星期五",
        6 to "星期六",
        7 to "星期日"
    )

    fun expandWeeks(spec: String): List<Int> {
        val source = spec.replace("，", ",")
        val odd = source.contains("单")
        val even = source.contains("双")
        val weeks = linkedSetOf<Int>()
        val range = Regex("""(\d+)(?:\s*[-~至]\s*(\d+))?""")
        for (token in source.split(",")) {
            val match = range.find(token) ?: continue
            var start = match.groupValues[1].toInt()
            var end = match.groupValues[2].ifBlank { match.groupValues[1] }.toInt()
            if (end < start) {
                val swapped = start
                start = end
                end = swapped
            }
            for (week in start..end) {
                if (odd && week % 2 == 0) continue
                if (even && week % 2 == 1) continue
                weeks.add(week)
            }
        }
        return weeks.sorted()
    }

    fun normalize(
        account: String,
        termCode: String,
        termName: String,
        campusCode: String,
        campusName: String,
        sections: List<JsonObject>,
        schedule: JsonObject,
        generatedAt: String
    ): JwxtScheduleDocument {
        val arranged = schedule.arrayOrEmpty("arrangedList")
        val notArranged = schedule.arrayOrEmpty("notArrangeList")
        val practice = schedule.arrayOrEmpty("practiceList")

        val events = arranged.flatMap { rawElement ->
            val raw = rawElement.asJsonObject
            val weekday = raw.intOrZero("dayOfWeek")
            val details = raw.stringList("titleDetail")
            var assessment = ""
            var grading = ""
            val last = details.lastOrNull()
            if (last != null && "/" in last) {
                val parts = last.split("/", limit = 2)
                assessment = parts[0].trim()
                grading = parts.getOrNull(1)?.trim().orEmpty()
            }
            val weekSpec = weekSpec(raw.stringOrEmpty("weeksAndTeachers"))
            val eventWithoutId = JwxtScheduleEvent(
                id = "",
                courseCode = raw.stringOrEmpty("courseCode"),
                courseName = raw.stringOrEmpty("courseName"),
                teachingClassId = raw.stringOrEmpty("teachClassId"),
                courseSerialNo = raw.stringOrEmpty("courseSerialNo"),
                teachingClassName = raw.stringOrEmpty("teachClassName"),
                teachingTarget = raw.stringOrEmpty("teachingTarget"),
                credit = raw.numberOrNull("credit"),
                weekday = weekday,
                weekdayName = weekdayNames[weekday].orEmpty(),
                sections = JwxtSections(
                    start = raw.intOrZero("beginSection"),
                    end = raw.intOrZero("endSection")
                ),
                time = JwxtTimeRange(
                    start = raw.stringOrEmpty("beginTime"),
                    end = raw.stringOrEmpty("endTime")
                ),
                campus = raw.stringOrEmpty("campusName").ifBlank { campusName },
                classroom = raw.stringOrEmpty("placeName"),
                weekSpec = weekSpec,
                weeks = expandWeeks(weekSpec),
                teachers = teachers(raw.stringOrEmpty("weeksAndTeachers")),
                assessment = assessment,
                grading = grading,
                details = details,
                weekTeacherClassroomDetails = raw.stringList("titleWeekTeacherClassroomDetail")
            )
            expandArrangements(eventWithoutId).map { event ->
                event.copy(id = eventId(event))
            }
        }.distinctBy { it.id }.sortedWith(
            compareBy(
                { it.weekday },
                { it.sections.start },
                { it.courseCode },
                { it.weekSpec },
                { it.id }
            )
        )

        val grouped = linkedMapOf<String, JwxtCourse>()
        for (event in events) {
            val key = "${event.courseCode}:${event.teachingClassId}"
            val existing = grouped[key]
            if (existing == null) {
                grouped[key] = JwxtCourse(
                    courseKey = key,
                    courseCode = event.courseCode,
                    courseName = event.courseName,
                    teachingClassId = event.teachingClassId,
                    courseSerialNo = event.courseSerialNo,
                    teachingClassName = event.teachingClassName,
                    teachingTarget = event.teachingTarget,
                    credit = event.credit,
                    teachers = event.teachers,
                    eventIds = listOf(event.id)
                )
            } else {
                grouped[key] = existing.copy(
                    eventIds = existing.eventIds + event.id,
                    teachers = (existing.teachers + event.teachers).distinct()
                )
            }
        }

        val normalizedSections = sections.map { item ->
            JwxtSection(
                number = item.intOrZero("code").takeIf { it != 0 } ?: item.intOrZero("sort"),
                name = item.stringOrEmpty("name"),
                enabled = item.get("enable")?.takeUnless { it.isJsonNull }?.asBoolean ?: true,
                sourceId = item.stringOrEmpty("id")
            )
        }.sortedBy { it.number }

        val courses = grouped.values.sortedWith(compareBy({ it.courseCode }, { it.teachingClassId }))
        return JwxtScheduleDocument(
            schemaVersion = "1.0.0",
            source = "NEU JWXT /jwapp/sys/kbapp/api/wdkbcx/getMyScheduleDetail.do",
            generatedAt = generatedAt,
            account = account,
            term = JwxtNamedCode(termCode, termName),
            campus = JwxtNamedCode(campusCode, campusName),
            summary = JwxtScheduleSummary(
                courseCount = courses.size,
                eventCount = events.size,
                notArrangedCount = notArranged.size(),
                practiceCount = practice.size()
            ),
            sections = normalizedSections,
            courses = courses,
            events = events,
            notArrangedCount = notArranged.size(),
            practiceCount = practice.size()
        )
    }

    private fun expandArrangements(base: JwxtScheduleEvent): List<JwxtScheduleEvent> {
        // JWXT may fold several week/teacher/room arrangements into one list item.
        // Its top-level fields describe only the first arrangement.
        val lines = base.weekTeacherClassroomDetails.filter { it.isNotBlank() }
            .ifEmpty { base.details.filter { arrangementPrefix.containsMatchIn(it.trim()) } }
        // Keep the structured fields (including the full teacher list) for ordinary items.
        if (lines.size <= 1) return listOf(base)

        val arrangements = lines.mapNotNull { line -> parseArrangement(line, base) }
        if (arrangements.isEmpty()) return listOf(base)

        // If a detail line is malformed, retain the original usable arrangement as well.
        val events = if (arrangements.size < lines.size && arrangements.none {
                it.weeks == base.weeks && it.classroom == base.classroom && it.campus == base.campus
            }) {
            listOf(base) + arrangements
        } else {
            arrangements
        }
        return events.distinctBy { eventId(it) }
    }

    private val detailWeekToken = """\d+(?:\s*[-~至]\s*\d+)?\s*(?:[单双]周|周(?:[（(]?[单双][）)]?)?)"""
    private val arrangementPrefix = Regex("""^($detailWeekToken(?:\s*[,，]\s*$detailWeekToken)*)\s+""")
    private val campusInDetail = Regex("""(?:^|\s)(\S+校区)(?:\s+|$)""")

    private fun parseArrangement(line: String, base: JwxtScheduleEvent): JwxtScheduleEvent? {
        val text = line.replace('\u00a0', ' ').replace('\u3000', ' ').trim()
        val prefix = arrangementPrefix.find(text) ?: return null
        val spec = prefix.groupValues[1].trim()
        val weeks = expandWeeks(spec)
        if (weeks.isEmpty()) return null
        val remainder = text.substring(prefix.range.last + 1).trim()
        val campusMatch = campusInDetail.find(remainder)
        val teacherText: String
        val campus: String
        val classroom: String
        if (campusMatch != null) {
            teacherText = remainder.substring(0, campusMatch.range.first).trim()
            campus = campusMatch.groupValues[1]
            classroom = remainder.substring(campusMatch.range.last + 1).trim()
        } else {
            // Some JWXT responses omit the campus from the display detail.
            val parts = remainder.split(Regex("""\s+"""), limit = 2)
            if (parts.size != 2) return null
            teacherText = parts[0]
            campus = base.campus
            classroom = parts[1].trim()
        }
        val detailTeachers = teacherText.replace(Regex("""\[[^\]]*\]"""), "")
            .split(Regex("""[,，、]\s*"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return base.copy(
            campus = campus,
            classroom = classroom,
            weekSpec = spec,
            weeks = weeks,
            teachers = detailTeachers,
            weekTeacherClassroomDetails = listOf(line)
        )
    }

    private fun weekSpec(raw: String): String {
        val prefix = raw.split("/", limit = 2).first()
        return prefix.replace(Regex("""\[[^\]]*\]"""), "").trim()
    }

    private fun teachers(raw: String): List<String> {
        val parts = raw.split("/", limit = 2)
        if (parts.size < 2) return emptyList()
        return Regex("""(?:^|[,，])\s*([^,，\[\]/]+?)\s*\[[^\]]+\]""")
            .findAll(parts[1])
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    private fun eventId(event: JwxtScheduleEvent): String {
        val identity = buildString {
            append("courseCode=").append(event.courseCode)
            append(";teachingClassId=").append(event.teachingClassId)
            append(";weekday=").append(event.weekday)
            append(";startSection=").append(event.sections.start)
            append(";endSection=").append(event.sections.end)
            append(";weekSpec=").append(event.weekSpec)
            append(";campus=").append(event.campus)
            append(";classroom=").append(event.classroom)
            append(";teachers=").append(event.teachers.sorted().joinToString(","))
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(20)
    }

    private fun JsonObject.arrayOrEmpty(name: String): JsonArray {
        val value = get(name)
        return if (value != null && value.isJsonArray) value.asJsonArray else JsonArray()
    }

    private fun JsonObject.stringOrEmpty(name: String): String {
        val value = get(name) ?: return ""
        return if (value.isJsonNull) "" else value.asString
    }

    private fun JsonObject.intOrZero(name: String): Int {
        val value = get(name) ?: return 0
        if (value.isJsonNull) return 0
        return try {
            value.asInt
        } catch (_: Exception) {
            value.asString.toIntOrNull() ?: 0
        }
    }

    private fun JsonObject.numberOrNull(name: String): Double? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return try {
            value.asDouble
        } catch (_: Exception) {
            value.asString.toDoubleOrNull()
        }
    }

    private fun JsonObject.stringList(name: String): List<String> {
        val value = get(name) ?: return emptyList()
        if (!value.isJsonArray) return emptyList()
        return value.asJsonArray.mapNotNull { element ->
            if (element.isJsonPrimitive) element.asString else null
        }
    }
}
