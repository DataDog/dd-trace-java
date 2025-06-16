import http from 'k6/http';
import {checkResponse, isOk, isRedirect} from "../../utils/k6.js";

const baseUrl = 'http://localhost:8080';

export const options = {
  discardResponseBodies: true,
  scenarios: {
    [`load--insecure-bank--${__ENV.VARIANT}--high_load`]: {
      vus: 5,
      iterations: 40000
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
