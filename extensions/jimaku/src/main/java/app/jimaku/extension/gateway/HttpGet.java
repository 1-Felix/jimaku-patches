package app.jimaku.extension.gateway;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A plain HTTP/1.1 GET, written out by hand so the request carries exactly the headers listed
 * here. The platform's HttpURLConnection adds cookies from any cookie handler the app installs,
 * and has no way to turn that off for one request. No redirects are followed.
 */
final class HttpGet {
    /** Subtitle documents of long videos stay well under this. */
    static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    private static final int MAX_HEADER_LINE = 8 * 1024;
    private static final boolean ON_ANDROID = "Dalvik".equals(System.getProperty("java.vm.name"));

    static final class Response {
        final int status;
        final Map<String, String> headers;
        final byte[] body;

        Response(int status, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private HttpGet() {
    }

    static Response get(URI url, String userAgent, int timeoutMillis, Cancellation cancellation) throws IOException {
        String scheme = url.getScheme();
        boolean tls = "https".equals(scheme);
        if (!tls && !"http".equals(scheme)) throw new IOException("Unsupported scheme " + scheme);
        String host = url.getHost();
        int port = url.getPort() != -1 ? url.getPort() : tls ? 443 : 80;
        String path = url.getRawPath() + (url.getRawQuery() != null ? "?" + url.getRawQuery() : "");

        Socket plain = new Socket();
        cancellation.attach(plain);
        Socket socket = plain;
        try {
            plain.connect(new InetSocketAddress(host, port), timeoutMillis);
            plain.setSoTimeout(timeoutMillis);
            if (tls) {
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket ssl = (SSLSocket) factory.createSocket(plain, host, port, true);
                cancellation.attach(ssl);
                socket = ssl;
                SSLParameters parameters = ssl.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(parameters);
                // The handshake checks the certificate names the host (endpoint identification).
                // Android's default verifier checks it again; a desktop JVM's refuses every host,
                // so the JVM tests rely on the handshake alone.
                ssl.startHandshake();
                if (ON_ANDROID && !HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.getSession())) {
                    throw new IOException("The certificate does not match " + host);
                }
            }

            String hostHeader = url.getPort() != -1 ? host + ":" + port : host;
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + hostHeader + "\r\n"
                    + "User-Agent: " + userAgent + "\r\n"
                    + "Accept: application/json\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            InputStream in = new BufferedInputStream(socket.getInputStream());
            int status = readStatus(readLine(in));
            Map<String, String> headers = new HashMap<>();
            for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
                int colon = line.indexOf(':');
                if (colon <= 0) throw new IOException("Malformed header");
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            return new Response(status, headers, readBody(in, headers));
        } catch (IOException e) {
            if (cancellation.isCancelled()) throw new Cancellation.CancelledException();
            throw e;
        } finally {
            cancellation.detach(plain);
            cancellation.detach(socket);
            Cancellation.closeQuietly(socket);
            Cancellation.closeQuietly(plain);
        }
    }

    private static int readStatus(String line) throws IOException {
        String[] parts = line.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/1.")) throw new IOException("Malformed status line");
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Malformed status line");
        }
    }

    private static byte[] readBody(InputStream in, Map<String, String> headers) throws IOException {
        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            return readChunked(in);
        }
        String length = headers.get("content-length");
        if (length != null) {
            long size;
            try {
                size = Long.parseLong(length);
            } catch (NumberFormatException e) {
                throw new IOException("Malformed Content-Length");
            }
            if (size < 0 || size > MAX_BODY_BYTES) throw new IOException("Body too large");
            byte[] body = new byte[(int) size];
            readFully(in, body);
            return body;
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        copy(in, body, -1);
        return body.toByteArray();
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(in);
            int extension = line.indexOf(';');
            String hex = (extension >= 0 ? line.substring(0, extension) : line).trim();
            long size;
            try {
                size = Long.parseLong(hex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Malformed chunk size");
            }
            if (size < 0 || body.size() + size > MAX_BODY_BYTES) throw new IOException("Body too large");
            if (size == 0) {
                // Skip any trailers up to the empty line that ends the message.
                while (!readLine(in).isEmpty()) {
                    continue;
                }
                return body.toByteArray();
            }
            copy(in, body, size);
            if (!readLine(in).isEmpty()) throw new IOException("Malformed chunk");
        }
    }

    /** Copies {@code size} bytes, or everything up to the end of the stream when it is negative. */
    private static void copy(InputStream in, ByteArrayOutputStream out, long size) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long left = size;
        while (size < 0 || left > 0) {
            int want = size < 0 ? buffer.length : (int) Math.min(buffer.length, left);
            int read = in.read(buffer, 0, want);
            if (read < 0) {
                if (size < 0) return;
                throw new EOFException("Body ended early");
            }
            out.write(buffer, 0, read);
            left -= read;
            if (out.size() > MAX_BODY_BYTES) throw new IOException("Body too large");
        }
    }

    private static void readFully(InputStream in, byte[] into) throws IOException {
        int offset = 0;
        while (offset < into.length) {
            int read = in.read(into, offset, into.length - offset);
            if (read < 0) throw new EOFException("Body ended early");
            offset += read;
        }
    }

    /** One line ending in CRLF (or a bare LF), without the line ending. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException("Connection closed");
            if (b == '\n') break;
            if (line.length() >= MAX_HEADER_LINE) throw new IOException("Line too long");
            line.append((char) b);
        }
        int last = line.length() - 1;
        if (last >= 0 && line.charAt(last) == '\r') line.setLength(last);
        return line.toString();
    }
}
