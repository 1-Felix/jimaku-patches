package app.jimaku.extension;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.jimaku.extension.gateway.Gateway;
import app.jimaku.extension.overlay.SubtitleView;
import app.jimaku.extension.session.VideoSession;
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
final class Player implements VideoSession.Display {
    private static final String OVERLAYS_LAYOUT =
            "com.google.android.apps.youtube.app.common.player.overlay.YouTubePlayerOverlaysLayout";
    private static final long PAUSED_CHECK_MILLIS = 250;
    /** YouTube's views in the row of controls at the bottom of the player, by resource name. */
    private static final String[] CONTROLS_ROW = {"timestamps_container", "fullscreen_button"};

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

    private boolean framePosted;
    private boolean pausedCheckPosted;
    /** What the log last said runs: "frames", "paused check" or "nothing". */
    private String loggedWork = "nothing";

    Player() {
        ExecutorService reads = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Jimaku reads");
            thread.setDaemon(true);
            return thread;
        });
        session = new VideoSession(new Gateway(), reads, main::post, this);
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
        session.reset();
    }

    @Override
    public void show(String video, String lang, List<Line> lines) {
        cursor = new LineCursor(lines);
        this.lang = lang;
        update();
    }

    /** Called from inside {@link VideoSession#open}, so it only stops; it must not look up again. */
    @Override
    public void clear() {
        cursor = null;
        if (view != null) view.setText(null);
        schedule(false, false);
    }

    private void onLayoutChange() {
        surfaceSearched = false;
        controlsRowSearched = false;
        update();
    }

    /** Looks up the video if it is new in the watch player, then draws or stops. */
    private void update() {
        String type = Morphe.playerType();
        boolean isShort = Morphe.lastVideoIsShort();

        if (videoId != null && !isShort && Morphe.isWatchPlayer(type)
                && !videoId.equals(faultedVideo) && !videoId.equals(session.video())) {
            session.open(videoId, Languages.languageFor(Locale.getDefault()));
        }

        boolean mayShow = cursor != null && !isShort && Morphe.showsSubtitles(type);
        if (mayShow && (view == null || !view.isAttachedToWindow())) findOverlayInActivity();
        boolean canShow = mayShow && isOnScreen();
        if (canShow) draw();
        else if (view != null) view.setText(null);

        boolean playing = isPlaying();
        schedule(canShow && playing, canShow && !playing);
    }

    private boolean isOnScreen() {
        return view != null && view.isAttachedToWindow()
                && view.getWindowVisibility() == View.VISIBLE && view.isShown()
                && view.getWidth() > 0 && view.getHeight() > 0;
    }

    private void draw() {
        Line line = cursor.at(Morphe.videoTimeMillis() / 1000.0);
        view.setLanguage(lang);
        view.setText(line == null ? null : line.text);
        view.setVideoArea(videoArea());
        view.setControlsTop(Morphe.controlsShown() ? controlsTop() : -1);
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
        if (overlay == null || overlay.getId() == View.NO_ID) return controlsRow;
        Resources resources = overlay.getResources();
        String resourcePackage;
        try {
            resourcePackage = resources.getResourcePackageName(overlay.getId());
        } catch (Resources.NotFoundException e) {
            return controlsRow;
        }
        for (int i = 0; i < CONTROLS_ROW.length; i++) {
            int id = resources.getIdentifier(CONTROLS_ROW[i], "id", resourcePackage);
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
