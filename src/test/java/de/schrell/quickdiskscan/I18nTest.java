package de.schrell.quickdiskscan;

public final class I18nTest {
    public static void main(String[] args) {
        assertEquals("Deutsch", I18n.text(I18n.Language.GERMAN, "Deutsch", "English"));
        assertEquals("English", I18n.text(I18n.Language.ENGLISH, "Deutsch", "English"));
        assertEquals("1.234", I18n.number(I18n.Language.GERMAN, 1_234));
        assertEquals("1,234", I18n.number(I18n.Language.ENGLISH, 1_234));
        I18n.Language original = I18n.language();
        try {
            I18n.saveLanguage(I18n.Language.GERMAN);
            assertEquals("1.234", I18n.number(1_234));
            I18n.saveLanguage(I18n.Language.ENGLISH);
            assertEquals("1,234", I18n.number(1_234));
        } finally {
            I18n.saveLanguage(original);
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Erwartet " + expected + ", erhalten " + actual);
        }
    }
}
