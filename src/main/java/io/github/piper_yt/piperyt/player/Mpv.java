package io.github.piper_yt.piperyt.player;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Minimal libmpv bindings over the Foreign Function &amp; Memory API: the client API plus the
 * OpenGL render API, which lets mpv draw into a GTK {@code GLArea} instead of its own window.
 * Needs {@code libmpv.so.2} ({@code sudo dnf install mpv-libs}).
 */
public final class Mpv {
    public static final int EVENT_NONE = 0;
    public static final int EVENT_SHUTDOWN = 1;
    public static final int EVENT_END_FILE = 7;
    public static final int EVENT_PROPERTY_CHANGE = 22;

    public static final int FORMAT_NONE = 0;
    public static final int FORMAT_FLAG = 3;
    public static final int FORMAT_DOUBLE = 5;
    private static final int FORMAT_STRING = 1;
    private static final int FORMAT_NODE = 6;
    private static final int FORMAT_NODE_ARRAY = 7;

    public static final int END_FILE_REASON_ERROR = 4;

    private static final int RENDER_PARAM_INVALID = 0;
    private static final int RENDER_PARAM_API_TYPE = 1;
    private static final int RENDER_PARAM_OPENGL_INIT_PARAMS = 2;
    private static final int RENDER_PARAM_OPENGL_FBO = 3;
    private static final int RENDER_PARAM_FLIP_Y = 4;
    private static final int RENDER_PARAM_BLOCK_FOR_TARGET_TIME = 12;

    private static final int LC_NUMERIC = 1;

    private static final int GL_FRAMEBUFFER_BINDING = 0x8CA6;
    private static final int GL_COLOR_BUFFER_BIT = 0x4000;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBMPV = library("libmpv.so.2");
    private static final SymbolLookup LIBEGL = library("libEGL.so.1");
    private static final SymbolLookup LIBGL = library("libGL.so.1");

