import http from 'k6/http';
import {checkResponse, isOk} from "../../utils/k6.js";

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
  "appsec": {
    "APP_URL": 'http://localhost:8083',
  },
  "iast": {
    "APP_URL": 'http://localhost:8084',
  },
  "code_origins": {
    "APP_URL": 'http://localhost:8085',
  }
}

export const options = function (variants) {
  const scenarios = {};
  for (const variant of Object.keys(variants)) {
    scenarios[`load--petclinic--${variant}--warmup`] = {
      executor: 'constant-vus',  // https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/#all-executors
      vus: 5,
      duration: '160s',
      gracefulStop: '2s',
      env: {
        "APP_URL": variants[variant]["APP_URL"]
      }
    };

    scenarios[`load--petclinic--${variant}--high_load`] = {
      executor: 'constant-vus',
      vus: 5,
      startTime: '162s',
      duration: '30s',
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

  // find owner
  const ownersList = http.get(`${__ENV.APP_URL}/owners?lastName=`);
  checkResponse(ownersList, isOk);
}
