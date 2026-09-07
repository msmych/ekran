package uk.matvey.ekran.domain;

public record FilmographyItem(
    long movieTmdbId,
    String title,
    Integer year,
    String role
) {
}