    private static final MethodHandle CREATE = mpv("mpv_create", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle INITIALIZE =
            mpv("mpv_initialize", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle SET_OPTION_STRING = mpv("mpv_set_option_string",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle SET_PROPERTY_STRING = mpv("mpv_set_property_string",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle SET_PROPERTY = mpv("mpv_set_property",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle GET_PROPERTY_STRING = mpv("mpv_get_property_string",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle FREE = mpv("mpv_free", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle COMMAND =
            mpv("mpv_command", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle OBSERVE_PROPERTY = mpv("mpv_observe_property",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT));
    private static final MethodHandle WAIT_EVENT =
            mpv("mpv_wait_event", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_DOUBLE));
    private static final MethodHandle WAKEUP = mpv("mpv_wakeup", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle TERMINATE_DESTROY =
            mpv("mpv_terminate_destroy", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle ERROR_STRING =
            mpv("mpv_error_string", FunctionDescriptor.of(ADDRESS, JAVA_INT));
    private static final MethodHandle RENDER_CREATE = mpv("mpv_render_context_create",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle RENDER_SET_UPDATE_CALLBACK =
            mpv("mpv_render_context_set_update_callback",
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle RENDER_UPDATE =
            mpv("mpv_render_context_update", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle RENDER =
            mpv("mpv_render_context_render", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle RENDER_FREE =
            mpv("mpv_render_context_free", FunctionDescriptor.ofVoid(ADDRESS));

    private static final MethodHandle EGL_GET_PROC_ADDRESS =
            find(LIBEGL, "eglGetProcAddress", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle GLX_GET_PROC_ADDRESS =
            find(LIBGL, "glXGetProcAddressARB", FunctionDescriptor.of(ADDRESS, ADDRESS));

    private static final MethodHandle SETLOCALE = find(LINKER.defaultLookup(), "setlocale",
            FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS));

    private static MemorySegment getProcAddressStub;
    private static MethodHandle glGetIntegerv;
    private static MethodHandle glClearColor;
    private static MethodHandle glClear;

    private final MemorySegment handle;

    /** Whether libmpv and an OpenGL loader could be found. */
    public static boolean isAvailable() {
        return LIBMPV != null && (EGL_GET_PROC_ADDRESS != null || GLX_GET_PROC_ADDRESS != null);
    }

    public Mpv() {
        // mpv refuses to start unless numbers are formatted the C way; GTK sets the user's locale
        try (Arena arena = Arena.ofConfined()) {
            call(SETLOCALE, LC_NUMERIC, arena.allocateFrom("C"));
        }
        handle = (MemorySegment) call(CREATE);
        if (handle.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("Couldn't create the mpv player");
        }
    }

    /** One event from {@link #waitEvent}. {@code value} is the new value of an observed property. */
    public record Event(int id, long userData, Object value, int endReason, int endError) {
    }

    public void setOption(final String name, final String value) {
        try (Arena arena = Arena.ofConfined()) {
            check(call(SET_OPTION_STRING, handle, arena.allocateFrom(name),
                    arena.allocateFrom(value)), name);
        }
    }

    public void initialize() {
        check(call(INITIALIZE, handle), "initialize");
    }

    public void setProperty(final String name, final String value) {
        try (Arena arena = Arena.ofConfined()) {
            check(call(SET_PROPERTY_STRING, handle, arena.allocateFrom(name),
                    arena.allocateFrom(value)), name);
        }
    }

    /**
     * Sets a list option (e.g. {@code audio-files}) as a node array, which avoids mpv's string
     * parsing: path lists are split on ':' and ',', and stream URLs contain both.
     */
    public void setProperty(final String name, final List<String> values) {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment items = arena.allocate(16L * Math.max(1, values.size()), 8);
            for (int i = 0; i < values.size(); i++) {
                items.set(ADDRESS, 16L * i, arena.allocateFrom(values.get(i)));
                items.set(JAVA_INT, 16L * i + 8, FORMAT_STRING);
            }
            // mpv_node_list { int num; mpv_node *values; char **keys; }
            final MemorySegment list = arena.allocate(24, 8);
            list.set(JAVA_INT, 0, values.size());
            list.set(ADDRESS, 8, items);
            list.set(ADDRESS, 16, MemorySegment.NULL);
            // mpv_node { union u; mpv_format format; }
            final MemorySegment node = arena.allocate(16, 8);
            node.set(ADDRESS, 0, list);
            node.set(JAVA_INT, 8, FORMAT_NODE_ARRAY);
            check(call(SET_PROPERTY, handle, arena.allocateFrom(name), FORMAT_NODE, node), name);
        }
    }

    /** Returns a property as a string, or null when it is unavailable. */
    public String getProperty(final String name) {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment value =
                    (MemorySegment) call(GET_PROPERTY_STRING, handle, arena.allocateFrom(name));
            if (value.equals(MemorySegment.NULL)) {
                return null;
            }
            try {
                return value.reinterpret(Long.MAX_VALUE).getString(0);
            } finally {
                call(FREE, value);
            }
        }
    }

    public void command(final String... args) {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment argv = arena.allocate(ADDRESS, args.length + 1);
            for (int i = 0; i < args.length; i++) {
                argv.setAtIndex(ADDRESS, i, arena.allocateFrom(args[i]));
            }
            argv.setAtIndex(ADDRESS, args.length, MemorySegment.NULL);
            check(call(COMMAND, handle, argv), args[0]);
        }
    }

    public void observe(final long userData, final String name, final int format) {
        try (Arena arena = Arena.ofConfined()) {
            check(call(OBSERVE_PROPERTY, handle, userData, arena.allocateFrom(name), format), name);
        }
    }

    /** Blocks for the next event; only one thread may call this. */
    public Event waitEvent(final double timeoutSeconds) {
        final MemorySegment event = ((MemorySegment) call(WAIT_EVENT, handle, timeoutSeconds))
                .reinterpret(24);
        final int id = event.get(JAVA_INT, 0);
        final long userData = event.get(JAVA_LONG, 8);
        final MemorySegment data = event.get(ADDRESS, 16);
        if (id == EVENT_PROPERTY_CHANGE) {
            final MemorySegment property = data.reinterpret(24);
            final int format = property.get(JAVA_INT, 8);
            final MemorySegment value = property.get(ADDRESS, 16);
            final Object parsed = switch (format) {
                case FORMAT_FLAG -> value.reinterpret(4).get(JAVA_INT, 0) != 0;
                case FORMAT_DOUBLE -> value.reinterpret(8).get(JAVA_DOUBLE, 0);
                default -> null;
            };
            return new Event(id, userData, parsed, 0, 0);
        }
        if (id == EVENT_END_FILE) {
            final MemorySegment endFile = data.reinterpret(8);
            return new Event(id, userData, null, endFile.get(JAVA_INT, 0), endFile.get(JAVA_INT, 4));
        }
        return new Event(id, userData, null, 0, 0);
    }

    /** Makes a blocked {@link #waitEvent} return. Safe to call from any thread. */
    public void wakeup() {
        call(WAKEUP, handle);
    }

    /** Destroys the player. Free any render context and stop the event thread first. */
    public void destroy() {
        call(TERMINATE_DESTROY, handle);
    }

    public static String errorString(final int error) {
        return ((MemorySegment) call(ERROR_STRING, error)).reinterpret(256).getString(0);
    }

    /**
     * Creates an OpenGL render context. Call with the target GL context current.
     * {@code onUpdate} runs on an mpv thread whenever a new frame should be drawn; it must not
     * call back into mpv.
     */
    public GlRender createGlRender(final Runnable onUpdate) {
        return new GlRender(onUpdate);
    }

    /** Draws mpv's video and on-screen controller into the currently bound framebuffer. */
    public final class GlRender {
        private final Arena arena = Arena.ofShared();
        private final MemorySegment context;
        private final MemorySegment renderParams;
        private final MemorySegment fbo;
        private final MemorySegment fboBinding;

        private GlRender(final Runnable onUpdate) {
            initGl();
            final MemorySegment initParams = arena.allocate(16, 8);
            initParams.set(ADDRESS, 0, getProcAddressStub);
            initParams.set(ADDRESS, 8, MemorySegment.NULL);

            final MemorySegment params = arena.allocate(16 * 3, 8);
            param(params, 0, RENDER_PARAM_API_TYPE, arena.allocateFrom("opengl"));
            param(params, 1, RENDER_PARAM_OPENGL_INIT_PARAMS, initParams);
            param(params, 2, RENDER_PARAM_INVALID, MemorySegment.NULL);

            final MemorySegment out = arena.allocate(ADDRESS);
            check(call(RENDER_CREATE, out, handle, params), "render context");
            context = out.get(ADDRESS, 0);

            final Runnable safeUpdate = () -> {
                try {
                    onUpdate.run();
                } catch (final Throwable t) {
                    t.printStackTrace();
                }
            };
            try {
                final MethodHandle run = MethodHandles.lookup()
                        .findVirtual(Runnable.class, "run", MethodType.methodType(void.class))
                        .bindTo(safeUpdate);
                final MemorySegment stub = LINKER.upcallStub(
                        MethodHandles.dropArguments(run, 0, MemorySegment.class),
                        FunctionDescriptor.ofVoid(ADDRESS), arena);
                call(RENDER_SET_UPDATE_CALLBACK, context, stub, MemorySegment.NULL);
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }

            fbo = arena.allocate(16, 4);
            fboBinding = arena.allocate(JAVA_INT);
            final MemorySegment flipY = arena.allocate(JAVA_INT);
            flipY.set(JAVA_INT, 0, 1);
            // Rendering runs in GTK's draw on the main thread: never wait there for a frame's
            // display time (up to video-timing-offset, 50 ms by default), or the whole UI
            // stalls and GTK misses vblanks. PlayerView also sets video-timing-offset to 0,
            // so mpv asks for each frame when it's due instead of early.
            final MemorySegment noBlock = arena.allocate(JAVA_INT);
            noBlock.set(JAVA_INT, 0, 0);
            renderParams = arena.allocate(16 * 4, 8);
            param(renderParams, 0, RENDER_PARAM_OPENGL_FBO, fbo);
            param(renderParams, 1, RENDER_PARAM_FLIP_Y, flipY);
            param(renderParams, 2, RENDER_PARAM_BLOCK_FOR_TARGET_TIME, noBlock);
            param(renderParams, 3, RENDER_PARAM_INVALID, MemorySegment.NULL);
        }

        /**
         * Draws the current frame. Runs once per frame on the main thread, so it calls the
         * handles with invokeExact: invokeWithArguments boxes and allocates on every call and
         * stays slow until the JIT warms up, which made the first minute of playback stutter.
         */
        public void render(final int width, final int height) {
            try {
                glGetIntegerv.invokeExact(GL_FRAMEBUFFER_BINDING, fboBinding);
                // mpv_opengl_fbo { int fbo; int w; int h; int internal_format; }
                fbo.set(JAVA_INT, 0, fboBinding.get(JAVA_INT, 0));
                fbo.set(JAVA_INT, 4, width);
                fbo.set(JAVA_INT, 8, height);
                fbo.set(JAVA_INT, 12, 0);
                final long flags = (long) RENDER_UPDATE.invokeExact(context);
                final int result = (int) RENDER.invokeExact(context, renderParams);
            } catch (final Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        /** Frees the context. Call with its GL context current. */
        public void free() {
            call(RENDER_FREE, context);
            arena.close();
        }
    }

    /** Fills the current framebuffer with black, for when there is nothing to render. */
    public static void clearGl() {
        initGl();
        call(glClearColor, 0f, 0f, 0f, 1f);
        call(glClear, GL_COLOR_BUFFER_BIT);
    }

    private static synchronized void initGl() {
        if (getProcAddressStub != null) {
            return;
        }
        try {
            final MethodHandle getProc = MethodHandles.lookup().findStatic(Mpv.class,
                    "getProcAddress", MethodType.methodType(MemorySegment.class,
                            MemorySegment.class, MemorySegment.class));
            getProcAddressStub = LINKER.upcallStub(getProc,
                    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS), Arena.global());
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        glGetIntegerv = glFunction("glGetIntegerv", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
        glClearColor = glFunction("glClearColor",
                FunctionDescriptor.ofVoid(JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
        glClear = glFunction("glClear", FunctionDescriptor.ofVoid(JAVA_INT));
    }

    /** {@code get_proc_address} for mpv: GTK uses EGL on both Wayland and X11 by default. */
    private static MemorySegment getProcAddress(final MemorySegment ctx, final MemorySegment name) {
        try {
            if (EGL_GET_PROC_ADDRESS != null) {
                final MemorySegment address = (MemorySegment) EGL_GET_PROC_ADDRESS.invokeExact(name);
                if (!address.equals(MemorySegment.NULL)) {
                    return address;
                }
            }
            if (GLX_GET_PROC_ADDRESS != null) {
                return (MemorySegment) GLX_GET_PROC_ADDRESS.invokeExact(name);
            }
        } catch (final Throwable t) {
            t.printStackTrace();
        }
        return MemorySegment.NULL;
    }

    private static MethodHandle glFunction(final String name, final FunctionDescriptor descriptor) {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment address =
                    getProcAddress(MemorySegment.NULL, arena.allocateFrom(name));
            if (address.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("OpenGL function not found: " + name);
            }
            return LINKER.downcallHandle(address, descriptor);
        }
    }

    private static void param(final MemorySegment params, final int index, final int type,
                              final MemorySegment data) {
        // mpv_render_param { int type; void *data; }
        params.set(JAVA_INT, 16L * index, type);
        params.set(ADDRESS, 16L * index + 8, data);
    }

    private static void check(final Object result, final String what) {
        final int error = (int) result;
        if (error < 0) {
            throw new IllegalStateException("mpv " + what + ": " + errorString(error));
        }
    }

    private static Object call(final MethodHandle function, final Object... args) {
        if (function == null) {
            throw new IllegalStateException("libmpv is not installed");
        }
        try {
            return function.invokeWithArguments(args);
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static SymbolLookup library(final String name) {
        try {
            return SymbolLookup.libraryLookup(name, Arena.global());
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private static MethodHandle mpv(final String name, final FunctionDescriptor descriptor) {
        return find(LIBMPV, name, descriptor);
    }

    private static MethodHandle find(final SymbolLookup library, final String name,
                                     final FunctionDescriptor descriptor) {
        if (library == null) {
            return null;
        }
        return library.find(name)
                .map(address -> LINKER.downcallHandle(address, descriptor))
                .orElse(null);
    }
}
