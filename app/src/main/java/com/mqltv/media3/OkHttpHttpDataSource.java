package com.mqltv.media3;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.HttpUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * OkHttp-backed {@link HttpDataSource} for API 19 STBs.
 * Uses {@link com.mqltv.NetworkClient#getClient()} (Conscrypt + TLS 1.2) without
 * depending on media3-datasource-okhttp (minSdk 21).
 */
@UnstableApi
public final class OkHttpHttpDataSource extends BaseDataSource implements HttpDataSource {

    public static final class Factory extends HttpDataSource.BaseFactory {
        private final Call.Factory callFactory;
        @Nullable private final String userAgent;

        public Factory(Call.Factory callFactory, @Nullable String userAgent) {
            this.callFactory = callFactory;
            this.userAgent = userAgent;
        }

        @Override
        protected HttpDataSource createDataSourceInternal(RequestProperties defaultRequestProperties) {
            return new OkHttpHttpDataSource(callFactory, userAgent, defaultRequestProperties);
        }
    }

    private static final String HEADER_RANGE = "Range";
    private static final String HEADER_USER_AGENT = "User-Agent";
    private static final String HEADER_ACCEPT_ENCODING = "Accept-Encoding";

    private final Call.Factory callFactory;
    @Nullable private final String userAgent;
    @Nullable private final RequestProperties defaultRequestProperties;
    private final RequestProperties requestProperties;

    @Nullable private DataSpec dataSpec;
    @Nullable private Response response;
    @Nullable private InputStream responseByteStream;
    private boolean opened;
    private long bytesToRead;
    private long bytesRead;

    private OkHttpHttpDataSource(
            Call.Factory callFactory,
            @Nullable String userAgent,
            @Nullable RequestProperties defaultRequestProperties) {
        super(/* isNetwork= */ true);
        this.callFactory = callFactory;
        this.userAgent = userAgent;
        this.defaultRequestProperties = defaultRequestProperties;
        this.requestProperties = new RequestProperties();
    }

    @Override
    @Nullable
    public Uri getUri() {
        if (response != null) {
            return Uri.parse(response.request().url().toString());
        }
        return dataSpec != null ? dataSpec.uri : null;
    }

    @Override
    public int getResponseCode() {
        return response != null ? response.code() : -1;
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return response != null ? response.headers().toMultimap() : Collections.emptyMap();
    }

    @Override
    public void setRequestProperty(String name, String value) {
        requestProperties.set(name, value);
    }

    @Override
    public void clearRequestProperty(String name) {
        requestProperties.remove(name);
    }

    @Override
    public void clearAllRequestProperties() {
        requestProperties.clear();
    }

    @Override
    public long open(DataSpec dataSpec) throws HttpDataSourceException {
        this.dataSpec = dataSpec;
        bytesRead = 0;
        bytesToRead = 0;
        transferInitializing(dataSpec);

        Request request = makeRequest(dataSpec);
        try {
            response = callFactory.newCall(request).execute();
            ResponseBody body = response.body();
            if (body == null) {
                throw new HttpDataSourceException(
                        "Null response body",
                        dataSpec,
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                        HttpDataSourceException.TYPE_OPEN);
            }
            responseByteStream = body.byteStream();
        } catch (IOException e) {
            throw HttpDataSourceException.createForIOException(
                    e, dataSpec, HttpDataSourceException.TYPE_OPEN);
        }

        int responseCode = response.code();
        if (!response.isSuccessful()) {
            if (responseCode == 416) {
                String contentRange = response.header("Content-Range");
                long documentSize = HttpUtil.getDocumentSize(contentRange);
                if (dataSpec.position == documentSize) {
                    opened = true;
                    transferStarted(dataSpec);
                    return dataSpec.length != C.LENGTH_UNSET ? dataSpec.length : 0;
                }
            }
            byte[] errorBody = readErrorBody();
            Map<String, List<String>> headers = response.headers().toMultimap();
            closeConnectionQuietly();
            IOException cause = responseCode == 416
                    ? new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
                    : null;
            throw new InvalidResponseCodeException(
                    responseCode,
                    response.message(),
                    cause,
                    headers,
                    dataSpec,
                    errorBody);
        }

        long bytesToSkip = responseCode == 200 && dataSpec.position != 0 ? dataSpec.position : 0;
        if (dataSpec.length != C.LENGTH_UNSET) {
            bytesToRead = dataSpec.length;
        } else {
            ResponseBody body = response.body();
            long contentLength = body != null ? body.contentLength() : -1;
            bytesToRead = contentLength != -1 ? (contentLength - bytesToSkip) : C.LENGTH_UNSET;
        }

        opened = true;
        transferStarted(dataSpec);
        try {
            skipFully(bytesToSkip, dataSpec);
        } catch (HttpDataSourceException e) {
            closeConnectionQuietly();
            throw e;
        }
        return bytesToRead;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws HttpDataSourceException {
        try {
            return readInternal(buffer, offset, length);
        } catch (IOException e) {
            throw HttpDataSourceException.createForIOException(
                    e, dataSpec, HttpDataSourceException.TYPE_READ);
        }
    }

    @Override
    public void close() {
        if (opened) {
            opened = false;
            transferEnded();
            closeConnectionQuietly();
        }
        response = null;
        dataSpec = null;
    }

    private Request makeRequest(DataSpec dataSpec) throws HttpDataSourceException {
        HttpUrl url = HttpUrl.parse(dataSpec.uri.toString());
        if (url == null) {
            throw new HttpDataSourceException(
                    "Malformed URL",
                    dataSpec,
                    PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
                    HttpDataSourceException.TYPE_OPEN);
        }

        Request.Builder builder = new Request.Builder().url(url);
        Map<String, String> headers = new HashMap<>();
        if (defaultRequestProperties != null) {
            headers.putAll(defaultRequestProperties.getSnapshot());
        }
        headers.putAll(requestProperties.getSnapshot());
        headers.putAll(dataSpec.httpRequestHeaders);

        for (Map.Entry<String, String> header : headers.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }

        String rangeHeader = HttpUtil.buildRangeRequestHeader(dataSpec.position, dataSpec.length);
        if (rangeHeader != null) {
            builder.addHeader(HEADER_RANGE, rangeHeader);
        }
        if (userAgent != null && !headers.containsKey(HEADER_USER_AGENT)
                && !headers.containsKey("user-agent")) {
            builder.addHeader(HEADER_USER_AGENT, userAgent);
        }
        if (!dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)) {
            builder.addHeader(HEADER_ACCEPT_ENCODING, "identity");
        }

        RequestBody requestBody = null;
        if (dataSpec.httpBody != null) {
            requestBody = RequestBody.create(null, dataSpec.httpBody);
        } else if (dataSpec.httpMethod == DataSpec.HTTP_METHOD_POST) {
            requestBody = RequestBody.create(null, Util.EMPTY_BYTE_ARRAY);
        }
        builder.method(dataSpec.getHttpMethodString(), requestBody);
        return builder.build();
    }

    private void skipFully(long bytesToSkip, DataSpec dataSpec) throws HttpDataSourceException {
        if (bytesToSkip == 0) return;
        byte[] skipBuffer = new byte[4096];
        try {
            while (bytesToSkip > 0) {
                int readLength = (int) Math.min(bytesToSkip, skipBuffer.length);
                int read = responseByteStream.read(skipBuffer, 0, readLength);
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException();
                }
                if (read == -1) {
                    throw new HttpDataSourceException(
                            dataSpec,
                            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                            HttpDataSourceException.TYPE_OPEN);
                }
                bytesToSkip -= read;
                bytesTransferred(read);
            }
        } catch (IOException e) {
            if (e instanceof HttpDataSourceException) {
                throw (HttpDataSourceException) e;
            }
            throw new HttpDataSourceException(
                    dataSpec,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    HttpDataSourceException.TYPE_OPEN);
        }
    }

    private int readInternal(byte[] buffer, int offset, int readLength) throws IOException {
        if (readLength == 0) return 0;
        if (bytesToRead != C.LENGTH_UNSET) {
            long bytesRemaining = bytesToRead - bytesRead;
            if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT;
            readLength = (int) Math.min(readLength, bytesRemaining);
        }
        int read = responseByteStream.read(buffer, offset, readLength);
        if (read == -1) return C.RESULT_END_OF_INPUT;
        bytesRead += read;
        bytesTransferred(read);
        return read;
    }

    private byte[] readErrorBody() {
        if (responseByteStream == null) return Util.EMPTY_BYTE_ARRAY;
        try {
            byte[] buf = new byte[4096];
            int total = 0;
            byte[] out = Util.EMPTY_BYTE_ARRAY;
            int n;
            while ((n = responseByteStream.read(buf)) != -1) {
                byte[] next = new byte[total + n];
                if (total > 0) System.arraycopy(out, 0, next, 0, total);
                System.arraycopy(buf, 0, next, total, n);
                out = next;
                total += n;
                if (total > 64 * 1024) break;
            }
            return out;
        } catch (IOException ignored) {
            return Util.EMPTY_BYTE_ARRAY;
        }
    }

    private void closeConnectionQuietly() {
        if (response != null) {
            ResponseBody body = response.body();
            if (body != null) {
                try {
                    body.close();
                } catch (Exception ignored) {
                }
            }
        }
        responseByteStream = null;
    }
}
