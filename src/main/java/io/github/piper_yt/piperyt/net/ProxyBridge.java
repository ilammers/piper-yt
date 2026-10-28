package io.github.piper_yt.piperyt.net;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * A local HTTP proxy for mpv, which only speaks HTTP proxies, never SOCKS, and for yt-dlp (one
 * bridge each, so the connection log can say whose a connection is). Each connection
 * goes out the way {@link NetworkSettings} says, like the app's own requests. That matters
 * beyond privacy: YouTube ties stream URLs to the IP that extracted them, so mpv has to
 * fetch from the same exit.
 *
 * <p>Handles {@code CONNECT} (HTTPS, which is all YouTube uses) and plain HTTP requests in
 * absolute form, which are forwarded unchanged to the requested host. Listens on loopback only.
 */
final class ProxyBridge {
    private static final int MAX_HEADER = 16 * 1024;
    private static final int TIMEOUT_MS = 15_000;
    private static final byte[] ESTABLISHED =
            "HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BAD_GATEWAY = ("HTTP/1.1 502 Bad Gateway\r\n"
            + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BAD_REQUEST = ("HTTP/1.1 400 Bad Request\r\n"
            + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);

    private final ServerSocket server;
    /** Who connects through it, for the connection log ("mpv", "yt-dlp"). */
    private final String label;

    private ProxyBridge(final ServerSocket server, final String label) {
        this.server = server;
        this.label = label;
    }

    static ProxyBridge start(final String label) throws IOException {
        final ProxyBridge bridge = new ProxyBridge(
                new ServerSocket(0, 50, InetAddress.getLoopbackAddress()), label);
        Thread.ofPlatform().daemon().name("proxy-bridge-" + label).start(bridge::acceptLoop);
        return bridge;
    }

    int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                final Socket client = server.accept();
                Thread.ofVirtual().start(() -> handle(client, label));
            } catch (final IOException e) {
                System.err.println("Proxy bridge: " + e.getMessage());
            }
        }
    }

    private static void handle(final Socket client, final String label) {
        try (client) {
            client.setSoTimeout(TIMEOUT_MS);
            final InputStream in = new BufferedInputStream(client.getInputStream());
            final OutputStream out = client.getOutputStream();
            final byte[] header = readHeader(in);
            if (header == null) {
                out.write(BAD_REQUEST);
                return;
            }
            final String requestLine = new String(header, StandardCharsets.ISO_8859_1)
                    .lines().findFirst().orElse("");
            final String[] parts = requestLine.split(" ");
            if (parts.length != 3) {
                out.write(BAD_REQUEST);
                return;
            }
            final boolean connect = parts[0].equalsIgnoreCase("CONNECT");
            final InetSocketAddress target = connect
                    ? NetworkSettings.parseAddress(parts[1]) : absoluteTarget(parts[1]);
            if (target == null) {
                out.write(BAD_REQUEST);
                return;
            }

            final Proxy proxy = NetworkSettings.proxy();
            Network.log(label, target.getHostString() + ":" + target.getPort(), proxy);
            try (Socket upstream = new Socket(proxy)) {
                try {
                    // Unresolved for SOCKS, so the proxy does the DNS lookup
                    upstream.connect(proxy.type() == Proxy.Type.DIRECT
                            ? new InetSocketAddress(target.getHostString(), target.getPort())
                            : target, TIMEOUT_MS);
                } catch (final IOException e) {
                    Network.log("FAILED", target.getHostString() + " (" + e.getMessage() + ")",
                            proxy);
                    out.write(BAD_GATEWAY);
                    return;
                }
                if (connect) {
                    out.write(ESTABLISHED);
                } else {
                    upstream.getOutputStream().write(header);
                }
                out.flush();
                client.setSoTimeout(0);
                pipe(in, out, client, upstream);
            }
        } catch (final IOException | IllegalArgumentException e) {
            // The client went away or sent garbage; nothing to report
        }
    }

    /** Copies both ways until both sides are done. */
    private static void pipe(final InputStream clientIn, final OutputStream clientOut,
                             final Socket client, final Socket upstream) throws IOException {
        final Thread up = Thread.ofVirtual().start(() -> {
            try {
                clientIn.transferTo(upstream.getOutputStream());
                upstream.shutdownOutput();
            } catch (final IOException ignored) {
                // Closed below
            }
        });
        try {
            upstream.getInputStream().transferTo(clientOut);
            client.shutdownOutput();
        } catch (final IOException ignored) {
            // Either side closed
        }
        try {
            up.join(TIMEOUT_MS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The request headers up to and including the blank line, or null if too long or cut off. */
    private static byte[] readHeader(final InputStream in) throws IOException {
        final ByteArrayOutputStream header = new ByteArrayOutputStream();
        int matched = 0;
        while (header.size() < MAX_HEADER) {
            final int b = in.read();
            if (b < 0) {
                return null;
            }
            header.write(b);
            matched = (b == '\r' && matched % 2 == 0) || (b == '\n' && matched % 2 == 1)
                    ? matched + 1 : (b == '\r' ? 1 : 0);
            if (matched == 4) {
                return header.toByteArray();
            }
        }
        return null;
    }

    /** host:port of an absolute-form {@code http://} request target. */
    private static InetSocketAddress absoluteTarget(final String target) {
        try {
            final URI uri = new URI(target);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                return null;
            }
            return InetSocketAddress.createUnresolved(uri.getHost(),
                    uri.getPort() < 0 ? 80 : uri.getPort());
        } catch (final Exception e) {
            return null;
        }
    }
}
