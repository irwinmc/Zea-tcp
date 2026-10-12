"""Check the real release profile with synthetic credentials and a local publisher."""

import base64
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import secrets
import shlex
import shutil
import subprocess
import tempfile
import threading
import unittest
from email.parser import BytesParser
from email.policy import default
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[2]
NAMESPACE = "http://maven.apache.org/POM/4.0.0"
NS = {"m": NAMESPACE}
PREFIX = "com/akakata/verification/publishing-regression/0.0.0/publishing-regression-0.0.0"
ARTIFACTS = [PREFIX + suffix for suffix in (".jar", ".pom", "-sources.jar", "-javadoc.jar")]
ALGORITHMS = ("md5", "sha1", "sha256", "sha512")
DEPLOYMENT = "00000000-0000-4000-8000-000000000001"
USERNAME = "synthetic-publication-user"
PASSWORD = "synthetic-publication-password"


def validate_bundle(content):
    """Reject repository metadata, missing signatures, and incorrect checksums."""
    with zipfile.ZipFile(io.BytesIO(content)) as bundle:
        entries = [entry.filename for entry in bundle.infolist() if not entry.is_dir()]
        if len(entries) != len(set(entries)):
            raise ValueError("Duplicate bundle entries")
        files = {name: bundle.read(name) for name in entries}
    directories = {str(PurePosixPath(name).parent) for name in files}
    for directory in directories:
        if not any(str(PurePosixPath(name).parent) == directory and name.endswith(".pom")
                   for name in files):
            raise ValueError("Bundle has content that does NOT have a .pom file: " + directory)
    expected = {name + suffix for name in ARTIFACTS
                for suffix in ("", ".asc", *("." + algorithm for algorithm in ALGORITHMS))}
    if set(files) != expected:
        raise ValueError("Unexpected or missing bundle files: " + ", ".join(sorted(set(files) ^ expected)))
    for name in ARTIFACTS:
        for algorithm in ALGORITHMS:
            expected_digest = hashlib.new(algorithm, files[name]).hexdigest().encode()
            if files[name + "." + algorithm].strip() != expected_digest:
                raise ValueError("Invalid checksum: " + name + "." + algorithm)
    return files


def zip_files(files):
    """Create an in-memory bundle for validation regressions."""
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as bundle:
        for name, content in files.items():
            bundle.writestr(name, content)
    return output.getvalue()


class BundleValidationTest(unittest.TestCase):
    """Keep local repository files and incomplete artifacts out of publication."""

    def setUp(self):
        self.files = {}
        for name in ARTIFACTS:
            self.files[name] = b"synthetic artifact"
            self.files[name + ".asc"] = b"synthetic signature"
            for algorithm in ALGORITHMS:
                self.files[name + "." + algorithm] = hashlib.new(algorithm, self.files[name]).hexdigest().encode()

    def test_complete_bundle(self):
        self.assertEqual(validate_bundle(zip_files(self.files)), self.files)

    def test_local_metadata_without_a_version_pom(self):
        name = str(PurePosixPath(PREFIX).parent.parent / "maven-metadata-local.xml")
        self.files[name] = b"local repository metadata"
        with self.assertRaisesRegex(ValueError, "does NOT have a .pom file"):
            validate_bundle(zip_files(self.files))

    def test_local_repository_tracking_file(self):
        self.files[str(PurePosixPath(PREFIX).parent / "_remote.repositories")] = b"local tracking"
        with self.assertRaisesRegex(ValueError, "Unexpected or missing"):
            validate_bundle(zip_files(self.files))

    def test_missing_pom(self):
        del self.files[PREFIX + ".pom"]
        with self.assertRaisesRegex(ValueError, "does NOT have a .pom file"):
            validate_bundle(zip_files(self.files))

    def test_missing_signature(self):
        del self.files[PREFIX + ".jar.asc"]
        with self.assertRaisesRegex(ValueError, "Unexpected or missing"):
            validate_bundle(zip_files(self.files))

    def test_incorrect_checksum(self):
        self.files[PREFIX + ".jar.sha256"] = b"invalid digest"
        with self.assertRaisesRegex(ValueError, "Invalid checksum"):
            validate_bundle(zip_files(self.files))


