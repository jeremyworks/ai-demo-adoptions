create table dog
(
    name        varchar,
    description text,
    dob         date,
    owner       varchar,
    gender      char,
    image       varchar(1024),
    id          integer not null constraint dog_pk primary key
);

alter table dog owner to admin;
