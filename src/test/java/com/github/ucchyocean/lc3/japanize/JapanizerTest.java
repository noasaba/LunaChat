/*
 * @license LGPLv3
 */
package com.github.ucchyocean.lc3.japanize;

import java.util.LinkedHashMap;
import java.util.Map;

import junit.framework.TestCase;

public class JapanizerTest extends TestCase {

    public void testLongerDictionaryEntriesWinRegardlessOfInsertionOrder() {
        Map<String, String> dictionary = new LinkedHashMap<String, String>();
        dictionary.put("subame", "スバメ");
        dictionary.put("oosubame", "オオスバメ");
        dictionary.put("koromori", "コロモリ");
        dictionary.put("kokoromori", "ココロモリ");
        dictionary.put("neiti", "ネイティ");
        dictionary.put("neitio", "ネイティオ");

        assertEquals("スバメとオオスバメ", Japanizer.japanize(
                "subametooosubame", JapanizeType.KANA, dictionary));
        assertEquals("ココロモリとコロモリ", Japanizer.japanize(
                "kokoromoritokoromori", JapanizeType.KANA, dictionary));
        assertEquals("ネイティオとネイティ", Japanizer.japanize(
                "neitiotoneiti", JapanizeType.KANA, dictionary));
    }
}
