import http from 'k6/http';
import {checkResponse, isOk} from "../../utils/k6.js";

const baseUrl = 'http://localhost:8080';

export const options = {
  discardResponseBodies: true,
  scenarios: {
    [`load--petclinic--${__ENV.VARIANT}--warmup`]: {
      executor: 'constant-arrival-rate',  // https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/#all-executors
      preAllocatedVUs: 5,
      duration: '10s',
      gracefulStop: '2s',
      timeUnit: '1s',
      rate: 200,
    },
    [`load--petclinic--${__ENV.VARIANT}--high_load`]: {
      executor: 'constant-arrival-rate',
      preAllocatedVUs: 5,
      startTime: '12s',
      duration: '20s',
      gracefulStop: '2s',
      timeUnit: '1s',
      rate: 200,
    },
  }
};

export default function () {

  // find owner
  const ownersList = http.get(`${baseUrl}/owners?lastName=`);
  checkResponse(ownersList, isOk);
}
