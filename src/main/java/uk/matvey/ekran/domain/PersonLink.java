package uk.matvey.ekran.domain;

public record PersonLink(
    long tmdbId,
    String name,
    String role,
    Department department
) {
}