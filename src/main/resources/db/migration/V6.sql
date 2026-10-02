-- personal context: an optional note per mark, an optional description per
-- playlist, and an optional note per playlist membership. All nullable —
-- absent data stays absent, existing ordering (playlist_movies.position) is
-- untouched
ALTER TABLE marked_movies
    ADD COLUMN note TEXT;

ALTER TABLE playlists
    ADD COLUMN description TEXT;

ALTER TABLE playlist_movies
    ADD COLUMN note TEXT;