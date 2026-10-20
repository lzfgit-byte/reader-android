package com.ghs.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ghs.mobile.core.data.GhsDatabase
import com.ghs.mobile.core.data.LocalRepository
import com.ghs.mobile.core.data.SettingScope
import com.ghs.mobile.core.model.ReaderKind
import com.ghs.mobile.core.model.ReadingProgress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 本地仓库真机验证（内存库）：进度 / 设置 / 收藏 / 历史 / 搜索。
 *
 * 语义依据 `docs/03-api/local-store.md` §3.2–§3.3 与 §6 的保留策略。
 */
@RunWith(AndroidJUnit4::class)
class LocalRepositoryInstrumentedTest {

    private lateinit var database: GhsDatabase
    private lateinit var repository: LocalRepository

    @Before
    fun setUp() {
        database = GhsDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        repository = LocalRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun progress(
        url: String,
        kind: ReaderKind = ReaderKind.TEXT,
        chapter: Int = 0,
        offset: Int = 0,
        percent: Double = 0.0,
        updatedAt: Long = 1_000,
    ) = ReadingProgress(
        itemUrl = url,
        kind = kind,
        chapterUrl = "$url#c$chapter",
        chapterIndex = chapter,
        chapterCount = 10,
        chapterOffset = offset,
        positionMs = 0,
        durationMs = 0,
        percent = percent,
        updatedAt = updatedAt,
    )

    @Test
    fun progress_round_trip() = runBlocking {
        assertTrue(repository.saveProgress(progress("https://x.test/a", chapter = 3, offset = 7, percent = 30.0)))

        val loaded = repository.progress("https://x.test/a")
        assertEquals(ReaderKind.TEXT, loaded!!.kind)
        assertEquals(3, loaded.chapterIndex)
        assertEquals(7, loaded.chapterOffset)
        assertEquals(30.0, loaded.percent, 0.001)
    }

    @Test
    fun newer_progress_wins_older_is_ignored() = runBlocking {
        repository.saveProgress(progress("https://x.test/a", chapter = 1, updatedAt = 2_000))
        // 更旧的时间戳不应覆盖
        assertFalse(repository.saveProgress(progress("https://x.test/a", chapter = 9, updatedAt = 1_000)))
        assertEquals(1, repository.progress("https://x.test/a")!!.chapterIndex)

        // 更新的时间戳应覆盖
        assertTrue(repository.saveProgress(progress("https://x.test/a", chapter = 5, updatedAt = 3_000)))
        assertEquals(5, repository.progress("https://x.test/a")!!.chapterIndex)
    }

    @Test
    fun progress_is_unique_per_item_regardless_of_kind() = runBlocking {
        repository.saveProgress(progress("https://x.test/a", kind = ReaderKind.IMAGE, chapter = 2, updatedAt = 1))
        repository.saveProgress(progress("https://x.test/a", kind = ReaderKind.IMAGE, chapter = 4, updatedAt = 2))
        assertEquals(1, repository.recentProgress().size)
        assertEquals(4, repository.recentProgress().single().chapterIndex)
    }

    @Test
    fun recent_progress_sorted_by_updated_at() = runBlocking {
        repository.saveProgress(progress("https://x.test/a", updatedAt = 100))
        repository.saveProgress(progress("https://x.test/b", updatedAt = 300))
        repository.saveProgress(progress("https://x.test/c", updatedAt = 200))

        assertEquals(
            listOf("https://x.test/b", "https://x.test/c", "https://x.test/a"),
            repository.recentProgress().map { it.itemUrl },
        )
    }

    @Test
    fun settings_are_scoped() = runBlocking {
        repository.putSetting(SettingScope.PREFS, "theme", "dark")
        repository.putSetting(SettingScope.DEVICE, "theme", "light")

        assertEquals("dark", repository.setting(SettingScope.PREFS, "theme"))
        assertEquals("light", repository.setting(SettingScope.DEVICE, "theme"))
        assertEquals(mapOf("theme" to "dark"), repository.settings(SettingScope.PREFS))
        assertNull(repository.setting(SettingScope.PREFS, "missing"))

        // 覆盖写
        repository.putSetting(SettingScope.PREFS, "theme", "system")
        assertEquals("system", repository.setting(SettingScope.PREFS, "theme"))
    }

    @Test
    fun collect_counts_repeats_and_can_be_removed() = runBlocking {
        repository.collect("demo", "https://x.test/a", """{"title":"A"}""")
        repository.collect("demo", "https://x.test/a", """{"title":"A"}""")
        repository.collect("other", "https://x.test/a", """{"title":"A"}""")

        val list = repository.collects("demo")
        assertEquals(1, list.size)
        assertEquals(2, list.single().count)

        assertTrue(repository.isCollected("demo", "https://x.test/a"))
        assertFalse(repository.isCollected("demo", "https://x.test/b"))

        repository.uncollect("demo", "https://x.test/a")
        assertFalse(repository.isCollected("demo", "https://x.test/a"))
        // 其他配置的收藏不受影响
        assertTrue(repository.isCollected("other", "https://x.test/a"))
    }

    @Test
    fun history_trim_keeps_newest() = runBlocking {
        repeat(5) { index ->
            repository.recordView("demo", "https://x.test/$index", "{}", now = 1_000L + index)
        }
        assertEquals(5, repository.history("demo").size)

        repository.trimHistory(2)
        val kept = repository.history("demo").map { it.url }
        assertEquals(listOf("https://x.test/4", "https://x.test/3"), kept)
    }

    @Test
    fun viewed_history_is_unique_by_url() = runBlocking {
        repository.recordView("demo", "https://x.test/a", "{}", now = 1)
        repository.recordView("demo", "https://x.test/a", "{}", now = 2)
        assertEquals(1, repository.history("demo").size)
        assertEquals(2, repository.history("demo").single().createTime)
    }

    @Test
    fun search_history_dedupes_and_orders() = runBlocking {
        repository.recordSearch("demo", "猫")
        repository.recordSearch("demo", "狗", now = 2_000)
        repository.recordSearch("demo", "猫", now = 3_000)
        repository.recordSearch("demo", "   ")

        assertEquals(listOf("猫", "狗"), repository.searchHistory("demo"))

        repository.clearSearchHistory("demo")
        assertTrue(repository.searchHistory("demo").isEmpty())
    }
}
