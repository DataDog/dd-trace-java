import http from 'k6/http';
import {check} from 'k6';

export function checkResponse(response) {
  const checks = Array.prototype.slice.call(arguments, 1);
  const reduced = checks.reduce((result, current) => Object.assign(result, current), {});
  check(response, reduced);
}

export const isOk = {
  'is OK': r => r.status === 200
};

export const isRedirect = {
  'is redirect': r => r.status >= 300 && r.status < 400
};

export function bodyContains(text) {
  return {
    'body contains': r => r.body.includes(text)
  }
}

const baseUrl = 'http://localhost:8080';

export const options = {
  discardResponseBodies: true,
  scenarios: {
    noprobe: {
        exec: 'noprobe',
        executor: 'per-vu-iterations',
        vus: 1,
        iterations: 200000,
        maxDuration: '90s'
    },
    basic: {
        exec  : 'basic',
        executor: 'per-vu-iterations',
        vus: 1,
        iterations: 100000,
        startTime: '90s',
        maxDuration: '60s'
    },
//    lotsoffields: {
//        exec: 'lotsoffields',
//        executor: 'per-vu-iterations',
//        vus: 1,
//        iterations: 100000,
//        startTime: '150s',
//        maxDuration: '120s'
//    },
//    lotsofargs: {
//        exec: 'lotsofargs',
//        executor: 'per-vu-iterations',
//        vus: 1,
//        iterations: 100000,
//        startTime: '270s',
//        maxDuration: '120s'
//    },
//    largecollections: {
//        exec: 'largecollections',
//        executor: 'per-vu-iterations',
//        vus: 1,
//        iterations: 50000,
//        startTime: '390s',
//        maxDuration: '300s'
//    },
    loop: {
        exec: 'loop',
        executor: 'per-vu-iterations',
        vus: 1,
        iterations: 20000,
        startTime: '150s',
//        startTime: '690s',
        maxDuration: '300s'
    }
  }
};

export function handleSummary(data) {
  return {
    'summary.json': JSON.stringify(data),
  };
}

export function basic() {
  const vetsList = http.get(`${baseUrl}/benchmark/basic`);
  checkResponse(vetsList, isOk);
}

export function noprobe() {
  const ownersList = http.get(`${baseUrl}/benchmark/noprobe`);
  checkResponse(ownersList, isOk);
}

export function lotsoffields() {
  const ownersList = http.get(`${baseUrl}/benchmark/lotsoffields`);
  checkResponse(ownersList, isOk);
}

export function lotsofargs() {
  const ownersList = http.get(`${baseUrl}/benchmark/lotsofargs`);
  checkResponse(ownersList, isOk);
}

export function largecollections() {
  const ownersList = http.get(`${baseUrl}/benchmark/largecollections`);
  checkResponse(ownersList, isOk);
}

export function loop() {
  const ownersList = http.get(`${baseUrl}/benchmark/loop`);
  checkResponse(ownersList, isOk);
}
