package com.astra.agent.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Schedule parsing, next-run maths, digit entry and card naming. Pure JVM: java.time + org.json only. */
class ScheduleTest {
    private val utc = ZoneId.of("UTC")
    // Monday 18 November 2024, 10:00 UTC
    private val now = ZonedDateTime.of(2024, 11, 18, 10, 0, 0, 0, utc)

    // ── natural language ─────────────────────────────────────────────────
    @Test fun everyDayFromChat() {
        val p = Schedules.parse("open YouTube every day at 8:30 pm", now)
        assertNotNull(p)
        assertEquals(RepeatType.DAILY, p!!.schedule.repeat)
        assertEquals(20, p.schedule.hour)
        assertEquals(30, p.schedule.minute)
        assertEquals("open YouTube", p.prompt)
    }

    @Test fun weekdaysAndWeekends() {
        val wd = Schedules.parse("every weekday at 9", now)!!
        assertEquals(RepeatType.WEEKDAYS, wd.schedule.repeat)
        assertEquals(9, wd.schedule.hour)
        assertEquals("", wd.prompt)

        val we = Schedules.parse("every weekend at 10 am", now)!!
        assertEquals(RepeatType.WEEKENDS, we.schedule.repeat)
        assertEquals(10, we.schedule.hour)
    }

    @Test fun namedDaysBecomeACustomMask() {
        val p = Schedules.parse("on monday and wednesday at 7 pm water the plants", now)!!
        assertEquals(RepeatType.CUSTOM, p.schedule.repeat)
        assertEquals(Schedules.MON or Schedules.WED, p.schedule.daysMask)
        assertEquals(19, p.schedule.hour)
        assertEquals("water the plants", p.prompt)
    }

    @Test fun tomorrowBecomesAOneTimeRun() {
        val p = Schedules.parse("tomorrow at 18:00 open the news", now)!!
        assertEquals(RepeatType.ONCE, p.schedule.repeat)
        assertEquals(18, p.schedule.hour)
        assertEquals(LocalDate.of(2024, 11, 19).toEpochDay(), p.schedule.dateEpochDay)
        assertEquals("open the news", p.prompt)
    }

    @Test fun everyMorningIsDailyAtEight() {
        val p = Schedules.parse("open YouTube every morning", now)!!
        assertEquals(RepeatType.DAILY, p.schedule.repeat)
        assertEquals(8, p.schedule.hour)
        assertEquals("open YouTube", p.prompt)
    }

    @Test fun intervalsAndDelays() {
        val every = Schedules.parse("every 2 hours check email", now)!!
        assertEquals(RepeatType.INTERVAL, every.schedule.repeat)
        assertEquals(120, every.schedule.intervalMinutes)
        assertEquals("check email", every.prompt)

        val delay = Schedules.parse("in 30 minutes check the oven", now)!!
        assertEquals(RepeatType.ONCE, delay.schedule.repeat)
        assertEquals(10, delay.schedule.hour)
        assertEquals(30, delay.schedule.minute)
        assertEquals(LocalDate.of(2024, 11, 18).toEpochDay(), delay.schedule.dateEpochDay)
        assertEquals("check the oven", delay.prompt)
    }

    @Test fun chatTextWithABareNumberIsNotASchedule() {
        assertNull(Schedules.parse("summarize 5 articles", now))
        assertNull(Schedules.parse("how many days until Friday?", now))
        // The sheet's natural-language field may read a bare time, chat may not.
        val sheet = Schedules.parse("8:15", now, scheduleOnly = true)!!
        assertEquals(8, sheet.schedule.hour)
        assertEquals(15, sheet.schedule.minute)
        assertEquals(LocalDate.of(2024, 11, 19).toEpochDay(), sheet.schedule.dateEpochDay)
    }

