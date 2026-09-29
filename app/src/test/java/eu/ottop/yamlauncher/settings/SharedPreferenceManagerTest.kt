package eu.ottop.yamlauncher.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPreferenceManagerTest {
    @Test
    fun weatherIntervalParser_supportsMinutesHoursDaysAndBareMinutes() {
        assertEquals(15 * 60_000L, WeatherIntervalParser.parse("15m"))
        assertEquals(2 * 60 * 60_000L, WeatherIntervalParser.parse("2h"))
        assertEquals(7 * 24 * 60 * 60_000L, WeatherIntervalParser.parse("7d"))
        assertEquals(30 * 60_000L, WeatherIntervalParser.parse("30"))
    }

    @Test
    fun weatherIntervalParser_fallsBackForInvalidAndNonPositiveValues() {
        val default = 15 * 60_000L
        assertEquals(default, WeatherIntervalParser.parse(null))
        assertEquals(default, WeatherIntervalParser.parse("invalid"))
        assertEquals(default, WeatherIntervalParser.parse("0m"))
        assertEquals(default, WeatherIntervalParser.parse("-1m"))
    }

    @Test
    fun weatherIntervalParser_doesNotOverflow() {
        assertEquals(Long.MAX_VALUE, WeatherIntervalParser.parse("999999999999999999d"))
    }

    @Test
    fun preferenceValues_recoverLegacyPrimitiveTypes() {
        assertEquals("42", PreferenceValues.asString(42, "default"))
        assertEquals(42, PreferenceValues.asInt("42", 0))
        assertTrue(PreferenceValues.asBoolean("true", false))
        assertFalse(PreferenceValues.asBoolean(0, true))
        assertEquals(3.5f, PreferenceValues.asFloat("3.5", 0f))
    }

    @Test
    fun preferenceValues_useDefaultsForCorruptValues() {
        assertEquals(7, PreferenceValues.asInt("oops", 7))
        assertEquals(2f, PreferenceValues.asFloat("NaN", 2f))
        assertTrue(PreferenceValues.asBoolean("yes", true))
    }

    @Test
    fun shortcutSetting_roundTripsValidData() {
        val shortcut = ShortcutSetting("example/app.Main", 1, "Example §splitter§ Work", false)
        assertEquals(shortcut, ShortcutSetting.decode(shortcut.encode()))
    }

    @Test
    fun shortcutSetting_rejectsMalformedData() {
        assertNull(ShortcutSetting.decode(null))
        assertNull(ShortcutSetting.decode("e§splitter§e§splitter§e§splitter§e"))
        assertNull(ShortcutSetting.decode("component§splitter§bad§splitter§Name§splitter§false"))
        assertNull(ShortcutSetting.decode("component§splitter§-1§splitter§Name§splitter§false"))
    }

    @Test
    fun widgetStackIds_roundTripAndDropJunk() {
        assertEquals(listOf(3, 7), WidgetStackIds.decode(WidgetStackIds.encode(listOf(3, 7, 3, 0))))
        assertEquals(listOf(5, 9), WidgetStackIds.decode("5, x,0,-2, 9,5"))
        assertTrue(WidgetStackIds.decode(null).isEmpty())
        assertTrue(WidgetStackIds.decode("").isEmpty())
    }

    @Test
    fun appFolder_roundTripsAndSanitizesNames() {
        val folders = listOf(
            AppFolder(1, "Work", listOf("a.b/a.b.Main" to 0, "c.d/c.d.Main" to 1)),
            AppFolder(2, "Empty", emptyList()),
        )
        assertEquals(folders, AppFolder.decode(AppFolder.encode(folders)))
        val tricky = AppFolder(3, "  Games §folder§ §splitter§x ", emptyList())
        assertEquals("Games folder splitterx", AppFolder.decode(AppFolder.encode(listOf(tricky))).single().name)
    }

    @Test
    fun appFolder_dropsCorruptEntries() {
        assertTrue(AppFolder.decode(null).isEmpty())
        assertTrue(AppFolder.decode("").isEmpty())
        val decoded = AppFolder.decode(
            "bad§splitter§§splitter§Name§folder§" +
                "5§splitter§x/y§profile§-1§app§a/b§profile§2§app§a/b§profile§2§splitter§Ok§folder§" +
                "5§splitter§§splitter§Duplicate id§folder§6§splitter§§splitter§   "
        )
        assertEquals(listOf(AppFolder(5, "Ok", listOf("a/b" to 2))), decoded)
    }

    @Test
    fun appFolder_componentKeyRoundTrips() {
        assertEquals(42L, AppFolder.idFromComponentKey(AppFolder.componentKey(42)))
        assertNull(AppFolder.idFromComponentKey("com.example/com.example.Main"))
        assertNull(AppFolder.idFromComponentKey("folder:oops"))
    }

    @Test
    fun gestureSetting_rejectsMissingOrInvalidFields() {
        assertEquals(
            GestureSetting("Example §splitter§ Work", "example/app.Main", 0),
            GestureSetting.decode("Example §splitter§ Work§splitter§example/app.Main§splitter§0"),
        )
        assertNull(GestureSetting.decode("Example§splitter§component"))
        assertNull(GestureSetting.decode("Example§splitter§component§splitter§bad"))
    }
}
