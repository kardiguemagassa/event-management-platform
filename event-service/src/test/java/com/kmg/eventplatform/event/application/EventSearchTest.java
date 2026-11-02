package com.kmg.eventplatform.event.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventSearchTest {

    @Test
    void blankTextAndEmptyIdsMeanNoFilter() {
        EventSearch search = new EventSearch(null, "   ", null, false, Set.of());

        assertThat(search).isEqualTo(EventSearch.all());
    }

    @Test
    void textIsTrimmedAndIdsAreCopied() {
        Set<UUID> ids = new HashSet<>(Set.of(UUID.randomUUID()));

        EventSearch search = new EventSearch(null, "  Paris ", null, false, ids);
        ids.clear();

        assertThat(search.text()).isEqualTo("Paris");
        assertThat(search.ids()).hasSize(1);
    }
}
