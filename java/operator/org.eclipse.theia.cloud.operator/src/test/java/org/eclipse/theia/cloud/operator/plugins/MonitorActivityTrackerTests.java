/********************************************************************************
 * Copyright (C) 2026 EclipseSource and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.theia.cloud.operator.plugins;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.eclipse.theia.cloud.common.k8s.client.SessionResourceClient;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionSpec;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionStatus;
import org.eclipse.theia.cloud.operator.messaging.MonitorMessagingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.sentry.ISpan;
import io.sentry.ITransaction;

/**
 * The behaviour these tests pin down is the one that was wrong in production: an activity poll that does not come back
 * must never be read as "the user is idle". Before the fix, an unreachable monitor and a genuinely idle user were
 * indistinguishable, so every session was reaped once its stale lastActivity aged past the timeout - however hard the
 * student was typing.
 */
class MonitorActivityTrackerTests {

    private static final String SESSION_NAME = "ws-student-session";
    private static final int SHUTDOWN_AFTER = 60;
    private static final int NOTIFY_AFTER = 55;
    private static final int PORT = 8081;

    private TheiaCloudClient resourceClient;
    private SessionResourceClient sessions;
    private MonitorMessagingService messagingService;
    private ITransaction transaction;

    @BeforeEach
    void setUp() {
        resourceClient = Mockito.mock(TheiaCloudClient.class);
        sessions = Mockito.mock(SessionResourceClient.class);
        messagingService = Mockito.mock(MonitorMessagingService.class);
        when(resourceClient.sessions()).thenReturn(sessions);
        // updateStatus returns the updated resource; without this the tracker would carry on with a null session.
        when(sessions.updateStatus(anyString(), any(Session.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        transaction = Mockito.mock(ITransaction.class);
        ISpan span = Mockito.mock(ISpan.class);
        when(transaction.startChild(anyString(), anyString())).thenReturn(span);
        when(span.startChild(anyString(), anyString())).thenReturn(span);
    }

    @Test
    void pingSession_pollThrows_doesNotStopTheSession() {
        // Long past the timeout, so falling through to the timeout check would delete it.
        Session session = createSession(minutesAgo(90));
        MonitorActivityTracker tracker = createTracker(() -> {
            throw new IOException("connection refused");
        });

        boolean success = tracker.pingSession(transaction, "cid", session, "10.0.0.1", PORT, SHUTDOWN_AFTER,
                NOTIFY_AFTER);

        assertFalse(success, "an unanswered poll is not a success");
        verify(sessions, never()).delete(anyString(), anyString());
        verify(messagingService, never()).sendTimeoutMessage(any(), anyString());
    }

    @Test
    void pingSession_pollReturnsNoTimestamp_doesNotStopTheSession() {
        Session session = createSession(minutesAgo(90));
        MonitorActivityTracker tracker = createTracker(Optional::empty);

        boolean success = tracker.pingSession(transaction, "cid", session, "10.0.0.1", PORT, SHUTDOWN_AFTER,
                NOTIFY_AFTER);

        assertFalse(success);
        verify(sessions, never()).delete(anyString(), anyString());
    }

    @Test
    void pingSession_pollThrowsUnchecked_doesNotStopTheSession() {
        // A malformed body is handled inside fetchLastActivity, but nothing unchecked escaping the
        // poll may take the session down either.
        Session session = createSession(minutesAgo(90));
        MonitorActivityTracker tracker = createTracker(() -> {
            throw new IllegalStateException("unexpected");
        });

        boolean success = tracker.pingSession(transaction, "cid", session, "10.0.0.1", PORT, SHUTDOWN_AFTER,
                NOTIFY_AFTER);

        assertFalse(success);
        verify(sessions, never()).delete(anyString(), anyString());
    }

    @Test
    void pingSession_pollSucceedsAndUserIsIdle_stopsTheSession() {
        long idleSince = minutesAgo(90);
        Session session = createSession(idleSince);
        MonitorActivityTracker tracker = createTracker(() -> Optional.of(idleSince));

        boolean success = tracker.pingSession(transaction, "cid", session, "10.0.0.1", PORT, SHUTDOWN_AFTER,
                NOTIFY_AFTER);

        assertTrue(success);
        verify(messagingService).sendTimeoutMessage(eq(session), anyString());
        verify(sessions).delete(anyString(), eq(SESSION_NAME));
    }

    @Test
    void pingSession_pollSucceedsAndUserIsActive_leavesTheSessionAlone() {
        long activeSince = minutesAgo(1);
        Session session = createSession(activeSince);
        MonitorActivityTracker tracker = createTracker(() -> Optional.of(activeSince));

        boolean success = tracker.pingSession(transaction, "cid", session, "10.0.0.1", PORT, SHUTDOWN_AFTER,
                NOTIFY_AFTER);

        assertTrue(success);
        verify(sessions, never()).delete(anyString(), anyString());
    }

    @Test
    void pingSession_pollRecoversAfterFailures_stillEvaluatesNormally() {
        long idleSince = minutesAgo(90);
        Session session = createSession(idleSince);
        FlakyPoll poll = new FlakyPoll(idleSince);
        MonitorActivityTracker tracker = createTracker(poll);

        // Three failures in a row: the session must survive all of them.
        for (int i = 0; i < 3; i++) {
            tracker.pingSession(transaction, "cid", session, "10.0.0.1", PORT, SHUTDOWN_AFTER, NOTIFY_AFTER);
        }
        verify(sessions, never()).delete(anyString(), anyString());

        // Once the monitor answers again, the normal decision resumes.
        poll.recovered = true;
        tracker.pingSession(transaction, "cid", session, "10.0.0.1", PORT, SHUTDOWN_AFTER, NOTIFY_AFTER);
        verify(sessions).delete(anyString(), eq(SESSION_NAME));
    }

    // ========== helpers ==========

    @FunctionalInterface
    private interface PollStub {
        Optional<Long> poll() throws IOException;
    }


    private static final class FlakyPoll implements PollStub {
        private final long timestamp;
        private boolean recovered;

        private FlakyPoll(long timestamp) {
            this.timestamp = timestamp;
        }

        @Override
        public Optional<Long> poll() throws IOException {
            if (!recovered) {
                throw new IOException("connection refused");
            }
            return Optional.of(timestamp);
        }
    }

    /**
     * Builds a tracker whose only stubbed behaviour is the HTTP poll; every decision under test is the real code.
     */
    private MonitorActivityTracker createTracker(PollStub poll) {
        MonitorActivityTracker tracker = new MonitorActivityTracker() {
            @Override
            protected Optional<Long> fetchLastActivity(Session session, String getActivityURL) throws IOException {
                return poll.poll();
            }
        };
        setFieldUnchecked(tracker, "resourceClient", resourceClient);
        setFieldUnchecked(tracker, "messagingService", messagingService);
        return tracker;
    }

    private Session createSession(long lastActivity) {
        Session session = new Session();
        session.setSpec(new SessionSpec(SESSION_NAME, "java-17-latest", "student@tum.de"));
        session.setMetadata(new ObjectMetaBuilder().withName(SESSION_NAME)
                .withUid("0f6c6f5e-3a1b-4f2c-9c1a-7d2b8e4f1a20").build());
        // getNonNullStatus() returns a fresh throwaway when no status is set, so attach a real one.
        SessionStatus status = new SessionStatus();
        status.setLastActivity(lastActivity);
        session.setStatus(status);
        return session;
    }

    private static long minutesAgo(int minutes) {
        return Instant.now().toEpochMilli() - TimeUnit.MINUTES.toMillis(minutes);
    }

    private void setFieldUnchecked(Object target, String fieldName, Object value) {
        try {
            // The fields live on the tracker itself, not on the anonymous subclass used for stubbing.
            Field field = MonitorActivityTracker.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
