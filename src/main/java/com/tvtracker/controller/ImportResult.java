package com.tvtracker.controller;

import java.util.List;

public record ImportResult(int total, List<String> failedTitles) {}
