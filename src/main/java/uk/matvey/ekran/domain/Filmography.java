package uk.matvey.ekran.domain;

import java.util.List;

public record Filmography(
    List<FilmographyItem> directing,
    List<FilmographyItem> writing,
    List<FilmographyItem> acting
) {
}