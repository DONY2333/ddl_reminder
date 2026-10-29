package com.example.ddlreminder.smartadd

import com.example.ddlreminder.data.DdlEntity
import com.example.ddlreminder.reminder.AndroidAlarmScheduler
import com.example.ddlreminder.reminder.ReminderOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import com.example.ddlreminder.ui.smartadd.SmartAddEventMetadata

class SmartAddLogicTest {
    @Test(expected = IllegalArgumentException::class) fun `negative reminder is rejected`() { ReminderOffset(-1) }
    @Test(expected = IllegalArgumentException::class) fun `over maximum reminder is rejected`() { ReminderOffset(ReminderOffset.MAX_MINUTES + 1) }
    @Test fun `normalize sorts and deduplicates`() { assertEquals(listOf(60L, 15L, 0L), ReminderOffset.normalize(listOf(0, 60, 15, 60)).map { it.minutes }) }
    @Test fun `legacy enum maps to offsets`() { assertEquals(listOf(7200L, 1440L), ReminderOffset.decodeLegacy("FIVE_DAYS,ONE_DAY").map { it.minutes }) }
    @Test fun `title and deadline duplicate is detected`() {
        val ddl = DdlEntity(id=1,title="same",deadlineMillis=99,createdAtMillis=0,updatedAtMillis=0)
        assertTrue(DuplicateDetector.mark(listOf(ScheduleDraft("same",99)), listOf(ddl)).single().possibleDuplicate)
    }
    @Test fun `different deadline is not duplicate`() {
        val ddl = DdlEntity(id=1,title="same",deadlineMillis=99,createdAtMillis=0,updatedAtMillis=0)
        assertFalse(DuplicateDetector.mark(listOf(ScheduleDraft("same",100)), listOf(ddl)).single().possibleDuplicate)
    }
    @Test fun `alarm identity includes custom offset and deadline`() {
        assertEquals("ddlreminder://alarm/7/9000/15", AndroidAlarmScheduler.alarmIdentity(7,9000,15))
    }
    @Test fun `ai invalid json does not crash`() = runTest {
        val parser = AiNaturalLanguageParser(com.example.ddlreminder.settings.AiSettings("x","y","z"), fakeSuccess("not json"))
        assertTrue(parser.parse("secret", 0, ZoneId.of("UTC")).drafts.isEmpty())
    }
    @Test fun `ai network failure falls back locally`() = runTest {
        val ai = AiNaturalLanguageParser(com.example.ddlreminder.settings.AiSettings("x","y","z"), object : AiApiClient {
            override suspend fun complete(config: com.example.ddlreminder.settings.AiSettings, systemPrompt: String, userPrompt: String) =
                AiApiResult.Failure(AiApiErrorType.NETWORK, "offline", endpoint = "x/chat/completions")
        })
        val result = FallbackNaturalLanguageParser(ai, LocalNaturalLanguageParser()).parse("明天下午3点交作业", 1_700_000_000_000, ZoneId.of("Asia/Shanghai"))
        assertTrue(result.drafts.isNotEmpty()); assertTrue(result.fellBackToLocal)
    }
    @Test fun `source text is not part of validity`() { assertTrue(ScheduleDraft("x", 1, sourceText="private").isValid) }
    @Test fun `event metadata cannot contain natural language or title`() {
        val metadata = SmartAddEventMetadata.parseResult(3, true, false)
        assertFalse(metadata.contains("下周三交高数作业")); assertFalse(metadata.contains("private title"))
        assertEquals("{\"event_count\":3,\"used_ai\":true,\"fallback\":false}", metadata)
    }

    private fun fakeSuccess(body: String) = object : AiApiClient {
        override suspend fun complete(config: com.example.ddlreminder.settings.AiSettings, systemPrompt: String, userPrompt: String) =
            AiApiResult.Success(body, 200, 1, "x/chat/completions")
    }
}
