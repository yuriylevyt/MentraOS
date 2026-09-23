package com.mentra.asg_client.camera.preview;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Bytes of the H.264 preview wire protocol (ADR 0013), with no sockets, so it runs under plain
 * JUnit. The glasses write one HTTP request head by hand (no Content-Length, no chunked encoding),
 * read the phone's immediate status line, then write access units framed as: 4-byte big-endian
 * payload length, 8-byte big-endian timestamp (µs), 1-byte flags, payload.
 */
final class PreviewStreamWire {
    static final int UNIT_HEADER_BYTES = 13;
    /** Must match the phone's PreviewStreamParser.maxUnitBytes; the phone closes on anything bigger. */
    static final int MAX_UNIT_BYTES = 1024 * 1024;
    static final int MAX_RESPONSE_HEAD_BYTES = 4096;

    /** Where the stream goes: taken from the http:// URL the phone sent (path used as given). */
    static final class Endpoint {
        final String host;
        final int port;
        final String path;

        Endpoint(String host, int port, String path) {
            this.host = host;
            this.port = port;
            this.path = path;
        }
    }

    private PreviewStreamWire() {}

    static Endpoint endpoint(String url) {
        URI uri = URI.create(url);
        if (!"http".equals(uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.US))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("H.264 preview needs an http:// URL, got " + url);
        }
        int port = uri.getPort() == -1 ? 80 : uri.getPort();
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return new Endpoint(uri.getHost(), port, path);
    }

    static byte[] requestHead(Endpoint endpoint, String token) {
        String head = "POST " + endpoint.path + " HTTP/1.1\r\n"
                + "Host: " + endpoint.host + ":" + endpoint.port + "\r\n"
                + "Authorization: Bearer " + token + "\r\n"
                + "X-Preview-Format: h264\r\n"
                + "\r\n";
        return head.getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] unitHeader(AccessUnit unit) {
        return ByteBuffer.allocate(UNIT_HEADER_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(unit.payload.length)
                .putLong(unit.ptsUs)
                .put((byte) unit.flags)
                .array();
    }

    /** Status code from a response head ("HTTP/1.1 200 OK…"), or -1 when it isn't one. */
    static int parseStatus(String head) {
        if (head == null || !head.startsWith("HTTP/1.")) {
            return -1;
        }
        String[] parts = head.split(" ", 3);
        if (parts.length < 2) {
            return -1;
        }
        try {
            return Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Reads the response head up to the blank line and returns its status code. */
    static int readStatus(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (head.length() < MAX_RESPONSE_HEAD_BYTES) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("connection closed before the response head ended");
            }
            head.append((char) b);
            int n = head.length();
            if (n >= 4 && head.charAt(n - 4) == '\r' && head.charAt(n - 3) == '\n'
                    && head.charAt(n - 2) == '\r' && head.charAt(n - 1) == '\n') {
                return parseStatus(head.toString());
            }
        }
        return -1;
    }
}
