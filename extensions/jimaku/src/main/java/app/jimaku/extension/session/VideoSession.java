package app.jimaku.extension.session;

import java.util.List;
import java.util.concurrent.Executor;

import app.jimaku.extension.JimakuLog;
import app.jimaku.extension.gateway.Cancellation;
import app.jimaku.extension.gateway.Gateway;
import app.jimaku.extension.gateway.GatewayException;
import app.jimaku.extension.gateway.Lookup;
import app.jimaku.extension.gateway.Reads;
import app.jimaku.extension.subtitles.Line;

/**
 * The subtitles of the video in the watch player. A new video removes the old one's subtitles
 * before anything else; then the lookup, then the document in the viewer's language, both on a
 * background thread. An answer for a video that is no longer the current one is dropped.
 * <p>
 * Every method is called on the main thread, and {@link Display} is called there too.
 */
public final class VideoSession {
    /** Where the subtitles go. */
    public interface Display {
        void show(String video, String lang, List<Line> lines);

        void clear();
    }

    private final Reads reads;
    private final Executor background;
    private final Executor main;
    private final Display display;

    private String video;
    private Cancellation pending;
    private long generation;

    public VideoSession(Reads reads, Executor background, Executor main, Display display) {
        this.reads = reads;
        this.background = background;
        this.main = main;
        this.display = display;
    }

    /** The video these subtitles are for, or null. */
    public String video() {
        return video;
    }

    /** A new video started, or nothing may show: the subtitles go and pending reads stop. */
    public void reset() {
        generation++;
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
        video = null;
        display.clear();
    }

    /**
     * {@code videoId} is open in the watch player and the viewer's language is {@code lang}.
     * Does nothing if it is already the current video.
     */
    public void open(String videoId, String lang) {
        if (videoId == null || videoId.equals(video)) return;
        reset();
        if (!Gateway.isVideoId(videoId)) return;

        video = videoId;
        long readFor = generation;
        Cancellation cancellation = new Cancellation();
        pending = cancellation;
        JimakuLog.info("Looking up " + videoId + " in " + lang);
        background.execute(() -> {
            List<Line> lines = read(videoId, lang, cancellation);
            main.execute(() -> {
                if (readFor != generation) return;
                pending = null;
                if (lines == null || lines.isEmpty()) return;
                JimakuLog.info("Showing " + lines.size() + " subtitles in " + lang + " for " + videoId);
                display.show(videoId, lang, lines);
            });
        });
    }

    /** Background thread. Null when the cache has nothing in {@code lang} or the gateway failed. */
    private List<Line> read(String videoId, String lang, Cancellation cancellation) {
        try {
            Lookup lookup = reads.lookup(videoId, cancellation);
            if (lookup == null) {
                JimakuLog.info("Not cached: " + videoId);
                return null;
            }
            if (!lookup.cachedLanguages().contains(lang)) {
                JimakuLog.info("Cached for " + videoId + " in " + lookup.cachedLanguages() + ", not in " + lang);
                return null;
            }
            return reads.document(videoId, lang, cancellation);
        } catch (GatewayException e) {
            if (!"cancelled".equals(e.code)) JimakuLog.warn("Gateway read for " + videoId + " failed: " + e.code);
            return null;
        } catch (RuntimeException e) {
            JimakuLog.fault("gateway read", e);
            return null;
        }
    }
}
