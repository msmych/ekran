package uk.matvey.ekran.tmdb;

import uk.matvey.ekran.domain.SearchResultPage;
import uk.matvey.ekran.domain.SearchType;
import uk.matvey.ekran.repository.SearchRepository;

public class TmdbSearchRepository implements SearchRepository {

    private final TmdbClient client;
    private final TmdbMapper mapper;

    public TmdbSearchRepository(TmdbClient client, TmdbMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public SearchResultPage search(String query, int page, SearchType type) {
        return switch (type) {
            case MOVIE -> mapper.toSearchResults(client.searchMovies(query, page));
            case PERSON -> mapper.toPersonSearchResults(client.searchPersons(query, page));
        };
    }
}
