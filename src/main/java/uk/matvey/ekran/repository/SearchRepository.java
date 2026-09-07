package uk.matvey.ekran.repository;

import uk.matvey.ekran.domain.SearchResultPage;

public interface SearchRepository {

    SearchResultPage search(String query, int page);
}