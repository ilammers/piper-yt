package io.github.piper_yt.piperyt.net;

import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.CookieJar;
import okhttp3.Dns;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Every outbound connection the app makes: extractor requests, thumbnails, SponsorBlock, and
 * (through {@link ProxyBridge}) mpv's video streams. All of it follows {@link NetworkSettings}.
 *
 * <p>No cookie jar: cookies YouTube sets are dropped. The only cookies sent are the ones the
 * extractor adds per request (the shared "reject all" consent value, and a mix's cookie for
 * that mix's next page), so nothing identifying survives a request or reaches the disk.
 */
public final class Network {
    /** The User-Agent NewPipe sends, so requests blend in with the rest of the NewPipe family. */
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofSeconds(30))
            .proxySelector(new SettingsProxySelector())
            .dns(Network::lookup)
            .cookieJar(CookieJar.NO_COOKIES)
            .eventListener(new LoggingListener())
            .build();

    private static ProxyBridge bridge;
    private static ProxyBridge ytDlpBridge;

    private Network() {
    }

    /** The one HTTP client; everything that talks to the network uses it. */
    public static OkHttpClient client() {
        return CLIENT;
    }

    /**
     * Closes idle pooled connections, e.g. after the route or the exit changed, so the next
     * requests don't reuse a connection (and TLS session) opened the old way.
     */
    public static void resetConnections() {
        CLIENT.connectionPool().evictAll();
    }

    static void settingsChanged() {
        resetConnections();
    }

    /**
     * The HTTP proxy mpv uses: a local bridge that sends its connections the same way as
     * everything else. Started on first use, it runs until the app exits.
     */
    public static synchronized String mpvProxyUrl() throws IOException {
        if (bridge == null) {
            bridge = ProxyBridge.start("mpv");
        }
        return "http://127.0.0.1:" + bridge.port();
    }

    /**
     * Arguments every yt-dlp run starts with (see {@code service.YtDlp}): no cookies, no cache,
     * no config files that could set another proxy, and a local bridge like mpv's as the
     * proxy. The bridge sends each connection the app's way (with SOCKS, hostnames unresolved,
     * so the proxy does the DNS lookups) and puts it in the connection log.
     */
    public static List<String> ytDlpArgs() throws IOException {
        // YouTube now needs a JS runtime to pass its bot check. yt-dlp only looks for deno
        // unless told otherwise, so also allow node, which most distros ship.
        return List.of("--ignore-config", "--no-cookies", "--no-cache-dir",
                "--js-runtimes", "deno", "--js-runtimes", "node",
                "--proxy", ytDlpProxyUrl());
    }

    private static synchronized String ytDlpProxyUrl() throws IOException {
        if (ytDlpBridge == null) {
            ytDlpBridge = ProxyBridge.start("yt-dlp");
        }
        return "http://127.0.0.1:" + ytDlpBridge.port();
    }

    /** "socks5 10.64.0.1:1080" or "direct", for logs and messages. */
    public static String describe(final Proxy proxy) {
        if (proxy == null || proxy.type() == Proxy.Type.DIRECT) {
            return "direct";
        }
        final SocketAddress address = proxy.address();
        final String where = address instanceof InetSocketAddress a
                ? a.getHostString() + ":" + a.getPort() : String.valueOf(address);
        return (proxy.type() == Proxy.Type.SOCKS ? "socks5 " : "http ") + where;
    }

    /** Logs one outbound connection when connection logging is on. */
    static void log(final String what, final String host, final Proxy proxy) {
        if (NetworkSettings.logConnections()) {
            final boolean proxied = proxy != null && proxy.type() != Proxy.Type.DIRECT;
            System.err.printf("[net %s] %-8s %s %s %s%n",
                    LocalTime.now().truncatedTo(ChronoUnit.MILLIS), what, host,
                    proxied ? "via" : "NOT PROXIED,", describe(proxy));
        }
    }

    /**
     * OkHttp hands hostnames to a SOCKS proxy unresolved, so a lookup while the SOCKS route is
     * on would mean a request about to bypass it. Refuse instead of leaking the DNS query.
     */
    private static List<InetAddress> lookup(final String hostname)
            throws UnknownHostException {
        if (NetworkSettings.route() == NetworkSettings.Route.SOCKS5) {
            throw new UnknownHostException("Refusing local DNS lookup of " + hostname
                    + " while the SOCKS5 route is on");
        }
        return Dns.SYSTEM.lookup(hostname);
    }

    /** Picks the proxy per connection, so a settings change applies without a restart. */
    private static final class SettingsProxySelector extends ProxySelector {
        @Override
        public List<Proxy> select(final URI uri) {
            return List.of(NetworkSettings.proxy());
        }

        @Override
        public void connectFailed(final URI uri, final SocketAddress address,
                                  final IOException e) {
            // Never fall back to another route
        }
    }

    /** Logs each request with the route its connection actually took. */
    private static final class LoggingListener extends EventListener {
        @Override
        public void connectionAcquired(final Call call, final Connection connection) {
            log(call.request().method(), call.request().url().host(),
                    connection.route().proxy());
        }

        @Override
        public void connectFailed(final Call call, final InetSocketAddress address,
                                  final Proxy proxy, final Protocol protocol,
                                  final IOException e) {
            // "Socket closed" is the losing attempt of OkHttp's IPv4/IPv6 race, not a failure
            if (!"Socket closed".equals(e.getMessage())) {
                log("FAILED", call.request().url().host() + " (" + e.getMessage() + ")", proxy);
            }
        }
    }
}
