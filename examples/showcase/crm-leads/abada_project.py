"""Abada side of the CRM showcase, through the public REST API (/api/v1).

  python3 abada_project.py setup          create or update the project and deploy the process
  python3 abada_project.py lead <name>    start an instance with a sample lead
  python3 abada_project.py status         list the latest instances

Everything here is what a person would do in Studio, as API calls: create a
project, add the `crm` tool server and the form, store the tool credential,
deploy the APL document, start an instance. It signs in as the dev user alice
(Keycloak password grant, dev realm only). Standard library only.
"""
import base64
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
API = os.environ.get("ABADA_API_URL", "http://api.localhost/api")
OIDC = os.environ.get("ABADA_OIDC_URL", "http://keycloak.localhost") + "/realms/abada-dev/protocol/openid-connect/token"
USER = os.environ.get("ABADA_SHOWCASE_USER", "alice")
PASSWORD = os.environ.get("ABADA_SHOWCASE_PASSWORD", "alice")
PROJECT_SLUG = "crm-showcase"
PROCESS_KEY = "lead_to_opportunity"

LEADS = {
    # Known in the CRM, no opportunity yet, concrete need: a new logo for review.
    "mongodb": {"company": "MongoDB", "contact_name": "Dana Whitfield", "contact_email": "dana.whitfield@mongodb.com",
                "message": "We are moving 400 engineers to MacBook Pro this quarter and need volume pricing "
                           "and device management. Can we talk next week?"},
    # Known, with won and open opportunities: an existing customer.
    "zendesk": {"company": "Zendesk", "contact_name": "Luis Ortega", "contact_email": "luis.ortega@zendesk.com",
                "message": "Our support team is growing by 120 agents in Q1; we would like to add iPads "
                           "to our current agreement."},
    # Unknown to the CRM, vague: rules send it to nurturing, nobody is bothered.
    "bakery": {"company": "Rosie's Corner Bakery", "contact_name": "Rosie Park", "contact_email": "rosie@example.com",
               "message": "hi, do you sell computers?"},
}


def token():
    form = urllib.parse.urlencode({"client_id": "abada-frontend", "grant_type": "password",
                                   "username": USER, "password": PASSWORD}).encode()
    with urllib.request.urlopen(urllib.request.Request(OIDC, data=form), timeout=15) as response:
        return json.load(response)["access_token"]


class Abada:
    def __init__(self):
        self.bearer = token()

    def call(self, method, path, body=None, headers=None):
        request = urllib.request.Request(API + path, method=method,
                                         data=None if body is None else json.dumps(body).encode(),
                                         headers={"Authorization": f"Bearer {self.bearer}",
                                                  "Content-Type": "application/json", **(headers or {})})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read()
                return json.loads(raw) if raw else None, response.headers.get("ETag")
        except urllib.error.HTTPError as error:
            raise SystemExit(f"{method} {path} -> HTTP {error.code}: {error.read().decode()[:600]}") from None

    def get(self, path):
        return self.call("GET", path)[0]


def b64(path):
    return base64.b64encode(path.read_bytes()).decode()


def project_id(abada, create=False):
    for project in abada.get("/v1/projects"):
        if project["slug"] == PROJECT_SLUG:
            return project["id"]
    if not create:
        raise SystemExit("The crm-showcase project does not exist yet: run ./showcase.sh up")
    created, _ = abada.call("POST", "/v1/projects", {
        "slug": PROJECT_SLUG, "name": "CRM showcase",
        "description": "Lead to opportunity with Twenty CRM: agent + MCP tools, rules, a form, a worker"})
    print(f"  created project {PROJECT_SLUG}")
    return created["id"]


def walk(node):
    yield node
    for child in node.get("children") or []:
        yield from walk(child)


def tree_nodes(abada, pid):
    tree = abada.get(f"/v1/projects/{pid}/tree")
    roots = tree if isinstance(tree, list) else [tree]
    return [node for root in roots for node in walk(root)]


