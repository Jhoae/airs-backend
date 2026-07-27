-- 운영 DB와 관계없는 로컬 MQTT 부하 실험용 캠퍼스·공간·노드 100개를 준비한다.
INSERT INTO campuses (name, latitude, longitude, radius_meter, created_at, deleted_at)
VALUES ('AIRS MQTT Staging Campus', 37.5500000, 126.9400000, 1000, NOW(6), NULL)
ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id), deleted_at = NULL;

SET @stage_campus_id = LAST_INSERT_ID();

INSERT INTO buildings (campus_id, name, created_at, deleted_at)
VALUES (@stage_campus_id, 'MQTT 부하 실험 건물', NOW(6), NULL)
ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id), deleted_at = NULL;

SET @stage_building_id = LAST_INSERT_ID();

-- 로컬 HTTP 부하 실험에 쓸 전용 ROOT_ADMIN 계정을 만든다. 비밀번호는 1234이며 운영 계정과 무관하다.
INSERT INTO users (nickname, email, password_hash, phone, campus_id, role, created_at)
VALUES (
    'stage-admin',
    'stage-admin@example.invalid',
    '$2y$10$RDPecXpdMltqjQghzF1WE.ScpgTfDdfZ.kjhjKVs3o37UxnnwJ6b.',
    NULL,
    @stage_campus_id,
    'ROOT_ADMIN',
    NOW(6)
)
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), campus_id = VALUES(campus_id), role = VALUES(role);

-- 반복 실행해도 같은 node/space를 재사용하도록 100개의 fixture 노드를 idempotent하게 만든다.
DROP PROCEDURE IF EXISTS seed_stage_nodes;

DELIMITER //
CREATE PROCEDURE seed_stage_nodes()
BEGIN
    DECLARE node_index INT DEFAULT 1;
    -- Flyway V1 테이블과 같은 collation을 선언해 MySQL 8 기본값 충돌을 막는다.
    DECLARE stage_suffix CHAR(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    DECLARE stage_node_id VARCHAR(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    DECLARE stage_space_code VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    DECLARE stage_space_id BIGINT;

    WHILE node_index <= 100 DO
        SET stage_suffix = LPAD(node_index, 3, '0');
        SET stage_node_id = CONCAT('stage_node_', stage_suffix);
        SET stage_space_code = CONCAT('STAGE-', stage_suffix);

        INSERT INTO spaces (
            campus_id, building_id, code, name, floor_label, space_type,
            latitude, longitude, created_at, deleted_at
        ) VALUES (
            @stage_campus_id, @stage_building_id, stage_space_code,
            CONCAT('부하 실험 공간 ', stage_suffix), '1층', 'OTHER',
            37.5500000, 126.9400000, NOW(6), NULL
        )
        ON DUPLICATE KEY UPDATE name = VALUES(name), deleted_at = NULL;

        SELECT id INTO stage_space_id
        FROM spaces
        WHERE campus_id = @stage_campus_id AND code = stage_space_code;

        INSERT INTO airs_nodes (id, hardware_version, firmware_version, created_at, deleted_at)
        VALUES (stage_node_id, 'stage-hw-1.0', 'stage-fw-1.0', NOW(6), NULL)
        ON DUPLICATE KEY UPDATE deleted_at = NULL;

        INSERT INTO node_installations (node_id, space_id, installed_by_user_id, installed_at, is_active)
        SELECT stage_node_id, stage_space_id, NULL, NOW(6), TRUE
        WHERE NOT EXISTS (
            SELECT 1
            FROM node_installations
            WHERE node_id = stage_node_id AND is_active = TRUE
        );

        SET node_index = node_index + 1;
    END WHILE;
END//
DELIMITER ;

CALL seed_stage_nodes();
DROP PROCEDURE IF EXISTS seed_stage_nodes;
