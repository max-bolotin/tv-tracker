package com.tvtracker.controller;

import java.util.List;

public record ImportResult(int showsImported, int moviesImported, List<String> failedTitles) {}
