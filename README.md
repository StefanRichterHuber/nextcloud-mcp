# Nextcloud MCP Server

> **Disclaimer:** This project is an independent community effort and is not affiliated with, maintained, or endorsed by the original [Nextcloud project](https://nextcloud.com/).

A [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server that bridges Claude (and other MCP clients) to a [Nextcloud](https://nextcloud.com/) instance, enabling AI assistants to work with files stored in Nextcloud on behalf of authenticated users.

The primary use case is **collaborative work on text-based files between a user and an LLM**. Rather than having the AI silently overwrite files, the intended workflow is iterative and transparent: the user and the AI discuss changes, the AI proposes them in a reviewable form, and the user stays in control of what actually gets written. Every change is traceable through Nextcloud's built-in version history, so nothing is ever truly lost.

Built with [Quarkus](https://quarkus.io/) 3.x and the [Quarkus MCP Server](https://docs.quarkiverse.io/quarkus-mcp-server/dev/) extension.

## Deployment

An docker image is available at `stefanrichterhuber/nextcloudmcp` at docker hub.

Prepare an env file using the [Configuration](#configuration) `config.env`. The mcp server is available at `localhost:8080/mcp`. Create an empty file `users.json` as user database. File must be writable by the container (see config property `app.user-repository.file` or env variable `APP_USER_REPOSITORY_FILE`).

```bash
docker run --volume ./users.json:/work/users.json --env-file ./config.env -p 8080:8080 stefanrichterhuber/nextcloudmcp@latest
```

## Architecture Overview

```text
MCP Client (e.g. Claude.ai)
        │  MCP over HTTP (SSE)
        ▼
Nextcloud MCP Server  ──OIDC──►  Identity Provider (e.g. Keycloak)
        │  WebDAV / Nextcloud REST API
        ▼
Nextcloud Instance
```

The server sits between any MCP-capable client and a Nextcloud instance. Authentication is handled via OIDC: every `/mcp/*` endpoint requires a valid bearer token. The server proxies the necessary OIDC endpoints (`/register`, `/authorize`, `/token`) so that MCP clients that implement the [OAuth 2.0 Authorization Code flow for MCP](https://spec.modelcontextprotocol.io/specification/basic/authorization/) work without additional configuration.

Each authenticated user maintains their own Nextcloud credentials (obtained via [Nextcloud Login Flow V2](https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html)) and an optional access configuration that restricts which files the AI is permitted to access.

### Collaborative Editing Workflow

Nextcloud stores the full revision history of every file. This server exposes that history and builds a collaboration workflow on top of it:

1. The AI reads the current file content and understands its context.
2. When a change is needed, the AI computes a unified diff (git patch format) against the current version rather than rewriting the file outright.
3. The user reviews the proposed patch — seeing exactly what would change — and decides whether to apply it.
4. Once applied, Nextcloud automatically creates a new revision, preserving the previous state.
5. At any point, the user or AI can inspect the revision history, compare any two versions, or roll back to a prior state.

This approach makes the AI a collaborative editor rather than an autonomous one: changes are always explicit, incremental, and reversible.

## Prerequisites

* **Java 25** and **Maven**
* A running **Nextcloud** instance
* An **OIDC-capable identity provider** (e.g. Keycloak, Authentik) with a pre-registered client

## Configuration

The following environment variables configure the server:

| Variable | Required | Description |
| :--- | :---: | :--- |
| `QUARKUS_OIDC_AUTH_SERVER_URL` | yes | Base URL of the OIDC identity provider (e.g. `https://idp.example.com/realms/myrealm`) |
| `QUARKUS_OIDC_CLIENT_ID` | yes | Pre-registered OIDC client ID provided to MCP clients during dynamic registration |
| `QUARKUS_OIDC_CREDENTIALS_SECRET` | yes | Corresponding OIDC client secret |
| `APP_ROOT_URL` | yes | Public root URL of this server (used in CORS headers and MCP App CSP), e.g. `https://nextcloud-mcp.example.com`. The mcp server is available ${app.root-url}/mcp |
| `NEXTCLOUD_URL` | yes | Root URL of the Nextcloud instance, e.g. `https://nextcloud.example.com` |
| `NEXTCLOUD_USER` | no | Nextcloud user for integration tests only |
| `NEXTCLOUD_PASSWORD` | no | Nextcloud password for integration tests only |

Additional settings are controlled via `src/main/resources/application.properties`:

| Property | Default | Description |
| :--- | :--- | :--- |
| `app.user-repository.file` | `users.json` | Path to the JSON file that persists user credentials and access configuration |
| `app.root-url` | `http://localhost:8080` | Root URL (fallback if `APP_ROOT_URL` is not set) |
| `app.mcp.app.inline-resources` | `true` | Inline static resources into MCP App HTML responses. Required for Claude AI. |
| `nextcloud.app-name` | `mcp-server` | App name used when requesting Nextcloud app passwords |

### User Data

User credentials and per-user access settings are stored in a JSON file (`users.json` by default). Each entry is keyed by the OIDC subject claim (username):

```json
{
  "alice": {
    "credentials": {
      "loginName": "<nextcloud-user>",
      "appPassword": "<nextcloud-app-password>",
      "server": "https://nextcloud.example.com"
    },
    "accessConfig": {
      "rootFolder": "/Documents",
      "filePatterns": ["*.md", "*.txt"],
      "textContent": true,
      "imageContent": false,
      "audioContent": false
    }
  }
}
```

This file is written and read at runtime. For production deployments it should be stored on a persistent volume and protected appropriately (e.g. filesystem permissions, secrets management).

## MCP Tools

### File Operations

| Tool | Description |
| :--- | :--- |
| `list-files` | List files and directories at a given path |
| `get-file-content` | Read the content of a file (text or binary blob) |
| `get-file-revisions` | List all stored revisions of a file |
| `create-file-diff` | Create a unified diff (git patch format) between two files |
| `delete-file` | Delete a file |
| `write-file` | Create or overwrite a file with new content |
| `apply-file-patch` | Apply a git-style patch to an existing file |
| `search-files` | Full-text search across all accessible files |
| `search-in-file` | Semantic search within a single file using local embeddings |

The `search-in-file` tool uses a locally running embedding model (`all-MiniLM-L6-v2-q`) via [LangChain4j](https://docs.langchain4j.dev/) to split and embed file content, enabling semantic similarity search without any external service.

### Authentication Tools

| Tool | Description |
| :--- | :--- |
| `check-for-login` | Check whether the current user already has Nextcloud credentials stored |
| `initiate-login` | Start the Nextcloud Login Flow V2 and wait for the user to authorize the server |
| `delete-login` | Stopps on-going login processes and deletes existing Nextcloud credentials. Relogin with `initiate-login` |

When no credentials are present, the AI can invoke `initiate-login` to obtain a login URL that the user opens in a browser. Upon authorization, the server stores the resulting app password automatically.

### Configuration Tools

| Tool | Description |
| :--- | :--- |
| `config-ui` | Return an MCP App (embedded web UI) for managing the user's access restrictions |
| `set-access-config` | Programmatically update the user's file access configuration |

The `config-ui` tool returns an interactive HTML application (built with Bootstrap) that allows users to configure which folders, file patterns, and content types are accessible to the AI.

> **Note:** The configuration UI is implemented but not yet fully integrated into the access control enforcement. The `rootFolder` and `filePatterns` fields in `accessConfig` are stored correctly, but filtering on `filePatterns` and hidden-file exclusion are not yet fully applied in all file listing and search operations. This is a known limitation that will be addressed in a future release.

## MCP Resources

| Resource URI | Description |
| :--- | :--- |
| `ui://config` | HTML MCP App for the security configuration UI |

## Authentication & Security

### OIDC Flow

This mcp server exposed a standard compliant `/.well-known/oauth-protected-resource` endpoint to point to the OIDC identity provider as well as the correct resources metadata pointing to when the `/mcp` endpoint is called without proper authentication (controlled by Quarkus property `quarkus.oidc.resource-metadata.enabled`). If the OIDC Identity Provider, however, does not support all features required (esp. RFC 7591 Dynamic Client Registration), some MCP Client fall back to the MCP server for the authentication process. The server exposes three endpoints that proxy requests to the configured identity provider:

| Endpoint | Description |
| :--- | :--- |
| `POST /register` | RFC 7591 dynamic client registration — always returns the pre-configured `client_id` and `client_secret` |
| `GET /authorize` | Redirects to the identity provider's authorization endpoint |
| `POST /token` | Proxies token requests to the identity provider's token endpoint |

All `/mcp/*` endpoints require a valid OIDC bearer token. The server is configured as an OIDC `service` application type, and resource metadata is published at:

| Endpoint | Description |
| :--- | :--- |
| `GET /.well-known/oauth-protected-resource` | RFC 9728 resource metadata |
| `GET /.well-known/oauth-protected-resource/mcp` | MCP-specific resource metadata |

### Per-User Access Control

Each user can configure an `accessConfig` that restricts what the AI can access:

* **`rootFolder`** — Confines all file operations to a specific Nextcloud folder subtree.
* **`filePatterns`** — Glob patterns (e.g. `*.md`, `*.txt`) to restrict which files are visible.
* **`textContent`** / **`imageContent`** / **`audioContent`** — Toggle whether each content category is accessible.

> **Known limitation:** The `filePatterns` filtering and content-type restrictions are stored and configurable through the UI, but enforcement is not yet uniformly applied across all file listing, search, and read operations. Currently only explicit content-type filtering is partially enforced.

## Running

### Development Mode

```shell
./mvnw quarkus:dev
```

The server starts on `http://localhost:8080`. The Quarkus Dev UI is available at `http://localhost:8080/q/dev/`.

### Packaging

```shell
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar
```

### Über-JAR

```shell
./mvnw package -Dquarkus.package.jar.type=uber-jar
java -jar target/*-runner.jar
```

### Native Executable

```shell
# Requires GraalVM
./mvnw package -Dnative

# Or using a container build (no local GraalVM required)
./mvnw package -Dnative -Dquarkus.native.container-build=true

./target/nextcloud-mcp-1.0.0-SNAPSHOT-runner
```

## Container

Pre-built Dockerfiles are available for all packaging variants under `src/main/docker/`.

### JVM Container

```shell
./mvnw package
docker build -f src/main/docker/Dockerfile.jvm -t nextcloud-mcp:jvm .
docker run -i --rm -p 8080:8080 \
  -e QUARKUS_OIDC_AUTH_SERVER_URL=https://idp.example.com/realms/myrealm \
  -e QUARKUS_OIDC_CLIENT_ID=nextcloud-mcp \
  -e QUARKUS_OIDC_CREDENTIALS_SECRET=secret \
  -e APP_ROOT_URL=https://mcp.example.com \
  -e NEXTCLOUD_URL=https://cloud.example.com \
  -v /data/users.json:/deployments/users.json \
  nextcloud-mcp:jvm
```

### Native Container

```shell
./mvnw package -Dnative -Dquarkus.native.container-build=true
docker build -f src/main/docker/Dockerfile.native -t nextcloud-mcp:native .
docker run -i --rm -p 8080:8080 \
  -e QUARKUS_OIDC_AUTH_SERVER_URL=... \
  nextcloud-mcp:native
```

## Testing

Integration tests require a live Nextcloud instance. Configure it via environment variables before running:

```shell
export NEXTCLOUD_URL=https://cloud.example.com
export NEXTCLOUD_USER=testuser
export NEXTCLOUD_PASSWORD=testpassword

./mvnw verify -DskipITs=false
```

Unit tests run without external dependencies:

```shell
./mvnw test
```

## Known Limitations and Missing Features

* **File pattern filtering** (`filePatterns` in access config) is stored but not yet enforced uniformly across all file operations.
* **Hidden file exclusion** in directory listings and revision views is not fully implemented.
* **File revision names** — The Nextcloud WebDAV API returns only a file ID (not the original filename) in version paths. Additional metadata requests would be required to resolve human-readable names.
* **Full-text search** — Some Nextcloud search operations may not work depending on the server configuration and enabled apps.
* **Content type visibility** — The list of content types served as text (vs. blob) is currently hardcoded and not configurable per user.
* **Security UI** — The configuration UI is functional for saving settings but enforcement of all configured restrictions is incomplete (see [Per-User Access Control](#per-user-access-control)).

## Technology Stack

| Component | Technology |
| :--- | :--- |
| Runtime | Quarkus 3.34.2 on Java 25 |
| MCP Server | quarkus-mcp-server-http 1.11.0 |
| Authentication | Quarkus OIDC |
| WebDAV Client | Sardine 5.13 |
| Embeddings | LangChain4j + all-MiniLM-L6-v2-q |
| Diff / Patch | java-diff-utils 4.16 |
| Content Detection | Apache Tika (quarkus-tika) |
| Frontend | Bootstrap 5.3.8, MCP ext-apps 1.5.0 |
| Templating | Quarkus Qute |
