package app.jimaku.extension.menu;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.jimaku.extension.Guard;
import app.jimaku.extension.settings.Look;
import app.jimaku.extension.shared.Wording;
import app.jimaku.extension.ui.Icons;
import app.jimaku.extension.ui.Rows;
import app.jimaku.extension.ui.Sheet;
import app.jimaku.extension.ui.Theme;

/**
 * Jimaku's menu in a sheet of YouTube's kind: the languages as the extension's menu model has them
 * for a signed-out viewer, Other languages, Original caption and Settings, each of the last two
 * replacing the main sheet. Its rows are built again after every change, so they always read the
 * video and the settings as they are. A row that asks first turns into its question in place, with
 * ✕ and ✓; a question its row no longer asks is dropped. Main thread only.
 */
public final class Menu {
    /** What the menu needs from the player. */
    public interface Host {
        /** The preferred language, the app's. */
        String preferred();

        /** The video in the watch player, or null. */
        String video();

        /** What is known about the video now, for the menu model. */
        MenuInput input();

        /** Shows {@code subtitle}, which exists for the video. */
        void show(Subtitle subtitle);

        /** Hides the subtitles on screen. */
        void hide();

        /** Makes {@code subtitle}: the menu has closed. */
        void start(Subtitle subtitle);

        /** Stops the job making {@code subtitle}: the menu has closed. */
        void stop(Subtitle subtitle);

        /** A press while another language is made: the menu has closed, and the message says so. */
        void busy();

        /** Settings opened or closed: the captions show their outline and grip, or a sample, while it is open. */
        void settingsShown(boolean shown);

        /** Whether the app shows its dark theme, or null where it can't say. */
        Boolean darkTheme();

        /** A touch beside Settings, in screen coordinates: whether it is the captions' grip, which keeps the sheet open. */
        boolean besideTouch(MotionEvent event);
    }

    private enum Panel { MAIN, OTHER, SETTINGS }

    /** The row asking its question, in the panel it was asked in, with what its ✓ does and the words last announced. */
    private static final class Asking {
        final Subtitle subtitle;
        final Panel panel;
        final LanguageRow.Press press;
        String announced;

        Asking(Subtitle subtitle, Panel panel, LanguageRow.Press press) {
            this.subtitle = subtitle;
            this.panel = panel;
            this.press = press;
        }
    }

    private final Look look;
    private final Host host;
    private Sheet sheet;
    private Panel panel = Panel.MAIN;
    private Asking asking;
    /** The question ✕ just took back: its row gets TalkBack's focus when it is drawn again. */
    private Asking cancelled;
    private EditText search;
    private View checkedRow;

    public Menu(Look look, Host host) {
        this.look = look;
        this.host = host;
    }

    public boolean isOpen() {
        return sheet != null && sheet.isOpen();
    }

    /** Whether Settings is the sheet on screen. */
    public boolean settingsShown() {
        return isOpen() && panel == Panel.SETTINGS;
    }

    /** What Settings covers of the screen at its right edge in fullscreen, in screen pixels, or null. */
    public Rect settingsCover() {
        return settingsShown() ? sheet.coveredAtEnd() : null;
    }

    /** Opens the main sheet over {@code activity}, its top below {@code maxTop} in portrait. */
    public void open(Activity activity, int maxTop) {
        if (isOpen()) return;
        // A sheet whose activity went never tells it closed; its Settings ends here.
        if (sheet != null && panel == Panel.SETTINGS) host.settingsShown(false);
        panel = Panel.MAIN;
        asking = null;
        search = null;
        sheet = Sheet.open(activity, maxTop, host.darkTheme());
        Sheet opened = sheet;
        sheet.setOnClosed(() -> Guard.run("menu closed", () -> {
            if (sheet != opened) return;
            boolean wasSettings = panel == Panel.SETTINGS;
            sheet = null;
            panel = Panel.MAIN;
            asking = null;
            search = null;
            if (wasSettings) host.settingsShown(false);
        }));
        sheet.setBeside(event -> {
            boolean[] taken = {false};
            Guard.run("captions drag", () -> taken[0] = sheet == opened && panel == Panel.SETTINGS && host.besideTouch(event));
            return taken[0];
        });
        sheet.show(main());
        View checked = checkedRow;
        if (checked != null) sheet.reveal(checked);
    }

    public void close() {
        if (sheet != null) sheet.close();
    }

    /** While the captions are dragged: Settings fades where it is over them. */
    public void setSeeThrough(boolean value) {
        if (sheet != null) sheet.setSeeThrough(value);
    }

