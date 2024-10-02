/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.service.http.impl.service.server.grizzly;

import static org.mule.runtime.http.api.HttpHeaders.Names.CONNECTION;
import static org.mule.service.http.impl.AllureConstants.HttpFeature.HTTP_SERVICE;
import static org.mule.service.http.impl.AllureConstants.HttpFeature.HttpStory.RESPONSES;
import static org.mule.tck.MuleTestUtils.testWithSystemProperty;

import static java.lang.Thread.currentThread;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.util.MultiMap;
import org.mule.runtime.http.api.domain.entity.InputStreamHttpEntity;
import org.mule.runtime.http.api.domain.message.response.HttpResponse;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.qameta.allure.Feature;
import io.qameta.allure.Issue;
import io.qameta.allure.Story;
import org.glassfish.grizzly.Transport;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.ProcessingState;
import org.junit.Before;
import org.junit.Test;

@Feature(HTTP_SERVICE)
@Story(RESPONSES)
public class ResponseStreamingCompletionHandlerTestCase extends BaseResponseCompletionHandlerTestCase {

  private ResponseStreamingCompletionHandler handler;
  private InputStream mockStream;
  private final ExecutorService workerPool = mock(ExecutorService.class);

  @Before
  public void setUp() {
    when(ctx.getConnection()).thenReturn(connection);
    when(connection.getTransport()).thenReturn(mock(Transport.class, RETURNS_DEEP_STUBS));
    mockStream = spy(mock(InputStream.class));
    responseMock = HttpResponse.builder().entity(new InputStreamHttpEntity(mockStream)).build();
    handler = new ResponseStreamingCompletionHandler(ctx,
                                                     currentThread().getContextClassLoader(),
                                                     request,
                                                     responseMock,
                                                     callback,
                                                     workerPool);
  }

  @Override
  protected BaseResponseCompletionHandler getHandler() {
    return handler;
  }

  @Test
  public void keepAliveConnection() {
    final MultiMap<String, String> headers = new MultiMap<>();
    headers.put(CONNECTION, KEEP_ALIVE);
    responseMock = HttpResponse.builder().entity(new InputStreamHttpEntity(mockStream)).headers(headers).build();

    handler = new ResponseStreamingCompletionHandler(ctx,
                                                     currentThread().getContextClassLoader(),
                                                     request,
                                                     responseMock,
                                                     callback,
                                                     workerPool);
    assertThat(handler.getHttpResponsePacket().getHeader(CONNECTION), equalTo(KEEP_ALIVE));
  }

  @Test
  public void cLoseConnection() {
    final MultiMap<String, String> headers = new MultiMap<>();
    headers.put(CONNECTION, CLOSE);
    responseMock = HttpResponse.builder().entity(new InputStreamHttpEntity(mockStream)).headers(headers).build();
    when(request.getProcessingState()).thenReturn(new ProcessingState());
    handler = new ResponseStreamingCompletionHandler(ctx,
                                                     currentThread().getContextClassLoader(),
                                                     request,
                                                     responseMock,
                                                     callback,
                                                     workerPool);
    assertThat(getHandler().getHttpResponsePacket().getHeader(CONNECTION), equalTo(CLOSE));
  }

  @Test
  public void completionHandlerFailsIfAReadOperationThrowsAMuleRuntimeException() throws IOException {
    responseMock = HttpResponse.builder().entity(new InputStreamHttpEntity(mockStream)).build();
    when(request.getProcessingState()).thenReturn(new ProcessingState());
    handler = spy(new ResponseStreamingCompletionHandler(ctx,
                                                         currentThread().getContextClassLoader(),
                                                         request,
                                                         responseMock,
                                                         callback,
                                                         workerPool));

    when(mockStream.read(any(), anyInt(), anyInt())).thenThrow(new MuleRuntimeException(new NullPointerException()));
    handler.sendInputStreamChunk();

    verify(getHandler(), times(1)).failed(any(Throwable.class));
  }

  @Test
  public void IOExceptionIsRethrownIfCauseOfFailure() throws IOException {
    responseMock = HttpResponse.builder().entity(new InputStreamHttpEntity(mockStream)).build();
    when(request.getProcessingState()).thenReturn(new ProcessingState());
    handler = spy(new ResponseStreamingCompletionHandler(ctx,
                                                         currentThread().getContextClassLoader(),
                                                         request,
                                                         responseMock,
                                                         callback,
                                                         workerPool));
    when(mockStream.read(any(), anyInt(), anyInt())).thenThrow(new MuleRuntimeException(new IOException()));
    assertThrows(IOException.class, () -> handler.sendInputStreamChunk());
  }

  @Test
  @Issue("MULE-19727")
  public void handlerDoesntThrowNPEWhenConnectionIsNull() {
    // Given a valid handler.
    handler = new ResponseStreamingCompletionHandler(ctx,
                                                     currentThread().getContextClassLoader(),
                                                     request,
                                                     responseMock,
                                                     callback,
                                                     workerPool);
    // When an unexpected error makes getConnection return null.
    when(ctx.getConnection()).thenReturn(null);
    // Then the failed() method is executed without throwing NPE.
    handler.failed(createExpectedException());
  }

  @Test
  @Issue("MULE-19727")
  public void failedMethodBehaviorIsExecutedOnlyOnceForTheSameHandler() throws IOException {
    // Given a valid handler.
    handler = new ResponseStreamingCompletionHandler(ctx,
                                                     currentThread().getContextClassLoader(),
                                                     request,
                                                     responseMock,
                                                     callback,
                                                     workerPool);

    // When the failed() method is invoked several times
    handler.failed(createExpectedException());
    handler.failed(createExpectedException());
    handler.failed(createExpectedException());

    // Then the effects of failed() are invoked only once.
    verify(mockStream, times(1)).close();
    verify(callback, times(1)).onErrorSendingResponse(any(Exception.class));
  }

  @Test
  public void whenTheTimeoutIsElapsedThenTheStartIsExecutedInTheWorkerScheduler() throws Exception {
    responseMock = HttpResponse.builder().entity(new InputStreamHttpEntity(mockStream)).build();
    testWithSystemProperty("mule.timeoutToUseSelectorWhileStreamingResponseMillis", "0", () -> {
      handler = new ResponseStreamingCompletionHandler(ctx,
                                                       currentThread().getContextClassLoader(),
                                                       request,
                                                       responseMock,
                                                       callback,
                                                       workerPool);

      handler.start();
      verify(workerPool, times(1)).submit(any(Runnable.class));
    });
  }

  @Test
  public void whenTheTimeoutIsNotElapsedThenTheStartIsNotExecutedInTheWorkerScheduler() throws Exception {
    responseMock = HttpResponse.builder().entity(new InputStreamHttpEntity(mockStream)).build();
    testWithSystemProperty("mule.timeoutToUseSelectorWhileStreamingResponseMillis", "100000", () -> {
      handler = new ResponseStreamingCompletionHandler(ctx,
                                                       currentThread().getContextClassLoader(),
                                                       request,
                                                       responseMock,
                                                       callback,
                                                       workerPool);

      handler.start();
      verify(workerPool, never()).submit(any(Runnable.class));
      verify(ctx, times(1)).write(any(HttpContent.class), eq(handler));
    });
  }

  private Exception createExpectedException() {
    return new Exception("EXPECTED EXCEPTION");
  }
}
