package app.jimaku.extension.gateway;

import java.util.List;

import app.jimaku.extension.subtitles.Line;

/** The two reads a video needs. Calls block; run them off the main thread. */
public interface Reads {
    /** What the shared cache holds for {@code video}, or null when it holds nothing. */
    Lookup lookup(String video, Cancellation cancellation) throws GatewayException;

    /** The subtitles of {@code video} in {@code lang}. */
    List<Line> document(String video, String lang, Cancellation cancellation) throws GatewayException;
}
