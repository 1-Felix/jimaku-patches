package app.jimaku.extension;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

import android.content.ContextWrapper;
import android.widget.LinearLayout;

import app.jimaku.extension.gateway.Gateway;
import app.jimaku.extension.gateway.Requests;
import app.jimaku.extension.gateway.Lookup;
import app.jimaku.extension.menu.Menu;
import app.jimaku.extension.menu.MenuModel;
import app.jimaku.extension.overlay.PlayerButton;
import app.jimaku.extension.overlay.SubtitleView;
import app.jimaku.extension.session.VideoSession;
import app.jimaku.extension.settings.KeptInstall;
import app.jimaku.extension.settings.Look;
import app.jimaku.extension.shared.Samples;
import app.jimaku.extension.shared.Wording;
import app.jimaku.extension.subtitles.Line;
import app.jimaku.extension.subtitles.LineCursor;

/**
 * Jimaku in the watch player: follows Morphe's player events, looks up the video in the watch
 * player, and draws the subtitle for the current position. Everything here runs on the main thread.
 * <p>
 * Work per frame runs only while subtitles can show and the video plays. While they can show and
 * the video is paused, a check a few times a second catches seeks and the controls. While they
 * can't show, nothing runs until one of Morphe's events or a layout change says otherwise.
 */
final class Player implements VideoSession.Display, Menu.Host {
    private static final String OVERLAYS_LAYOUT =
            "com.google.android.apps.youtube.app.common.player.overlay.YouTubePlayerOverlaysLayout";
    private static final long PAUSED_CHECK_MILLIS = 250;
    /** YouTube's views in the row of controls at the bottom of the player, by resource name. */
    private static final String[] CONTROLS_ROW = {"timestamps_container", "fullscreen_button"};
    /** YouTube's top bar in the player, and its CC button, beside which Jimaku's button sits. */
    private static final String TOP_BAR = "player_additional_view_container";
    private static final String CC_BUTTON = "player_subtitle_button";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final VideoSession session;
    private final Choreographer.FrameCallback frame = frameTimeNanos -> Guard.run("frame", () -> {
        framePosted = false;
        update();
    });
    private final Runnable pausedCheck = () -> Guard.run("paused check", () -> {
        pausedCheckPosted = false;
        update();
    });
    private final View.OnLayoutChangeListener layoutChange =
            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    Guard.run("layout change", this::onLayoutChange);

    private WeakReference<Activity> activity = new WeakReference<>(null);
    private SubtitleView view;
    private ViewGroup overlay;
    private WeakReference<View> controls = new WeakReference<>(null);
    private WeakReference<View> surface = new WeakReference<>(null);
    /** Whether {@link #surface} is the result of a search since the last layout change. */
    private boolean surfaceSearched;
    private final View[] controlsRow = new View[CONTROLS_ROW.length];
    private boolean controlsRowSearched;
    private String loggedControlsRow;
    private final int[] viewLocation = new int[2];
    private final int[] otherLocation = new int[2];
    private final Rect area = new Rect();
    private final Rect surfaceArea = new Rect();

    /** The video Morphe last reported, in whatever player. */
    private String videoId;
    /** A video whose subtitles stopped after a fault; it isn't looked up again. */
    private String faultedVideo;
    /** The playing state from Morphe's video state event, or null before the first one. */
    private Boolean statePlaying;
    /** When the playback time last moved forward, by the uptime clock, and to what. */
    private long timeMovedAt = -1;
    private long lastTimeMillis = -1;
    private LineCursor cursor;
    private String lang;
    private LineCursor originalCursor;
    /** While Settings is open, the captions last drawn stay through the gap before the next. */
    private String heldText;
    private String heldOriginal;
    private boolean editing;
    /** A drag by the captions' grip under way: where the finger went down and where the captions were then. */
    private Drag drag;

    private static final class Drag {
        final float downX;
        final float downY;
        final float startDx;
        final float startDy;

