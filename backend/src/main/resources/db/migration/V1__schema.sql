-- 설계 §4 데이터 모델. 스키마의 주인은 Flyway다(불변 조건 10) — 이후 변경은 새 V파일로.
-- 상태 열거는 CHECK로, 유일성은 UNIQUE로 DB가 못박는다(SchemaConstraintTest가 우회 삽입으로 존재를 확인한다).

create table app_user (
    id            bigserial primary key,
    username      varchar(50)  not null unique,
    password_hash varchar(72)  not null,
    display_name  varchar(100) not null,
    role          varchar(16)  not null check (role in ('OPERATOR', 'APPROVER', 'ADMIN')),
    enabled       boolean      not null,
    created_at    timestamptz  not null
);

create table equipment (
    id               bigserial primary key,
    code             varchar(50)  not null unique,
    console_url      varchar(500) not null,
    model            varchar(100) not null,
    status           varchar(16)  not null check (status in ('RUNNING', 'STOPPED', 'MAINTENANCE')),
    console_user     varchar(100) not null,
    console_password varchar(200) not null,
    created_at       timestamptz  not null
);

create table recipe (
    id          bigserial primary key,
    part_no     varchar(50) not null,
    version     integer     not null check (version >= 1),
    params      jsonb       not null,
    status      varchar(16) not null check (status in ('DRAFT', 'ACTIVE', 'RETIRED')),
    created_by  bigint      not null references app_user (id),
    approved_by bigint      references app_user (id),
    created_at  timestamptz not null,
    unique (part_no, version)
);

create table rollout_job (
    id              bigserial primary key,
    recipe_id       bigint       not null references recipe (id),
    status          varchar(16)  not null check (status in ('PENDING', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED')),
    idempotency_key varchar(100) not null unique,
    requested_by    bigint       not null references app_user (id),
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null
);

create table rollout_task (
    id              bigserial primary key,
    job_id          bigint      not null references rollout_job (id),
    equipment_id    bigint      not null references equipment (id),
    status          varchar(16) not null check (status in ('PENDING', 'CLAIMED', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    attempt         integer     not null check (attempt >= 0),
    last_error      text,
    claimed_by      varchar(100),
    lease_expires_at timestamptz,
    -- 백오프 대기 시각(결정 대기 2ⓐ). 리스와 뜻이 겹치지 않게 컬럼을 나눈다.
    next_attempt_at timestamptz,
    created_at      timestamptz not null,
    updated_at      timestamptz not null,
    unique (job_id, equipment_id)
);

create table application_history (
    id              bigserial primary key,
    equipment_id    bigint      not null references equipment (id),
    part_no         varchar(50) not null,
    version         integer     not null,
    params_snapshot jsonb       not null,
    applied_at      timestamptz not null,
    applied_by      bigint      not null references app_user (id),
    result          varchar(16) not null check (result in ('APPLIED', 'NO_CHANGE'))
);

create table audit_log (
    id          bigserial primary key,
    at          timestamptz  not null,
    -- 워커 대행은 actor_id가 없다(actor_name='system', detail.on_behalf_of=<요청자>).
    actor_id    bigint       references app_user (id),
    actor_name  varchar(100) not null,
    action      varchar(50)  not null,
    target_type varchar(50)  not null,
    target_id   bigint,
    detail      jsonb        not null,
    result      varchar(16)  not null check (result in ('OK', 'DENIED', 'ERROR'))
);

-- append-only는 앱 코드가 아니라 DB가 막는다(불변 조건 9②).
create function audit_log_append_only() returns trigger as $$
begin
    raise exception 'audit_log는 append-only다(% 금지)', tg_op;
end;
$$ language plpgsql;

create trigger audit_log_no_update_delete
    before update or delete on audit_log
    for each row execute function audit_log_append_only();
