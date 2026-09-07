package uk.matvey.ekran.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PersonDetailResponse(
    long id,
    String name,
    String knownForDepartment,
    String biography,
    String profilePath,
    PersonMovieCreditsResponse movieCredits
) {
}