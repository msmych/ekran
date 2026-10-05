package uk.matvey.ekran.domain;

import java.net.URI;
import java.time.LocalDate;

public record FilmographyItem(
    long movieTmdbId,
    String title,
    LocalDate releaseDate,
    URI posterUrl
) {
}
