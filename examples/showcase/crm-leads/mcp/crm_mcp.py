"""crm-mcp: a narrow MCP facade over the Twenty CRM REST API (CRM showcase).

The agent never talks to the CRM directly. It gets three tools, and the `crm`
tool server document in Abada decides how each may be used:

  find_company(name)                    read   companies whose name matches
  list_opportunities(company_id)        read   the company's opportunities
  add_note(company_id, title, body)     write  a note on the company, idempotent

MCP over streamable HTTP (JSON responses) on port 8770, path /mcp. Every MCP
request needs `Authorization: Bearer $CRM_MCP_TOKEN`: Abada stores that token
as the project's `crm-mcp-token` tool credential and hands it only to the agent
worker holding the task. The Twenty API key stays here, never in Abada.

add_note honours the Idempotency-Key Abada sends with every write (HTTP header
and `_meta.idempotencyKey`): a retried call returns the note it already wrote.
Keys are kept in memory, so the guarantee lasts as long as this process; a
production adapter would persist them next to the data. Single file, no
dependencies: Python 3.12 standard library only.
"""
import hmac
import json
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("CRM_MCP_PORT", "8770"))
TWENTY_URL = os.environ.get("TWENTY_URL", "http://twenty-server:3000").rstrip("/")
TWENTY_API_KEY = os.environ.get("TWENTY_API_KEY", "")
MCP_TOKEN = os.environ.get("CRM_MCP_TOKEN", "")

TOOLS = [
    {"name": "find_company",
     "description": "Find CRM companies whose name contains the given text. Returns id, name, "
                    "website domain, city and annual revenue.",
     "inputSchema": {"type": "object", "required": ["name"],
                     "properties": {"name": {"type": "string", "description": "Part of the company name"}}}},
    {"name": "list_opportunities",
     "description": "List the opportunities of a CRM company: name, stage, amount in USD and close date.",
     "inputSchema": {"type": "object", "required": ["company_id"],
                     "properties": {"company_id": {"type": "string"}}}},
    {"name": "add_note",
     "description": "Attach a short research note to a CRM company.",
     "inputSchema": {"type": "object", "required": ["company_id", "title", "body"],
                     "properties": {"company_id": {"type": "string"},
                                    "title": {"type": "string", "maxLength": 120},
                                    "body": {"type": "string", "maxLength": 2000}}}},
]
NOTES_BY_KEY, LOCK = {}, threading.Lock()


def log(message):
    print(f"{time.strftime('%H:%M:%S')} {message}", flush=True)


class TwentyError(Exception):
    pass


