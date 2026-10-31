package eu.ottop.yamlauncher.settings

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafePreferencesTest {
    @Test
    fun readsValuesStoredWithTheExpectedType() {
        val store = SafePreferences(FakePreferences("name" to "Example", "flag" to true, "count" to "12", "weight" to "0.5"))
        assertEquals("Example", store.string("name", "default"))
        assertTrue(store.boolean("flag", false))
        assertEquals(12, store.intFromString("count", 0))
        assertEquals(12L, store.longFromString("count", 0L))
        assertEquals(0.5f, store.floatFromString("weight", 0f))
    }

    @Test
    fun usesDefaultsForMissingKeys() {
        val store = SafePreferences(FakePreferences())
        assertEquals("default", store.string("name", "default"))
        assertTrue(store.boolean("flag", true))
        assertEquals(7, store.intFromString("count", 7))
        assertEquals(7L, store.longFromString("count", 7L))
        assertEquals(2f, store.floatFromString("weight", 2f))
    }

    @Test
    fun recoversValuesStoredWithAnotherType() {
        val store = SafePreferences(
            FakePreferences("name" to 42, "flag" to "true", "zero" to 0, "count" to 12, "big" to 5_000_000_000L, "weight" to 0.25f)
        )
        assertEquals("42", store.string("name", "default"))
        assertTrue(store.boolean("flag", false))
        assertFalse(store.boolean("zero", true))
        assertEquals(12, store.intFromString("count", 0))
        assertEquals(5_000_000_000L, store.longFromString("big", 0L))
        assertEquals(0.25f, store.floatFromString("weight", 0f))
    }

    @Test
    fun usesDefaultsForCorruptValues() {
        val store = SafePreferences(
            FakePreferences("name" to setOf("a"), "flag" to "yes", "count" to "oops", "weight" to "NaN")
        )
        assertEquals("default", store.string("name", "default"))
        assertTrue(store.boolean("flag", true))
        assertEquals(7, store.intFromString("count", 7))
        assertEquals(7L, store.longFromString("count", 7L))
        assertEquals(2f, store.floatFromString("weight", 2f))
    }

    @Test
    fun coercesNumbersIntoRange() {
        val store = SafePreferences(FakePreferences("count" to "500", "weight" to 3))
        assertEquals(200, store.intFromString("count", 20, 0..200))
        assertEquals(1f, store.floatFromString("weight", 0.11f, 0.01f..1f))
    }

    /** Typed getters cast the stored value like the platform implementation, so a mismatch throws. */
    private class FakePreferences(vararg entries: Pair<String, Any>) : SharedPreferences {
        private val values = mutableMapOf(*entries)

        override fun getAll(): Map<String, *> = values.toMap()
        override fun contains(key: String) = key in values
        override fun getString(key: String, defValue: String?) = (values[key] ?: defValue) as String?
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?) = (values[key] ?: defValues) as Set<String>?
        override fun getInt(key: String, defValue: Int) = (values[key] ?: defValue) as Int
        override fun getLong(key: String, defValue: Long) = (values[key] ?: defValue) as Long
        override fun getFloat(key: String, defValue: Float) = (values[key] ?: defValue) as Float
        override fun getBoolean(key: String, defValue: Boolean) = (values[key] ?: defValue) as Boolean
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    }
}
