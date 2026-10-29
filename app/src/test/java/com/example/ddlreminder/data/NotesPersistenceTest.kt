package com.example.ddlreminder.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 使用真实 Room/SQLite 验证字段映射，不只依赖内存 Fake DAO。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotesPersistenceTest {
    private lateinit var database: AppDatabase

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun close() = database.close()

    @Test fun `room preserves notes and can update and clear them`() = runTest {
        val dao = database.ddlDao()
        val original = DdlEntity(title = "exam", deadlineMillis = 10000, createdAtMillis = 0,
            updatedAtMillis = 0, notes = "闭卷，1小时\n分A/B卷")
        val id = dao.insert(original)
        assertEquals(original.notes, dao.getById(id)!!.notes)
        dao.update(dao.getById(id)!!.copy(notes = "教材 P86，第1、3、5、7题"))
        assertEquals("教材 P86，第1、3、5、7题", dao.getById(id)!!.notes)
        dao.update(dao.getById(id)!!.copy(notes = null))
        assertEquals(null, dao.getById(id)!!.notes)
        assertEquals("exam", dao.getById(id)!!.title)
    }

    @Test fun `room supports optional and long notes without truncation`() = runTest {
        val dao = database.ddlDao()
        val original = DdlEntity(title = "exam", deadlineMillis = 10000, createdAtMillis = 0, updatedAtMillis = 0)
        val id = dao.insert(original)
        assertEquals(null, dao.getById(id)!!.notes)
        val longNotes = "完整备忘\n".repeat(2000)
        dao.update(dao.getById(id)!!.copy(notes = longNotes))
        assertEquals(longNotes, dao.getById(id)!!.notes)
    }
}
