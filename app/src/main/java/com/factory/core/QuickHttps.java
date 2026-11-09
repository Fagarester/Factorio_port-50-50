package com.factory.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A small HTTPS GET (HTTP/1.0, redirects, Range) that tries all the server's addresses at once and takes the first
 * that answers, as browsers do.
 * HttpURLConnection tries them one by one: on a Wi-Fi with a broken IPv6 the 6 IPv6 addresses of cdn.factorio.com
 * cost 6 × the connect timeout before an IPv4 one (launcher.log: 30 s).
 */
public final class QuickHttps {
    private QuickHttps() {}

    private static final boolean ON_ANDROID = String.valueOf(System.getProperty("java.vm.name")).contains("Dalvik");

    /** An answer: the status, the headers (names in lower case) and the body as a stream; close() ends the connection. */
    public static final class Response implements Closeable {
        public final URL url;
        public final int status;
        public final Map<String, String> headers;
        public final InputStream body;
        private final Socket socket;

        Response(URL url, int status, Map<String, String> headers, InputStream body, Socket socket) {
            this.url = url;
            this.status = status;
            this.headers = headers;
            this.body = body;
            this.socket = socket;
        }

        /** Content-Length, or -1. */
        public long length() {
            try {
                String value = headers.get("content-length");
                return value != null ? Long.parseLong(value.trim()) : -1;
            }
            catch (NumberFormatException e) {
                return -1;
            }
        }

        /** The file's name at the end of the address (after the redirects). */
        public String fileName() {
            String path = url.getPath();
            return path.substring(path.lastIndexOf('/') + 1);
        }

        @Override
        public void close() {
            try {
                socket.close();
            }
            catch (IOException e) {
                // closed anyway
            }
        }
    }

    /** The body of a 200 answer, at most [maxBytes]; IOException otherwise. */
    public static byte[] get(String url, int connectTimeoutMs, int readTimeoutMs, int maxBytes) throws IOException {
        try (Response response = open(url, null, connectTimeoutMs, readTimeoutMs)) {
            if (response.status != 200) throw new IOException("answer "+response.status);
            long length = response.length();
            if (length > maxBytes) throw new IOException("too big: "+length+" bytes");
            ByteArrayOutputStream body = new ByteArrayOutputStream(length > 0 ? (int)length : 1 << 16);
            byte[] buffer = new byte[16384];
            for (int n; (n = response.body.read(buffer)) > 0; ) {
                body.write(buffer, 0, n);
                if (body.size() > maxBytes) throw new IOException("too big");
            }
            if (length >= 0 && body.size() != length) throw new IOException("cut off: "+body.size()+" of "+length+" bytes");
            return body.toByteArray();
        }
    }

    /** A GET with redirects followed (up to 5); [range] is the first byte wanted ("Range"), or null for all. */
    public static Response open(String url, Long range, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        URL address = new URL(url);
        for (int redirects = 0; ; redirects++) {
            Response response = request(address, range, connectTimeoutMs, readTimeoutMs);
            String location = response.headers.get("location");
            if (response.status / 100 != 3 || location == null) return response;
            response.close();
            if (redirects == 5) throw new IOException("too many redirects");
            address = new URL(address, location);
            if (!"https".equals(address.getProtocol())) throw new IOException("not https: "+address.getProtocol());
        }
    }

    private static Response request(URL address, Long range, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        if (!"https".equals(address.getProtocol())) throw new IOException("not https: "+address);
        String host = address.getHost();
        int port = address.getPort() > 0 ? address.getPort() : 443;
        Socket socket = firstConnected(InetAddress.getAllByName(host), port, connectTimeoutMs);
        try {
            socket.setSoTimeout(readTimeoutMs);
            SSLSocket ssl = (SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(socket, host, port, true);
            // the certificate must be for this name, as in HttpsURLConnection
            SSLParameters parameters = ssl.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(parameters);
            ssl.startHandshake();
            // Android's check again (a desktop JDK's default one refuses all: tests run there)
            if (ON_ANDROID && !HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.getSession())) {
                throw new IOException("the certificate isn't for "+host);
            }

            String path = address.getFile().isEmpty() ? "/" : address.getFile();
            // HTTP/1.0: the answer is the plain body up to the end, never in chunks
            OutputStream out = ssl.getOutputStream();
            out.write(("GET "+path+" HTTP/1.0\r\nHost: "+host+"\r\nUser-Agent: Factory\r\nConnection: close\r\n"+
                (range != null ? "Range: bytes="+range+"-\r\n" : "")+"\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = new BufferedInputStream(ssl.getInputStream(), 1 << 16);
            String[] status = readLine(in).split(" ");
            int code;
            try {
                code = status.length >= 2 ? Integer.parseInt(status[1]) : -1;
            }
            catch (NumberFormatException e) {
                code = -1;
            }
            if (code < 0) throw new IOException("not an HTTP answer");
            Map<String, String> headers = new HashMap<>();
            for (String line; !(line = readLine(in)).isEmpty(); ) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            return new Response(address, code, headers, in, ssl);
        }
        catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    /** Connects to every address at once; the first socket wins, the others are closed. */
    private static Socket firstConnected(InetAddress[] all, int port, int timeoutMs) throws IOException {
        final BlockingQueue<Object> results = new ArrayBlockingQueue<>(all.length);
        final List<Thread> threads = new ArrayList<>();
        for (final InetAddress one : all) {
            Thread thread = new Thread(() -> {
                Socket socket = new Socket();
                try {
                    socket.connect(new InetSocketAddress(one, port), timeoutMs);
                    results.add(socket);
                }
                catch (IOException e) {
                    try {
                        socket.close();
                    }
                    catch (IOException ignored) {
                        // not connected
                    }
                    results.add(e);
                }
            }, "quick-https");
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }

        Socket winner = null;
        IOException last = null;
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs + 1000L);
        for (int i = 0; i < all.length && winner == null; i++) {
            Object result;
            try {
                result = results.poll(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (result == null) break;
            if (result instanceof Socket) winner = (Socket)result;
            else last = (IOException)result;
        }
        // the late ones: closed when they come in
        final Socket kept = winner;
        new Thread(() -> {
            for (Thread thread : threads) {
                try {
                    thread.join();
                }
                catch (InterruptedException e) {
                    return;
                }
            }
            for (Object result; (result = results.poll()) != null; ) {
                if (result instanceof Socket && result != kept) {
                    try {
                        ((Socket)result).close();
                    }
                    catch (IOException ignored) {
                        // closing only
                    }
                }
            }
        }, "quick-https-close").start();
        if (winner == null) throw last != null ? last : new IOException("no address answered in "+timeoutMs+" ms");
        return winner;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int c; (c = in.read()) != '\n'; ) {
            if (c < 0) throw new IOException("the answer ended early");
            if (c != '\r') line.append((char)c);
            if (line.length() > 8192) throw new IOException("header line too long");
        }
        return line.toString();
    }
}
