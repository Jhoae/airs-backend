# AIRS Backend

> AIRS 백엔드 서버 프로젝트

## API Docs

### ✨ [AIRS Backend Swagger UI](https://petstore.swagger.io/?url=https://raw.githubusercontent.com/Jhoae/airs-backend/main/openapi.yaml)

현재 백엔드 소스의 기준 저장소는 [`Jhoae/airs-backend`](https://github.com/Jhoae/airs-backend)입니다.
프로젝트 종료 후에는 운영 배포와 CI/CD를 수행하지 않고, 로컬 테스트를 통과한 코드만 개인 저장소에서 관리합니다.

## 기술스택

<p>
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white"/>&nbsp;
  <img alt="Spring Boot 3.5.13" src="https://img.shields.io/badge/Spring_Boot-3.5.13-6DB33F?logo=springboot&logoColor=white"/>&nbsp;
  <img alt="Spring Security" src="https://img.shields.io/badge/Spring_Security-6DB33F?logo=springsecurity&logoColor=white"/>&nbsp;
  <img alt="Spring Data JPA" src="https://img.shields.io/badge/Spring_Data_JPA-59666C"/>&nbsp;
  <img alt="JWT" src="https://img.shields.io/badge/JWT-000000?logo=jsonwebtokens&logoColor=white"/>&nbsp;
  <img alt="MySQL" src="https://img.shields.io/badge/MySQL-4479A1?logo=mysql&logoColor=white"/>&nbsp;
  <img alt="InfluxDB" src="https://img.shields.io/badge/InfluxDB-22ADF6?logo=influxdb&logoColor=white"/>&nbsp;
  <img alt="Redis" src="https://img.shields.io/badge/Redis-DC382D?logo=redis&logoColor=white"/>&nbsp;
  <img alt="MQTT" src="https://img.shields.io/badge/MQTT-660066?logo=mqtt&logoColor=white"/>&nbsp;
  <img alt="Flyway" src="https://img.shields.io/badge/Flyway-CC0200?logo=flyway&logoColor=white"/>&nbsp;
  <img alt="Docker" src="https://img.shields.io/badge/Docker-2496ED?logo=docker&logoColor=white"/>&nbsp;
  <img alt="Testcontainers" src="https://img.shields.io/badge/Testcontainers-3E86A0"/>&nbsp;
</p>

## 개발·실행 환경

| 구분 | 환경 |
|---|---|
| Application | Java 21 · Spring Boot 3.5.13 · Gradle Wrapper |
| Data & Messaging | MySQL 8.4 · InfluxDB 2.7 · Redis 7.4 · Mosquitto 2 |
| Local & Staging | Docker Compose · Caddy 2.8 |

## 시스템 구성도

![AIRS Backend System Architecture](docs/architecture/system-architecture.png)

## ERD

![AIRS Backend ERD](docs/erd/airs-erd.png)

## Usage

```sh
cp .env.example .env
# .env의 DB 비밀번호, InfluxDB token, JWT secret을 변경합니다.
docker compose up -d --build
curl http://localhost:8080/actuator/health

./gradlew test

docker compose down
```