class LocalPublisher(BaseHTTPRequestHandler):
    """Validate authentication and uploaded files before reporting publication."""

    def log_message(self, *_args):
        pass

    def respond(self, code, body, content_type="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self.respond(200, b"{}")

    def do_POST(self):
        expected = base64.b64encode((USERNAME + ":" + PASSWORD).encode()).decode()
        if self.headers.get("Authorization") not in ("Bearer " + expected, "UserToken " + expected):
            self.server.error = "Incorrect publication credentials"
            self.respond(401, b"{}")
            return
        path = urlsplit(self.path)
        query = parse_qs(path.query)
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if path.path == "/api/v1/publisher/upload":
            try:
                if query.get("publishingType") != ["AUTOMATIC"]:
                    raise ValueError("Automatic publishing is required")
                header = "Content-Type: " + self.headers["Content-Type"] + "\r\nMIME-Version: 1.0\r\n\r\n"
                message = BytesParser(policy=default).parsebytes(header.encode() + body)
                parts = [part for part in message.iter_parts()
                         if part.get_param("name", header="content-disposition") == "bundle"]
                if len(parts) != 1:
                    raise ValueError("Expected exactly one publication bundle")
                files = validate_bundle(parts[0].get_payload(decode=True))
                for name, content in files.items():
                    destination = self.server.workspace / "uploaded" / name
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(content)
                for name in ARTIFACTS:
                    artifact = self.server.workspace / "uploaded" / name
                    subprocess.run(["gpg", "--homedir", str(self.server.key_home), "--batch", "--verify",
                                    str(artifact) + ".asc", str(artifact)],
                                   check=True, capture_output=True, timeout=30)
                self.server.uploaded = True
            except (ValueError, subprocess.SubprocessError, zipfile.BadZipFile) as failure:
                self.server.error = str(failure)
            self.respond(201, DEPLOYMENT.encode(), "text/plain")
        elif path.path == "/api/v1/publisher/status" and query.get("id") == [DEPLOYMENT]:
            self.server.status_checks += 1
            response = {"deploymentId": DEPLOYMENT, "deploymentName": "local-regression",
                        "deploymentState": "FAILED" if self.server.error else "PUBLISHED",
                        "purls": ["pkg:maven/com.akakata.verification/publishing-regression@0.0.0"],
                        "errors": {"common": [self.server.error]} if self.server.error else {}}
            self.respond(200, json.dumps(response).encode())
        else:
            self.respond(404, b"{}")


class PublishingLifecycleTest(unittest.TestCase):
    """Exercise the workflow command with the project's actual release profile."""

    def test_signed_deployment(self):
        for executable in ("gpg", "gpgconf"):
            self.assertIsNotNone(shutil.which(executable), executable + " must be installed")
        with tempfile.TemporaryDirectory(prefix="zea-central-test-", dir="/tmp") as temporary:
            workspace = Path(temporary)
            key_home = workspace / "gpg"
            key_home.mkdir(mode=0o700)
            try:
                passphrase = secrets.token_urlsafe(32)
                gpg = ["gpg", "--homedir", str(key_home), "--batch", "--pinentry-mode", "loopback"]
                subprocess.run(gpg + ["--passphrase-fd", "0", "--quick-generate-key",
                                      "Publication regression <publication@example.invalid>", "rsa2048", "sign", "1d"],
                               input=passphrase.encode(), check=True, capture_output=True, timeout=60)
                private_key = subprocess.run(gpg + ["--passphrase-fd", "0", "--armor", "--export-secret-keys"],
                                             input=passphrase.encode(), check=True,
                                             capture_output=True, timeout=30).stdout.decode()
                self.run_publication(workspace, key_home, private_key, passphrase)
            finally:
                subprocess.run(["gpgconf", "--homedir", str(key_home), "--kill", "gpg-agent"],
                               capture_output=True, timeout=15)

    def run_publication(self, workspace, key_home, private_key, passphrase):
        workflow = (ROOT / ".github/workflows/publish-maven-central.yml").read_text()
        server_id = re.search(r"^\s+server-id:\s*(\S+)\s*$", workflow, re.MULTILINE).group(1)
        step = workflow.split("- name: Sign and publish the release\n", 1)[1]
        command_text = step.split("run: >-\n", 1)[1].split("\n      - name:", 1)[0]
        command = shlex.split(command_text)
        self.assertEqual(command[0], "./mvnw")
        shutil.copy2(ROOT / "mvnw", workspace / "mvnw")
        shutil.copytree(ROOT / ".mvn/wrapper", workspace / ".mvn/wrapper")
        pom = ET.parse(ROOT / "pom.xml").getroot()
        pom.find("m:groupId", NS).text = "com.akakata.verification"
        pom.find("m:artifactId", NS).text = "publishing-regression"
        pom.find("m:version", NS).text = "0.0.0"
        pom.remove(pom.find("m:dependencies", NS))
        for configuration in pom.findall("m:build/m:plugins/m:plugin/m:configuration", NS):
            processors = configuration.find("m:annotationProcessorPaths", NS)
            if processors is not None:
                configuration.remove(processors)
        ET.register_namespace("", NAMESPACE)
        ET.ElementTree(pom).write(workspace / "pom.xml", encoding="UTF-8", xml_declaration=True)
        source = workspace / "src/main/java/verification"
        source.mkdir(parents=True)
        (source / "PublicationProbe.java").write_text(
            "package verification;\n/**\n * Provides a minimal publication fixture.\n */\n"
            "public final class PublicationProbe {\n"
            "    /**\n     * Creates the publication fixture.\n     */\n"
            "    public PublicationProbe() { }\n}\n")
        (source / "package-info.java").write_text(
            "/**\n * Provides fixtures for signed publication regressions.\n */\npackage verification;\n")
        settings = workspace / "settings.xml"
        settings.write_text("<settings><servers><server><id>" + server_id + "</id>"
                            "<username>${env.MAVEN_CENTRAL_USERNAME}</username>"
                            "<password>${env.MAVEN_CENTRAL_PASSWORD}</password>"
                            "</server></servers></settings>")
        env = {**os.environ, "MAVEN_CENTRAL_USERNAME": USERNAME, "MAVEN_CENTRAL_PASSWORD": PASSWORD,
               "MAVEN_GPG_KEY": private_key, "MAVEN_GPG_PASSPHRASE": passphrase}
        server = ThreadingHTTPServer(("127.0.0.1", 0), LocalPublisher)
        server.workspace, server.key_home = workspace, key_home
        server.error, server.uploaded, server.status_checks = None, False, 0
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            result = subprocess.run(["./mvnw", "--batch-mode", "--no-transfer-progress", "--settings", str(settings),
                                     "clean", "verify"], cwd=workspace, env=env,
                                    capture_output=True, text=True, timeout=180)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            command += ["--settings", str(settings),
                        "-DcentralBaseUrl=http://127.0.0.1:" + str(server.server_port)]
            result = subprocess.run(command, cwd=workspace, env=env,
                                    capture_output=True, text=True, timeout=180)
            output = result.stdout + result.stderr
            self.assertEqual(result.returncode, 0, output)
            self.assertIsNone(server.error, server.error)
            self.assertTrue(server.uploaded, "No signed bundle was uploaded")
            self.assertGreater(server.status_checks, 0, "Publication status was not checked")
            self.assertNotIn("Not using credentials of server", output)
            self.assertIn("Skipping artifact installation", output)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)


if __name__ == "__main__":
    unittest.main(verbosity=2)
