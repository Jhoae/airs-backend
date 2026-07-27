import http from 'k6/http';
import { check } from 'k6';

// 한 번의 로그인으로 읽기 시나리오 전체가 사용할 JWT를 발급받습니다.
export function login(config) {
  // 로그인 JSON은 실행 메모리에서만 만들고 파일이나 로그에 쓰지 않습니다.
  const payload = JSON.stringify({
    email: config.email,
    password: config.password,
  });
  // 일반 API와 같은 JSON 요청 형식을 적용합니다.
  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
    tags: {
      endpoint: 'auth-login-setup',
    },
  };
  // 부하 측정 대상이 아닌 setup 단계에서만 로그인 API를 한 번 호출합니다.
  const response = http.post(`${config.baseUrl}/airs/auth/login`, payload, params);
  // 토큰 발급 성공 여부와 필수 필드를 동시에 확인합니다.
  const passed = check(response, {
    'setup login status is 200': (res) => res.status === 200,
    'setup login has access token': (res) => {
      try {
        return Boolean(res.json('accessToken'));
      } catch (_) {
        return false;
      }
    },
  });

  // 이후 모든 요청이 잘못된 토큰으로 실행되는 일을 막기 위해 즉시 실패합니다.
  if (!passed) {
    throw new Error(`setup login failed with status ${response.status}`);
  }

  // setup 반환값은 k6가 각 VU에 복사하지만 결과 summary에는 기록하지 않습니다.
  return response.json('accessToken');
}

// 인증된 GET API에 공통으로 전달할 헤더와 endpoint tag를 만듭니다.
export function authorizedGetParams(token, endpoint) {
  // JWT는 실행 중 HTTP 헤더로만 사용합니다.
  return {
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'application/json',
    },
    tags: {
      endpoint,
    },
  };
}
