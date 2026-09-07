package uk.matvey.ekran.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record MovieSearchItem(
    long id,
    String title,
    String originalTitle,
    String releaseDate,
    String posterPath,
    Double voteAverage
) {
}