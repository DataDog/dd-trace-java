import http from 'k6/http';
import {checkResponse, isOk, isRedirect} from "../../utils/k6.js";

const baseUrl = 'http://localhost:8080';

export const options = {
  discardResponseBodies: true,
  scenarios: {
    [`warmup--load:insecure-bank:${__ENV.VARIANT}`]: {
      executor: 'constant-vus',  // https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/#all-executors
      vus: 5,
      duration: '15s',
    },
    [`high_load--load:insecure-bank:${__ENV.VARIANT}`]: {
      executor: 'constant-vus',
      vus: 5,
      duration: '20s',
    },
  }
};

export default function () {

  // login form
  const loginResponse = http.post(`${baseUrl}/login`, {
    username: 'john',
    password: 'test'
  }, {
    redirects: 0
  });
  checkResponse(loginResponse, isRedirect);

  // dashboard
  const dashboard = http.get(`${baseUrl}/dashboard`);
  checkResponse(dashboard, isOk);

  // logout
  const logout = http.get(`${baseUrl}/j_spring_security_logout`, {
    redirects: 0
  });
  checkResponse(logout, isRedirect);
}
