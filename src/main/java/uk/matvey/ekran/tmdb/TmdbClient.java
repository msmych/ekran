package uk.matvey.ekran.tmdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import uk.matvey.ekran.config.AppConfig;
import uk.matvey.ekran.domain.NotFoundException;
import uk.matvey.ekran.domain.TmdbAuthException;
import uk.matvey.ekran.domain.TmdbUnavailableException;
import uk.matvey.ekran.tmdb.dto.MovieDetailResponse;
import uk.matvey.ekran.tmdb.dto.MovieSearchResponse;
import uk.matvey.ekran.tmdb.dto.PersonDetailResponse;

public class TmdbClient {

    private static final Logger log = LoggerFactory.getLogger(TmdbClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AppConfig config;
    private final String baseUrl;

    public TmdbClient(HttpClient httpClient, ObjectMapper objectMapper, AppConfig config) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.config = config;
        this.baseUrl = stripTrailingSlash(config.tmdbBaseUrl());
    }

    public MovieSearchResponse searchMovies(String query, int page) {
        var params = new LinkedHashMap<String, String>();
        params.put("query", query);
        params.put("include_adult", "false");
        params.put("page", String.valueOf(page));
        return get("/search/movie", params, MovieSearchResponse.class, config.searchTimeout());
    }

    public MovieDetailResponse movieWithCredits(long tmdbId) {
        var params = new LinkedHashMap<String, String>();
        params.put("append_to_response", "credits");
        params.put("language", "en-US");
        return get("/movie/" + tmdbId, params, MovieDetailResponse.class, config.detailTimeout());
    }

    public PersonDetailResponse personWithMovieCredits(long tmdbId) {
        var params = new LinkedHashMap<String, String>();
        params.put("append_to_response", "movie_credits");
        params.put("language", "en-US");
        return get("/person/" + tmdbId, params, PersonDetailResponse.class, config.detailTimeout());
    }

    private <T> T get(String path, Map<String, String> params, Class<T> type, Duration timeout) {
        var request = HttpRequest.newBuilder(uri(path, params))
            .timeout(timeout)
            .header("Authorization", "Bearer " + config.tmdbApiToken())
            .header("Accept", "application/json")
            .GET()
            .build();
        var startedAt = System.nanoTime();
        try {
            var response = httpClient.send(request, BodyHandlers.ofString());
            var durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            var status = response.statusCode();
            if (status >= 200 && status < 300) {
                return objectMapper.readValue(response.body(), type);
            }
            log.warn("TMDB call failed: path={} status={} durationMs={}", path, status, durationMs);
            if (status == 404) {
                throw new NotFoundException("TMDB resource not found: " + path);
            }
            if (status == 401 || status == 403) {
                throw new TmdbAuthException("TMDB rejected credentials");
            }
            throw new TmdbUnavailableException("TMDB error: HTTP " + status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TmdbUnavailableException("TMDB call interrupted");
        } catch (IOException e) {
            var durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.warn("TMDB call failed: path={} durationMs={} error={}", path, durationMs, e.getMessage());
            throw new TmdbUnavailableException("TMDB request failed: " + e.getMessage());
        }
    }

    private URI uri(String path, Map<String, String> params) {
        var builder = new StringBuilder(baseUrl).append(path);
        if (!params.isEmpty()) {
            builder.append('?');
            for (var entry : params.entrySet()) {
                builder.append(encode(entry.getKey())).append('=').append(encode(entry.getValue())).append('&');
            }
            builder.setLength(builder.length() - 1);
        }
        return URI.create(builder.toString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}