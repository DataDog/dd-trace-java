import http from 'k6/http';
import {checkResponse, isOk, isRedirect} from "../../utils/k6.js";

const variants = {
  "no_agent": {
    "APP_URL": 'http://localhost:8086',
  },
  "tracing": {
    "APP_URL": 'http://localhost:8087',
  },
  "profiling": {
    "APP_URL": 'http://localhost:8088',
  },
  "iast": {
    "APP_URL": 'http://localhost:8089',
  },
  "iast_GLOBAL": {
    "APP_URL": 'http://localhost:8090',
  },
  "iast_FULL": {
    "APP_URL": 'http://localhost:8091',
  },
}

export const options = function (variants) {
  let scenarios = {};
  for (const variant of Object.keys(variants)) {
    scenarios[`load--insecure-bank--${variant}--warmup`] = {
      executor: 'constant-vus',  // https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/#all-executors
      vus: 5,
      duration: '165s',
      gracefulStop: '2s',
      env: {
        "APP_URL": variants[variant]["APP_URL"]
      }
    };

    scenarios[`load--insecure-bank--${variant}--high_load`] = {
      executor: 'constant-vus',
      vus: 5,
      startTime: '167s',
      duration: '15s',
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
