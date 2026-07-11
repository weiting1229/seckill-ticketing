package com.seckill.event.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 活動狀態機轉移規則(DRAFT → PUBLISHED → CLOSED,單向;同狀態為 no-op)。 */
class EventStatusTest {

    @Test
    void sameStatusIsAlwaysAllowed() {
        for (EventStatus s : EventStatus.values()) {
            assertThat(s.canTransitionTo(s)).isTrue();
        }
    }

    @Test
    void draftCanGoToPublishedOrClosed() {
        assertThat(EventStatus.DRAFT.canTransitionTo(EventStatus.PUBLISHED)).isTrue();
        assertThat(EventStatus.DRAFT.canTransitionTo(EventStatus.CLOSED)).isTrue();
    }

    @Test
    void publishedCanOnlyGoToClosed() {
        assertThat(EventStatus.PUBLISHED.canTransitionTo(EventStatus.CLOSED)).isTrue();
        assertThat(EventStatus.PUBLISHED.canTransitionTo(EventStatus.DRAFT)).isFalse();
    }

    @Test
    void closedIsTerminal() {
        assertThat(EventStatus.CLOSED.canTransitionTo(EventStatus.DRAFT)).isFalse();
        assertThat(EventStatus.CLOSED.canTransitionTo(EventStatus.PUBLISHED)).isFalse();
    }
}