def twenty(method, path, body=None):
    """Calls the Twenty REST API with the server-side API key."""
    request = urllib.request.Request(
        TWENTY_URL + path, method=method,
        data=None if body is None else json.dumps(body).encode(),
        headers={"Authorization": f"Bearer {TWENTY_API_KEY}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.loads(response.read() or b"{}")
    except urllib.error.HTTPError as error:
        raise TwentyError(f"Twenty answered HTTP {error.code} for {method} {path.split('?')[0]}") from None
    except urllib.error.URLError:
        raise TwentyError("Twenty is unreachable") from None


def query(**params):
    return "?" + urllib.parse.urlencode(params)


def money(amount):
    """Twenty stores currency amounts in micros."""
    if not amount or amount.get("amountMicros") is None:
        return None
    return {"amount": amount["amountMicros"] / 1_000_000, "currency": amount.get("currencyCode")}


def find_company(arguments):
    name = str(arguments.get("name", "")).strip()
    if not name:
        return {"error": "name is required"}, True
    found = twenty("GET", "/rest/companies" + query(filter=f'name[ilike]:"%{name}%"', limit=5))
    companies = [{
        "id": company["id"],
        "name": company.get("name"),
        "domain": (company.get("domainName") or {}).get("primaryLinkUrl"),
        "city": (company.get("address") or {}).get("addressCity"),
        "annual_revenue": money(company.get("annualRevenue")),
    } for company in found.get("data", {}).get("companies", [])]
    return {"companies": companies}, False


def list_opportunities(arguments):
    company_id = str(arguments.get("company_id", ""))
    found = twenty("GET", "/rest/opportunities" + query(filter=f"companyId[eq]:{company_id}", limit=20))
    opportunities = [{
        "id": opportunity["id"],
        "name": opportunity.get("name"),
        "stage": opportunity.get("stage"),
        "amount": money(opportunity.get("amount")),
        "close_date": opportunity.get("closeDate"),
    } for opportunity in found.get("data", {}).get("opportunities", [])]
    return {"opportunities": opportunities}, False


def add_note(arguments, idempotency_key):
    company_id, title = str(arguments.get("company_id", "")), str(arguments.get("title", ""))[:120]
    body = str(arguments.get("body", ""))[:2000]
    if not company_id or not title:
        return {"error": "company_id and title are required"}, True
    with LOCK:  # one writer per key: a concurrent retry waits and gets the same note
        if idempotency_key and idempotency_key in NOTES_BY_KEY:
            log(f"add_note replayed for key {idempotency_key[:12]}...")
            return NOTES_BY_KEY[idempotency_key], False
        note = twenty("POST", "/rest/notes", {"title": title, "bodyV2": {"markdown": body}})
        note_id = note["data"]["createNote"]["id"]
        twenty("POST", "/rest/noteTargets", {"noteId": note_id, "targetCompanyId": company_id})
        result = {"note_id": note_id, "company_id": company_id}
        if idempotency_key:
            NOTES_BY_KEY[idempotency_key] = result
    log(f"add_note company={company_id} note={note_id}")
    return result, False


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _json(self, code, payload, headers=None):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def _empty(self, code):
        self.send_response(code)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _authorized(self):
        supplied = self.headers.get("Authorization", "").removeprefix("Bearer ").strip()
        return bool(MCP_TOKEN) and hmac.compare_digest(supplied.encode(), MCP_TOKEN.encode())

    def do_GET(self):
        if self.path == "/healthz":
            return self._json(200, {"ok": True})
        self._empty(405)  # no server-initiated stream

    def do_DELETE(self):
        self._empty(200)

    def do_POST(self):
        if self.path != "/mcp":
            return self._empty(404)
        if not self._authorized():
            log("refused an MCP request without the expected bearer token")
            return self._empty(401)
        request = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
        if "id" not in request:  # a notification
            return self._empty(202)
        method, params = request.get("method"), request.get("params") or {}
        headers = {}
        if method == "initialize":
            result = {"protocolVersion": params.get("protocolVersion", "2025-06-18"),
                      "capabilities": {"tools": {}},
                      "serverInfo": {"name": "crm-mcp", "version": "1"}}
            headers["Mcp-Session-Id"] = "crm-mcp-session"
        elif method == "tools/list":
            result = {"tools": TOOLS}
        elif method == "tools/call":
            key = self.headers.get("Idempotency-Key") or (params.get("_meta") or {}).get("idempotencyKey")
            result = self._call(params.get("name"), params.get("arguments") or {}, key)
        else:
            return self._json(200, {"jsonrpc": "2.0", "id": request["id"],
                                    "error": {"code": -32601, "message": "Method not found"}})
        self._json(200, {"jsonrpc": "2.0", "id": request["id"], "result": result}, headers)

    def _call(self, tool, arguments, idempotency_key):
        try:
            if tool == "find_company":
                payload, error = find_company(arguments)
            elif tool == "list_opportunities":
                payload, error = list_opportunities(arguments)
            elif tool == "add_note":
                payload, error = add_note(arguments, idempotency_key)
            else:
                payload, error = {"error": f"unknown tool {tool}"}, True
        except TwentyError as failure:
            payload, error = {"error": str(failure)}, True
        if tool != "add_note":
            log(f"{tool} {json.dumps(arguments)[:80]}{' (error)' if error else ''}")
        result = {"content": [{"type": "text", "text": json.dumps(payload)}]}
        if error:
            result["isError"] = True
        return result


if not TWENTY_API_KEY or not MCP_TOKEN:
    raise SystemExit("TWENTY_API_KEY and CRM_MCP_TOKEN are required (./showcase.sh up sets both)")
log(f"crm-mcp on :{PORT}/mcp -> {TWENTY_URL}")
ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