    /** Builds the rows again from what is known now and the settings as they are. */
    public void refresh() {
        if (!isOpen()) return;
        sheet.show(rows());
    }

    private List<View> rows() {
        return switch (panel) {
            case MAIN -> main();
            case OTHER -> other();
            case SETTINGS -> settingsRows();
        };
    }

    private List<View> main() {
        Context context = sheet.context();
        Theme theme = sheet.theme();
        MenuInput input = host.input();
        MainPanel main = MenuModel.mainPanel(input);
        List<View> rows = new ArrayList<>();
        checkedRow = null;
        rows.add(Rows.space(context, theme, 8));
        if (main.note != null) {
            rows.add(Rows.note(context, theme, main.note));
        } else {
            for (LanguageRow row : main.rows) {
                View view = language(context, theme, row);
                if (row.checked) checkedRow = view;
                rows.add(view);
            }
            if (main.other) rows.add(Rows.submenu(context, theme, Icons.TRANSLATE, Wording.t(Wording.MENU_OTHER), this::openOther));
        }
        dropStale(main.rows);
        rows.add(Rows.divider(context, theme));

        rows.add(Rows.toggle(context, theme, Icons.DUAL, Wording.t(Wording.MENU_ORIGINAL), look.dual(), main.original, () -> {
            if (!MenuModel.originalAvailable(host.input())) return;
            // The settings' change tells the player, which reads the original or drops it.
            look.setDual(!look.dual());
            refresh();
        }));
        rows.add(Rows.submenu(context, theme, Icons.TUNE, Wording.t(Wording.MENU_SETTINGS), this::openSettings));
        return rows;
    }

    /** Other languages' list under its search: the caption, or that nothing matches, then the languages. */
    private List<View> other() {
        Context context = sheet.context();
        Theme theme = sheet.theme();
        String typed = search != null ? search.getText().toString().trim() : "";
        OtherPanel other = MenuModel.otherPanel(host.input(), typed);
        List<View> rows = new ArrayList<>();
        String caption = !other.rows.isEmpty() || typed.isEmpty() ? other.caption : Wording.t(Wording.POPUP_NO_LANGUAGE, typed);
        rows.add(Rows.listCaption(context, theme, caption));
        for (LanguageRow row : other.rows) rows.add(language(context, theme, row));
        dropStale(other.rows);
        return rows;
    }

    /** A language row, or its question where it asks one. */
    private View language(Context context, Theme theme, LanguageRow row) {
        Subtitle subtitle = row.subtitle();
        Asking current = asking;
        if (current != null && current.panel == panel && current.press == row.press && subtitle.equals(current.subtitle) && row.ask != null) {
            // A question is heard when it first shows, and again where its words changed.
            boolean announce = !row.ask.question.equals(current.announced);
            current.announced = row.ask.question;
            return Rows.question(context, theme, row.name, row.lang, row.ask.question,
                    Wording.t(Wording.MENU_ROW_ASKS, row.name, row.ask.question), Wording.t(Wording.ASK_CANCEL), row.ask.yes,
                    announce, this::unask, () -> act(row));
        }
        View view = Rows.language(context, theme, row.name, row.lang, row.value, row.checked, row.version == Version.PREMIUM,
                Wording.t(Wording.MENU_PREMIUM), () -> press(row));
        Asking back = cancelled;
        if (back != null && back.panel == panel && back.press == row.press && subtitle.equals(back.subtitle)) {
            cancelled = null;
            Rows.focusWhenShown(view);
        }
        return view;
    }

    /** A row that no longer asks what the viewer was asked has dropped its question. */
    private void dropStale(List<LanguageRow> rows) {
        Asking current = asking;
        if (current == null || current.panel != panel) return;
        for (LanguageRow row : rows) {
            if (row.ask != null && row.press == current.press && row.subtitle().equals(current.subtitle)) return;
        }
        asking = null;
    }

    private void press(LanguageRow row) {
        if (row.ask == null) {
            act(row);
            return;
        }
        // The row turns into its question, with TalkBack's focus on ✕.
        asking = new Asking(row.subtitle(), panel, row.press);
        refresh();
    }

    /** ✕: the row is back as it was, with TalkBack's focus on it, and nothing starts. */
    private void unask() {
        cancelled = asking;
        asking = null;
        refresh();
        cancelled = null;
    }