def upsert_resource(abada, pid, name, kind, source, content_type, folder_id=""):
    """Creates the resource, or replaces its content when it already exists."""
    for node in tree_nodes(abada, pid):
        if node.get("kind") == "RESOURCE" and node.get("name") == name:
            current, etag = abada.call("GET", f"/v1/projects/{pid}/resources/{node['id']}")
            abada.call("PUT", f"/v1/projects/{pid}/resources/{node['id']}", {
                "expectedRevision": int(etag.strip('"')), "contentType": content_type,
                "contentBase64": b64(source)})
            print(f"  updated {kind} {name}")
            return
    abada.call("POST", f"/v1/projects/{pid}/resources", {
        "name": name, "folderId": folder_id, "kind": kind, "contentType": content_type,
        "contentBase64": b64(source)})
    print(f"  created {kind} {name}")


def setup():
    abada = Abada()
    pid = project_id(abada, create=True)

    # 1. The tool server: which MCP server, which tools, which policy each.
    upsert_resource(abada, pid, "crm.yaml", "TOOL_SERVER", HERE / "tool-servers/crm.yaml", "application/yaml")

    # 2. The tool credential the tool server names. Stored encrypted; only the
    #    agent worker holding a task bound to `crm` can read it back.
    abada.call("PUT", f"/v1/projects/{pid}/tool-credentials/crm-mcp-token",
               {"secret": os.environ["CRM_MCP_TOKEN"]})
    print("  stored tool credential crm-mcp-token")

    # 3. The task form, in the project's system `forms` folder (formKey = file name).
    forms = next(node for node in tree_nodes(abada, pid)
                 if node.get("kind") == "FOLDER" and node.get("name") == "forms" and node.get("system"))
    upsert_resource(abada, pid, "lead-qualification.json", "FORM", HERE / "forms/lead-qualification.json",
                    "application/json", forms["id"])

    # 4. The process: save the APL document, then deploy that exact revision.
    source = (HERE / "process/lead-to-opportunity.apl.yaml").read_text()
    documents = abada.get(f"/v1/projects/{pid}/documents?size=100")
    document = next((d for d in documents if d["processKey"] == PROCESS_KEY), None)
    if document is None:
        document, etag = abada.call("POST", f"/v1/projects/{pid}/documents", {
            "processKey": PROCESS_KEY, "description": "Lead to opportunity", "aplSource": source,
            "fileName": "lead-to-opportunity.apl.yaml"})
    else:
        document, etag = abada.call("PUT", f"/v1/projects/{pid}/documents/{document['id']}",
                                    {"description": "Lead to opportunity", "aplSource": source},
                                    {"If-Match": str(document["revision"])})
    deployed, _ = abada.call("POST", f"/v1/projects/{pid}/documents/{document['id']}/deploy",
                             headers={"If-Match": etag})
    print(f"  deployed {PROCESS_KEY} version {deployed.get('version', '?')}")


def lead(name):
    if name not in LEADS:
        raise SystemExit(f"Unknown lead '{name}'. Choose one of: {', '.join(LEADS)}")
    abada = Abada()
    pid = project_id(abada)
    started, _ = abada.call("POST", f"/v1/projects/{pid}/processes/{PROCESS_KEY}/start",
                            {"lead": LEADS[name]}, {"Idempotency-Key": str(uuid.uuid4())})
    print(f"Started instance {started['processInstanceId']} for {LEADS[name]['company']}")
    print(f"Follow it in Studio: http://studio.localhost (project 'CRM showcase' > Operations)")


def status():
    abada = Abada()
    pid = project_id(abada)
    for instance in abada.get(f"/v1/projects/{pid}/instances?size=10"):
        wrapped = abada.get(f"/v1/projects/{pid}/instances/{instance['id']}/variables") or {}
        variables = {name: entry.get("value") for name, entry in wrapped.items()}
        company = (variables.get("lead") or {}).get("company", "?")
        print(f"{instance['id'][:8]}  {instance.get('status', '?'):10} {company:24} "
              f"lane={variables.get('lane', '-')}  opportunity={variables.get('opportunity_url', '-')}")


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    if command == "setup":
        setup()
    elif command == "lead" and len(sys.argv) == 3:
        lead(sys.argv[2])
    elif command == "status":
        status()
    else:
        raise SystemExit(__doc__)
