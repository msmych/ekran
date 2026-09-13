package uk.matvey.ekran.domain;

public record MovieVideo(
    String key,
    String name,
    String type,
    boolean official,
    String language,
    String publishedAt
) {
}