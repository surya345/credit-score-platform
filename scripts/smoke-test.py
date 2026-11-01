#!/usr/bin/env python3
"""Exercise the running local stack with a restricted demo service account.

Requires Python 3.10+. Creates one synthetic customer and financial submission.
Never prints tokens. Run after `docker compose up --build -d`.
"""
import argparse
import json
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://localhost:8080')
    parser.add_argument('--identity-url', default='http://localhost:8180')
    parser.add_argument('--pdf-output')
    parser.add_argument('--email', action='store_true', help='Send a synthetic summary to local Mailpit')
    args = parser.parse_args()
    token_request = Request(
        args.identity_url + '/realms/credit-platform/protocol/openid-connect/token',
        data=urlencode({'grant_type': 'client_credentials', 'client_id': 'credit-smoke-test',
                        'client_secret': 'local-smoke-test-secret'}).encode(),
        headers={'Content-Type': 'application/x-www-form-urlencoded'})
    with urlopen(token_request, timeout=15) as response:
        token = json.load(response)['access_token']

    def api(path, method='GET', body=None, status=200, headers=None, authenticated=True):
        request_headers = {'Accept': 'application/json, application/pdf', **(headers or {})}
        if authenticated:
            request_headers['Authorization'] = 'Bearer ' + token
        if body is not None:
            request_headers['Content-Type'] = 'application/json'
        req = Request(args.base_url + path, method=method,
                      data=None if body is None else json.dumps(body).encode(), headers=request_headers)
        try:
            response = urlopen(req, timeout=20)
        except HTTPError as error:
            response = error
        with response:
            content = response.read()
            assert response.status == status, f'{method} {path}: expected {status}, got {response.status}: {content[:250]!r}'
            if 'application/pdf' in response.headers.get('Content-Type', ''):
                return content
            return json.loads(content) if content else None

    api('/api/customers', status=401, authenticated=False)
    api('/api/admin/users/audit', status=403)
    print('PASS: anonymous requests and officer audit access are denied', flush=True)
    customer = api('/api/customers', 'POST', {
        'reference': 'SMOKE-' + uuid.uuid4().hex[:10], 'fullName': 'Synthetic Smoke Customer',
        'email': 'smoke@example.test', 'currency': 'INR'}, status=201)
    customer_id = customer['id']
    url = '/api/data/customers/' + customer_id
    financial = {'monthlyIncome': 80000, 'monthlyDebtPayments': 12000, 'creditUtilizationPercent': 25,
                 'missedPaymentsLast12Months': 0, 'creditHistoryMonths': 72, 'recentCreditInquiries': 1, 'currency': 'INR'}
    headers = {'Idempotency-Key': str(uuid.uuid4())}
    api(url, 'POST', {**financial, 'monthlyIncome': 0}, status=400, headers=headers)
    event = api(url, 'POST', financial, status=202, headers=headers)
    retry = api(url, 'POST', financial, status=202, headers=headers)
    assert retry['eventId'] == event['eventId'] and retry['sourceVersion'] == event['sourceVersion']
    api(url, 'POST', {**financial, 'monthlyIncome': 90000}, status=409, headers=headers)
    assert api(url)['total'] == 1
    print('PASS: validation and idempotent submission', flush=True)
    deadline = time.monotonic() + 90
    reports = []
    while time.monotonic() < deadline:
        reports = api('/api/reports/customers/' + customer_id)['items']
        if reports:
            break
        time.sleep(1)
    assert len(reports) == 1, 'Kafka processing did not produce exactly one report within 90 seconds'
    score = api('/api/scores/customers/' + customer_id + '/latest')
    assert score['result']['score'] == 763, score['result']
    assert score['sourceEventId'] == event['eventId']
    assert reports[0]['scoreId'] == score['id']
    assert api('/api/scores/customers/' + customer_id + '/history')['total'] == 1
    print('PASS: real Kafka pipeline produced score 763 and its matching report', flush=True)
    pdf = api('/api/reports/' + reports[0]['id'] + '/pdf')
    assert pdf.startswith(b'%PDF-') and len(pdf) > 1000
    if args.pdf_output:
        Path(args.pdf_output).write_bytes(pdf)
    if args.email:
        api('/api/reports/' + reports[0]['id'] + '/email', 'POST', {'to': 'smoke@example.test'})
        print('PASS: local SMTP delivery accepted', flush=True)
    print('PASS: authenticated PDF download', flush=True)
    print('Smoke test passed. Synthetic customer: ' + customer_id, flush=True)


if __name__ == '__main__':
    main()
