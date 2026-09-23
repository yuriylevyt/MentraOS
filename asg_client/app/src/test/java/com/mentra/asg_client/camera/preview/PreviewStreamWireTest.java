package com.mentra.asg_client.camera.preview;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class PreviewStreamWireTest {

    @Test
    public void endpoint_takesHostPortAndPath_defaultsPort80_rejectsHttps() {
        PreviewStreamWire.Endpoint e = PreviewStreamWire.endpoint("http://192.168.0.130:52345/stream");
        assertEquals("192.168.0.130", e.host);
        assertEquals(52345, e.port);
        assertEquals("/stream", e.path);
        assertEquals(80, PreviewStreamWire.endpoint("http://phone.local/stream").port);
        assertThrows(IllegalArgumentException.class, () -> PreviewStreamWire.endpoint("https://h:1/stream"));
    }

    @Test
    public void requestHead_isAPostWithBearerTokenAndNoBodyLength() {
        byte[] head = PreviewStreamWire.requestHead(
                PreviewStreamWire.endpoint("http://192.168.0.130:52345/stream"), "tok");
        assertEquals(
                "POST /stream HTTP/1.1\r\n"
                        + "Host: 192.168.0.130:52345\r\n"
                        + "Authorization: Bearer tok\r\n"
                        + "X-Preview-Format: h264\r\n"
                        + "\r\n",
                new String(head, StandardCharsets.US_ASCII));
    }

    @Test
    public void unitHeader_isLengthThenTimestampThenFlags_bigEndian() {
        AccessUnit unit = new AccessUnit(new byte[300], 0x0102030405L, AccessUnit.FLAG_KEYFRAME);
        assertArrayEquals(
                new byte[] {0, 0, 0x01, 0x2C, 0, 0, 0, 0x01, 0x02, 0x03, 0x04, 0x05, 0x01},
                PreviewStreamWire.unitHeader(unit));
    }

    @Test
    public void parseStatus_readsTheCode_orMinusOne() {
        assertEquals(200, PreviewStreamWire.parseStatus("HTTP/1.1 200 OK\r\n\r\n"));
        assertEquals(401, PreviewStreamWire.parseStatus("HTTP/1.1 401 Unauthorized\r\n\r\n"));
        assertEquals(-1, PreviewStreamWire.parseStatus("SSH-2.0-OpenSSH\r\n"));
        assertEquals(-1, PreviewStreamWire.parseStatus("HTTP/1.1 abc\r\n\r\n"));
    }

    @Test
    public void readStatus_stopsAtTheBlankLine_andThrowsOnEarlyClose() throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream(
                "HTTP/1.1 200 OK\r\nConnection: keep-alive\r\n\r\nNEXT".getBytes(StandardCharsets.US_ASCII));
        assertEquals(200, PreviewStreamWire.readStatus(in));
        assertEquals('N', in.read());
        assertThrows(EOFException.class, () -> PreviewStreamWire.readStatus(
                new ByteArrayInputStream("HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.US_ASCII))));
    }
}
