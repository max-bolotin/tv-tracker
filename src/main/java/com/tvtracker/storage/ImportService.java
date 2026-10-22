package com.tvtracker.storage;

import com.tvtracker.model.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ImportService {

  public record ImportResult(List<TrackedShow> shows, List<String> stubTitles) {

  }

  public record ResolvedImport(List<ImportedShow> shows, List<String> stubTitles) {

  }

  public record ImportedShow(TrackedShow show, WatchStatus sourceSection) {

  }

  public ImportResult resolve(ImportExportPayload payload) {
    ResolvedImport resolved = resolveWithSource(payload);
    return new ImportResult(resolved.shows().stream().map(ImportedShow::show).toList(),
        resolved.stubTitles());
  }

  public ResolvedImport resolveWithSource(ImportExportPayload payload) {
    List<ImportedShow> result = new ArrayList<>();
    addAll(result, payload.shows, WatchStatus.WATCHING_NOW);
    addAll(result, payload.watchlistShows, WatchStatus.NOT_WATCHED);
    addAll(result, payload.upToDateShows, WatchStatus.UP_TO_DATE);
    addAll(result, payload.finishedShows, WatchStatus.FINISHED);
    addAll(result, payload.stoppedShows, WatchStatus.DROPPED);
    return new ResolvedImport(result, List.of());
  }

  private void addAll(
      List<ImportedShow> result,
      List<ImportExportPayload.ImportShow> imports,
      WatchStatus sourceSection) {
      if (imports == null) {
          return;
      }
    for (ImportExportPayload.ImportShow imported : imports) {
      TrackedShow show = fromImport(imported, sourceSection);
      result.add(new ImportedShow(show, sourceSection));
    }
  }

  private TrackedShow fromImport(ImportExportPayload.ImportShow imported,
      WatchStatus sourceSection) {
    TrackedShow show = new TrackedShow();
    show.id = UUID.randomUUID().toString();
    show.tmdbId = imported.tmdbId;
    show.tvmazeId = imported.tvmazeId;
    show.imdbId = imported.imdbId;
    show.title = imported.title;
    show.firstAirYear = imported.year;
    show.posterPath = imported.posterPath;
    show.overview = imported.overview;
    show.totalSeasons = imported.totalSeasons == null
        ? (imported.seasons == null ? 0 : imported.seasons.size())
        : imported.totalSeasons;
    show.productionStatus = parseProductionStatus(imported.productionStatus);
    show.watchStatus = sourceSection;
    show.imdbRating = imported.imdbRating;
    show.rtRating = imported.rtRating;
    show.ratingsUpdatedAt = imported.ratingsUpdatedAt;
    show.ratingLastFetched = imported.ratingLastFetched;
    show.personalRating = imported.personalRating;
    show.cast = imported.cast == null ? new ArrayList<>() : new ArrayList<>(imported.cast);
    show.seasons = imported.seasons == null
        ? new ArrayList<>()
        : imported.seasons.stream()
            .map(this::fromImport)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

    if (imported.seasons == null || imported.seasons.isEmpty()) {
      markWatchedSeasons(show, imported.watchedSeasons);
    }
    return show;
  }

  private ProductionStatus parseProductionStatus(String value) {
      if (value == null || value.isBlank()) {
          return ProductionStatus.ONGOING;
      }
    try {
      return ProductionStatus.valueOf(value);
    } catch (IllegalArgumentException ignored) {
      return ProductionStatus.ONGOING;
    }
  }

  private Season fromImport(ImportExportPayload.SeasonDetail imported) {
    Season season = new Season(imported.number);
    season.imdbRating = imported.imdbRating;
    season.rtRating = imported.rtRating;
    season.ratingsUpdatedAt = imported.ratingsUpdatedAt;
    if (imported.episodes != null) {
      season.episodes = imported.episodes.stream().map(episode -> {
        Episode result = new Episode(episode.number, episode.name, episode.airDate);
        result.watched = episode.watched;
        return result;
      }).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }
    return season;
  }

  private void markWatchedSeasons(TrackedShow show, List<Integer> watchedSeasons) {
      if (watchedSeasons == null || watchedSeasons.isEmpty()) {
          return;
      }
    Set<Integer> watched = new HashSet<>(watchedSeasons);
    for (Season season : show.seasons) {
      if (watched.contains(season.number)) {
        season.episodes.forEach(episode -> episode.watched = true);
      }
    }
  }

  public ImportExportPayload toPayload(List<TrackedShow> shows) {
    ImportExportPayload payload = new ImportExportPayload();
    payload.shows = new ArrayList<>();
    payload.watchlistShows = new ArrayList<>();
    payload.upToDateShows = new ArrayList<>();
    payload.finishedShows = new ArrayList<>();
    payload.stoppedShows = new ArrayList<>();

    for (TrackedShow show : shows) {
      List<Season> seasons = show.seasons == null ? List.of() : show.seasons;
      ImportExportPayload.ImportShow entry = new ImportExportPayload.ImportShow();
      entry.title = show.title;
      entry.year = show.firstAirYear;
      entry.totalSeasons = show.totalSeasons;
      entry.tmdbId = show.tmdbId;
      entry.tvmazeId = show.tvmazeId;
      entry.imdbId = show.imdbId;
      entry.posterPath = show.posterPath;
      entry.overview = show.overview;
      entry.productionStatus = show.productionStatus == null ? null : show.productionStatus.name();
      entry.imdbRating = show.imdbRating;
      entry.rtRating = show.rtRating;
      entry.ratingsUpdatedAt = show.ratingsUpdatedAt;
      entry.ratingLastFetched = show.ratingLastFetched;
      entry.personalRating = show.personalRating;
      entry.cast = show.cast == null ? new ArrayList<>() : new ArrayList<>(show.cast);
      entry.seasons = toSeasonDetails(seasons);
      if (show.watchStatus == WatchStatus.WATCHING_NOW) {
        List<Integer> watchedSeasons = seasons.stream()
            .filter(Season::allWatched)
            .map(season -> season.number)
            .toList();
        entry.watchedSeasons = watchedSeasons.isEmpty() ? null : watchedSeasons;
      }

      switch (show.watchStatus) {
        case WATCHING_NOW -> payload.shows.add(entry);
        case NOT_WATCHED -> payload.watchlistShows.add(entry);
        case UP_TO_DATE -> payload.upToDateShows.add(entry);
        case FINISHED -> payload.finishedShows.add(entry);
        case DROPPED -> payload.stoppedShows.add(entry);
      }
    }
    return payload;
  }

  private List<ImportExportPayload.SeasonDetail> toSeasonDetails(List<Season> seasons) {
      if (seasons == null) {
          return List.of();
      }
    return seasons.stream().map(season -> {
      ImportExportPayload.SeasonDetail result = new ImportExportPayload.SeasonDetail();
      result.number = season.number;
      result.imdbRating = season.imdbRating;
      result.rtRating = season.rtRating;
      result.ratingsUpdatedAt = season.ratingsUpdatedAt;
      result.episodes =
          season.episodes == null ? List.of() : season.episodes.stream().map(episode -> {
            ImportExportPayload.EpisodeDetail detail = new ImportExportPayload.EpisodeDetail();
            detail.number = episode.number;
            detail.name = episode.name;
            detail.watched = episode.watched;
            detail.airDate = episode.airDate;
            return detail;
          }).toList();
      return result;
    }).toList();
  }
}
