package uk.matvey.ekran.domain;

import java.net.URI;

public record Person(
    long tmdbId,
    String name,
    Department knownFor,
    String biography,
    URI profileUrl,
    Filmography filmography
) {
}