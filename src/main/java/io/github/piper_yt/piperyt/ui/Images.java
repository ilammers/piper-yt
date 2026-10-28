package io.github.piper_yt.piperyt.ui;

import io.github.piper_yt.piperyt.net.Network;
import okhttp3.Request;
import okhttp3.Response;
import org.gnome.gdk.Paintable;
import org.gnome.gdk.Texture;
import org.gnome.gdkpixbuf.InterpType;
import org.gnome.gdkpixbuf.Pixbuf;
import org.gnome.gio.MemoryInputStream;
import org.gnome.gobject.GObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Downloads and caches thumbnails and avatars as GDK textures. */
public final class Images {
    private static final int MAX_CACHED = 400;
    /** No downscaling. */
    public static final int FULL_SIZE = 0;

    private static final Map<String, Texture> CACHE =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, Texture> eldest) {
                    return size() > MAX_CACHED;
                }
            };

    private Images() {
    }

    /** {@link #load(String, int, GObject, Consumer)} at full size. */
    public static void load(final String url, final GObject target,
                            final Consumer<Paintable> setter) {
        load(url, FULL_SIZE, target, setter);
    }

    /**
     * Loads {@code url} and passes the texture to {@code setter} on the main thread.
     *
     * <p>{@code target} is the widget {@code setter} writes to. It's kept alive until then:
     * java-gi frees a widget as soon as it's unparented (e.g. a page re-rendering its cards),
     * and an image arriving afterwards crashed the app in gtk_picture_set_paintable.
     *
     * <p>Images wider than {@code maxWidth} pixels (unless {@link #FULL_SIZE}) are scaled down
     * off the main thread. YouTube's 1280px thumbnails in small cards otherwise cost a large
     * texture upload each on the main thread, where video frames are drawn too.
     */
    public static void load(final String url, final int maxWidth, final GObject target,
                            final Consumer<Paintable> setter) {
        if (url == null || url.isBlank()) {
            return;
        }
        final String key = maxWidth > 0 ? maxWidth + " " + url : url;
        final Texture cached;
        synchronized (CACHE) {
            cached = CACHE.get(key);
        }
        if (cached != null) {
            setter.accept(cached);
            return;
        }
        target.ref();
        Ui.background(() -> {
            Texture texture = null;
            final Request request = new Request.Builder().url(url)
                    .header("User-Agent", Network.USER_AGENT).build();
            try (Response response = Network.client().newCall(request).execute()) {
                if (response.isSuccessful()) {
                    // Textures are immutable, so decoding off the main thread is safe
                    texture = decode(response.body().bytes(), maxWidth);
                    synchronized (CACHE) {
                        CACHE.put(key, texture);
                    }
                }
            } catch (final Exception e) {
                System.err.println("Image load failed: " + url + ": " + e.getMessage());
            }
            final Texture result = texture;
            // Released on the main thread: the last unref may finalize the widget
            Ui.runOnMain(() -> {
                try {
                    if (result != null) {
                        setter.accept(result);
                    }
                } finally {
                    target.unref();
                }
            });
        });
    }

    @SuppressWarnings("deprecation") // Texture.forPixbuf: still the simplest scaled upload
    private static Texture decode(final byte[] bytes, final int maxWidth) throws Exception {
        if (maxWidth <= 0) {
            return Texture.fromBytes(bytes);
        }
        final Pixbuf pixbuf = Pixbuf.fromStream(MemoryInputStream.fromBytes(bytes), null);
        final int width = pixbuf.getWidth();
        if (width <= maxWidth) {
            return Texture.forPixbuf(pixbuf);
        }
        final int height = Math.max(1, (int) ((long) pixbuf.getHeight() * maxWidth / width));
        return Texture.forPixbuf(pixbuf.scaleSimple(maxWidth, height, InterpType.BILINEAR));
    }
}
