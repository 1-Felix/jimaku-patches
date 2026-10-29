package app.jimaku.extension.session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import app.jimaku.extension.JimakuLog;
import app.jimaku.extension.gateway.Cancellation;
import app.jimaku.extension.gateway.Gateway;
import app.jimaku.extension.gateway.GatewayException;
import app.jimaku.extension.gateway.Lookup;
import app.jimaku.extension.gateway.Reads;
import app.jimaku.extension.menu.MenuInput;
import app.jimaku.extension.menu.Subtitle;
import app.jimaku.extension.menu.Version;
import app.jimaku.extension.subtitles.Line;
import app.jimaku.extension.youtube.TrackInfo;

/**
 * The subtitles of the video in the watch player. A new video removes the old one's subtitles
 * before anything else; then the lookup, and with subtitles on the preferred language's document,
 * on a background thread. The menu then shows or hides a language and asks for the original. An
 * answer for a video that is no longer the current one, or for a press the viewer has since
 * overridden, is dropped. A document read once is kept for the video, so showing it again reads
 * nothing.
 * <p>
 * Every method is called on the main thread, and {@link Display} is called there too.
 */
public final class VideoSession {
    /** Where the subtitles go. */
    public interface Display {
        /** What the cache holds for {@code video}: null when nothing, or when it couldn't be read. */
        void lookedUp(String video, Lookup lookup);

        /** The subtitles on screen for {@code video}; null {@code lang} and {@code lines} when none show. */
        void show(String video, String lang, List<Line> lines);

        /** The video's transcript as said, for Original caption. */
        void original(String video, List<Line> lines);

        /** A new video started, or nothing may show: everything of the last video goes. */
        void clear();

        /** YouTube said what it has for {@code video}: its caption tracks, length and live state. */
        default void tracked(String video) {
        }
    }

    /** YouTube's caption tracks for a video, read as the installed app reads them. */
    public interface Tracks {
        /** What YouTube says about {@code video}, or null where it gives nothing to go by. */
        TrackInfo tracks(String video, Cancellation cancellation) throws GatewayException;
    }

    private final Reads reads;
    private final Tracks tracks;
    private final Executor background;
    private final Executor main;
    private final Display display;

    private String video;
    private Lookup lookup;
    private boolean lookedUp;
    /** What YouTube said about the video, or null while it hasn't, or where it said nothing to go by. */
    private TrackInfo track;
    private final Map<String, List<Line>> documents = new HashMap<>();
    private List<Line> original;
    private boolean originalAsked;
    /** The language on screen, or null. */
    private String showing;
    /** The language the latest press asked for, which a read on its way must still match. */
    private String wanted;
    private final Set<Cancellation> pending = new HashSet<>();
    private long generation;

    public VideoSession(Reads reads, Executor background, Executor main, Display display) {
        this(reads, null, background, main, display);
    }

    /** {@code tracks}: YouTube's caption tracks, or null to read none. */
    public VideoSession(Reads reads, Tracks tracks, Executor background, Executor main, Display display) {
        this.reads = reads;
        this.tracks = tracks;
        this.background = background;
        this.main = main;
        this.display = display;
    }

    /** The video these subtitles are for, or null. */
    public String video() {
        return video;
    }

    /** What the cache holds for the video, or null while unknown or when it holds nothing. */
    public Lookup lookup() {
        return lookup;
    }

    /** Whether the lookup has answered, with something or nothing. */
    public boolean lookedUp() {
        return lookedUp;
    }

    /** The language on screen, or null. */
    public String showing() {
        return showing;
    }

    /** The language the latest press asked for, still on its way or on screen, or null. */
    public String wanted() {
        return wanted;
    }

    /** The transcript's lines once read, or null. */
    public List<Line> original() {
        return original;
    }

    /** What YouTube said about the video, or null. */
    public TrackInfo track() {
        return track;
    }

    /**
     * What the menu model reads for a signed-out viewer: the cache, what shows, and YouTube's track,
     * length and live state, the track unknown until YouTube has said.
     */
    public MenuInput input(String preferred, Locale locale) {
        MenuInput.Builder input = new MenuInput.Builder(preferred).lookup(lookup).locale(locale);
        if (showing != null) input.showing(new Subtitle(showing, Version.PREMIUM));
        TrackInfo said = track;
        if (said != null) {
            List<String> uploaded = new ArrayList<>();
            for (TrackInfo.Uploaded each : said.uploaded) uploaded.add(each.lang);
            input.track(said.language).duration(said.duration).live(said.live).uploaded(uploaded);
        }
        return input.build();
    }

    /** A new video started, or nothing may show: the subtitles go and pending reads stop. */
    public void reset() {
        generation++;
        for (Cancellation cancellation : pending) cancellation.cancel();
        pending.clear();
        video = null;
        lookup = null;
        lookedUp = false;
        track = null;
        documents.clear();
        original = null;
        originalAsked = false;
        showing = null;
        wanted = null;
        display.clear();
    }

