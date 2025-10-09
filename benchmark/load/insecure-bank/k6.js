import http from 'k6/http';
import {checkResponse, isOk, isRedirect} from "../../utils/k6.js";

const variants = {
  "no_agent": {
    "APP_URL": 'http://localhost:8080',
  },
  "tracing": {
    "APP_URL": 'http://localhost:8081',
  },
  "profiling": {
    "APP_URL": 'http://localhost:8082',
  },
  "iast": {
    "APP_URL": 'http://localhost:8083',
  },
  "iast_GLOBAL": {
    "APP_URL": 'http://localhost:8084',
  },
  "iast_FULL": {
    "APP_URL": 'http://localhost:8085',
  },
}

export const options = function (variants) {
  let scenarios = {};
  for (const variant of Object.keys(variants)) {
    scenarios[`load--insecure-bank--${variant}--warmup`] = {
      executor: 'constant-vus',  // https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/#all-executors
      vus: 5,
      duration: '60s',
      gracefulStop: '2s',
      env: {
        "APP_URL": variants[variant]["APP_URL"]
      }
    };

    scenarios[`load--insecure-bank--${variant}--high_load`] = {
      executor: 'constant-vus',
      vus: 5,
      startTime: '62s',
      duration: '60s',
      gracefulStop: '2s',
      env: {
        "APP_URL": variants[variant]["APP_URL"]
      }
    };
  }

  return {
    discardResponseBodies: true,
    scenarios,
  }
}(variants);

export default function () {

  // login form
  const loginResponse = http.post(`${__ENV.APP_URL}/login`, {
    username: 'john',
    password: 'test'
  }, {
    redirects: 0
  });
  checkResponse(loginResponse, isRedirect);

  // dashboard
  const dashboard = http.get(`${__ENV.APP_URL}/dashboard`);
  checkResponse(dashboard, isOk);

  // logout
  const logout = http.get(`${__ENV.APP_URL}/j_spring_security_logout`, {
    redirects: 0
  });
  checkResponse(logout, isRedirect);
}
