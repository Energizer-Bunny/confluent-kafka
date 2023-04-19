/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.clients.consumer.internals;

import org.apache.kafka.clients.ApiVersions;
import org.apache.kafka.clients.ClientRequest;
import org.apache.kafka.clients.ClientResponse;
import org.apache.kafka.clients.ClientUtils;
import org.apache.kafka.clients.KafkaClient;
import org.apache.kafka.clients.NetworkClient;
import org.apache.kafka.clients.RequestCompletionHandler;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.network.ChannelBuilder;
import org.apache.kafka.common.network.Selector;
import org.apache.kafka.common.requests.AbstractRequest;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Timer;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

/**
 * A wrapper around the {@link org.apache.kafka.clients.NetworkClient} to handle network poll and send operations.
 */
public class NetworkClientDelegate implements AutoCloseable {

    private static final String METRIC_GRP_PREFIX = "consumer";

    private final Logger log;
    private final PrototypeAsyncConsumerContext consumerContext;
    private final KafkaClient client;
    private final int requestTimeoutMs;
    private final Queue<UnsentRequest> unsentRequests;
    private final long retryBackoffMs;

    public NetworkClientDelegate(PrototypeAsyncConsumerContext consumerContext,
                                 final ConsumerConfig config,
                                 final KafkaClient client) {
        this.log = consumerContext.logContext.logger(getClass());
        this.consumerContext = consumerContext;
        this.client = client;
        this.unsentRequests = new ArrayDeque<>();
        this.requestTimeoutMs = config.getInt(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG);
        this.retryBackoffMs = config.getLong(ConsumerConfig.RETRY_BACKOFF_MS_CONFIG);
    }

    public static NetworkClientDelegate create(PrototypeAsyncConsumerContext consumerContext,
                                               ConsumerConfig config,
                                               Metrics metrics,
                                               ConsumerMetadata metadata,
                                               ApiVersions apiVersions,
                                               Sensor throttleSensor) {
        final ChannelBuilder channelBuilder = ClientUtils.createChannelBuilder(config,
                consumerContext.time,
                consumerContext.logContext);
        final Selector selector = new Selector(config.getLong(ConsumerConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG),
                metrics,
                consumerContext.time,
                METRIC_GRP_PREFIX,
                channelBuilder,
                consumerContext.logContext);
        final NetworkClient networkClient = ClientUtils.createNetworkClient(selector,
                metadata,
                consumerContext.logContext,
                apiVersions,
                throttleSensor,
                consumerContext.time,
                config,
                100);   // a fixed large enough value will suffice for max in-flight requests
        return new NetworkClientDelegate(consumerContext,
                config,
                networkClient);
    }

    /**
     * Returns the responses of the sent requests. This method will try to send the unsent requests, poll for responses,
     * and check the disconnected nodes.
     *
     * @param timeoutMs     timeout time
     * @param currentTimeMs current time
     * @return a list of client response
     */
    public void poll(final long timeoutMs, final long currentTimeMs) {
        trySend(currentTimeMs);

        long pollTimeoutMs = timeoutMs;
        if (!unsentRequests.isEmpty()) {
            pollTimeoutMs = Math.min(retryBackoffMs, pollTimeoutMs);
        }
        this.client.poll(pollTimeoutMs, currentTimeMs);
        checkDisconnects();
    }

    /**
     * Tries to send the requests in the unsentRequest queue. If the request doesn't have an assigned node, it will
     * find the leastLoadedOne, and will be retried in the next {@code poll()}. If the request is expired, a
     * {@link TimeoutException} will be thrown.
     */
    private void trySend(final long currentTimeMs) {
        Iterator<UnsentRequest> iterator = unsentRequests.iterator();
        while (iterator.hasNext()) {
            UnsentRequest unsent = iterator.next();
            unsent.timer.update(currentTimeMs);
            if (unsent.timer.isExpired()) {
                iterator.remove();
                unsent.handler.onFailure(new TimeoutException(
                    "Failed to send request after " + unsent.timer.timeoutMs() + " ms."));
                continue;
            }

            if (!doSend(unsent, currentTimeMs)) {
                // continue to retry until timeout.
                continue;
            }
            iterator.remove();
        }
    }

    private boolean doSend(final UnsentRequest r,
                           final long currentTimeMs) {
        Node node = r.node.orElse(client.leastLoadedNode(currentTimeMs));
        if (node == null || nodeUnavailable(node)) {
            log.debug("No broker available to send the request: {}. Retrying.", r);
            return false;
        }
        ClientRequest request = makeClientRequest(r, node, currentTimeMs);
        if (!client.ready(node, currentTimeMs)) {
            // enqueue the request again if the node isn't ready yet. The request will be handled in the next iteration
            // of the event loop
            log.debug("Node is not ready, handle the request in the next event loop: node={}, request={}", node, r);
            return false;
        }
        client.send(request, currentTimeMs);
        return true;
    }

