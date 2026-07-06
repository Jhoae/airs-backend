# AIRS Backend

> AIRS 백엔드 서버 프로젝트

## API Docs

### ✨ [AIRS Backend Swagger UI](https://petstore.swagger.io/?url=https://raw.githubusercontent.com/airs-release/backend/main/openapi.yaml)

## 기술스택

<p>
  <img src="https://img.shields.io/badge/-Java_21-orange"/>&nbsp;
  <img src="https://img.shields.io/badge/-Spring_Boot-6DB33F"/>&nbsp;
  <img src="https://img.shields.io/badge/-Spring_Security-2E8B57"/>&nbsp;
  <img src="https://img.shields.io/badge/-Spring_Data_JPA-59666C"/>&nbsp;
  <img src="https://img.shields.io/badge/-JWT-000000"/>&nbsp;
  <img src="https://img.shields.io/badge/-MySQL-4479A1"/>&nbsp;
  <img src="https://img.shields.io/badge/-InfluxDB-22ADF6"/>&nbsp;
  <img src="https://img.shields.io/badge/-MQTT-660066"/>&nbsp;
</p>

## 개발환경

- backend
  - Java 21
  - Gradle
  - Spring Boot 3.5.13
- database
  - MySQL
  - InfluxDB
- messaging
  - MQTT Broker

## 시스템 구성도

![AIRS Backend System Architecture](docs/architecture/system-architecture.png)

## ERD

![AIRS Backend ERD](docs/erd/airs-erd.png)

## Usage


Docker Compose는 다음 서비스를 함께 실행합니다.

- MySQL
- InfluxDB
- MQTT Broker

따라서 Docker Compose 실행 전 `.env` 값을 실행 환경에 맞게 준비해야 합니다.

필수로 확인할 값:

- `AIRS_MYSQL_DATABASE`
- `AIRS_MYSQL_USER`
- `AIRS_MYSQL_PASSWORD`
- `AIRS_MYSQL_ROOT_PASSWORD`
- `AIRS_DDL_AUTO`
- `AIRS_JWT_SECRET_KEY`
- `AIRS_JWT_ACCESS_TOKEN_EXPIRATION_MINUTES`
- `AIRS_INFLUX_USERNAME`
- `AIRS_INFLUX_PASSWORD`
- `AIRS_INFLUX_TOKEN`
- `AIRS_INFLUX_ORG`
- `AIRS_INFLUX_BUCKET`
- `AIRS_INFLUX_MEASUREMENT`
- `AIRS_INFLUX_NODE_ID_TAG`
- `AIRS_MQTT_TOPIC`
- `AIRS_JAVA_TOOL_OPTIONS`

예시 `.env`는 `.env.example`을 참고합니다.
실제 값은 `.env`에만 작성하고, `.env`는 GitHub에 올리지 않습니다.


### Docker / Compose 실행

1. 라즈베리파이에 실제 `.env` 파일 준비

```sh
cp .env.example .env
```

2. `.env`의 예시값을 실제 값으로 수정

```text
AIRS_INFLUX_TOKEN, DB 비밀번호, JWT secret 등은 실제 운영 값으로 변경합니다.
```

3. compose로 전체 서비스 실행

```sh
docker compose up -d --build
```