        Drag(float downX, float downY, float startDx, float startDy) {
            this.downX = downX;
            this.downY = downY;
            this.startDx = startDx;
            this.startDy = startDy;
        }
    }

    private Look look;
    private Menu menu;
    private PlayerButton button;
    private WeakReference<ViewGroup> topBar = new WeakReference<>(null);
    private boolean topBarMissingLogged;

    private boolean framePosted;
    private boolean pausedCheckPosted;
    /** What the log last said runs: "frames", "paused check" or "nothing". */
    private String loggedWork = "nothing";

    Player() {
        // An error a request throws ends its thread, never the app; the pool starts another.
        ExecutorService requests = Requests.pool((dead, fault) -> JimakuLog.fault("requests thread", fault));
        session = new VideoSession(new Gateway(KeptInstall.of(Morphe::context)), requests,
                answer -> main.post(() -> Guard.run("gateway answer", answer)), this);
    }

    /** The settings, read once there is a context to read them from. */
    private Look look() {
        if (look == null) {
            Context context = Morphe.context();
            if (context == null) context = activity.get();
            if (context == null) return null;
            Context app = context.getApplicationContext();
            look = new Look(app != null ? app : context);
            look.setOnChange(() -> Guard.run("settings change", this::onLookChange));
            menu = new Menu(session, look, this);
        }
        return look;
    }

    private void onLookChange() {
        if (menu != null) menu.refresh();
        update();
    }

    @Override
    public String preferred() {
        return Languages.languageFor(Locale.getDefault());
    }

    @Override
    public void settingsShown(boolean shown) {
        editing = shown;
        heldText = null;
        heldOriginal = null;
        // Settings closed under a drag: its touches no longer come, so the drag ends here, unsaved.
        if (!shown && drag != null) {
            drag = null;
            if (view != null) view.setDragging(false);
        }
        if (view != null) view.setEditing(shown);
        update();
    }

    @Override
    public Boolean darkTheme() {
        try {
            return Morphe.darkTheme();
        } catch (RuntimeException | LinkageError e) {
            JimakuLog.warn("No theme from Morphe: " + e);
            return null;
        }
    }

