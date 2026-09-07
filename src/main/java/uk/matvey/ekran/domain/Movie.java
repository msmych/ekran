package uk.matvey.ekran.domain;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

public record Movie(
    long tmdbId,
    String title,
    String originalTitle,
    LocalDate releaseDate,
    Integer runtimeMinutes,
    List<String> genres,
    Double rating,
    String overview,
    URI posterUrl,
    URI backdropUrl,
    List<PersonLink> directors,
    List<PersonLink> writers,
    List<PersonLink> cast
) {
}