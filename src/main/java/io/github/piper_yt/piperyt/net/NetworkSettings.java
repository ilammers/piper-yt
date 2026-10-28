package io.github.piper_yt.piperyt.net;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.prefs.Preferences;

/**
 * The network privacy settings, stored with {@code java.util.prefs}. The only source of truth
 * for how traffic leaves the app: {@link Network} reads it for every new connection.
 */
public final class NetworkSettings {
    /** Mullvad's SOCKS5 proxy, reachable only through its WireGuard tunnel. */
    public static final String DEFAULT_SOCKS = "10.64.0.1:1080";
    public static final int DEFAULT_RETRIES = 3;
    public static final int MAX_RETRIES = 6;

    private static final Preferences PREFS = Preferences.userNodeForPackage(NetworkSettings.class);
    private static final String ROUTE = "route";
    private static final String SOCKS = "socks";
    private static final String RETRIES = "botRetries";
    private static final String ROTATE = "rotateExit";
    private static final String LOCATIONS = "rotateLocations";
    private static final String DEBUG = "logConnections";
    private static final String ROTATE_BATCHES = "rotateBetweenBatches";

    /** How traffic leaves the app. */
    public enum Route {
        /**
         * Directly, relying on the VPN tunnel being the default route. The default: Mullvad's
         * own kill switch blocks traffic while the tunnel is down, and its WireGuard exits
         * hit fewer bot checks than its SOCKS5 exits.
         */
        SYSTEM("System route"),
        /**
         * Through a SOCKS5 proxy. Fails closed: if the proxy is unreachable nothing is sent,
         * and hostnames are resolved by the proxy, never locally.
         */
        SOCKS5("SOCKS5 proxy");

        public final String label;

        Route(final String label) {
            this.label = label;
        }
    }

    private NetworkSettings() {
    }

    public static Route route() {
        try {
            return Route.valueOf(PREFS.get(ROUTE, Route.SYSTEM.name()));
        } catch (final IllegalArgumentException e) {
            return Route.SYSTEM;
        }
    }

    public static void setRoute(final Route route) {
        PREFS.put(ROUTE, route.name());
        Network.settingsChanged();
    }

    /** The SOCKS5 proxy as {@code host:port}. */
    public static String socksAddress() {
        return PREFS.get(SOCKS, DEFAULT_SOCKS);
    }

    /** Stores {@code host:port}; throws for anything that doesn't parse. */
    public static void setSocksAddress(final String address) {
        parseAddress(address);
        PREFS.put(SOCKS, address.strip());
        Network.settingsChanged();
    }

    /** The proxy new connections use: SOCKS5 or {@link Proxy#NO_PROXY}. */
    public static Proxy proxy() {
        if (route() == Route.SYSTEM) {
            return Proxy.NO_PROXY;
        }
        final InetSocketAddress address = parseAddress(socksAddress());
        // The proxy's own address is resolved here; destinations are resolved by the proxy
        return new Proxy(Proxy.Type.SOCKS,
                new InetSocketAddress(address.getHostString(), address.getPort()));
    }

    /** How often a request that hit a bot check is retried. */
    public static int botRetries() {
        return Math.clamp(PREFS.getInt(RETRIES, DEFAULT_RETRIES), 0, MAX_RETRIES);
    }

    public static void setBotRetries(final int retries) {
        PREFS.putInt(RETRIES, Math.clamp(retries, 0, MAX_RETRIES));
    }

    /** Whether a bot check makes Mullvad reconnect through another relay before retrying. */
    public static boolean rotateExit() {
        return PREFS.getBoolean(ROTATE, false);
    }

    public static void setRotateExit(final boolean rotate) {
        PREFS.putBoolean(ROTATE, rotate);
    }

    /**
     * Whether paced background work (feed refresh, homepage) switches the Mullvad exit
     * between batches, so one exit never sees a whole refresh.
     */
    public static boolean rotateBetweenBatches() {
        return PREFS.getBoolean(ROTATE_BATCHES, false);
    }

    public static void setRotateBetweenBatches(final boolean rotate) {
        PREFS.putBoolean(ROTATE_BATCHES, rotate);
    }

    /**
     * Mullvad locations to pick from when rotating, e.g. {@code "se, ch, nl got"}; blank keeps
     * the current location and only changes the relay.
     */
    public static String rotateLocations() {
        return PREFS.get(LOCATIONS, "");
    }

    public static void setRotateLocations(final String locations) {
        PREFS.put(LOCATIONS, locations.strip());
    }

    /** Whether every outbound connection is logged to stderr ({@code PIPER_NET_DEBUG=1} too). */
    public static boolean logConnections() {
        return PREFS.getBoolean(DEBUG, false) || "1".equals(System.getenv("PIPER_NET_DEBUG"));
    }

    public static void setLogConnections(final boolean log) {
        PREFS.putBoolean(DEBUG, log);
    }

    /** Parses {@code host:port} into an unresolved address, so DNS goes through the proxy. */
    static InetSocketAddress parseAddress(final String address) {
        final String s = address == null ? "" : address.strip();
        final int colon = s.lastIndexOf(':');
        if (colon <= 0 || colon == s.length() - 1) {
            throw new IllegalArgumentException("Expected host:port, got \"" + s + "\"");
        }
        final int port;
        try {
            port = Integer.parseInt(s.substring(colon + 1));
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port in \"" + s + "\"");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port in \"" + s + "\"");
        }
        return InetSocketAddress.createUnresolved(s.substring(0, colon), port);
    }
}
