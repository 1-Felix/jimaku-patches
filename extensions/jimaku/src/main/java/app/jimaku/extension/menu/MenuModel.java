package app.jimaku.extension.menu;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.jimaku.extension.Languages;
import app.jimaku.extension.gateway.Lookup;

/**
 * What Jimaku's menu lists, as the extension's {@code lib/menu-model.ts} decides it for a viewer
 * who is signed out and reads only the shared cache: every cached version, the preferred language
 * first and the rest by name, without the video's own language unless it is the preferred one.
 * Every cached version is Premium. Plain Java, so the shared cases run on the JVM.
 */
public final class MenuModel {
    /** One language row. */
    public static final class Row {
        public final String lang;
        public final String name;
        public final boolean checked;
        /** Every row read from the shared cache is a Premium version. */
        public final boolean premium = true;

        Row(String lang, boolean checked) {
            this.lang = lang;
            this.name = Languages.name(lang);
            this.checked = checked;
        }

        @Override
        public String toString() {
            return lang + (checked ? " checked" : "");
        }
    }

    private MenuModel() {
    }

    /**
     * The main sheet's language rows for {@code lookup}, null when nothing is cached, with
     * {@code showing} on screen, or null.
     */
    public static List<Row> languages(Lookup lookup, String preferred, String showing, Locale locale) {
        if (lookup == null) return Collections.emptyList();
        List<String> entries = new ArrayList<>();
        for (String lang : lookup.cachedLanguages()) {
            if (isOriginal(lookup, preferred, lang)) continue;
            entries.add(lang);
        }
        if (showing != null && !entries.contains(showing) && lookup.cachedLanguages().contains(showing)) entries.add(showing);
        Collator collator = Collator.getInstance(locale);
        entries.sort((a, b) -> {
            int first = (a.equals(preferred) ? 0 : 1) - (b.equals(preferred) ? 0 : 1);
            return first != 0 ? first : collator.compare(Languages.name(a), Languages.name(b));
        });
        List<Row> rows = new ArrayList<>(entries.size());
        for (String lang : entries) rows.add(new Row(lang, lang.equals(showing)));
        return rows;
    }

    /** The video's own language, which Original caption shows, is no row of its own unless it is the preferred one. */
    private static boolean isOriginal(Lookup lookup, String preferred, String lang) {
        return !lang.equals(preferred) && lang.equals(lookup.original);
    }

    /**
     * Whether Original caption has something to show: not without a transcript, nor where it is in
     * the language on screen, or with nothing there, the preferred language. A transcript in two
     * languages always has, since its own language's subtitles hold the other one translated.
     */
    public static boolean originalAvailable(Lookup lookup, String preferred, String showing) {
        if (lookup == null) return false;
        String repeated = showing != null ? showing : preferred;
        return !(lookup.original.equals(repeated) && !lookup.mixed);
    }
}
