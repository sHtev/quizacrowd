create table tenants (
    slug          text primary key,
    name          text not null,
    host_key_hash text not null,
    created_at    timestamptz not null default now()
);

create table quizzes (
    id            uuid primary key,
    tenant_slug   text not null references tenants (slug),
    name          text not null,
    mode          text not null check (mode in ('PUB', 'STADIUM', 'TELLY')),
    state         text not null check (state in ('LOBBY', 'QUESTION_OPEN', 'QUESTION_CLOSED', 'ENDED')),
    current_index int,
    created_at    timestamptz not null default now()
);

create table questions (
    id            uuid primary key,
    quiz_id       uuid not null references quizzes (id) on delete cascade,
    idx           int not null,
    text          text not null,
    options       jsonb not null,
    correct_index int not null,
    started_at    timestamptz,
    deadline      timestamptz,
    unique (quiz_id, idx)
);

create table players (
    id         uuid primary key,
    quiz_id    uuid not null references quizzes (id) on delete cascade,
    name       text not null,
    created_at timestamptz not null default now()
);

create table answers (
    question_id uuid not null references questions (id) on delete cascade,
    player_id   uuid not null references players (id) on delete cascade,
    choice      int not null,
    received_at timestamptz not null,
    points      int not null,
    primary key (question_id, player_id)
);
