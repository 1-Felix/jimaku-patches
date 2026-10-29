package app.jimaku.extension.gateway;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import app.jimaku.extension.BuildConfig;
import app.jimaku.extension.Languages;
import app.jimaku.extension.subtitles.Line;

/**
 * Reads from Jimaku's gateway what the shared cache holds, as the extension's
 * {@code lib/gateway.ts} does, with the same checks on each answer. Reads are anonymous: a
 * request names the video and the language, and carries no cookie, no credentials and no account
 * data. Calls block; run them off the main thread.
 */
public final class Gateway {
    public static final String ORIGIN = "https://api.jimaku.app";
    private static final int TIMEOUT_MILLIS = 15_000;
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    private final String origin;
    private final String userAgent;
    private final int timeoutMillis;

    public Gateway() {
        this(ORIGIN, userAgent(), TIMEOUT_MILLIS);
    }

    Gateway(String origin, String userAgent, int timeoutMillis) {
        this.origin = origin;
        this.userAgent = userAgent;
        this.timeoutMillis = timeoutMillis;
    }

    /** Names only Jimaku and the bundle's version, not the phone. */
    public static String userAgent() {
        return "Jimaku-Android/" + BuildConfig.BUNDLE_VERSION;
    }

    public static boolean isVideoId(String value) {
        return value != null && VIDEO_ID.matcher(value).matches();
    }

    /** What the shared cache holds for {@code video}, or null when it holds nothing. */
    public Lookup lookup(String video, Cancellation cancellation) throws GatewayException {
        if (!isVideoId(video)) throw new GatewayException("bad_video");
        HttpGet.Response response = send("/videos/" + video, cancellation);
        if (response.status == 404) return null;
        if (!isOk(response)) throw refusal(response);
        JSONObject answer = object(response);
        Object original = answer.opt("original");
        Object translations = answer.opt("translations");
        if (!(original instanceof String) || !Languages.isLanguageCode((String) original)
                || !(translations instanceof JSONArray)) {
            throw new GatewayException("bad_answer");
        }
        JSONArray list = (JSONArray) translations;
        List<String> langs = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            Object lang = list.opt(i);
            if (lang instanceof String && Languages.isLanguageCode((String) lang)) langs.add((String) lang);
        }
        return new Lookup((String) original, langs, Boolean.TRUE.equals(answer.opt("mixed")));
    }

    /** The lines of {@code video}'s subtitles in {@code lang}. */
    public List<Line> document(String video, String lang, Cancellation cancellation) throws GatewayException {
        if (!isVideoId(video)) throw new GatewayException("bad_video");
        if (!Languages.isLanguageCode(lang)) throw new GatewayException("bad_language");
        HttpGet.Response response = send("/videos/" + video + "/" + lang, cancellation);
        if (!isOk(response)) throw refusal(response);
        Object lines = object(response).opt("lines");
        if (!(lines instanceof JSONArray)) throw new GatewayException("bad_answer");
        JSONArray list = (JSONArray) lines;
        List<Line> result = new ArrayList<>(list.length());
        for (int i = 0; i < list.length(); i++) {
            Object item = list.opt(i);
            if (!(item instanceof JSONObject)) throw new GatewayException("bad_answer");
            JSONObject line = (JSONObject) item;
            Object start = line.opt("start");
            Object end = line.opt("end");
            Object text = line.opt("text");
            if (!(start instanceof Number) || !(end instanceof Number) || !(text instanceof String)) {
                throw new GatewayException("bad_answer");
            }
            result.add(new Line(((Number) start).doubleValue(), ((Number) end).doubleValue(), (String) text));
        }
        return result;
    }

    private HttpGet.Response send(String path, Cancellation cancellation) throws GatewayException {
        try {
            return HttpGet.get(URI.create(origin + path), userAgent, timeoutMillis, cancellation);
        } catch (Cancellation.CancelledException e) {
            throw new GatewayException("cancelled", e);
        } catch (IOException e) {
            throw new GatewayException(cancellation.isCancelled() ? "cancelled" : "offline", e);
        }
    }

    private static boolean isOk(HttpGet.Response response) {
        return response.status >= 200 && response.status < 300;
    }

    /** The answer's JSON object; an empty one when it holds something else, as the extension's record() does. */
    private static JSONObject object(HttpGet.Response response) throws GatewayException {
        Object value;
        try {
            value = new JSONTokener(response.text()).nextValue();
        } catch (JSONException e) {
            throw new GatewayException(isOk(response) ? "bad_answer" : "http_" + response.status, e);
        }
        return value instanceof JSONObject ? (JSONObject) value : new JSONObject();
    }

    /** The gateway's own error code where its answer has one. */
    private static GatewayException refusal(HttpGet.Response response) {
        try {
            Object error = object(response).opt("error");
            if (error instanceof String) return new GatewayException((String) error);
        } catch (GatewayException ignored) {
            // No readable answer: fall back to the status.
        }
        return new GatewayException("http_" + response.status);
    }
}
