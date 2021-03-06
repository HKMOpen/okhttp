/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.DelegatingServerSocketFactory;
import com.squareup.okhttp.DelegatingSocketFactory;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.OkUrlFactory;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;

import static org.junit.Assert.fail;

public final class ThreadInterruptTest {

  // The size of the socket buffers in bytes.
  private static final int SOCKET_BUFFER_SIZE = 256 * 1024;

  private MockWebServer server;
  private OkHttpClient client;

  @Before public void setUp() throws Exception {
    server = new MockWebServer();
    client = new OkHttpClient();

    // Sockets on some platforms can have large buffers that mean writes do not block when
    // required. These socket factories explicitly set the buffer sizes on sockets created.
    server.setServerSocketFactory(
        new DelegatingServerSocketFactory(ServerSocketFactory.getDefault()) {
          @Override
          protected void configureServerSocket(ServerSocket serverSocket) throws IOException {
            serverSocket.setReceiveBufferSize(SOCKET_BUFFER_SIZE);
          }
        });
    client.setSocketFactory(new DelegatingSocketFactory(SocketFactory.getDefault()) {
      @Override
      protected void configureSocket(Socket socket) throws IOException {
        socket.setSendBufferSize(SOCKET_BUFFER_SIZE);
        socket.setReceiveBufferSize(SOCKET_BUFFER_SIZE);
      }
    });
  }

  @Test public void interruptWritingRequestBody() throws Exception {
    int requestBodySize = 2 * 1024 * 1024; // 2 MiB

    server.enqueue(new MockResponse()
        .throttleBody(64 * 1024, 125, TimeUnit.MILLISECONDS)); // 500 Kbps
    server.play();

    interruptLater(500);

    HttpURLConnection connection = new OkUrlFactory(client).open(server.getUrl("/"));
    connection.setDoOutput(true);
    connection.setFixedLengthStreamingMode(requestBodySize);
    OutputStream requestBody = connection.getOutputStream();
    byte[] buffer = new byte[1024];
    try {
      for (int i = 0; i < requestBodySize; i += buffer.length) {
        requestBody.write(buffer);
        requestBody.flush();
      }
      fail("Expected thread to be interrupted");
    } catch (InterruptedIOException expected) {
    }

    connection.disconnect();
  }

  @Test public void interruptReadingResponseBody() throws Exception {
    int responseBodySize = 2 * 1024 * 1024; // 2 MiB

    server.enqueue(new MockResponse()
        .setBody(new byte[responseBodySize])
        .throttleBody(64 * 1024, 125, TimeUnit.MILLISECONDS)); // 500 Kbps
    server.play();

    interruptLater(500);

    HttpURLConnection connection = new OkUrlFactory(client).open(server.getUrl("/"));
    InputStream responseBody = connection.getInputStream();
    byte[] buffer = new byte[1024];
    try {
      while (responseBody.read(buffer) != -1) {
      }
      fail("Expected thread to be interrupted");
    } catch (InterruptedIOException expected) {
    }

    connection.disconnect();
  }

  private void interruptLater(final int delayMillis) {
    final Thread toInterrupt = Thread.currentThread();
    Thread interruptingCow = new Thread() {
      @Override public void run() {
        try {
          sleep(delayMillis);
          toInterrupt.interrupt();
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    };
    interruptingCow.start();
  }
}
