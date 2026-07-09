CREATE TABLE flyway_probe (
	id BIGINT PRIMARY KEY,
	note VARCHAR(100) NOT NULL
);

INSERT INTO flyway_probe (id, note)
VALUES (1, 'baseline migration applied');
