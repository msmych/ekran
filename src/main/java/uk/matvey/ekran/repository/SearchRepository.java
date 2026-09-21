package uk.matvey.ekran.repository;

import uk.matvey.ekran.domain.SearchResultPage;
import uk.matvey.ekran.domain.SearchType;

public interface SearchRepository {

    SearchResultPage search(String query, int page, SearchType type);
}