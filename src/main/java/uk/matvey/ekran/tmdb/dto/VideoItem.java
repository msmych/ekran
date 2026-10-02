package uk.matvey.ekran.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VideoItem(
    String key,
    String name,
    String site,
    String type,
    Boolean official,
    @JsonProperty("iso_639_1") String iso6391,
    @JsonProperty("published_at") String publishedAt
) {
}
