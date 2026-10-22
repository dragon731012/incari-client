package incari.classlib.additions.java.util;

import incari.classlib.InstanceMethod;
import java.util.Locale;

/** {@code java.util.Locale} methods TeaVM lacks. */
public final class LocaleAdditions {

    private LocaleAdditions() {}

    /**
     * The language, region and variant of a BCP 47 tag ({@code en-US}); extensions and private-use
     * parts are dropped, and {@code und} is the root locale.
     */
    public static Locale forLanguageTag(String tag) {
        String[] parts = tag.split("[-_]", -1);
        String language = parts.length > 0 && !parts[0].equals("und") ? parts[0] : "";
        String country = "";
        String variant = "";
        int i = 1;
        if (i < parts.length && parts[i].length() == 4 && Character.isLetter(parts[i].charAt(0))) {
            i++; // A script subtag, which TeaVM's Locale doesn't carry.
        }
        if (i < parts.length && (parts[i].length() == 2 || (parts[i].length() == 3 && isDigits(parts[i])))) {
            country = parts[i++];
        }
        // BCP 47 variants: five to eight characters, or four starting with a digit.
        if (i < parts.length
                && (parts[i].length() >= 5 || (parts[i].length() == 4 && Character.isDigit(parts[i].charAt(0))))) {
            variant = parts[i];
        }
        return Locale.of(language, country, variant);
    }

    /** The browser build carries no ISO 3166 table; callers treat an empty code as unknown. */
    @InstanceMethod
    public static String getISO3Country(Locale self) {
        return "";
    }

    private static boolean isDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
