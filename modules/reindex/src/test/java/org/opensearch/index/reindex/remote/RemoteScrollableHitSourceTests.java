/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.index.reindex.remote;

import org.apache.http.ContentTooLongException;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolVersion;
import org.apache.http.StatusLine;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;
import org.opensearch.LegacyESVersion;
import org.opensearch.OpenSearchStatusException;
import org.opensearch.Version;
import org.opensearch.action.bulk.BackoffPolicy;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.client.HeapBufferedAsyncResponseConsumer;
import org.opensearch.client.RestClient;
import org.opensearch.common.io.Streams;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.concurrent.OpenSearchExecutors;
import org.opensearch.core.common.ParsingException;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.core.common.unit.ByteSizeUnit;
import org.opensearch.core.common.unit.ByteSizeValue;
import org.opensearch.core.concurrency.OpenSearchRejectedExecutionException;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.core.util.FileSystemUtils;
import org.opensearch.index.reindex.RejectAwareActionListener;
import org.opensearch.index.reindex.ScrollableHitSource;
import org.opensearch.index.reindex.ScrollableHitSource.Response;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.TestThreadPool;
import org.opensearch.threadpool.ThreadPool;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.opensearch.common.unit.TimeValue.timeValueMillis;
import static org.opensearch.common.unit.TimeValue.timeValueMinutes;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RemoteScrollableHitSourceTests extends OpenSearchTestCase {
    private static final String FAKE_SCROLL_ID = "DnF1ZXJ5VGhlbkZldGNoBQAAAfakescroll";
    private int retries;
    private ThreadPool threadPool;
    private SearchRequest searchRequest;
    private int retriesAllowed;

    private final Queue<ScrollableHitSource.AsyncResponse> responseQueue = new LinkedBlockingQueue<>();

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        final ExecutorService directExecutor = OpenSearchExecutors.newDirectExecutorService();
        threadPool = new TestThreadPool(getTestName()) {
            @Override
            public ExecutorService executor(String name) {
                return directExecutor;
            }

            @Override
            public ScheduledCancellable schedule(Runnable command, TimeValue delay, String name) {
                command.run();
                return null;
            }
        };
        retries = 0;
        searchRequest = new SearchRequest();
        searchRequest.scroll(timeValueMinutes(5));
        searchRequest.source(new SearchSourceBuilder().size(10).version(true).sort("_doc").size(123));
        retriesAllowed = 0;
    }

    @After
    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        terminate(threadPool);
    }

    @After
    public void validateAllConsumed() {
        assertTrue(responseQueue.isEmpty());
    }

    public void testLookupRemoteVersion() throws Exception {
        assertLookupRemoteVersion(LegacyESVersion.fromString("0.20.5"), "main/0_20_5.json");
        assertLookupRemoteVersion(LegacyESVersion.fromString("0.90.13"), "main/0_90_13.json");
        assertLookupRemoteVersion(LegacyESVersion.fromString("1.7.5"), "main/1_7_5.json");
        assertLookupRemoteVersion(LegacyESVersion.fromId(2030399), "main/2_3_3.json");
        // assert for V_5_0_0 (no qualifier) since we no longer consider qualifier in Version since 7
        assertLookupRemoteVersion(LegacyESVersion.fromId(5000099), "main/5_0_0_alpha_3.json");
        // V_5_0_0 since we no longer consider qualifier in Version
        assertLookupRemoteVersion(LegacyESVersion.fromId(5000099), "main/with_unknown_fields.json");
        assertLookupRemoteVersion(Version.fromId(1000099 ^ Version.MASK), "main/OpenSearch_1_0_0.json");
    }

    private void assertLookupRemoteVersion(Version expected, String s) throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        sourceWithMockedRemoteCall(false, ContentType.APPLICATION_JSON, s).lookupRemoteVersion(wrapAsListener(v -> {
            assertEquals(expected, v);
            called.set(true);
        }));
        assertTrue(called.get());
    }

    public void testParseStartOk() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        sourceWithMockedRemoteCall("start_ok.json").doStart(wrapAsListener(r -> {
            assertFalse(r.isTimedOut());
            assertEquals(FAKE_SCROLL_ID, r.getScrollId());
            assertEquals(4, r.getTotalHits());
            assertThat(r.getFailures(), empty());
            assertThat(r.getHits(), hasSize(1));
            assertEquals("test", r.getHits().get(0).getIndex());
            assertEquals("AVToMiC250DjIiBO3yJ_", r.getHits().get(0).getId());
            assertEquals("{\"test\":\"test2\"}", r.getHits().get(0).getSource().utf8ToString());
            assertNull(r.getHits().get(0).getRouting());
            called.set(true);
        }));
        assertTrue(called.get());
    }

    public void testParseScrollOk() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        sourceWithMockedRemoteCall("scroll_ok.json").doStartNextScroll("", timeValueMillis(0), wrapAsListener(r -> {
            assertFalse(r.isTimedOut());
            assertEquals(FAKE_SCROLL_ID, r.getScrollId());
            assertEquals(4, r.getTotalHits());
            assertThat(r.getFailures(), empty());
            assertThat(r.getHits(), hasSize(1));
            assertEquals("test", r.getHits().get(0).getIndex());
            assertEquals("AVToMiDL50DjIiBO3yKA", r.getHits().get(0).getId());
            assertEquals("{\"test\":\"test3\"}", r.getHits().get(0).getSource().utf8ToString());
            assertNull(r.getHits().get(0).getRouting());
            called.set(true);
        }));
        assertTrue(called.get());
    }

    /**
     * Test for parsing _ttl, _timestamp, _routing, and _parent.
     */
    public void testParseScrollFullyLoaded() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        sourceWithMockedRemoteCall("scroll_fully_loaded.json").doStartNextScroll("", timeValueMillis(0), wrapAsListener(r -> {
            assertEquals("AVToMiDL50DjIiBO3yKA", r.getHits().get(0).getId());
            assertEquals("{\"test\":\"test3\"}", r.getHits().get(0).getSource().utf8ToString());
            assertEquals("testrouting", r.getHits().get(0).getRouting());
            called.set(true);
        }));
        assertTrue(called.get());
    }

    /**
     * Test for parsing _ttl, _routing, and _parent. _timestamp isn't available.
     */
    public void testParseScrollFullyLoadedFrom1_7() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        sourceWithMockedRemoteCall("scroll_fully_loaded_1_7.json").doStartNextScroll("", timeValueMillis(0), wrapAsListener(r -> {
            assertEquals("AVToMiDL50DjIiBO3yKA", r.getHits().get(0).getId());
            assertEquals("{\"test\":\"test3\"}", r.getHits().get(0).getSource().utf8ToString());
            assertEquals("testrouting", r.getHits().get(0).getRouting());
            called.set(true);
        }));
        assertTrue(called.get());
    }

    /**
     * Versions of OpenSearch before 2.1.0 don't support sort:_doc and instead need to use search_type=scan. Scan doesn't return
     * documents the first iteration but reindex doesn't like that. So we jump start strait to the next iteration.
     */
    public void testScanJumpStart() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        sourceWithMockedRemoteCall("start_scan.json", "scroll_ok.json").doStart(wrapAsListener(r -> {
            assertFalse(r.isTimedOut());
            assertEquals(FAKE_SCROLL_ID, r.getScrollId());
            assertEquals(4, r.getTotalHits());
            assertThat(r.getFailures(), empty());
            assertThat(r.getHits(), hasSize(1));
            assertEquals("test", r.getHits().get(0).getIndex());
            assertEquals("AVToMiDL50DjIiBO3yKA", r.getHits().get(0).getId());
            assertEquals("{\"test\":\"test3\"}", r.getHits().get(0).getSource().utf8ToString());
            assertNull(r.getHits().get(0).getRouting());
            called.set(true);
        }));
        assertTrue(called.get());
    }

    public void testParseRejection() throws Exception {
        // The rejection comes through in the handler because the mocked http response isn't marked as an error
        AtomicBoolean called = new AtomicBoolean();
        // Handling a scroll rejection is the same as handling a search rejection so we reuse the verification code
        Consumer<Response> checkResponse = r -> {
            assertFalse(r.isTimedOut());
            assertEquals(FAKE_SCROLL_ID, r.getScrollId());
            assertEquals(4, r.getTotalHits());
            assertThat(r.getFailures(), hasSize(1));
            assertEquals("test", r.getFailures().get(0).getIndex());
            assertEquals((Integer) 0, r.getFailures().get(0).getShardId());
            assertEquals("87A7NvevQxSrEwMbtRCecg", r.getFailures().get(0).getNodeId());
            assertThat(r.getFailures().get(0).getReason(), instanceOf(OpenSearchRejectedExecutionException.class));
            assertEquals(
                "rejected execution of org.opensearch.transport.TransportService$5@52d06af2 on "
                    + "OpenSearchThreadPoolExecutor[search, queue capacity = 1000, org.opensearch.common.util.concurrent."
                    + "OpenSearchThreadPoolExecutor@778ea553[Running, pool size = 7, active threads = 7, queued tasks = 1000, "
                    + "completed tasks = 4182]]",
                r.getFailures().get(0).getReason().getMessage()
            );
            assertThat(r.getHits(), hasSize(1));
            assertEquals("test", r.getHits().get(0).getIndex());
            assertEquals("AVToMiC250DjIiBO3yJ_", r.getHits().get(0).getId());
            assertEquals("{\"test\":\"test1\"}", r.getHits().get(0).getSource().utf8ToString());
            called.set(true);
        };
        sourceWithMockedRemoteCall("rejection.json").doStart(wrapAsListener(checkResponse));
        assertTrue(called.get());
        called.set(false);
        sourceWithMockedRemoteCall("rejection.json").doStartNextScroll("scroll", timeValueMillis(0), wrapAsListener(checkResponse));
        assertTrue(called.get());
    }

    public void testParseFailureWithStatus() throws Exception {
        // The rejection comes through in the handler because the mocked http response isn't marked as an error
        AtomicBoolean called = new AtomicBoolean();
        // Handling a scroll rejection is the same as handling a search rejection so we reuse the verification code
        Consumer<Response> checkResponse = r -> {
            assertFalse(r.isTimedOut());
            assertEquals(FAKE_SCROLL_ID, r.getScrollId());
            assertEquals(10000, r.getTotalHits());
            assertThat(r.getFailures(), hasSize(1));
            assertEquals(null, r.getFailures().get(0).getIndex());
            assertEquals(null, r.getFailures().get(0).getShardId());
            assertEquals(null, r.getFailures().get(0).getNodeId());
            assertThat(r.getFailures().get(0).getReason(), instanceOf(RuntimeException.class));
            assertEquals(
                "Unknown remote exception with reason=[SearchContextMissingException[No search context found for id [82]]]",
                r.getFailures().get(0).getReason().getMessage()
            );
            assertThat(r.getHits(), hasSize(1));
            assertEquals("test", r.getHits().get(0).getIndex());
            assertEquals("10000", r.getHits().get(0).getId());
            assertEquals("{\"test\":\"test10000\"}", r.getHits().get(0).getSource().utf8ToString());
            called.set(true);
        };
        sourceWithMockedRemoteCall("failure_with_status.json").doStart(wrapAsListener(checkResponse));
        assertTrue(called.get());
        called.set(false);
        sourceWithMockedRemoteCall("failure_with_status.json").doStartNextScroll(
            "scroll",
            timeValueMillis(0),
            wrapAsListener(checkResponse)
        );
        assertTrue(called.get());
    }

    public void testParseRequestFailure() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        Consumer<Response> checkResponse = r -> {
            assertFalse(r.isTimedOut());
            assertNull(r.getScrollId());
            assertEquals(0, r.getTotalHits());
            assertThat(r.getFailures(), hasSize(1));
            assertThat(r.getFailures().get(0).getReason(), instanceOf(ParsingException.class));
            ParsingException failure = (ParsingException) r.getFailures().get(0).getReason();
            assertEquals("Unknown key for a VALUE_STRING in [invalid].", failure.getMessage());
            assertEquals(2, failure.getLineNumber());
            assertEquals(14, failure.getColumnNumber());
            called.set(true);
        };
        sourceWithMockedRemoteCall("request_failure.json").doStart(wrapAsListener(checkResponse));
        assertTrue(called.get());
        called.set(false);
        sourceWithMockedRemoteCall("request_failure.json").doStartNextScroll("scroll", timeValueMillis(0), wrapAsListener(checkResponse));
        assertTrue(called.get());
    }

    public void testRetryAndSucceed() throws Exception {
        retriesAllowed = between(1, Integer.MAX_VALUE);
        sourceWithMockedRemoteCall("fail:rejection.json", "start_ok.json", "fail:rejection.json", "scroll_ok.json").start();
        ScrollableHitSource.AsyncResponse response = responseQueue.poll();
        assertNotNull(response);
        assertThat(response.response().getFailures(), empty());
        assertTrue(responseQueue.isEmpty());
        assertEquals(1, retries);
        retries = 0;
        response.done(timeValueMillis(0));
        response = responseQueue.poll();
        assertNotNull(response);
        assertThat(response.response().getFailures(), empty());
        assertTrue(responseQueue.isEmpty());
        assertEquals(1, retries);
    }

    public void testRetryUntilYouRunOutOfTries() throws Exception {
        retriesAllowed = between(0, 10);
        String[] paths = new String[retriesAllowed + 2];
        for (int i = 0; i < retriesAllowed + 2; i++) {
            paths[i] = "fail:rejection.json";
        }
        RuntimeException e = expectThrows(RuntimeException.class, () -> sourceWithMockedRemoteCall(paths).start());
        assertEquals("failed", e.getMessage());
        assertTrue(responseQueue.isEmpty());
        assertEquals(retriesAllowed, retries);
        retries = 0;
        String[] searchOKPaths = Stream.concat(Stream.of("start_ok.json"), Stream.of(paths)).toArray(String[]::new);
        sourceWithMockedRemoteCall(searchOKPaths).start();
        ScrollableHitSource.AsyncResponse response = responseQueue.poll();
        assertNotNull(response);
        assertThat(response.response().getFailures(), empty());
        assertTrue(responseQueue.isEmpty());

        e = expectThrows(RuntimeException.class, () -> response.done(timeValueMillis(0)));
        assertEquals("failed", e.getMessage());
        assertTrue(responseQueue.isEmpty());
        assertEquals(retriesAllowed, retries);
    }

    public void testThreadContextRestored() throws Exception {
        String header = randomAlphaOfLength(5);
        threadPool.getThreadContext().putHeader("test", header);
        AtomicBoolean called = new AtomicBoolean();
        sourceWithMockedRemoteCall("start_ok.json").doStart(wrapAsListener(r -> {
            assertEquals(header, threadPool.getThreadContext().getHeader("test"));
            called.set(true);
        }));
        assertTrue(called.get());
    }

    public void testWrapExceptionToPreserveStatus() throws IOException {
        Exception cause = new Exception();

        // Successfully get the status without a body
        RestStatus status = randomFrom(RestStatus.values());
        OpenSearchStatusException wrapped = RemoteScrollableHitSource.wrapExceptionToPreserveStatus(status.getStatus(), null, cause);
        assertEquals(status, wrapped.status());
        assertEquals(cause, wrapped.getCause());
        assertEquals("No error body.", wrapped.getMessage());

        // Successfully get the status without a body
        HttpEntity okEntity = new StringEntity("test body", ContentType.TEXT_PLAIN);
        wrapped = RemoteScrollableHitSource.wrapExceptionToPreserveStatus(status.getStatus(), okEntity, cause);
        assertEquals(status, wrapped.status());
        assertEquals(cause, wrapped.getCause());
        assertEquals("body=test body", wrapped.getMessage());

        // Successfully get the status with a broken body
        IOException badEntityException = new IOException();
        HttpEntity badEntity = mock(HttpEntity.class);
        when(badEntity.getContent()).thenThrow(badEntityException);
        wrapped = RemoteScrollableHitSource.wrapExceptionToPreserveStatus(status.getStatus(), badEntity, cause);
        assertEquals(status, wrapped.status());
        assertEquals(cause, wrapped.getCause());
        assertEquals("Failed to extract body.", wrapped.getMessage());
        assertEquals(badEntityException, wrapped.getSuppressed()[0]);

        // Fail to get the status without a body
        int notAnHttpStatus = -1;
        assertNull(RestStatus.fromCode(notAnHttpStatus));
        wrapped = RemoteScrollableHitSource.wrapExceptionToPreserveStatus(notAnHttpStatus, null, cause);
        assertEquals(RestStatus.INTERNAL_SERVER_ERROR, wrapped.status());
        assertEquals(cause, wrapped.getCause());
        assertEquals("Couldn't extract status [" + notAnHttpStatus + "]. No error body.", wrapped.getMessage());

        // Fail to get the status without a body
        wrapped = RemoteScrollableHitSource.wrapExceptionToPreserveStatus(notAnHttpStatus, okEntity, cause);
        assertEquals(RestStatus.INTERNAL_SERVER_ERROR, wrapped.status());
        assertEquals(cause, wrapped.getCause());
        assertEquals("Couldn't extract status [" + notAnHttpStatus + "]. body=test body", wrapped.getMessage());

        // Fail to get the status with a broken body
        wrapped = RemoteScrollableHitSource.wrapExceptionToPreserveStatus(notAnHttpStatus, badEntity, cause);
        assertEquals(RestStatus.INTERNAL_SERVER_ERROR, wrapped.status());
        assertEquals(cause, wrapped.getCause());
        assertEquals("Couldn't extract status [" + notAnHttpStatus + "]. Failed to extract body.", wrapped.getMessage());
        assertEquals(badEntityException, wrapped.getSuppressed()[0]);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void testTooLargeResponse() throws Exception {
        ContentTooLongException tooLong = new ContentTooLongException("too long!");
        CloseableHttpAsyncClient httpClient = mock(CloseableHttpAsyncClient.class);
        when(
            httpClient.<HttpResponse>execute(
                any(HttpAsyncRequestProducer.class),
                any(HttpAsyncResponseConsumer.class),
                any(HttpClientContext.class),
                any(FutureCallback.class)
            )
        ).then(new Answer<Future<HttpResponse>>() {
            @Override
            public Future<HttpResponse> answer(InvocationOnMock invocationOnMock) throws Throwable {
                HeapBufferedAsyncResponseConsumer consumer = (HeapBufferedAsyncResponseConsumer) invocationOnMock.getArguments()[1];
                FutureCallback callback = (FutureCallback) invocationOnMock.getArguments()[3];
                assertEquals(new ByteSizeValue(100, ByteSizeUnit.MB).bytesAsInt(), consumer.getBufferLimit());
                callback.failed(tooLong);
                return null;
            }
        });
        RemoteScrollableHitSource source = sourceWithMockedClient(true, httpClient);

        Throwable e = expectThrows(RuntimeException.class, source::start);
        // Unwrap the some artifacts from the test
        while (e.getMessage().equals("failed")) {
            e = e.getCause();
        }
        // This next exception is what the user sees
        assertEquals("Remote responded with a chunk that was too large. Use a smaller batch size.", e.getMessage());
        // And that exception is reported as being caused by the underlying exception returned by the client
        assertSame(tooLong, e.getCause());
        assertTrue(responseQueue.isEmpty());
    }

    public void testNoContentTypeIsError() {
        RuntimeException e = expectListenerFailure(
            RuntimeException.class,
            (RejectAwareActionListener<Version> listener) -> sourceWithMockedRemoteCall(false, null, "main/0_20_5.json")
                .lookupRemoteVersion(listener)
        );
        assertEquals(e.getMessage(), "Response didn't include supported Content-Type, remote is likely not an OpenSearch instance");
    }

    public void testInvalidJsonThinksRemoteIsNotES() throws IOException {
        Exception e = expectThrows(RuntimeException.class, () -> sourceWithMockedRemoteCall("some_text.txt").start());
        assertEquals(
            "Error parsing the response, remote is likely not an OpenSearch instance",
            e.getCause().getCause().getCause().getMessage()
        );
    }

    public void testUnexpectedJsonThinksRemoteIsNotES() throws IOException {
        // Use the response from a main action instead of a proper start response to generate a parse error
        Exception e = expectThrows(RuntimeException.class, () -> sourceWithMockedRemoteCall("main/2_3_3.json").start());
        assertEquals(
            "Error parsing the response, remote is likely not an OpenSearch instance",
            e.getCause().getCause().getCause().getMessage()
        );
    }

    public void testCleanupSuccessful() throws Exception {
        AtomicBoolean cleanupCallbackCalled = new AtomicBoolean();
        RestClient client = mock(RestClient.class);
        TestRemoteScrollableHitSource hitSource = new TestRemoteScrollableHitSource(client);
        hitSource.cleanup(() -> cleanupCallbackCalled.set(true));
        verify(client).close();
        assertTrue(cleanupCallbackCalled.get());
    }

    public void testCleanupFailure() throws Exception {
        AtomicBoolean cleanupCallbackCalled = new AtomicBoolean();
        RestClient client = mock(RestClient.class);
        doThrow(new RuntimeException("test")).when(client).close();
        TestRemoteScrollableHitSource hitSource = new TestRemoteScrollableHitSource(client);
        hitSource.cleanup(() -> cleanupCallbackCalled.set(true));
        verify(client).close();
        assertTrue(cleanupCallbackCalled.get());
    }

    private RemoteScrollableHitSource sourceWithMockedRemoteCall(String... paths) throws Exception {
        return sourceWithMockedRemoteCall(true, ContentType.APPLICATION_JSON, paths);
    }

    /**
     * Creates a hit source that doesn't make the remote request and instead returns data from some files. Also requests are always returned
     * synchronously rather than asynchronously.
     */
    @SuppressWarnings("unchecked")
    private RemoteScrollableHitSource sourceWithMockedRemoteCall(boolean mockRemoteVersion, ContentType contentType, String... paths) {
        URL[] resources = new URL[paths.length];
        for (int i = 0; i < paths.length; i++) {
            resources[i] = Thread.currentThread().getContextClassLoader().getResource("responses/" + paths[i].replace("fail:", ""));
            if (resources[i] == null) {
                throw new IllegalArgumentException("Couldn't find [" + paths[i] + "]");
            }
        }

        CloseableHttpAsyncClient httpClient = mock(CloseableHttpAsyncClient.class);
        when(
            httpClient.<HttpResponse>execute(
                any(HttpAsyncRequestProducer.class),
                any(HttpAsyncResponseConsumer.class),
                any(HttpClientContext.class),
                any(FutureCallback.class)
            )
        ).thenAnswer(new Answer<Future<HttpResponse>>() {

            int responseCount = 0;

            @Override
            public Future<HttpResponse> answer(InvocationOnMock invocationOnMock) throws Throwable {
                // Throw away the current thread context to simulate running async httpclient's thread pool
                threadPool.getThreadContext().stashContext();
                HttpAsyncRequestProducer requestProducer = (HttpAsyncRequestProducer) invocationOnMock.getArguments()[0];
                FutureCallback<HttpResponse> futureCallback = (FutureCallback<HttpResponse>) invocationOnMock.getArguments()[3];
                HttpEntityEnclosingRequest request = (HttpEntityEnclosingRequest) requestProducer.generateRequest();
                URL resource = resources[responseCount];
                String path = paths[responseCount++];
                ProtocolVersion protocolVersion = new ProtocolVersion("http", 1, 1);
                if (path.startsWith("fail:")) {
                    String body = Streams.copyToString(new InputStreamReader(request.getEntity().getContent(), StandardCharsets.UTF_8));
                    if (path.equals("fail:rejection.json")) {
                        StatusLine statusLine = new BasicStatusLine(protocolVersion, RestStatus.TOO_MANY_REQUESTS.getStatus(), "");
                        BasicHttpResponse httpResponse = new BasicHttpResponse(statusLine);
                        futureCallback.completed(httpResponse);
                    } else {
                        futureCallback.failed(new RuntimeException(body));
                    }
                } else {
                    StatusLine statusLine = new BasicStatusLine(protocolVersion, 200, "");
                    HttpResponse httpResponse = new BasicHttpResponse(statusLine);
                    httpResponse.setEntity(new InputStreamEntity(FileSystemUtils.openFileURLStream(resource), contentType));
                    futureCallback.completed(httpResponse);
                }
                return null;
            }
        });
        return sourceWithMockedClient(mockRemoteVersion, httpClient);
    }

    private RemoteScrollableHitSource sourceWithMockedClient(boolean mockRemoteVersion, CloseableHttpAsyncClient httpClient) {
        HttpAsyncClientBuilder clientBuilder = mock(HttpAsyncClientBuilder.class);
        when(clientBuilder.build()).thenReturn(httpClient);

        RestClient restClient = RestClient.builder(new HttpHost("localhost", 9200))
            .setHttpClientConfigCallback(httpClientBuilder -> clientBuilder)
            .build();

        TestRemoteScrollableHitSource hitSource = new TestRemoteScrollableHitSource(restClient) {
            @Override
            void lookupRemoteVersion(RejectAwareActionListener<Version> listener) {
                if (mockRemoteVersion) {
                    listener.onResponse(Version.CURRENT);
                } else {
                    super.lookupRemoteVersion(listener);
                }
            }
        };
        if (mockRemoteVersion) {
            hitSource.remoteVersion = Version.CURRENT;
        }
        return hitSource;
    }

    private BackoffPolicy backoff() {
        return BackoffPolicy.constantBackoff(timeValueMillis(0), retriesAllowed);
    }

    private void countRetry() {
        retries += 1;
    }

    private void failRequest(Throwable t) {
        throw new RuntimeException("failed", t);
    }

    private class TestRemoteScrollableHitSource extends RemoteScrollableHitSource {
        TestRemoteScrollableHitSource(RestClient client) {
            super(
                RemoteScrollableHitSourceTests.this.logger,
                backoff(),
                RemoteScrollableHitSourceTests.this.threadPool,
                RemoteScrollableHitSourceTests.this::countRetry,
                responseQueue::add,
                RemoteScrollableHitSourceTests.this::failRequest,
                client,
                new BytesArray("{}"),
                RemoteScrollableHitSourceTests.this.searchRequest
            );
        }
    }

    private <T> RejectAwareActionListener<T> wrapAsListener(Consumer<T> consumer) {
        Consumer<Exception> throwing = e -> { throw new AssertionError(e); };
        return RejectAwareActionListener.wrap(consumer::accept, throwing, throwing);
    }

    @SuppressWarnings("unchecked")
    private <T extends Exception, V> T expectListenerFailure(Class<T> expectedException, Consumer<RejectAwareActionListener<V>> subject) {
        AtomicReference<T> exception = new AtomicReference<>();
        subject.accept(RejectAwareActionListener.wrap(r -> fail(), e -> {
            assertThat(e, instanceOf(expectedException));
            assertTrue(exception.compareAndSet(null, (T) e));
        }, e -> fail()));
        assertNotNull(exception.get());
        return exception.get();
    }
}