    private void checkDisconnects() {
        // Check the connection of the unsent request. Disconnect the disconnected node if it is unable to be connected.
        Iterator<UnsentRequest> iter = unsentRequests.iterator();
        while (iter.hasNext()) {
            UnsentRequest u = iter.next();
            if (u.node.isPresent() && client.connectionFailed(u.node.get())) {
                iter.remove();
                AuthenticationException authenticationException = client.authenticationException(u.node.get());
                u.handler.onFailure(authenticationException);
            }
        }
    }

    private ClientRequest makeClientRequest(
        final UnsentRequest unsent,
        final Node node,
        final long currentTimeMs
    ) {
        return client.newClientRequest(
            node.idString(),
            unsent.requestBuilder,
            currentTimeMs,
            true,
            (int) unsent.timer.remainingMs(),
            unsent.handler
        );
    }

    public Node leastLoadedNode() {
        return this.client.leastLoadedNode(consumerContext.time.milliseconds());
    }

    public void send(final UnsentRequest r) {
        r.setTimer(consumerContext.time, this.requestTimeoutMs);
        unsentRequests.add(r);
    }

    public void wakeup() {
        client.wakeup();
    }

    /**
     * Check if the code is disconnected and unavailable for immediate reconnection (i.e. if it is in reconnect
     * backoff window following the disconnect).
     */
    public boolean nodeUnavailable(final Node node) {
        return client.connectionFailed(node) && client.connectionDelay(node, consumerContext.time.milliseconds()) > 0;
    }

    public void close() throws IOException {
        this.client.close();
    }

    public void addAll(final List<UnsentRequest> requests) {
        requests.forEach(u -> {
            u.setTimer(consumerContext.time, this.requestTimeoutMs);
        });
        this.unsentRequests.addAll(requests);
    }

    public static class PollResult {
        public final long timeUntilNextPollMs;
        public final List<UnsentRequest> unsentRequests;

        public PollResult(final long timeMsTillNextPoll) {
            this(timeMsTillNextPoll, Collections.emptyList());
        }

        public PollResult(final long timeMsTillNextPoll, final UnsentRequest unsentRequest) {
            this(timeMsTillNextPoll, Collections.singletonList(unsentRequest));
        }

        public PollResult(final long timeMsTillNextPoll, final List<UnsentRequest> unsentRequests) {
            this.timeUntilNextPollMs = timeMsTillNextPoll;
            this.unsentRequests = Collections.unmodifiableList(unsentRequests);
        }
    }

    public static class UnsentRequest {

        private final AbstractRequest.Builder<?> requestBuilder;
        private final FutureCompletionHandler handler;
        private final Optional<Node> node; // empty if random node can be chosen
        private Timer timer;

        public UnsentRequest(final AbstractRequest.Builder<?> requestBuilder, final Optional<Node> node) {
            this(requestBuilder, node, new FutureCompletionHandler());
        }

        public UnsentRequest(
                final AbstractRequest.Builder<?> requestBuilder,
                final Optional<Node> node,
                final FutureCompletionHandler handler) {
            Objects.requireNonNull(requestBuilder);
            this.requestBuilder = requestBuilder;
            this.node = node;
            this.handler = handler;
        }

        public void setTimer(final Time time, final long requestTimeoutMs) {
            this.timer = time.timer(requestTimeoutMs);
        }

        CompletableFuture<ClientResponse> future() {
            return handler.future;
        }

        RequestCompletionHandler callback() {
            return handler;
        }

        AbstractRequest.Builder<?> requestBuilder() {
            return requestBuilder;
        }

        @Override
        public String toString() {
            return "UnsentRequest(builder=" + requestBuilder + ")";
        }
    }

    public static class FutureCompletionHandler implements RequestCompletionHandler {
        private final CompletableFuture<ClientResponse> future;

        FutureCompletionHandler() {
            this.future = new CompletableFuture<>();
        }

        public void onFailure(final RuntimeException e) {
            future.completeExceptionally(e);
        }

        public CompletableFuture<ClientResponse> future() {
            return future;
        }

        @Override
        public void onComplete(final ClientResponse response) {
            if (response.authenticationException() != null) {
                onFailure(response.authenticationException());
            } else if (response.wasDisconnected()) {
                onFailure(DisconnectException.INSTANCE);
            } else if (response.versionMismatch() != null) {
                onFailure(response.versionMismatch());
            } else {
                future.complete(response);
            }
        }
    }

}
