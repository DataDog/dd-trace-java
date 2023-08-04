import {check} from 'k6';
import {Faker} from "k6/x/faker";

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

export const faker = new Faker(123456789)
