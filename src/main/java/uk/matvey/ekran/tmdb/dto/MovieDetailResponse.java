package uk.matvey.ekran.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record MovieDetailResponse(
    long id,
    String title,
    String originalTitle,
    String originalLanguage,
    String overview,
    String releaseDate,
    Integer runtime,
    Double voteAverage,
    String posterPath,
    String backdropPath,
    List<Genre> genres,
    CreditsResponse credits,
    VideosResponse videos
) {
}