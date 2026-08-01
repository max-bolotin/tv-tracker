package com.tvtracker.provider;

import com.tvtracker.model.ShowSearchResult;
import com.tvtracker.model.TrackedShow;

import java.util.List;

public interface MetadataProvider {
    List<ShowSearchResult> search(String query);
    TrackedShow fetchDetails(long externalId);
    /** Returns IDs of shows that have new episodes since yesterday */
    List<Long> fetchRecentlyUpdatedIds();
}
