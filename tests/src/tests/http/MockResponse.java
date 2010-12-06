/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tests.http;

import static tests.http.MockWebServer.ASCII;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A scripted response to be replayed by the mock web server.
 */
public class MockResponse {
    private static final byte[] EMPTY_BODY = new byte[0];

    private String status = "HTTP/1.1 200 OK";
    private Map<String, String> headers = new HashMap<String, String>();
    private byte[] body = EMPTY_BODY;
    private boolean closeConnectionAfter = false;
    private int numPackets = 0;

    public MockResponse() {
        addHeader("Content-Length", 0);
    }

    /**
     * Returns the HTTP response line, such as "HTTP/1.1 200 OK".
     */
    public String getStatus() {
        return status;
    }

    public MockResponse setResponseCode(int code) {
        this.status = "HTTP/1.1 " + code + " OK";
        return this;
    }

    /**
     * Returns the HTTP headers, such as "Content-Length: 0".
     */
    public List<String> getHeaders() {
        List<String> headerStrings = new ArrayList<String>();
        for (String header : headers.keySet()) {
            headerStrings.add(header + ": " + headers.get(header));
        }
        return headerStrings;
    }

    public MockResponse addHeader(String header, String value) {
        headers.put(header.toLowerCase(), value);
        return this;
    }

    public MockResponse addHeader(String header, long value) {
        return addHeader(header, Long.toString(value));
    }

    public MockResponse removeHeader(String header) {
        headers.remove(header.toLowerCase());
        return this;
    }

    /**
     * Returns an input stream containing the raw HTTP payload.
     */
    public byte[] getBody() {
        return body;
    }

    public MockResponse setBody(byte[] body) {
        addHeader("Content-Length", body.length);
        this.body = body;
        return this;
    }

    public MockResponse setBody(String body) {
        try {
            return setBody(body.getBytes(ASCII));
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError();
        }
    }

    public MockResponse setChunkedBody(byte[] body, int maxChunkSize) throws IOException {
        addHeader("Transfer-encoding", "chunked");

        ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
        int pos = 0;
        while (pos < body.length) {
            int chunkSize = Math.min(body.length - pos, maxChunkSize);
            bytesOut.write(Integer.toHexString(chunkSize).getBytes(ASCII));
            bytesOut.write("\r\n".getBytes(ASCII));
            bytesOut.write(body, pos, chunkSize);
            bytesOut.write("\r\n".getBytes(ASCII));
            pos += chunkSize;
        }
        bytesOut.write("0\r\n".getBytes(ASCII));
        this.body = bytesOut.toByteArray();
        return this;
    }

    public MockResponse setChunkedBody(String body, int maxChunkSize) throws IOException {
        return setChunkedBody(body.getBytes(ASCII), maxChunkSize);
    }

    @Override public String toString() {
        return status;
    }

    public boolean shouldCloseConnectionAfter() {
        return closeConnectionAfter;
    }

    public MockResponse setCloseConnectionAfter(boolean closeConnectionAfter) {
        this.closeConnectionAfter = closeConnectionAfter;
        return this;
    }

    public int getNumPackets() {
        return numPackets;
    }

    public MockResponse setNumPackets(int numPackets) {
        this.numPackets = numPackets;
        return this;
    }

}
