package io.github.piper_yt.piperyt.net;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import org.schabi.newpipe.extractor.downloader.CancellableCall;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The HTTP backend the extractor uses for every request, implemented with OkHttp
 * (which the extractor already depends on for {@link CancellableCall}) on {@link Network}'s
 * client, so it follows the proxy settings.
 */
public final class OkHttpDownloader extends Downloader {
    private static OkHttpDownloader instance;

    private final OkHttpClient client;
    private final OkHttpClient noRedirectClient;

    private OkHttpDownloader() {
        client = Network.client();
        // Shares the client's connection pool, proxy selector and DNS guard
        noRedirectClient = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    public static synchronized OkHttpDownloader getInstance() {
        if (instance == null) {
            instance = new OkHttpDownloader();
        }
        return instance;
    }

    @Override
    public Response execute(final Request request) throws IOException, ReCaptchaException {
        try (okhttp3.Response response = newCall(request).execute()) {
            return convert(request, response);
        }
    }

    @Override
    public CancellableCall executeAsync(final Request request, final AsyncCallback callback) {
        final Call call = newCall(request);
        final CancellableCall cancellable = new CancellableCall(call);
        call.enqueue(new Callback() {
            @Override
            public void onResponse(final Call c, final okhttp3.Response response) {
                try (response) {
                    callback.onSuccess(convert(request, response));
                } catch (final Exception e) {
                    callback.onError(e);
                } finally {
                    cancellable.setFinished();
                }
            }

            @Override
            public void onFailure(final Call c, final IOException e) {
                try {
                    callback.onError(e);
                } finally {
                    cancellable.setFinished();
                }
            }
        });
        return cancellable;
    }

    private Call newCall(final Request request) {
        final byte[] data = request.dataToSend();
        RequestBody body = null;
        if (data != null) {
            body = RequestBody.create(data);
        } else if ("POST".equals(request.httpMethod())) {
            body = RequestBody.create(new byte[0]);
        }

        final okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                .url(request.url())
                .method(request.httpMethod(), body)
                .header("User-Agent", Network.USER_AGENT);

        for (final Map.Entry<String, List<String>> header : request.headers().entrySet()) {
            builder.removeHeader(header.getKey());
            for (final String value : header.getValue()) {
                builder.addHeader(header.getKey(), value);
            }
        }

        final OkHttpClient c = request.followRedirects() ? client : noRedirectClient;
        return c.newCall(builder.build());
    }

    private static Response convert(final Request request, final okhttp3.Response response)
            throws IOException, ReCaptchaException {
        if (response.code() == 429) {
            throw new ReCaptchaException("reCaptcha challenge requested", request.url());
        }

        final ResponseBody body = response.body();
        final byte[] raw = body.bytes();
        final MediaType type = body.contentType();
        final Charset charset = type == null
                ? StandardCharsets.UTF_8 : type.charset(StandardCharsets.UTF_8);

        return new Response(response.code(), response.message(),
                response.headers().toMultimap(), new String(raw, charset), raw,
                response.request().url().toString());
    }
}