    /** What pressing a row, or its question's ✓, does. Showing and hiding leave the menu open. */
    private void act(LanguageRow row) {
        asking = null;
        Subtitle subtitle = row.subtitle();
        // Showing and hiding also say whether subtitles stay on for the next video.
        switch (row.press) {
            case SHOW -> {
                host.show(subtitle);
                refresh();
            }
            case HIDE -> {
                host.hide();
                refresh();
            }
            // Uploaded captions are never made or stopped: their rows show and hide.
            case START -> {
                if (subtitle.version == Version.UPLOADED) return;
                close();
                host.start(subtitle);
            }
            case STOP -> {
                if (subtitle.version == Version.UPLOADED) return;
                close();
                host.stop(subtitle);
            }
            case BUSY -> {
                close();
                host.busy();
            }
            // Minutes are bought from step 4 on: signed out no row offers it.
            case BUY -> close();
        }
    }

    private void openOther() {
        if (!isOpen()) return;
        panel = Panel.OTHER;
        asking = null;
        Context context = sheet.context();
        Theme theme = sheet.theme();
        search = Rows.search(context, theme, Wording.t(Wording.POPUP_SEARCH), typed -> {
            if (!isOpen() || panel != Panel.OTHER) return;
            // The sheet keeps the height it opened at while the list narrows.
            sheet.keepHeight();
            asking = null;
            sheet.show(other());
        });
        List<View> top = new ArrayList<>();
        top.add(Rows.title(context, theme, Wording.t(Wording.MENU_OTHER), null, false, null));
        top.add(Rows.divider(context, theme));
        top.add(Rows.space(context, theme, 8));
        top.add(Rows.searchRow(context, theme, search));
        sheet.replace(top, other());
    }

    private void openSettings() {
        if (!isOpen()) return;
        panel = Panel.SETTINGS;
        asking = null;
        sheet.replace(settingsRows());
        // In fullscreen Settings moves to the right edge, so the captions it changes stay in view (Felix, 2026-10-29).
        sheet.moveToEnd();
        host.settingsShown(true);
    }

    private List<View> settingsRows() {
        Context context = sheet.context();
        Theme theme = sheet.theme();
        String video = host.video();
        List<View> rows = new ArrayList<>();
        rows.add(Rows.title(context, theme, Wording.t(Wording.MENU_SETTINGS), Wording.t(Wording.SETTINGS_RESET_ALL),
                !look.atDefaults(video), () -> look.resetAll(host.video())));
        rows.add(Rows.divider(context, theme));
        rows.add(Rows.caption(context, theme, Wording.t(Wording.SETTINGS_SIZE)));
        rows.add(stepper(context, theme, Wording.t(Wording.SETTINGS_TRANSLATION), percent(look.translation()),
                look.translation() > Look.SIZE_MIN, look.translation() < Look.SIZE_MAX, look::stepTranslation));
        rows.add(stepper(context, theme, Wording.t(Wording.SETTINGS_ORIGINAL), percent(look.original()),
                look.original() > Look.SIZE_MIN, look.original() < Look.SIZE_MAX, look::stepOriginal));
        rows.add(Rows.divider(context, theme));
        rows.add(stepper(context, theme, Wording.t(Wording.SETTINGS_BACKGROUND), percent(look.background()),
                look.background() > Look.BACKGROUND_MIN, look.background() < Look.BACKGROUND_MAX, look::stepBackground));
        int timing = look.timing(video);
        rows.add(stepper(context, theme, Wording.t(Wording.SETTINGS_TIMING), seconds(timing),
                video != null && timing > Look.TIMING_MIN, video != null && timing < Look.TIMING_MAX,
                steps -> look.stepTiming(host.video(), steps)));
        rows.add(Rows.withAction(context, theme, Wording.t(Wording.SETTINGS_POSITION),
                Wording.t(look.moved() ? Wording.SETTINGS_POSITION_MOVED : Wording.SETTINGS_POSITION_DEFAULT),
                Wording.t(Wording.SETTINGS_RESET), look.moved(), look::resetPosition));
        return rows;
    }

    private interface Step {
        void step(int steps);
    }

    private static View stepper(Context context, Theme theme, String label, String value, boolean canDecrease,
                                boolean canIncrease, Step step) {
        return Rows.stepper(context, theme, label, value, Wording.t(Wording.SETTINGS_DECREASE, label),
                Wording.t(Wording.SETTINGS_INCREASE, label), canDecrease, canIncrease,
                () -> step.step(-1), () -> step.step(1));
    }

    /** A size or the background as Settings reads it: "110%". */
    private static String percent(int value) {
        return MenuModel.percent(Locale.getDefault(), value);
    }

    /** A timing in tenths of a second as Settings reads it: "+0.3 s", "0.0 s", "−0.3 s". */
    private static String seconds(int tenths) {
        return MenuModel.seconds(Locale.getDefault(), tenths / 10.0);
    }
}