    /**
     * {@code videoId} is open in the watch player and the preferred language is {@code preferred}.
     * With {@code show}, its subtitles in that language show where the cache has them. Does nothing
     * if it is already the current video.
     */
    public void open(String videoId, String preferred, boolean show) {
        if (videoId == null || videoId.equals(video)) return;
        reset();
        if (!Gateway.isVideoId(videoId)) return;

        video = videoId;
        if (show) wanted = preferred;
        long readFor = generation;
        Cancellation cancellation = started();
        JimakuLog.info("Looking up " + videoId + (show ? ", showing " + preferred : ""));
        background.execute(() -> {
            Lookup found = null;
            List<Line> lines = null;
            try {
                found = reads.lookup(videoId, cancellation);
                if (found == null) JimakuLog.info("Not cached: " + videoId);
                else if (show && found.cachedLanguages().contains(preferred)) {
                    JimakuLog.info("Reading " + preferred + " for " + videoId);
                    lines = reads.document(videoId, preferred, cancellation);
                }
            } catch (GatewayException e) {
                if (!"cancelled".equals(e.code)) JimakuLog.warn("Gateway read for " + videoId + " failed: " + e.code);
            } catch (RuntimeException e) {
                JimakuLog.fault("gateway read", e);
            }
            Lookup answer = found;
            List<Line> document = lines;
            main.execute(() -> {
                if (readFor != generation) return;
                pending.remove(cancellation);
                lookup = answer;
                lookedUp = true;
                if (preferred.equals(wanted) && (document == null || document.isEmpty())) wanted = null;
                display.lookedUp(videoId, answer);
                if (document != null && !document.isEmpty()) {
                    documents.put(preferred, document);
                    if (preferred.equals(wanted)) present(preferred);
                }
            });
        });
        readTracks(videoId, readFor);
    }

    /** Asks YouTube for the video's caption tracks beside the lookup, as the extension reads the page's. */
    private void readTracks(String videoId, long readFor) {
        if (tracks == null) return;
        Cancellation cancellation = started();
        background.execute(() -> {
            TrackInfo said = null;
            try {
                said = tracks.tracks(videoId, cancellation);
                if (said == null) JimakuLog.info("No caption tracks from YouTube for " + videoId);
            } catch (GatewayException e) {
                if (!"cancelled".equals(e.code)) JimakuLog.warn("YouTube read for " + videoId + " failed: " + e.code);
            } catch (RuntimeException e) {
                JimakuLog.fault("youtube read", e);
            }
            TrackInfo answer = said;
            main.execute(() -> {
                if (readFor != generation) return;
                pending.remove(cancellation);
                track = answer;
                if (answer != null) {
                    JimakuLog.info("YouTube tracks for " + videoId + ": " + (answer.language != null ? answer.language : "none")
                            + ", " + answer.uploaded.size() + " uploaded" + (answer.live ? ", live" : ""));
                }
                display.tracked(videoId);
            });
        });
    }

    /** Shows {@code lang}, reading it once if it hasn't been read for this video. */
    public void choose(String lang) {
        if (video == null || lookup == null || !lookup.cachedLanguages().contains(lang)) return;
        wanted = lang;
        if (lang.equals(showing)) return;
        if (documents.containsKey(lang)) {
            present(lang);
            return;
        }
        String videoId = video;
        long readFor = generation;
        Cancellation cancellation = started();
        background.execute(() -> {
            List<Line> lines = null;
            try {
                JimakuLog.info("Reading " + lang + " for " + videoId);
                lines = reads.document(videoId, lang, cancellation);
            } catch (GatewayException e) {
                if (!"cancelled".equals(e.code)) JimakuLog.warn("Gateway read for " + videoId + " failed: " + e.code);
            } catch (RuntimeException e) {
                JimakuLog.fault("gateway read", e);
            }
            List<Line> document = lines;
            main.execute(() -> {
                if (readFor != generation) return;
                pending.remove(cancellation);
                if (document == null || document.isEmpty()) {
                    // The press failed: what was on screen stays, and the menu checks it again.
                    if (lang.equals(wanted)) {
                        wanted = showing;
                        display.show(videoId, showing, showing == null ? null : documents.get(showing));
                    }
                    return;
                }
                documents.put(lang, document);
                if (lang.equals(wanted)) present(lang);
            });
        });
    }

    /** Hides the subtitles on screen, and a read on its way; showing them again reads nothing. */
    public void hide() {
        wanted = null;
        if (showing == null) return;
        showing = null;
        display.show(video, null, null);
    }

    /** Reads the transcript once for this video, where the cache has one. */
    public void askOriginal() {
        if (video == null || lookup == null || originalAsked) return;
        originalAsked = true;
        String videoId = video;
        long readFor = generation;
        Cancellation cancellation = started();
        background.execute(() -> {
            List<Line> lines = null;
            try {
                JimakuLog.info("Reading the original for " + videoId);
                lines = reads.original(videoId, cancellation);
            } catch (GatewayException e) {
                if (!"cancelled".equals(e.code)) JimakuLog.warn("Gateway read of " + videoId + "'s original failed: " + e.code);
            } catch (RuntimeException e) {
                JimakuLog.fault("gateway read", e);
            }
            List<Line> answer = lines;
            main.execute(() -> {
                if (readFor != generation) return;
                pending.remove(cancellation);
                if (answer == null || answer.isEmpty()) {
                    originalAsked = false;
                    return;
                }
                original = answer;
                display.original(videoId, answer);
            });
        });
    }

    private Cancellation started() {
        Cancellation cancellation = new Cancellation();
        pending.add(cancellation);
        return cancellation;
    }

    private void present(String lang) {
        showing = lang;
        List<Line> lines = documents.get(lang);
        JimakuLog.info("Showing " + lines.size() + " subtitles in " + lang + " for " + video);
        display.show(video, lang, lines);
    }
}