    // ── digit time entry ─────────────────────────────────────────────────
    @Test fun digitsParsing() {
        assertEquals(TimeEntry(8, 0), Schedules.parseDigits("8", use24h = false, pm = false))
        assertEquals(TimeEntry(20, 0), Schedules.parseDigits("8", use24h = false, pm = true))
        assertEquals(TimeEntry(8, 30), Schedules.parseDigits("830", use24h = false, pm = false))
        assertEquals(TimeEntry(20, 30), Schedules.parseDigits("830", use24h = false, pm = true))
        assertEquals(TimeEntry(0, 0), Schedules.parseDigits("12", use24h = false, pm = false))
        assertEquals(TimeEntry(12, 0), Schedules.parseDigits("12", use24h = false, pm = true))
        assertEquals(TimeEntry(17, 45), Schedules.parseDigits("1745", use24h = true))
        assertEquals(TimeEntry(17, 45), Schedules.parseDigits("1745", use24h = false))
        assertNull(Schedules.parseDigits("99", use24h = true))
        assertNull(Schedules.parseDigits("", use24h = true))
    }

    @Test fun digitsForExistingTimes() {
        assertEquals("830", Schedules.digitsFor(8, 30, use24h = false))
        assertEquals("0830", Schedules.digitsFor(8, 30, use24h = true))
        assertEquals("8", Schedules.digitsFor(8, 0, use24h = false))
        assertEquals("12", Schedules.digitsFor(0, 0, use24h = false))
        assertEquals("12", Schedules.digitsFor(12, 0, use24h = false))
    }

    // ── next run ─────────────────────────────────────────────────────────
    @Test fun nextRunDailyTodayOrTomorrow() {
        val later = Schedules.nextRun(Schedule(repeat = RepeatType.DAILY, hour = 12, minute = 0), now)!!
        assertEquals(LocalDate.of(2024, 11, 18), later.toLocalDate())
        val passed = Schedules.nextRun(Schedule(repeat = RepeatType.DAILY, hour = 8, minute = 0), now)!!
        assertEquals(LocalDate.of(2024, 11, 19), passed.toLocalDate())
    }

    @Test fun nextRunOnMonthEnd() {
        val endOfMonth = ZonedDateTime.of(2024, 11, 30, 9, 0, 0, 0, utc)
        val next = Schedules.nextRun(Schedule(repeat = RepeatType.DAILY, hour = 8, minute = 0), endOfMonth)!!
        assertEquals(LocalDate.of(2024, 12, 1), next.toLocalDate())
    }

    @Test fun nextRunWeekdaysSkipsTheWeekend() {
        val saturday = ZonedDateTime.of(2024, 11, 23, 12, 0, 0, 0, utc)
        val next = Schedules.nextRun(Schedule(repeat = RepeatType.WEEKDAYS, hour = 9, minute = 0), saturday)!!
        assertEquals(LocalDate.of(2024, 11, 25), next.toLocalDate())
    }

    @Test fun nextRunCustomDays() {
        val next = Schedules.nextRun(Schedule(repeat = RepeatType.CUSTOM, hour = 9, minute = 0, daysMask = Schedules.SAT or Schedules.SUN), now)!!
        assertEquals(LocalDate.of(2024, 11, 23), next.toLocalDate())
    }

    @Test fun onceScheduleInThePastHasNoNextRun() {
        val s = Schedule(repeat = RepeatType.ONCE, hour = 9, minute = 0, dateEpochDay = now.toLocalDate().toEpochDay())
        assertNull(Schedules.nextRun(s, now))
        val tomorrow = Schedules.nextRun(s.copy(dateEpochDay = now.toLocalDate().plusDays(1).toEpochDay()), now)!!
        assertEquals(LocalDate.of(2024, 11, 19), tomorrow.toLocalDate())
    }

    @Test fun intervalNextRunFollowsTheAnchor() {
        val s = Schedule(repeat = RepeatType.INTERVAL, intervalMinutes = 120, anchor = now.toInstant().toEpochMilli() - 30 * 60_000L)
        val next = Schedules.nextRun(s, now)!!
        assertEquals(now.plusMinutes(90).toInstant(), next.toInstant())
    }

