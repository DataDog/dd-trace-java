import http from 'k6/http'
import { check } from 'k6'

export const options = {
  scenarios: {
    home: {
      executor: 'constant-arrival-rate',
      duration: '10s',
      preAllocatedVUs: 1,
      maxVUs: 2,
      rate: 10,
      timeUnit: '1s',
      exec: 'default'
    },
    vets: {
      executor: 'constant-arrival-rate',
      duration: '10s',
      preAllocatedVUs: 1,
      maxVUs: 2,
      rate: 10,
      timeUnit: '1s',
      exec: 'vets'
    },
    owners: {
      executor: 'constant-arrival-rate',
      duration: '10s',
      preAllocatedVUs: 1,
      maxVUs: 2,
      rate: 10,
      timeUnit: '1s',
      exec: 'owners'
    }
  }
}

export default function() {
  const search = http.get('http://localhost:8080')
  check(search, {
    'home successful': (resp) => resp.status === 200
  })
}

export function vets () {
  const search = http.get('http://localhost:8080/vets')
  check(search, {
    'vets successful': (resp) => resp.status === 200
  })
}

export function owners () {
  const search = http.get('http://localhost:8080/owners?lastName=')
  check(search, {
    'owners successful': (resp) => resp.status === 200
  })
}
