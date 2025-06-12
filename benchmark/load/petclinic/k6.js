import http from 'k6/http';
import {checkResponse, isOk} from "../../utils/k6.js";

const baseUrl = 'http://localhost:8080';

export const options = {
  warmup: {
    discardResponseBodies: true,
    executor: 'constant-vus',  // https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/#all-executors
    vus: 5,
    duration: '15s',
  },
  high_load: {
    discardResponseBodies: true,
    executor: 'constant-vus',
    vus: 5,
    duration: '20s',
  },
};

export default function () {

  // find owner
  const ownersList = http.get(`${baseUrl}/owners?lastName=`);
  checkResponse(ownersList, isOk);
}
