import http from 'k6/http';
import {checkResponse, isOk} from "../../utils/k6.js";

const baseUrl = 'http://localhost:8080';

export const options = {
  executor: 'shared-iterations', // https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/#all-executors
  discardResponseBodies: true,
  vus: 5,
  iterations: 80000
};

export default function () {

  // find owner
  const ownersList = http.get(`${baseUrl}/owners?lastName=`);
  checkResponse(ownersList, isOk);
}
