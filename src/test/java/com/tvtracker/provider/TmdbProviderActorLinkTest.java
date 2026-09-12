package com.tvtracker.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TmdbProviderActorLinkTest {

    @Test
    void buildActorLink_usesTmdbPersonUrl() {
        String link = TmdbProvider.buildActorLink(58224L);

        assertThat(link).isEqualTo("https://www.themoviedb.org/person/58224");
    }

    @Test
    void buildActorLink_returnsNullForInvalidId() {
        assertThat(TmdbProvider.buildActorLink(0L)).isNull();
    }
}
