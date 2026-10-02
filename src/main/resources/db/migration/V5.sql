CREATE TABLE playlists (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX playlists_user_id_idx ON playlists (user_id);

-- position keeps the stable add order for shared snapshot URLs and drives
-- reordering: moves swap positions under the playlist row lock (see
-- PgPlaylistsRepository), gaps are allowed
CREATE TABLE playlist_movies (
    playlist_id BIGINT NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
    movie_id BIGINT NOT NULL,
    position INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (playlist_id, movie_id),
    UNIQUE (playlist_id, position)
);