    @Test fun nextRunSurvivesTheDstGap() {
        val berlin = ZoneId.of("Europe/Berlin")
        val before = ZonedDateTime.of(2024, 3, 30, 12, 0, 0, 0, berlin)
        val next = Schedules.nextRun(Schedule(repeat = RepeatType.DAILY, hour = 2, minute = 30), before)!!
        assertEquals(LocalDate.of(2024, 3, 31), next.toLocalDate())
        assertTrue(next.isAfter(before))
    }

    // ── validation and formatting ────────────────────────────────────────
    @Test fun problemsAreReported() {
        assertTrue(Schedules.problems(Schedule(repeat = RepeatType.INTERVAL, intervalMinutes = 10), now).any { it.contains("15 minutes") })
        assertTrue(Schedules.problems(Schedule(repeat = RepeatType.CUSTOM, daysMask = 0), now).any { it.contains("at least one day") })
        assertTrue(Schedules.problems(Schedule(repeat = RepeatType.ONCE, hour = 9, minute = 0, dateEpochDay = now.toLocalDate().toEpochDay()), now).isNotEmpty())
        assertTrue(Schedules.problems(Schedule(repeat = RepeatType.DAILY, hour = 12, minute = 0), now).isEmpty())
    }

    @Test fun labels() {
        assertEquals("12:05 AM", Schedules.formatTime(0, 5, use24h = false))
        assertEquals("8:30 PM", Schedules.formatTime(20, 30, use24h = false))
        assertEquals("20:30", Schedules.formatTime(20, 30, use24h = true))
        assertEquals("Every day, 8:00 AM", Schedules.describe(Schedule(repeat = RepeatType.DAILY, hour = 8, minute = 0)))
        assertEquals("Every 2 hours", Schedules.intervalLabel(120))
        assertEquals("Every 45 minutes", Schedules.intervalLabel(45))
        assertEquals("Weekends, 10:00 AM", Schedules.describe(Schedule(repeat = RepeatType.WEEKENDS, hour = 10, minute = 0)))
        assertTrue(Schedules.chip(Schedule(repeat = RepeatType.DAILY, hour = 8, minute = 0), now).contains("next"))
        assertTrue(Schedules.preview(Schedule(enabled = false), now).startsWith("Paused"))
    }

    // ── storage shape ────────────────────────────────────────────────────
    @Test fun scheduleJsonRoundTrip() {
        val s = Schedule(repeat = RepeatType.CUSTOM, hour = 7, minute = 45, daysMask = Schedules.MON or Schedules.WED,
            runMode = RunMode.NOTIFY, enabled = false, lastRun = 123L, nextRun = 456L)
        assertEquals(s, Schedule.fromJson(s.toJson()))
        assertNull(Schedule.fromJson(null))
    }

    @Test fun legacyCardsGetANameAndADailySchedule() {
        val l = AutomationStore.parseList("""[{"id":"1","req":"open YouTube","h":7,"m":30}]""")
        assertEquals(1, l.size)
        assertEquals("Open YouTube", l[0].name)
        assertEquals(RepeatType.DAILY, l[0].schedule?.repeat)
        assertEquals(RunMode.NOTIFY, l[0].schedule?.runMode)
        assertEquals(7, l[0].hour)
    }

    @Test fun atAutoCarriesItsSchedule() {
        val cmd = AutoCommand.parse("@auto open YouTube at 8 pm")!!
        val p = Schedules.parse(cmd.prompt)!!
        assertEquals(20, p.schedule.hour)
        assertEquals("open YouTube", p.prompt)
        assertNull(Schedules.parse("@auto"))
    }

    // ── names ────────────────────────────────────────────────────────────
    @Test fun namesAreRealAndUnique() {
        assertEquals("Daily weather summary", AutomationNames.suggest("every day at 8 give me the weather"))
        assertEquals("Open YouTube and play lofi music", AutomationNames.suggest("open YouTube and play lofi music"))
        assertEquals("Old 2", AutomationNames.unique("Old", listOf("old")))
        assertEquals("Play lofi", AutomationNames.resolve("", "play lofi", emptyList()))
        assertEquals(AutomationNames.MAX, AutomationNames.suggest("x".repeat(120)).length)
        assertFalse(AutomationNames.suggest("   ").isBlank())
    }
}
