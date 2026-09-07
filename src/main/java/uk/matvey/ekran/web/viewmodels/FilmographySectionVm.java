package uk.matvey.ekran.web.viewmodels;

import java.util.List;

public record FilmographySectionVm(
    String label,
    List<FilmographyItemVm> items
) {
}