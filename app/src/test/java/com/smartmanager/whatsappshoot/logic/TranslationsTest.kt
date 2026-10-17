package com.smartmanager.whatsappshoot.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The app ships in English, Arabic and French: every string must exist in all three, with the same placeholders. */
class TranslationsTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val res = File(root, "app/src/main/res")

    private fun load(dir: String): Map<String, String> {
        val text = File(res, "$dir/strings.xml").readText()
        return Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private val en = load("values")
    private val ar = load("values-ar")
    private val fr = load("values-fr")
    private val placeholder = Regex("%\\d\\$[sd]")

    @Test fun allThreeLanguagesHaveExactlyTheSameKeys() {
        assertEquals("Arabic is missing/extra", en.keys, ar.keys)
        assertEquals("French is missing/extra", en.keys, fr.keys)
        assertTrue(en.size > 250)
    }

    @Test fun placeholdersMatchTheEnglishOnes() {
        for ((k, v) in en) {
            val want = placeholder.findAll(v).map { it.value }.sorted().toList()
            assertEquals("ar:$k", want, placeholder.findAll(ar.getValue(k)).map { it.value }.sorted().toList())
            assertEquals("fr:$k", want, placeholder.findAll(fr.getValue(k)).map { it.value }.sorted().toList())
        }
    }

    @Test fun noTranslationIsEmpty_andMostAreReallyTranslated() {
        for ((k, v) in ar) assertTrue("ar:$k empty", v.isNotBlank())
        for ((k, v) in fr) assertTrue("fr:$k empty", v.isNotBlank())
        val same = { other: Map<String, String> -> en.keys.count { en[it] == other[it] } }
        assertTrue("Arabic has ${same(ar)} untranslated strings", same(ar) < 12)
        assertTrue("French has ${same(fr)} untranslated strings", same(fr) < 25)
        assertTrue("Arabic uses Arabic script", ar.getValue("h_start").any { it in '؀'..'ۿ' })
    }

    @Test fun everyStringTheCodeUsesExists() {
        val used = File(root, "app/src/main/java").walkTopDown().filter { it.extension == "kt" }
            .flatMap { Regex("R\\.string\\.(\\w+)").findAll(it.readText()).map { m -> m.groupValues[1] } }.toSet()
        val missing = used - en.keys
        assertTrue("strings used in code but not defined: $missing", missing.isEmpty())
    }

    @Test fun noScreenHardCodesEnglishText() {
        // User-visible text in the screens must come from string resources (so it can be translated).
        val offenders = File(root, "app/src/main/java/com/smartmanager/whatsappshoot/ui").listFiles { f -> f.extension == "kt" }!!.flatMap { f ->
            Regex("""\bText\(\s*"[A-Za-z]""").findAll(f.readText()).map { f.name + ": " + it.value }.toList()
        }
        assertTrue("hard-coded Text(\"...\") found: $offenders", offenders.isEmpty())
    }

    @Test fun languageNamesAreShownInTheirOwnLanguage() {
        for (m in listOf(en, ar, fr)) { assertEquals("English", m.getValue("lang_en")); assertEquals("العربية", m.getValue("lang_ar")); assertEquals("Français", m.getValue("lang_fr")) }
    }
}
