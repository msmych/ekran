package uk.matvey.ekran.domain;

import java.net.URI;
import java.time.LocalDate;

public record Person(
    long tmdbId,
    String name,
    Department knownFor,
    LocalDate born,
    LocalDate died,
    URI profileUrl,
    Filmography filmography
) {
}