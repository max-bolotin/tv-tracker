package com.tvtracker.controller;

import com.tvtracker.exception.ShowNotFoundException;
import com.tvtracker.model.Episode;
import com.tvtracker.model.Season;
import com.tvtracker.model.ShowSearchResult;
import com.tvtracker.model.TrackedShow;
import com.tvtracker.model.WatchStatus;
import com.tvtracker.provider.MetadataService;
import com.tvtracker.security.CurrentUserContext;
import com.tvtracker.storage.JsonStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ShowControllerTest {

  private static final String USER_ID = "user-123";

  private JsonStorageService storage;
  private MetadataService metadata;
  private ShowController controller;
  private MockedStatic<CurrentUserContext> userContext;

  @BeforeEach
  void setUp() {
    storage = mock(JsonStorageService.class);
    metadata = mock(MetadataService.class);
    controller = new ShowController(storage, metadata);

    userContext = mockStatic(CurrentUserContext.class);
    userContext.when(CurrentUserContext::currentUserId).thenReturn(USER_ID);
  }

  @AfterEach
  void tearDown() {
    userContext.close();
  }

  private TrackedShow show(String id, WatchStatus status) {
    TrackedShow show = new TrackedShow();
    show.id = id;
    show.watchStatus = status;
    show.seasons = new java.util.ArrayList<>();
    return show;
  }

  private Season season(int number, Episode... episodes) {
    Season season = new Season();
    season.number = number;
    season.episodes = new java.util.ArrayList<>(List.of(episodes));
    return season;
  }

  private Episode episode(int number, boolean watched) {
    Episode episode = new Episode();
    episode.number = number;
    episode.watched = watched;
    return episode;
  }

  // ---- popular ----

  @Test
  void popular_delegatesToMetadataServiceWithGivenLimit() {
    List<ShowSearchResult> expected = List.of(new ShowSearchResult());
    when(metadata.fetchPopular(5)).thenReturn(expected);

    List<ShowSearchResult> result = controller.popular(5);

    assertThat(result).isSameAs(expected);
    verify(metadata).fetchPopular(5);
  }

  // ---- getAll ----

  @Test
  void getAll_withoutStatusFilter_returnsEverythingForCurrentUser() throws Exception {
    List<TrackedShow> all = List.of(show("1", WatchStatus.NOT_WATCHED), show("2", WatchStatus.DROPPED));
    when(storage.loadAll(USER_ID)).thenReturn(all);

    List<TrackedShow> result = controller.getAll(null);

    assertThat(result).containsExactlyElementsOf(all);
  }

  @Test
  void getAll_withStatusFilter_returnsOnlyMatchingShows() throws Exception {
    TrackedShow notWatched = show("1", WatchStatus.NOT_WATCHED);
    TrackedShow dropped = show("2", WatchStatus.DROPPED);
    when(storage.loadAll(USER_ID)).thenReturn(List.of(notWatched, dropped));

    List<TrackedShow> result = controller.getAll(WatchStatus.DROPPED);

    assertThat(result).containsExactly(dropped);
  }

  // ---- getOne ----

  @Test
  void getOne_returnsShow_whenFound() throws Exception {
    TrackedShow found = show("1", WatchStatus.NOT_WATCHED);
    when(storage.findById(USER_ID, "1")).thenReturn(Optional.of(found));

    TrackedShow result = controller.getOne("1");

    assertThat(result).isSameAs(found);
  }

  @Test
  void getOne_throwsShowNotFoundException_whenMissing() throws Exception {
    when(storage.findById(USER_ID, "missing")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.getOne("missing"))
        .isInstanceOf(ShowNotFoundException.class);
  }

  // ---- search ----

  @Test
  void search_delegatesToMetadataService() {
    List<ShowSearchResult> expected = List.of(new ShowSearchResult());
    when(metadata.search("wednesday")).thenReturn(expected);

    List<ShowSearchResult> result = controller.search("wednesday");

    assertThat(result).isSameAs(expected);
  }

  // ---- addShow ----

  @Test
  void addShow_fetchesDetailsAssignsIdAndPersistsAsNotWatched() throws Exception {
    TrackedShow fetched = show(null, null);
    when(metadata.fetchDetails(42L, null)).thenReturn(fetched);
    when(storage.save(eq(USER_ID), any(TrackedShow.class))).thenAnswer(inv -> inv.getArgument(1));

    TrackedShow result = controller.addShow(new ShowController.AddShowRequest(42L, null));

    assertThat(result.id).isNotNull();
    assertThat(result.watchStatus).isEqualTo(WatchStatus.NOT_WATCHED);
    verify(storage).save(eq(USER_ID), eq(fetched));
  }

  // ---- delete ----

  @Test
  void delete_returnsNoContent_whenDeleted() throws Exception {
    when(storage.delete(USER_ID, "1")).thenReturn(true);

    ResponseEntity<Void> result = controller.delete("1");

    assertThat(result.getStatusCode().value()).isEqualTo(204);
  }

  @Test
  void delete_throwsShowNotFoundException_whenNothingDeleted() throws Exception {
    when(storage.delete(USER_ID, "missing")).thenReturn(false);

    assertThatThrownBy(() -> controller.delete("missing"))
        .isInstanceOf(ShowNotFoundException.class);
  }

  // ---- toggleEpisode ----

  @Test
  void toggleEpisode_marksMatchingEpisodeWatchedAndRecalculatesStatus() throws Exception {
    Episode ep1 = episode(1, false);
    TrackedShow show = show("1", WatchStatus.NOT_WATCHED);
    show.seasons.add(season(1, ep1));
    when(storage.findById(USER_ID, "1")).thenReturn(Optional.of(show));
    when(storage.save(eq(USER_ID), any())).thenAnswer(inv -> inv.getArgument(1));

    controller.toggleEpisode("1", 1, 1, new ShowController.EpisodeToggle(true));

    assertThat(ep1.watched).isTrue();
    verify(storage).save(eq(USER_ID), eq(show));
  }

  @Test
  void toggleEpisode_doesNothing_whenSeasonOrEpisodeNotFound() throws Exception {
    TrackedShow show = show("1", WatchStatus.NOT_WATCHED);
    show.seasons.add(season(1, episode(1, false)));
    when(storage.findById(USER_ID, "1")).thenReturn(Optional.of(show));
    when(storage.save(eq(USER_ID), any())).thenAnswer(inv -> inv.getArgument(1));

    controller.toggleEpisode("1", 99, 1, new ShowController.EpisodeToggle(true));

    assertThat(show.seasons.getFirst().episodes.getFirst().watched).isFalse();
  }

  // ---- toggleSeason ----

  @Test
  void toggleSeason_marksAllEpisodesInThatSeasonOnly() throws Exception {
    Episode s1e1 = episode(1, false);
    Episode s1e2 = episode(2, false);
    Episode s2e1 = episode(1, false);
    TrackedShow show = show("1", WatchStatus.NOT_WATCHED);
    show.seasons.add(season(1, s1e1, s1e2));
    show.seasons.add(season(2, s2e1));
    when(storage.findById(USER_ID, "1")).thenReturn(Optional.of(show));
    when(storage.save(eq(USER_ID), any())).thenAnswer(inv -> inv.getArgument(1));

    controller.toggleSeason("1", 1, new ShowController.EpisodeToggle(true));

    assertThat(s1e1.watched).isTrue();
    assertThat(s1e2.watched).isTrue();
    assertThat(s2e1.watched).isFalse();
  }

  // ---- toggleAllWatched ----

  @Test
  void toggleAllWatched_marksEveryEpisodeAcrossAllSeasons() throws Exception {
    Episode s1e1 = episode(1, false);
    Episode s2e1 = episode(1, false);
    TrackedShow show = show("1", WatchStatus.NOT_WATCHED);
    show.seasons.add(season(1, s1e1));
    show.seasons.add(season(2, s2e1));
    when(storage.findById(USER_ID, "1")).thenReturn(Optional.of(show));
    when(storage.save(eq(USER_ID), any())).thenAnswer(inv -> inv.getArgument(1));

    controller.toggleAllWatched("1", new ShowController.EpisodeToggle(true));

    assertThat(s1e1.watched).isTrue();
    assertThat(s2e1.watched).isTrue();
  }

  // ---- reorder ----

  @Test
  void reorder_delegatesOrderedIdsToStorage() throws Exception {
    List<String> ids = List.of("3", "1", "2");

    ResponseEntity<Void> result = controller.reorder(ids);

    verify(storage).reorder(USER_ID, ids);
    assertThat(result.getStatusCode().value()).isEqualTo(204);
  }

  // ---- refreshShow ----

  @Test
  void refreshShow_keepsIdWatchStatusAndCarriesOverWatchedFlags() throws Exception {
    Episode existingEp = episode(1, true);
    TrackedShow existing = show("1", WatchStatus.WATCHING_NOW);
    existing.seasons.add(season(1, existingEp));
    existing.tmdbId = 42L;

    Episode freshEp = episode(1, false);
    TrackedShow fresh = show(null, null);
    fresh.seasons.add(season(1, freshEp));

    when(storage.findById(USER_ID, "1")).thenReturn(Optional.of(existing));
    when(metadata.fetchDetails(42L, null)).thenReturn(fresh);
    when(storage.save(eq(USER_ID), any())).thenAnswer(inv -> inv.getArgument(1));

    TrackedShow result = controller.refreshShow("1");

    assertThat(result.id).isEqualTo("1");
    assertThat(result.watchStatus).isEqualTo(WatchStatus.WATCHING_NOW);
    assertThat(freshEp.watched).isTrue(); // carried over from the existing episode
  }

  @Test
  void refreshShow_throwsShowNotFoundException_whenShowMissing() throws Exception {
    when(storage.findById(USER_ID, "missing")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.refreshShow("missing"))
        .isInstanceOf(ShowNotFoundException.class);
  }
}