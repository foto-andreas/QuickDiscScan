package de.schrell.quickdiskscan;

import java.util.Locale;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

final class I18n {
    enum Language {
        GERMAN("Deutsch", '.', ','),
        ENGLISH("English", ',', '.');

        private final String label;
        private final char groupingSeparator;
        private final char decimalSeparator;

        Language(String label, char groupingSeparator, char decimalSeparator) {
            this.label = label;
            this.groupingSeparator = groupingSeparator;
            this.decimalSeparator = decimalSeparator;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final String PREF_LANGUAGE = "language";
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(I18n.class);
    private static volatile Language language = language(PREFERENCES.get(PREF_LANGUAGE,
            Locale.getDefault().getLanguage()));

    private I18n() {}

    static String text(String german, String english) {
        return text(language, german, english);
    }

    static Language language() {
        return language;
    }

    static void saveLanguage(Language language) {
        I18n.language = language;
        PREFERENCES.put(PREF_LANGUAGE, language.name());
        try {
            PREFERENCES.flush();
        } catch (BackingStoreException ignored) {
            // The language still applies once the preference store is available again.
        }
    }

    static String text(Language language, String german, String english) {
        return language == Language.GERMAN ? german : english;
    }

    static String number(long value) {
        return number(language, value);
    }

    static String number(Language language, long value) {
        String digits = Long.toString(value);
        int start = digits.startsWith("-") ? 1 : 0;
        StringBuilder formatted = new StringBuilder(digits.length() + (digits.length() - start - 1) / 3);
        if (start == 1) {
            formatted.append('-');
        }
        for (int index = start; index < digits.length(); index++) {
            if (index > start && (digits.length() - index) % 3 == 0) {
                formatted.append(language.groupingSeparator);
            }
            formatted.append(digits.charAt(index));
        }
        return formatted.toString();
    }

    static String decimal(double value, int fractionDigits) {
        return String.format(Locale.ROOT, "%." + fractionDigits + "f", value)
                .replace('.', language.decimalSeparator);
    }

    private static Language language(String value) {
        try {
            return Language.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return value.startsWith("de") ? Language.GERMAN : Language.ENGLISH;
        }
    }
}
