/*
 *******************************************************************************
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.eclipse.microprofile.lra.tck;

import static org.eclipse.microprofile.lra.tck.participant.api.NonParticipatingTckResource.END_PATH;
import static org.eclipse.microprofile.lra.tck.participant.api.NonParticipatingTckResource.START_BUT_DONT_END_PATH;
import static org.eclipse.microprofile.lra.tck.participant.api.NonParticipatingTckResource.STATUS_CODE_QUERY_NAME;
import static org.eclipse.microprofile.lra.tck.participant.api.NonParticipatingTckResource.TCK_NON_PARTICIPANT_RESOURCE_PATH;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.eclipse.microprofile.lra.annotation.ws.rs.LRA;
import org.eclipse.microprofile.lra.tck.participant.api.GenericLRAException;

import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;

public class LRAClientOps {
    private static final Logger LOGGER = Logger.getLogger(TckLRATypeTests.class.getName());

    private final WebTarget target;
    private final ScheduledExecutorService executor;
    private final Map<LRATask, ScheduledFuture<?>> lraTasks;

    public LRAClientOps(WebTarget target) {
        this.target = target;
        this.executor = Executors.newSingleThreadScheduledExecutor();
        this.lraTasks = new ConcurrentHashMap<>();
    }

    // synchronize access to the connection since it is shared with the LRA background cancellation code
    private synchronized Response invokeRestEndpoint(URI lra, String basePath, String path, int coerceResponse) {
        WebTarget resourcePath = target.path(basePath).path(path).queryParam(STATUS_CODE_QUERY_NAME, coerceResponse);
        Invocation.Builder builder = resourcePath.request();

        if (lra != null) {
            builder.header(LRA.LRA_HTTP_CONTEXT_HEADER, lra);
        }

        return builder.put(Entity.text(""));
    }

    public String invokeRestEndpointAndReturnLRA(URI lra, String basePath, String path, int coerceResponse) {
        Response response = invokeRestEndpoint(lra, basePath, path, coerceResponse);

        try {
            return response.readEntity(String.class);
        } finally {
            response.close();
        }
    }

    private int invokeRestEndpointAndReturnStatus(URI lra, String basePath, String path, int coerceResponse) {
        Response response = invokeRestEndpoint(lra, basePath, path, coerceResponse);

        try {
            return response.getStatus();
        } finally {
            response.close();
        }
    }

    private URI toURI(String lra) throws GenericLRAException {
        try {
            return new URI(lra);
        } catch (URISyntaxException e) {
            throw new GenericLRAException(null, e.getMessage(), e);
        }
    }

    public URI startLRA(URI parentLRA, String clientID, long timeout, ChronoUnit unit)
            throws GenericLRAException {
        String lra = invokeRestEndpoint(parentLRA,
                TCK_NON_PARTICIPANT_RESOURCE_PATH, START_BUT_DONT_END_PATH, 200)
                .readEntity(String.class);

        if (timeout > 0L) {
            scheduleCancelation(clientID, toURI(lra), timeout, unit);
        }

        return toURI(lra);
    }

    public void cancelLRA(URI lraId) throws GenericLRAException {
        cancelCancelation(lraId);

        invokeRestEndpointAndReturnLRA(lraId, TCK_NON_PARTICIPANT_RESOURCE_PATH, END_PATH, 500);
    }

    private void cancelLRA(String clientId, URI lra) {
        LOGGER.warning("cancelling LRA from the timer: clientId: " + clientId + " LRA id: " + lra.toASCIIString());
        cancelLRA(lra);
        throw new IllegalArgumentException("LRA timed out prematurely");
    }

    public void closeLRA(URI lraId) throws GenericLRAException {
        cancelCancelation(lraId);
        invokeRestEndpointAndReturnLRA(lraId, TCK_NON_PARTICIPANT_RESOURCE_PATH, END_PATH, 200);
    }

    void closeLRA(String lraId) {
        closeLRA(toURI(lraId));
    }

    private void leaveLRA(URI lra, String basePath, String resourcePath) throws GenericLRAException {
        invokeRestEndpoint(lra, basePath, resourcePath, 200);
    }

    /*
     * Include support for timing out LRAs. A timed out LRA will generally produce a test failure.
     */

    private static TimeUnit timeUnit(ChronoUnit unit) {
        switch (unit) {
            case NANOS :
                return TimeUnit.NANOSECONDS;
            case MICROS :
                return TimeUnit.MICROSECONDS;
            case MILLIS :
                return TimeUnit.MILLISECONDS;
            case SECONDS :
                return TimeUnit.SECONDS;
            case MINUTES :
                return TimeUnit.MINUTES;
            case HOURS :
                return TimeUnit.HOURS;
            case DAYS :
                return TimeUnit.DAYS;
            default :
                throw new IllegalArgumentException("ChronoUnit cannot be converted to TimeUnit: " + unit);
        }
    }

    /**
     * arrange for an LRA to be automatically cancelled after a specified timeout
     *
     * @param clientId
     *            client assigned arbitrary identifier for the LRA
     * @param lra
     *            the LRA that should be cancelled when the time limit is reached
     * @param timeout
     *            the time to wait before attempting cancellation of the LRA
     * @param unit
     *            the time unit
     */
    private void scheduleCancelation(String clientId, URI lra, long timeout, ChronoUnit unit) {
        lraTasks.put(new LRATask(clientId, lra),
                executor.schedule(() -> cancelLRA(clientId, lra), timeout, timeUnit(unit)));
    }

    private void cancelCancelation(URI lraId) {
        LRATask lra = new LRATask(null, lraId);
        if (lraTasks.containsKey(lra)) {
            lraTasks.remove(lra).cancel(false);
        }
    }

    void cleanUp(Logger logger, String testName) {
        lraTasks.forEach((lra, future) -> {
            logger.warning("Test: " + testName + " didn't finish LRA " + lra.lra + " with clientId " + lra.clientId);
            cancelLRA(lra.lra);
        });
    }

    // class to store the clientId associated with an LRA
    private class LRATask {
        final String clientId; // client assigned arbitrary identifier for the LRA
        final URI lra; // the LRA that clientId is associated with

        LRATask(String clientId, URI lra) {
            this.clientId = clientId;
            this.lra = lra;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            LRATask lraTask = (LRATask) o;
            return lra.equals(lraTask.lra);
        }

        @Override
        public int hashCode() {
            return Objects.hash(lra);
        }
    }
}
