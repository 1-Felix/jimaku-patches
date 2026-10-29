package app.jimaku.extension.gateway;

/**
 * A read that gave nothing to show. {@code code} is the gateway's stable error code, or one of
 * {@code offline} (not reachable), {@code cancelled} (the video was left), {@code bad_answer}
 * (an answer Jimaku can't read), {@code bad_video} and {@code http_<status>}, as in the extension.
 */
public final class GatewayException extends Exception {
    public final String code;

    public GatewayException(String code) {
        super(code);
        this.code = code;
    }

    public GatewayException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }
}
