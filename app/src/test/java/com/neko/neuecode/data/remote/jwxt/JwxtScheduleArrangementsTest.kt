package com.neko.neuecode.data.remote.jwxt

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.neko.neuecode.domain.jwxt.JwxtScheduleDocument
import com.neko.neuecode.domain.jwxt.SchedulePresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JwxtScheduleArrangementsTest {
    // Arrangement text from the supplied timetable; no student identity is needed.
    private val controlDetails = listOf(
        "1-2周 朱笑晨,石重霄,张天予,李霄剑 南湖校区 教304",
        "3周,7-14周,16-17周 朱笑晨,石重霄,张天予,李霄剑 南湖校区 大成309"
    )

    @Test
    fun normalize_splitsSaturdayControlCourseAndKeepsCourseGrouping() {
        val document = normalize(course(controlDetails))
        assertEquals(1, document.summary.courseCount)
        assertEquals(2, document.summary.eventCount)
        assertEquals(2, document.events.map { it.id }.distinct().size)
        assertEquals(document.events.map { it.id }, document.courses.single().eventIds)
        val first = document.events.single { it.classroom == "教304" }
        val later = document.events.single { it.classroom == "大成309" }
        assertEquals(listOf(1, 2), first.weeks)
        assertEquals(listOf(3) + (7..14).toList() + listOf(16, 17), later.weeks)
        assertEquals("3周,7-14周,16-17周", later.weekSpec)
        assertEquals(listOf("朱笑晨", "石重霄", "张天予", "李霄剑"), later.teachers)
        assertEquals(listOf(controlDetails[1]), later.weekTeacherClassroomDetails)
        document.events.forEach {
            assertEquals(6, it.weekday)
            assertEquals("星期六", it.weekdayName)
            assertEquals(3, it.sections.start)
            assertEquals(4, it.sections.end)
            assertEquals("10:30", it.time.start)
            assertEquals("12:10", it.time.end)
            assertEquals("考试", it.assessment)
            assertEquals("百分制", it.grading)
        }
    }

    @Test
    fun presentation_showsCorrectRoomInEveryWeekIncludingGaps() {
        val document = normalize(course(controlDetails))
        val pages = SchedulePresentation.cellsByWeek(document, 18)
        for (week in 1..18) {
            val expectedRooms = when (week) {
                1, 2 -> listOf("教304")
                3, in 7..14, 16, 17 -> listOf("大成309")
                else -> emptyList()
            }
            assertEquals("grid week $week", expectedRooms,
                SchedulePresentation.cellsForWeek(document, week).map { it.classroom })
            assertEquals("prefetched week $week", expectedRooms,
                pages[week - 1].map { it.classroom })
            assertEquals("today week $week", expectedRooms,
                SchedulePresentation.todayItems(document, 6, week).map { it.classroom })
        }
        val detail = SchedulePresentation.detail(document.events.single { it.classroom == "大成309" })
        assertEquals("大成309", detail.classroom)
        assertEquals("3周,7-14周,16-17周", detail.weekSpec)
    }

    @Test
    fun normalize_keepsAllFourRoomsIncludingOnlineTeaching() {
        val details = listOf(
            "5周 魏颖,林明秀 南湖校区 线上",
            "2周 魏颖,林明秀 南湖校区 教105",
            "1周 魏颖,林明秀 南湖校区 教103",
            "3-4周,6-8周 魏颖,林明秀 南湖校区 大成306"
        )
        val document = normalize(course(details, "5周[理论]/魏颖[主讲],林明秀[主讲]", "线上"))
        assertEquals(4, document.events.size)
        assertEquals(listOf("教103", "教105", "大成306", "大成306", "线上", "大成306", "大成306", "大成306"),
            (1..8).map { week -> document.events.single { week in it.weeks }.classroom })
    }

    @Test
    fun normalize_keepsOverlappingWeeksInDifferentRooms() {
        val document = normalize(course(listOf(
            "1-8周 焦明海 南湖校区 大学数学实验教学实验室(计算中心微机一室210)",
            "1-8周 焦明海 南湖校区 大学数学实验教学实验室(计算中心微机二室211)"
        )))
        assertEquals(2, document.events.size)
        assertEquals(2, document.events.map { it.id }.distinct().size)
        assertTrue(document.events.all { it.weeks == (1..8).toList() })
    }

    @Test
    fun normalize_readsTeacherAndCampusForEachArrangement() {
        val document = normalize(course(listOf(
            "1-2周 张三，李四 南湖校区 教304",
            "3-4周 王五 浑南校区 信息 A101"
        )))
        val later = document.events.single { 3 in it.weeks }
        assertEquals("浑南校区", later.campus)
        assertEquals("信息 A101", later.classroom)
        assertEquals(listOf("王五"), later.teachers)
        assertEquals(listOf("张三", "李四", "王五"), document.courses.single().teachers)
    }

    @Test
    fun normalize_supportsDetailsWithoutCampus() {
        val document = normalize(course(listOf("1-2周 张三 教304", "3周 李四 大成309")))
        assertEquals(2, document.events.size)
        assertTrue(document.events.all { it.campus == "南湖校区" })
        assertEquals(listOf("李四"), document.events.single { 3 in it.weeks }.teachers)
    }

    @Test
    fun normalize_supportsUnicodeWhitespaceAndMissingTeacher() {
        val document = normalize(course(listOf(
            "1-2周\u00a0张三\u3000南湖校区\u3000教304",
            "3周\t南湖校区 大成309"
        )))
        assertEquals(2, document.events.size)
        assertTrue(document.events.single { 3 in it.weeks }.teachers.isEmpty())
    }

    @Test
    fun normalize_fallsBackToTitleDetailsWhenDedicatedDetailsAreAbsent() {
        val raw = course(controlDetails).apply { remove("titleWeekTeacherClassroomDetail") }
        assertEquals(2, normalize(raw).events.size)
    }

    @Test
    fun normalize_keepsStructuredFieldsWhenThereAreNoDetails() {
        val raw = course(emptyList()).apply { remove("titleDetail") }
        val event = normalize(raw).events.single()
        assertEquals(listOf(1, 2), event.weeks)
        assertEquals("教304", event.classroom)
        assertEquals(listOf("朱笑晨", "石重霄", "张天予", "李霄剑"), event.teachers)
    }

    @Test
    fun normalize_keepsBaseAndValidLaterDetailWhenFirstDetailIsMalformed() {
        val document = normalize(course(listOf("无法识别的明细", controlDetails[1])))
        assertEquals(2, document.events.size)
        assertEquals(listOf(1, 2), document.events.single { it.classroom == "教304" }.weeks)
        assertTrue(17 in document.events.single { it.classroom == "大成309" }.weeks)
    }

    @Test
    fun normalize_ignoresMalformedExtraLineWithoutDuplicatingFirstArrangement() {
        val document = normalize(course(listOf(controlDetails[0], "无法识别的明细")))
        assertEquals(1, document.events.size)
        assertEquals(listOf(1, 2), document.events.single().weeks)
    }

    @Test
    fun normalize_doesNotDeduplicateDifferentCampusesOrTeachers() {
        val document = normalize(course(listOf(
            "1-2周 张三 南湖校区 101",
            "1-2周 李四 南湖校区 101",
            "1-2周 张三 浑南校区 101"
        )))
        assertEquals(3, document.events.size)
        assertEquals(3, document.events.map { it.id }.distinct().size)
    }

    @Test
    fun normalize_deduplicatesRepeatedArrangementsAndKeepsIdsStable() {
        val raw = course(controlDetails + controlDetails)
        val document = normalize(raw, raw.deepCopy())
        val reversed = normalize(course(controlDetails.reversed()))
        assertEquals(2, document.events.size)
        assertEquals(document.events.map { it.id }, reversed.events.map { it.id })
        assertEquals(2, document.courses.single().eventIds.size)
    }

    private fun course(
        details: List<String>,
        weeksAndTeachers: String = "1-2周[理论]/朱笑晨[主讲],石重霄[主讲],张天予[主讲],李霄剑[主讲]",
        classroom: String = "教304"
    ): JsonObject = JsonObject().apply {
        addProperty("courseCode", "A115251")
        addProperty("courseName", "控制理论与方法")
        addProperty("teachClassId", "test-class")
        addProperty("dayOfWeek", 6)
        addProperty("beginSection", 3)
        addProperty("endSection", 4)
        addProperty("beginTime", "10:30")
        addProperty("endTime", "12:10")
        addProperty("campusName", "南湖校区")
        addProperty("placeName", classroom)
        addProperty("weeksAndTeachers", weeksAndTeachers)
        add("titleWeekTeacherClassroomDetail", JsonArray().apply { details.forEach { add(it) } })
        add("titleDetail", JsonArray().apply {
            add("测试教学班")
            add("控制理论与方法 A115251")
            details.forEach { add(it) }
            add("考试 / 百分制")
        })
    }

    private fun normalize(vararg courses: JsonObject): JwxtScheduleDocument =
        JwxtScheduleNormalizer.normalize(
            account = "test-account",
            termCode = "2026-2027-1",
            termName = "2026-2027学年秋季学期",
            campusCode = "01",
            campusName = "南湖校区",
            sections = emptyList(),
            schedule = JsonObject().apply {
                add("arrangedList", JsonArray().apply { courses.forEach { add(it) } })
            },
            generatedAt = "2026-10-26T00:00:00Z"
        )
}
