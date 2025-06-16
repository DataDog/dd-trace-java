import http from 'k6/http';
import {checkResponse, isOk} from "../../utils/k6.js";

const baseUrl = 'http://localhost:8080';

export const options = {
  discardResponseBodies: true,
  scenarios: {
    [`load--petclinic--${__ENV.VARIANT}--warmup`]: {
      executor: 'constant-vus',  // https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/#all-executors
      vus: 5,
      duration: '20s',
      gracefulStop: '2s',
    },
    [`load--petclinic--${__ENV.VARIANT}--high_load`]: {
      executor: 'constant-arrival-rate',
      preAllocatedVUs: 5,
      startTime: '22s',
      duration: '20s',
      gracefulStop: '2s',
      timeUnit: '1s',
      rate: 150,
    },
  }
};

export default function () {

  // find owner
  const ownersList = http.get(`${baseUrl}/owners?lastName=`);
  checkResponse(ownersList, isOk);
}
