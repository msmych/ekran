package uk.matvey.ekran.web.viewmodels;

import uk.matvey.ekran.domain.PersonLink;

public record PersonLinkVm(
    String name,
    String role,
    String href
) {

    public static PersonLinkVm of(PersonLink link, String pathPrefix) {
        return new PersonLinkVm(
            link.name(),
            link.role(),
            "/persons/" + link.tmdbId() + pathPrefix
        );
    }
}