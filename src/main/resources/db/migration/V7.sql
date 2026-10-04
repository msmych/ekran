-- movie notes are detached from marks: a note is the user's annotation of a
-- movie, independent of marking. Marks return to being the quick inbox;
-- playlist memberships keep their own, deliberately written notes.
-- Existing mark notes become movie notes, and the marks lose the column.
CREATE TABLE movie_notes (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    movie_id BIGINT NOT NULL,
    note TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, movie_id)
);

INSERT INTO movie_notes (user_id, movie_id, note)
SELECT user_id, movie_id, note FROM marked_movies WHERE note IS NOT NULL;

ALTER TABLE marked_movies DROP COLUMN note;