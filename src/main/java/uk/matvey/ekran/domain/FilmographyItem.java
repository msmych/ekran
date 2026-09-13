package uk.matvey.ekran.domain;

import java.net.URI;

public record FilmographyItem(
    long movieTmdbId,
    String title,
    Integer year,
    URI posterUrl
) {
}