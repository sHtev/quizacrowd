-- The scorer re-totals each player's points after every batch.
create index answers_player_id on answers (player_id);
