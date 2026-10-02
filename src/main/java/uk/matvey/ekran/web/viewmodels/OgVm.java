package uk.matvey.ekran.web.viewmodels;

public record OgVm(
    String title,
    String description,
    String image,
    String url,
    String type,
    String siteName
) {

    // TMDB backdrops are w780 (780px wide): >= 600px wide gets Telegram's large
    // preview above the text; narrower images (e.g. a w342 poster fallback)
    // render as the compact preview beside it
    public static OgVm movie(MovieDetailVm vm, String canonicalUrl) {
        var title = vm.year() == null ? vm.title() : vm.title() + " (" + vm.year() + ")";
        return new OgVm(
            title,
            truncate(vm.overview(), 300),
            vm.backdropUrl() != null ? vm.backdropUrl() : vm.posterUrl(),
            canonicalUrl,
            "video.movie",
            "ekran"
        );
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1).trim() + "…";
    }
}
