package uk.matvey.ekran.domain;

import java.net.URI;

public record SearchResult(
    long tmdbId,
    SearchType type,
    String title,
    Integer year,
    String subtitle,
    URI thumbUrl
) {
}