package app.jimaku.extension.menu;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.jimaku.extension.Guard;
import app.jimaku.extension.gateway.Lookup;
import app.jimaku.extension.session.VideoSession;
import app.jimaku.extension.settings.Look;
import app.jimaku.extension.shared.Wording;
import app.jimaku.extension.ui.Icons;
import app.jimaku.extension.ui.Rows;
import app.jimaku.extension.ui.Sheet;
import app.jimaku.extension.ui.Theme;

/**
 * Jimaku's menu in a sheet of YouTube's kind: the languages, Original caption and Settings, then
 * Settings in place of them. Its rows are built again after every change, so they always read the
 * session and the settings as they are. Main thread only.
 */
public final class Menu {
    /** What the menu needs from the player. */
    public interface Host {
        /** The preferred language, the app's. */
        String preferred();

        /** Settings opened or closed: the captions show their outline and grip, or a sample, while it is open. */
        void settingsShown(boolean shown);

        /** Original caption was switched on: its lines are wanted. */
        void originalWanted();

        /** Whether the app shows its dark theme, or null where it can't say. */
        Boolean darkTheme();

        /** A touch beside Settings, in screen coordinates: whether it is the captions' grip, which keeps the sheet open. */
        boolean besideTouch(MotionEvent event);
    }

    private final VideoSession session;
    private final Look look;
    private final Host host;
    private Sheet sheet;
    private boolean settings;

    public Menu(VideoSession session, Look look, Host host) {
        this.session = session;
        this.look = look;
        this.host = host;
    }

    public boolean isOpen() {
        return sheet != null && sheet.isOpen();
    }

    /** Whether Settings is the sheet on screen. */
    public boolean settingsShown() {
        return isOpen() && settings;
    }

    /** What Settings covers of the screen at its right edge in fullscreen, in screen pixels, or null. */
    public Rect settingsCover() {
        return settingsShown() ? sheet.coveredAtEnd() : null;
    }

    /** Opens the main sheet over {@code activity}, its top below {@code maxTop} in portrait. */
    public void open(Activity activity, int maxTop) {
        if (isOpen()) return;
        // A sheet whose activity went never tells it closed; its Settings ends here.
        if (sheet != null && settings) host.settingsShown(false);
        settings = false;
        sheet = Sheet.open(activity, maxTop, host.darkTheme());
        Sheet opened = sheet;
        sheet.setOnClosed(() -> Guard.run("menu closed", () -> {
            if (sheet != opened) return;
            boolean wasSettings = settings;
            sheet = null;
            settings = false;
            if (wasSettings) host.settingsShown(false);
        }));
        sheet.setBeside(event -> {
            boolean[] taken = {false};
            Guard.run("captions drag", () -> taken[0] = sheet == opened && settings && host.besideTouch(event));
            return taken[0];
        });
        List<View> rows = main();
        sheet.show(rows);
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

    /** Builds the rows again from the session and the settings as they are now. */
    public void refresh() {
        if (!isOpen()) return;
        sheet.show(settings ? settingsRows() : main());
    }

    private View checkedRow;

    private List<View> main() {
        Context context = sheet.context();
        Theme theme = sheet.theme();
        Lookup lookup = session.lookup();
        String preferred = host.preferred();
        String wanted = session.wanted();
        List<View> rows = new ArrayList<>();
        checkedRow = null;
        rows.add(Rows.space(context, theme, 8));

        List<MenuModel.Row> languages = MenuModel.languages(lookup, preferred, wanted, Locale.getDefault());
        for (MenuModel.Row language : languages) {
            View row = Rows.language(context, theme, language.name, language.checked, language.premium,
                    Wording.t(Wording.MENU_PREMIUM), () -> press(language));
            if (language.checked) checkedRow = row;
            rows.add(row);
        }
        if (!languages.isEmpty()) rows.add(Rows.divider(context, theme));

        boolean available = MenuModel.originalAvailable(lookup, preferred, wanted);
        rows.add(Rows.toggle(context, theme, Icons.DUAL, Wording.t(Wording.MENU_ORIGINAL), look.dual(), available, () -> {
            if (!MenuModel.originalAvailable(session.lookup(), host.preferred(), session.wanted())) return;
            look.setDual(!look.dual());
            if (look.dual()) host.originalWanted();
            refresh();
        }));
        rows.add(Rows.submenu(context, theme, Icons.TUNE, Wording.t(Wording.MENU_SETTINGS), this::openSettings));
        return rows;
    }

    private void press(MenuModel.Row language) {
        if (language.checked) {
            session.hide();
            look.setOn(false);
        } else {
            session.choose(language.lang);
            look.setOn(true);
        }
        refresh();
    }

    private void openSettings() {
        if (!isOpen()) return;
        settings = true;
        sheet.replace(settingsRows());
        // In fullscreen Settings moves to the right edge, so the captions it changes stay in view (Felix, 2026-10-29).
        sheet.moveToEnd();
        host.settingsShown(true);
    }

    private List<View> settingsRows() {
        Context context = sheet.context();
        Theme theme = sheet.theme();
        String video = session.video();
        List<View> rows = new ArrayList<>();
        rows.add(Rows.title(context, theme, Wording.t(Wording.MENU_SETTINGS), Wording.t(Wording.SETTINGS_RESET_ALL),
                !look.atDefaults(video), () -> look.resetAll(session.video())));
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
                steps -> look.stepTiming(session.video(), steps)));
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