    /**
     * Drags the captions by their grip while Settings is open, as the extension does with a pointer:
     * they follow the finger within their reach, and where they are dropped is kept as a share of
     * the player. A touch that doesn't move them changes nothing.
     */
    @Override
    public boolean besideTouch(MotionEvent event) {
        SubtitleView captions = view;
        Look settings = look();
        if (captions == null || settings == null) return false;
        int[] at = new int[2];
        captions.getLocationOnScreen(at);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                RectF grip = captions.gripArea();
                if (grip.isEmpty() || !grip.contains(event.getRawX() - at[0], event.getRawY() - at[1])) return false;
                drag = new Drag(event.getRawX(), event.getRawY(), captions.drawnOffsetX(), captions.drawnOffsetY());
                captions.setDragging(true);
                if (menu != null) menu.setSeeThrough(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                Drag current = drag;
                Rect video = captions.videoArea();
                if (current == null) return false;
                if (video.isEmpty()) return true;
                // Up the screen is a larger offset from their own place.
                float dx = current.startDx + event.getRawX() - current.downX;
                float dy = current.startDy - (event.getRawY() - current.downY);
                captions.setPosition(dx / video.width(), dy / video.height());
                return true;
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                Drag ended = drag;
                if (ended == null) return false;
                drag = null;
                captions.setDragging(false);
                if (menu != null) menu.setSeeThrough(false);
                float slop = ViewConfiguration.get(captions.getContext()).getScaledTouchSlop();
                boolean moved = Math.hypot(event.getRawX() - ended.downX, event.getRawY() - ended.downY) > slop;
                Rect video = captions.videoArea();
                if (!moved || video.isEmpty()) {
                    update();
                    return true;
                }
                float dx = captions.drawnOffsetX();
                float dy = captions.drawnOffsetY();
                if (dx == 0 && dy == 0) settings.resetPosition();
                else settings.setPosition(dx / video.width(), dy / video.height());
                return true;
            }
            default -> {
                return drag != null;
            }
        }
    }

    @Override
    public void originalWanted() {
        session.askOriginal();
    }

    /** Whether Original caption shows an original on this video, by the viewer's switch and the menu's rules. */
    private boolean originalShown() {
        Look settings = look();
        return settings != null && settings.dual()
                && MenuModel.originalAvailable(session.lookup(), preferred(), session.wanted());
    }

    /** Keeps the resumed activity, to find the player in its views before the controls first show. */
    void watchActivities(Context context) {
        if (context instanceof Activity current) activity = new WeakReference<>(current);
        Context app = context != null ? context.getApplicationContext() : null;
        if (!(app instanceof Application application)) {
            JimakuLog.warn("No application to follow activities in");
            return;
        }
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity resumed) {
                Guard.run("activity resumed", () -> {
                    activity = new WeakReference<>(resumed);
                    update();
                });
            }

            @Override
            public void onActivityCreated(Activity created, Bundle state) {
            }

            @Override
            public void onActivityStarted(Activity started) {
            }

            @Override
            public void onActivityPaused(Activity paused) {
            }

            @Override
            public void onActivityStopped(Activity stopped) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity saved, Bundle state) {
            }

            @Override
            public void onActivityDestroyed(Activity destroyed) {
            }
        });
    }

    void onControlsCreated(View controlsButton) {
        if (controlsButton == null) return;
        if (controls.get() == null) JimakuLog.info("Overlay buttons start at " + describe(controlsButton));
        controls = new WeakReference<>(controlsButton);
        controlsRowSearched = false;
        ViewGroup found = overlaysLayout(controlsButton);
        if (found == null) {
            JimakuLog.warn("No player overlay layout above " + ancestry(controlsButton));
            return;
        }
        attach(found);
        placeButton();
        update();
    }

    void onNewVideo() {
        videoId = null;
        session.reset();
    }

    void onVideoId(String id) {
        videoId = id;
        update();
    }

    void onVideoState(Object state) {
        String name = String.valueOf(state);
        boolean nowPlaying = "PLAYING".equals(name);
        if (statePlaying == null || statePlaying != nowPlaying) JimakuLog.info("Video state " + name);
        statePlaying = nowPlaying;
        update();
    }

    void onVideoTime(long millis) {
        if (BuildConfig.FAULT_TEST) throw new IllegalStateException("Fault test at " + millis + " ms");
        if (millis != lastTimeMillis) {
            lastTimeMillis = millis;
            timeMovedAt = SystemClock.uptimeMillis();
        }
        update();
    }

    /**
     * Playing, by Morphe's video state event. Until that event has fired once, playing while the
     * time listener, which reports about once a second, saw the time move in the last 1.5 seconds.
     */
    private boolean isPlaying() {
        if (statePlaying != null) return statePlaying;
        return timeMovedAt >= 0 && SystemClock.uptimeMillis() - timeMovedAt < 1500;
    }

    /** After a fault: this video's subtitles go, and it plays on without them. */
    void onFault() {
        faultedVideo = videoId;
        if (menu != null) menu.close();
        session.reset();
    }

    @Override
    public void lookedUp(String video, Lookup lookup) {
        if (originalShown()) session.askOriginal();
        if (menu != null) menu.refresh();
        update();
    }

    @Override
    public void show(String video, String lang, List<Line> lines) {
        cursor = lines == null ? null : new LineCursor(lines);
        this.lang = lang;
        // Another language on screen can leave the original no longer repeating it.
        if (originalShown()) session.askOriginal();
        if (menu != null) menu.refresh();
        update();
    }

    @Override
    public void original(String video, List<Line> lines) {
        originalCursor = new LineCursor(lines);
        update();
    }

    /** Called from inside {@link VideoSession#open}, so it only stops; it must not look up again. */
    @Override
    public void clear() {
        cursor = null;
        lang = null;
        originalCursor = null;
        heldText = null;
        heldOriginal = null;
        if (view != null) {
            view.setText(null, null);
            view.setOriginal(null, null);
        }
        if (menu != null) menu.refresh();
        updateButton();
        schedule(false, false);
    }

    private void onLayoutChange() {
        surfaceSearched = false;
        controlsRowSearched = false;
        placeButton();
        update();
    }

    /** Looks up the video if it is new in the watch player, then draws or stops. */
    private void update() {
        String type = Morphe.playerType();
        boolean isShort = Morphe.lastVideoIsShort();

        Look settings = look();
        if (videoId != null && !isShort && Morphe.isWatchPlayer(type) && settings != null
                && !videoId.equals(faultedVideo) && !videoId.equals(session.video())) {
            session.open(videoId, preferred(), settings.on());
        }

        // Asked once per update, as it runs every frame and builds the menu's input each time.
        boolean originalOn = originalShown();
        boolean original = originalCursor != null && originalOn;
        boolean fullSize = settings != null && !isShort && Morphe.showsSubtitles(type);
        boolean mayShow = fullSize && (cursor != null || original || editing);
        // The button is wanted whether or not subtitles show: after YouTube made its activity anew,
        // the overlay is found again here, as Morphe doesn't report the new controls.
        if (fullSize && (view == null || !view.isAttachedToWindow())) findOverlayInActivity();
        boolean canShow = mayShow && isOnScreen();
        if (canShow) draw(original, originalOn);
        else if (view != null) {
            view.setText(null, null);
            view.setOriginal(null, null);
        }
        updateButton(originalOn);

        boolean playing = isPlaying();
        schedule(canShow && playing, canShow && !playing);
    }

    private boolean isOnScreen() {
        return view != null && view.isAttachedToWindow()
                && view.getWindowVisibility() == View.VISIBLE && view.isShown()
                && view.getWidth() > 0 && view.getHeight() > 0;
    }

    private void draw(boolean showOriginal, boolean originalOn) {
        Look settings = look();
        // A later timing shows each caption that much later: on screen is the caption of that much earlier.
        double t = Morphe.videoTimeMillis() / 1000.0 - settings.timing(session.video()) / 10.0;
        Line line = cursor != null ? cursor.at(t) : null;
        Line originalLine = showOriginal ? originalCursor.at(t) : null;
        String text = line == null ? null : line.text;
        String originalText = originalLine == null ? null : originalLine.text;
        // A line of the original that reads as the one below it would only repeat it.
        if (originalText != null && originalText.equals(text)) originalText = null;
        Lookup lookup = session.lookup();
        String originalLang = lookup != null ? lookup.original : null;
        String textLang = lang;

        if (editing) {
            // Through a gap the last lines stay. With no translation on, or nothing on screen yet, the
            // sample stands in, with the original's above it where Original caption shows one, as in
            // the extension; a sample is never held.
            boolean untranslated = cursor == null;
            if (!untranslated && text == null && originalText == null) {
                text = heldText;
                originalText = heldOriginal;
            }
            if (untranslated || (text == null && originalText == null)) {
                heldText = null;
                heldOriginal = null;
                textLang = preferred();
                text = Samples.line(textLang);
                originalText = originalLang != null && !originalLang.equals(textLang) && originalOn
                        ? Samples.line(originalLang) : null;
            } else {
                heldText = text;
                heldOriginal = originalText;
            }
        }

        view.setLook(settings.translation(), settings.original(), settings.background());
        if (drag == null) view.setPosition(settings.positionX(), settings.positionY());
        view.setEditing(editing);
        view.setText(text, textLang);
        view.setOriginal(originalText, originalLang);
        view.setVideoArea(videoArea());
        view.setControlsTop(Morphe.controlsShown() ? controlsTop() : -1);
        Rect cover = menu != null ? menu.settingsCover() : null;
        if (cover != null) {
            int[] at = new int[2];
            view.getLocationOnScreen(at);
            cover.offset(-at[0], -at[1]);
        }
        view.setCovered(cover);
    }

    /**
     * Jimaku's button as first child of YouTube's top bar, left of CC, sized as CC. Put back where
     * YouTube rebuilt the bar; without the bar, subtitles show as before, without the button.
     */
    private void placeButton() {
        if (overlay == null) return;
        ViewGroup bar = topBar.get();
        if (bar == null || !bar.isAttachedToWindow()) {
            bar = null;
            int id = resourceId(TOP_BAR);
            View found = id != 0 ? overlay.findViewById(id) : null;
            Activity current = activity.get();
            if (found == null && id != 0 && current != null && current.getWindow() != null) {
                View decor = current.getWindow().peekDecorView();
                if (decor != null) found = decor.findViewById(id);
            }
            if (found instanceof ViewGroup group) bar = group;
            topBar = new WeakReference<>(bar);
        }
        if (bar == null) {
            if (!topBarMissingLogged) JimakuLog.warn("No top bar to place the button in");
            topBarMissingLogged = true;
            return;
        }
        if (button != null && button.getParent() == bar && bar.getChildAt(0) == button) return;
        // A bar of a new activity gets a button of its own, so none keeps a finished activity.
        if (button != null && button.getContext() != bar.getContext()) {
            if (button.getParent() instanceof ViewGroup oldParent) oldParent.removeView(button);
            button = null;
        }
        if (button == null) {
            button = new PlayerButton(bar.getContext());
            button.setOnClickListener(v -> Guard.run("button", this::openMenu));
        }
        if (button.getParent() instanceof ViewGroup oldParent) oldParent.removeView(button);
        int ccId = resourceId(CC_BUTTON);
        View cc = ccId != 0 ? bar.findViewById(ccId) : null;
        ViewGroup.LayoutParams params;
        if (cc != null && cc.getLayoutParams() instanceof LinearLayout.LayoutParams ccParams && bar instanceof LinearLayout) {
            params = new LinearLayout.LayoutParams(ccParams);
        } else {
            int size = Math.round(48 * bar.getResources().getDisplayMetrics().density);
            params = new ViewGroup.LayoutParams(size, size);
        }
        bar.addView(button, 0, params);
        updateButton();
        JimakuLog.info("Button placed in the top bar" + (cc != null ? ", beside CC" : ""));
    }

    /** The button's fill and name, from what shows, as the extension's describeState() has them. */
    private void updateButton() {
        if (button != null) updateButton(originalShown());
    }

    private void updateButton(boolean originalOn) {
        if (button == null) return;
        boolean alone = cursor == null && originalCursor != null && originalOn;
        Lookup lookup = session.lookup();
        String wanted = session.wanted();
        String language = lang != null ? lang : wanted != null ? wanted
                : alone && lookup != null ? lookup.original : preferred();
        boolean on = cursor != null || alone;
        String english = Languages.englishName(language);
        String name;
        if (on) name = Wording.t(Wording.PLAYER_ON, english);
        else if (lookup != null && lookup.cachedLanguages().contains(language)) name = Wording.t(Wording.PLAYER_AVAILABLE, english);
        else if (session.lookedUp()) name = Wording.t(Wording.PLAYER_MISSING, english);
        else name = Wording.t(Wording.PLAYER_NAME);
        button.setOn(on);
        button.setName(name);
    }

    private void openMenu() {
        Activity current = activityOf(button != null ? button.getContext() : null);
        if (current == null || current.isFinishing() || current.isDestroyed()) current = activity.get();
        if (current == null || current.isFinishing() || current.isDestroyed() || look() == null) return;
        int maxTop = 0;
        if (overlay != null && overlay.isShown()) {
            overlay.getLocationOnScreen(otherLocation);
            maxTop = otherLocation[1] + overlay.getHeight();
        }
        menu.open(current, maxTop);
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper wrapper) {
            if (context instanceof Activity found) return found;
            context = wrapper.getBaseContext();
        }
        return null;
    }

    /** A view ID of YouTube's by its resource name, or 0. */
    private int resourceId(String name) {
        if (overlay == null || overlay.getId() == View.NO_ID) return 0;
        Resources resources = overlay.getResources();
        try {
            return resources.getIdentifier(name, "id", resources.getResourcePackageName(overlay.getId()));
        } catch (Resources.NotFoundException e) {
            return 0;
        }
    }

    private void schedule(boolean frames, boolean check) {
        if (frames && !framePosted) {
            framePosted = true;
            Choreographer.getInstance().postFrameCallback(frame);
        } else if (!frames && framePosted) {
            framePosted = false;
            Choreographer.getInstance().removeFrameCallback(frame);
        }

        if (check && !pausedCheckPosted) {
            pausedCheckPosted = true;
            main.postDelayed(pausedCheck, PAUSED_CHECK_MILLIS);
        } else if (!check && pausedCheckPosted) {
            pausedCheckPosted = false;
            main.removeCallbacks(pausedCheck);
        }

        String work = frames ? "frames" : check ? "paused check" : "nothing";
        if (!work.equals(loggedWork)) {
            loggedWork = work;
            JimakuLog.info("Runs " + work);
        }
    }

    private void attach(ViewGroup found) {
        if (overlay == found && view != null && view.getParent() == found) return;
        if (overlay != null) overlay.removeOnLayoutChangeListener(layoutChange);
        // An overlay of a new activity gets a view of its own, so none keeps a finished activity.
        if (view != null && view.getContext() != found.getContext()) {
            if (view.getParent() instanceof ViewGroup oldParent) oldParent.removeView(view);
            view = null;
        }
        if (view == null) {
            view = new SubtitleView(found.getContext());
            view.setOnScreenChange(() -> Guard.run("screen change", this::update));
        }
        ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup oldParent) oldParent.removeView(view);
        found.addView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        found.addOnLayoutChangeListener(layoutChange);
        overlay = found;
        surfaceSearched = false;
        controlsRowSearched = false;
        JimakuLog.info("Attached to the player overlay");
        placeButton();
    }

    /** The part of the view the video covers: the video surface cut to the view, or the whole view. */
    private Rect videoArea() {
        area.set(0, 0, view.getWidth(), view.getHeight());
        View video = videoSurface();
        if (video == null || !video.isShown() || video.getWidth() == 0) return area;
        view.getLocationInWindow(viewLocation);
        video.getLocationInWindow(otherLocation);
        int left = otherLocation[0] - viewLocation[0];
        int top = otherLocation[1] - viewLocation[1];
        surfaceArea.set(left, top, left + video.getWidth(), top + video.getHeight());
        if (surfaceArea.intersect(area)) area.set(surfaceArea);
        return area;
    }

    /** The largest surface the player draws video into, next to the overlay layout. */
    private View videoSurface() {
        View cached = surface.get();
        if (surfaceSearched && (cached == null || cached.isAttachedToWindow())) return cached;
        surfaceSearched = true;
        ViewParent root = overlay != null ? overlay.getParent() : null;
        if (!(root instanceof ViewGroup player)) return null;
        View largest = null;
        long largestArea = 0;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(player);
        while (!queue.isEmpty()) {
            View next = queue.poll();
            if (next == view) continue;
            if (next instanceof SurfaceView || next instanceof TextureView) {
                long size = (long) next.getWidth() * next.getHeight();
                if (next.isShown() && size > largestArea) {
                    largest = next;
                    largestArea = size;
                }
            } else if (next instanceof ViewGroup group) {
                for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
            }
        }
        surface = new WeakReference<>(largest);
        return largest;
    }

    /**
     * The top of the row of controls at the bottom of the player, in the view's coordinates, or -1:
     * the highest of YouTube's time and fullscreen button and the view the overlay-buttons listener
     * passed, which sits in that row. The container they sit in reaches far higher than they do.
     */
    private int controlsTop() {
        view.getLocationInWindow(viewLocation);
        int top = -1;
        for (View part : controlsRow()) top = higher(top, part);
        return higher(top, controls.get());
    }

    /** The higher of {@code top} and the top of {@code part} if it shows, in the view's coordinates. */
    private int higher(int top, View part) {
        if (part == null || !part.isShown() || part.getHeight() == 0) return top;
        part.getLocationInWindow(otherLocation);
        int partTop = otherLocation[1] - viewLocation[1];
        return top < 0 || partTop < top ? partTop : top;
    }

    /** YouTube's views of {@link #CONTROLS_ROW} in the overlay, searched again after a layout change. */
    private View[] controlsRow() {
        if (controlsRowSearched) return controlsRow;
        controlsRowSearched = true;
        Arrays.fill(controlsRow, null);
        for (int i = 0; i < CONTROLS_ROW.length; i++) {
            int id = resourceId(CONTROLS_ROW[i]);
            if (id != 0) controlsRow[i] = overlay.findViewById(id);
        }
        StringBuilder found = new StringBuilder();
        for (int i = 0; i < CONTROLS_ROW.length; i++) {
            if (controlsRow[i] != null) found.append(found.length() == 0 ? "" : ", ").append(CONTROLS_ROW[i]);
        }
        String row = found.length() == 0 ? "none" : found.toString();
        if (!row.equals(loggedControlsRow)) {
            loggedControlsRow = row;
            JimakuLog.info("Controls row found: " + row);
        }
        return controlsRow;
    }

    /**
     * Before the controls first show, the overlay buttons don't exist yet: find the player's
     * overlay layout in the activity's views instead. Runs only while subtitles are waiting to
     * show and no view is attached, at most on each of Morphe's player events.
     */
    private void findOverlayInActivity() {
        Activity current = activity.get();
        View decor = current != null && current.getWindow() != null ? current.getWindow().peekDecorView() : null;
        if (decor == null) return;
        ViewGroup largest = null;
        long largestArea = 0;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(decor);
        while (!queue.isEmpty()) {
            View next = queue.poll();
            if (!(next instanceof ViewGroup group)) continue;
            if (OVERLAYS_LAYOUT.equals(group.getClass().getName())) {
                long size = (long) group.getWidth() * group.getHeight();
                if (group.isShown() && size > largestArea) {
                    largest = group;
                    largestArea = size;
                }
                continue;
            }
            for (int i = 0; i < group.getChildCount(); i++) queue.add(group.getChildAt(i));
        }
        if (largest != null) attach(largest);
    }

    private static ViewGroup overlaysLayout(View start) {
        for (ViewParent parent = start.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof ViewGroup group && OVERLAYS_LAYOUT.equals(group.getClass().getName())) return group;
        }
        return null;
    }

    /** A view's class and resource name, for the log. */
    private static String describe(View view) {
        String name = view.getClass().getName();
        if (view.getId() == View.NO_ID) return name;
        try {
            return name + " " + view.getResources().getResourceEntryName(view.getId());
        } catch (Resources.NotFoundException e) {
            return name;
        }
    }

    private static String ancestry(View start) {
        StringBuilder chain = new StringBuilder(start.getClass().getName());
        for (ViewParent parent = start.getParent(); parent != null; parent = parent.getParent()) {
            chain.append(" < ").append(parent.getClass().getName());
        }
        return chain.toString();
    }
}
