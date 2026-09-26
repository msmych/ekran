-- marked movies for authenticated users; movie_id is a TMDB id (no local movie metadata)
CREATE TABLE marked_movies (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    movie_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, movie_id)
);