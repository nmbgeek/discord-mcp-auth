<div align="center">
  <img src="assets/img/Discord_MCP_full_logo.svg" width="60%" alt="DeepSeek-V3" />
</div>
<hr>
<div align="center" style="line-height: 1;">
    <a href="https://github.com/modelcontextprotocol/servers" target="_blank" style="margin: 2px;">
        <img alt="MCP Server" src="https://badge.mcpx.dev?type=server" style="display: inline-block; vertical-align: middle;"/>
    </a>
    <a href="https://discord.gg/5Uvxe5jteM" target="_blank" style="margin: 2px;">
        <img alt="Discord" src="https://img.shields.io/discord/936242526120194108?color=7389D8&label&logo=discord&logoColor=ffffff" style="display: inline-block; vertical-align: middle;"/>
    </a>
    <a href="https://github.com/nmbgeek/discord-mcp-auth/blob/main/LICENSE" target="_blank" style="margin: 2px;">
        <img alt="MIT License" src="https://img.shields.io/github/license/nmbgeek/discord-mcp-auth" style="display: inline-block; vertical-align: middle;"/>
    </a>
</div>


## 📖 Description

A [Model Context Protocol (MCP)](https://modelcontextprotocol.io/introduction) server for the Discord API using [(JDA)](https://jda.wiki/),
designed to integrate Discord bots with MCP-compatible applications such as Claude, ChatGPT etc. It allows AI assistants to interact with 
Discord by managing channels, sending messages, and retrieving server information. Ideal for building powerful Discord automation and AI-driven workflows.


## Discord bot setup and permissions

Create an application in the [Discord Developer Portal](https://discord.com/developers/applications),
open its **Bot** page, and copy or reset the bot token. Use that token as `DISCORD_TOKEN`,
not the application's client secret. Keep `MCP_AUTH_TOKEN` as a separate secret for your agents.

### Enable the required intents

On **Bot > Privileged Gateway Intents**, configure:

| Intent | Setting for this server |
| --- | --- |
| Server Members Intent | **On.** The server requests this intent at startup for member-related tools. Leaving it off can prevent the bot from connecting. |
| Message Content Intent | **On for reading other users' messages and attachment metadata.** Otherwise Discord can return empty content, except for messages such as the bot's own messages, DMs with the bot, and messages mentioning it. |
| Presence Intent | **Off.** The current tools do not require it. |

Intents are separate from the bot role's permissions. The message-reading tools fetch history
through Discord's HTTP API, so the application's Message Content setting matters even though
this server does not subscribe to that intent on the Gateway. See Discord's
[privileged intents and HTTP restrictions](https://docs.discord.com/developers/events/gateway#http-restrictions).
Follow any intent approval requirements shown in the Developer Portal, then restart the server.

### Install the bot in your server

1. Open **Installation**, enable **Guild Install**, and choose **Discord Provided Link**.
2. Under **Default Install Settings > Guild Install**, add the `bot` scope and select the
   permissions below. This MCP server does not register slash commands, so it does not need
   the `applications.commands` scope. User Install alone is not sufficient.
3. Open the install link, choose **Add to server**, select your server, and authorize it.
   Your Discord account needs permission to manage that server.
4. In Discord, check **Server Settings > Roles** and the target channels' permission overrides.
   Install settings define the requested permissions; the bot's actual server role and channel
   overrides control access after installation.

Discord's [installation guide](https://docs.discord.com/developers/quick-start/getting-started#adding-scopes-and-bot-permissions)
shows where these settings are located. Use the permissions for this project below rather than
copying the sample bot's selection.

### Start with reading and posting

Grant **View Channels**, **Read Message History**, and **Send Messages** in the channels your
agents should use. For a read-only bot, omit Send Messages. Add **Add Reactions** if needed,
and **Embed Links** if you want automatic previews for posted links.

Do not grant **Administrator**. It bypasses channel restrictions. Every agent with the MCP
bearer token can invoke every exposed tool, so Discord permissions are the limit on what those
tools can do. See Discord's [permission reference](https://docs.discord.com/developers/topics/permissions).

### Add permissions for optional tools

| Tool or capability | Additional bot permissions |
| --- | --- |
| Delete other users' messages | Manage Messages. Editing messages is limited to the bot's own messages. |
| Create or edit channels, categories, voice channels, and forums | Manage Channels |
| Create, edit, assign, or remove roles; edit channel permission overrides | Manage Roles |
| Create, list, or delete webhooks | Manage Webhooks |
| Kick members | Kick Members |
| Ban, unban, or list bans | Ban Members |
| Set or remove timeouts | Moderate Members |
| Change other members' nicknames | Manage Nicknames |
| Move or disconnect voice members | Move Members; moving also requires Connect in the destination channel |
| Server-mute or deafen voice members | Mute Members and/or Deafen Members |
| Create invites | Create Invite |
| List all server invites | Manage Server |
| Revoke invites | Manage Channels for the invite's channel, or Manage Server |
| Create a forum post | Send Messages in the parent forum; creating a post does not require Create Public Threads |
| Send messages inside threads or forum posts | Send Messages in Threads |
| Lock, archive, pin, or otherwise manage other users' forum posts | Manage Threads |
| Create emojis | Create Expressions |
| Edit or delete other users' emojis | Manage Expressions |
| Create scheduled events | Create Events |
| Edit or delete other users' scheduled events | Manage Events |

For [scheduled voice events](https://docs.discord.com/developers/resources/guild-scheduled-event#guild-scheduled-event-permissions-requirements),
also grant View Channels and Connect in the associated channel. Stage events additionally
require Manage Channels, Mute Members, and Move Members there. For
[emoji management](https://docs.discord.com/developers/resources/emoji#create-guild-emoji),
Create Expressions covers creating emojis and editing or deleting the bot's own emojis.

Place the bot's highest role **above the roles and members it needs to manage**. Discord's
role hierarchy still blocks actions against equal or higher roles and the server owner.
Limit View Channels to the channels agents should access. `DISCORD_GUILD_ID` only supplies
a default server ID; it does not restrict access to other servers the bot has joined.

If a tool reports **Missing Access** or **Missing Permissions**, check the target channel's
overrides and role hierarchy as well as the bot role. Empty message content usually points
to Message Content Intent. Private-message tools operate on conversations with the bot,
not users' private conversations with one another, and recipients' privacy settings can block DMs.

## 🔬 Installation

### ► 🐳 Docker Installation (Recommended)

> [!NOTE]
> Docker installation is required. Full instructions can be found on [docker.com](https://www.docker.com/products/docker-desktop/).

#### 1) Set local env variables
```bash
export DISCORD_TOKEN="YOUR_DISCORD_BOT_TOKEN"
export DISCORD_GUILD_ID="OPTIONAL_DEFAULT_SERVER_ID"
export SPRING_PROFILES_ACTIVE=http
export MCP_AUTH_TOKEN="$(openssl rand -hex 32)"
```

> [!IMPORTANT]
> Complete [Discord bot setup and permissions](#discord-bot-setup-and-permissions) before starting the container.

> [!TIP]
> The `DISCORD_GUILD_ID` env variable is optional.
> 
> When provided, it sets a default Discord server ID so any tool that accepts a `guildId` parameter can omit it.

#### 2) Run the Docker container
Pull the published image, then start the container:

```bash
docker pull nmbgeek/discord-mcp-auth:latest
docker run -d -i \
  --name discord-mcp-auth \
  --restart unless-stopped \
  -p 127.0.0.1:8085:8085 \
  -e SPRING_PROFILES_ACTIVE \
  -e MCP_AUTH_TOKEN \
  -e DISCORD_TOKEN \
  -e DISCORD_GUILD_ID \
  nmbgeek/discord-mcp-auth:latest
```

Default MCP endpoint URL (HTTP profile): `http://localhost:8085/mcp`

To build your checkout instead, run `docker build -t nmbgeek/discord-mcp-auth:latest .`.
For production, pin the image by digest. Find it with
`docker buildx imagetools inspect nmbgeek/discord-mcp-auth:latest`, then use
`nmbgeek/discord-mcp-auth@sha256:<digest>` as the image. Update the digest when adopting a release.

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        🐋 Docker Compose Installation
    </summary>

#### 1) Clone the repository
```bash
git clone https://github.com/nmbgeek/discord-mcp-auth
```

#### 2) Go to the project directory
```bash
cd discord-mcp-auth
```

#### 3) Create local runtime env
```bash
cat > .env <<EOF
SPRING_PROFILES_ACTIVE=http
MCP_AUTH_TOKEN=$(openssl rand -hex 32)
DISCORD_TOKEN=<YOUR_DISCORD_BOT_TOKEN>
DISCORD_GUILD_ID=<OPTIONAL_DEFAULT_SERVER_ID>
EOF
chmod 600 .env
```

#### 4) Start one shared MCP server container
```bash
docker compose up -d --build
```

#### 5) Verify
```bash
docker ps --filter name=discord-mcp-auth
curl -fsS http://localhost:8085/actuator/health
```

> [!TIP]
> You do not need to set `LOGGING_PATTERN_CONSOLE` manually.
> Logging is configured automatically for both `http` and legacy `stdio` modes.

Default MCP endpoint URL (HTTP profile): `http://localhost:8085/mcp`

Health endpoint (Actuator): `http://localhost:8085/actuator/health`

</details>

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        🔧 Manual Installation
    </summary>

#### 1) Clone the repository
```bash
git clone https://github.com/nmbgeek/discord-mcp-auth
```

#### 2) Build the project

> NOTE: Maven installation is required to use the mvn command. Full instructions can be found [here](https://www.baeldung.com/install-maven-on-windows-linux-mac).

```bash
cd discord-mcp-auth
mvn clean package # The jar file will be available in the /target directory
```

#### 3) Configure AI client
Run the JAR as a long-running server:

```bash
DISCORD_TOKEN=<YOUR_DISCORD_BOT_TOKEN> \
DISCORD_GUILD_ID=<OPTIONAL_DEFAULT_SERVER_ID> \
SPRING_PROFILES_ACTIVE=http \
MCP_AUTH_TOKEN="$MCP_AUTH_TOKEN" \
java -jar /absolute/path/to/discord-mcp-1.0.0.jar
```

> NOTE: The `DISCORD_GUILD_ID` environment variable is optional. When provided, it sets a default Discord server ID so any tool that accepts a `guildId` parameter can omit it.

Default MCP endpoint URL (HTTP profile): `http://localhost:8085/mcp`

</details>

## HTTP authentication and remote access

HTTP mode supports a shared bearer token and optional OAuth browser login. Both protect MCP
requests with an `Authorization: Bearer ...` header. To use a shared token:
Set `MCP_AUTH_TOKEN` to a separate random secret generated with `openssl rand -hex 32`.
Save it in your server's environment or `.env` and in each agent's secret store.
Do not give agents your Discord bot token.

With OAuth disabled, the server refuses to start in HTTP mode if the token is missing.
Any configured shared token must be at least 32 characters and use the bearer-token format.
Missing or incorrect credentials receive
`401 Unauthorized` with a `WWW-Authenticate: Bearer` challenge. Tokens in URLs, cookies, or MCP
session IDs do not authorize requests. GET and HEAD requests to `/actuator/health` remain public
for Docker probes, with health details and components hidden.

Actuator endpoint access defaults to `none`, with only `health` enabled for read access.
Changing `management.endpoints.web.exposure.include` to `*` alone does not enable diagnostic
endpoints. Keep `env`, `configprops`, and heap dumps disabled in deployed instances. Environment
and configuration values are explicitly masked with `show-values=never`; overriding endpoint
access and masking settings can expose runtime secrets to callers with access.

Authentication protects HTTP routes, including a custom MCP endpoint path. OAuth mode also
exposes its discovery, registration, token, and browser login endpoints. Local stdio
connections need no bearer token. For trusted local development only, you can explicitly set
`MCP_AUTH_ENABLED=false`; the server logs a warning when this is disabled.

For cloud agents:

1. Run the server with the `http` profile and `MCP_AUTH_TOKEN` set.
2. Put an HTTPS reverse proxy or tunnel in front of port 8085. Forward the `Authorization`
   header and support streaming responses without buffering. The public URL should look like
   `https://discord-mcp.example.com/mcp`.
3. Configure the agent to send the bearer header on every request, including session requests.
   Use the same secret stored on the server.

Docker publishes port 8085 on `127.0.0.1` by default so a proxy on the host can reach it.
For a proxy in another container, connect both services to a shared Docker network and use
`http://discord-mcp-auth:8085` as the upstream. If you need a different published interface,
set `MCP_BIND_ADDRESS` in Compose and restrict access to that port. Use HTTPS for public access;
the application's port serves plain HTTP.

A shared token grants access to all tools and Discord permissions granted to the bot. Clients
using this mode must support a custom bearer token or Authorization header. For browser login
in OAuth clients, enable the optional OAuth mode below.

To rotate the secret, replace `MCP_AUTH_TOKEN` on the server, recreate the container with
`docker compose up -d --force-recreate`, and update the agent secrets. Restarting closes existing
sessions, so clients must reconnect. Keep the token out of source control and request logs.

Verify that unauthenticated requests return `401`:

```bash
curl -i https://discord-mcp.example.com/mcp
```

Then test an authenticated MCP initialization with the token loaded into your shell environment:

```bash
curl -i https://discord-mcp.example.com/mcp \
  -H "Authorization: Bearer $MCP_AUTH_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  --data '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"auth-check","version":"1.0"}}}'
```

A successful initialization returns `200` and server capabilities. The tests run this handshake,
list tools, and call a harmless test tool through the real HTTP transport without a Discord connection.
Run them with `mvn test`.

## OAuth browser login and persistent authorization

OAuth is optional and runs inside the Java server. It uses Spring Security for browser login,
consent, authorization codes, PKCE, token exchange and revocation. No separate identity provider
or Node.js service is required. The login is a single owner account for your instance, separate
from your Discord account.

Add these values to the server's `.env` file:

```dotenv
MCP_AUTH_ENABLED=true
MCP_OAUTH_ENABLED=true
MCP_OAUTH_ISSUER=https://discord-mcp.example.com
MCP_OAUTH_USERNAME=your-login-name
MCP_OAUTH_PASSWORD='your-long-plaintext-password'
MCP_OAUTH_ACCESS_TOKEN_SECONDS=900
MCP_OAUTH_REFRESH_TOKEN_DAYS=90
```

Use your real public HTTPS origin, with no trailing slash or path. Choose a password of 16 to
256 characters. The password is plain text in the environment; you do not need to generate a
password hash. The server derives a password verifier internally. Single-quote values in a
Compose `.env` file to preserve literal `$` and `#` characters, and protect the file with
`chmod 600 .env`. Public signup and password reset endpoints are not provided.

Keep `MCP_AUTH_TOKEN` configured if unattended agents also use a static token. It can be empty
when OAuth is enabled. OAuth cannot be combined with `MCP_AUTH_ENABLED=false`.

Run `docker compose up -d --build` after configuring the values. Compose mounts the `oauth-data`
named volume at `/app/data/oauth`. For `docker run`, also use
`--env-file .env -v discord-mcp-oauth:/app/data/oauth -e MCP_OAUTH_DATA_DIR=/app/data/oauth`.
For a manual Java deployment, point `MCP_OAUTH_DATA_DIR` at a persistent directory.

The reverse proxy must forward the **entire origin**, including `/login`, `/oauth2/*` and
`/.well-known/*`, as well as `/mcp`. Preserve the public `Host` header, keep HTTPS on the public
side, and disable response buffering for MCP. This mode requires the application at the origin
root, without a servlet context path. A custom MCP endpoint path is supported.

For an OAuth client, configure the server URL as `https://discord-mcp.example.com/mcp` and choose
OAuth authentication. The server advertises dynamic client registration, so compatible clients
can obtain their own client ID automatically. Register with `token_endpoint_auth_method=none`
for a public client or `client_secret_basic` for a confidential client. Registration always
requires PKCE and user consent. HTTPS callbacks and HTTP loopback callbacks are supported;
client metadata URLs are not fetched. Choose dynamic registration in clients that offer both
that and Client ID Metadata Documents. CIMD is not implemented.

In Codex, add the URL without static credentials and then log in:

```bash
codex mcp add discord_mcp --url https://discord-mcp.example.com/mcp
codex mcp login discord_mcp
```

Remove an existing `http_headers.Authorization` or `bearer_token_env_var` setting for that client
before switching it to OAuth. In ChatGPT's custom MCP connection setup, use the same server URL
and OAuth with dynamic registration. The browser opens this instance's login page, where you
enter `MCP_OAUTH_USERNAME` and `MCP_OAUTH_PASSWORD`, then approve the client's access.

Request `mcp offline_access` scopes. `mcp` authorizes every tool the Discord bot can perform;
there are no per-tool permission tiers. `offline_access` allows refresh tokens. A login cookie
alone cannot call MCP, and OAuth access tokens do not authorize Actuator diagnostics.

Access tokens expire after 15 minutes by default. Refresh tokens expire after 90 days without
successful renewal. Each refresh returns a new refresh token with a new 90-day lifetime, so an
active client can remain connected long term. The client must save the replacement token and
serialize refresh requests. Reusing an old refresh token revokes that authorization grant,
including its current access and refresh tokens. There is no retry grace period. A lost token
response or duplicate refresh therefore requires a new login.

Client registrations, grants and refresh-token replay history survive container restarts and
image upgrades when the data volume is retained. Browser sessions and MCP transport sessions
are recreated after a restart; clients can use their stored refresh token to reconnect. This
embedded database supports one server process, not multiple replicas. OAuth storage uses POSIX
file permissions and requires Linux or macOS, including the provided Linux container. Treat its directory and
backups as secrets because they contain bearer credentials. The directory is owner-only and
excluded from Git and Docker build context. Do not use `docker compose down -v` unless you intend
to erase authorization state and require clients to register and log in again.

Changing the owner username, password or public resource URL revokes all OAuth grants on the
next startup. Clients can revoke a token at the advertised `/oauth2/revoke` endpoint. A shared
`MCP_AUTH_TOKEN` remains valid until you rotate that value separately.

Login attempts are limited to 10 per minute per direct peer, registration to 20, and token or
revocation requests to 120. Global limits also apply. Behind a reverse proxy, callers may share
that peer limit. Registration bodies are limited to 8 KiB, with at most five redirect URIs and
1,000 stored clients. Abandoned registrations with no consent or grant expire after 24 hours;
expired token history is cleaned up during operation. Apply additional request limits at your
public proxy if needed.

## 🔗 Connections

Use your public HTTPS URL for remote agents. The HTTP examples below require the server's bearer
token. Header configuration varies by client; store the token in the client's secret store where supported.

### ► 🗞️ Default config.json Connection

Recommended (HTTP singleton mode):
```json
{
  "mcpServers": {
    "discord-mcp": {
      "url": "http://localhost:8085/mcp",
      "headers": {
        "Authorization": "Bearer <YOUR_MCP_AUTH_TOKEN>"
      }
    }
  }
}
```

Legacy mode (stdio, starts a new process/container per client session):
```json
{
  "mcpServers": {
    "discord-mcp": {
      "command": "docker",
      "args": [
        "run",
        "--rm",
        "-i",
        "-e",
        "DISCORD_TOKEN=<YOUR_DISCORD_BOT_TOKEN>",
        "-e",
        "DISCORD_GUILD_ID=<OPTIONAL_DEFAULT_SERVER_ID>",
        "nmbgeek/discord-mcp-auth:latest"
      ]
    }
  }
}
```

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        ⌨️ Claude Code Connection
    </summary>

Recommended (HTTP singleton mode):
```bash
claude mcp add discord-mcp --transport http http://localhost:8085/mcp \
  --header "Authorization: Bearer $MCP_AUTH_TOKEN"
```

Legacy mode (stdio, starts a new process/container per client session):
```bash
claude mcp add discord-mcp -- docker run --rm -i -e DISCORD_TOKEN=<YOUR_DISCORD_BOT_TOKEN> -e DISCORD_GUILD_ID=<OPTIONAL_DEFAULT_SERVER_ID> nmbgeek/discord-mcp-auth:latest
```

</details>

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        🤖 Codex CLI Connection
    </summary>

```bash
codex mcp add discord-mcp --url https://discord-mcp.example.com/mcp \
  --bearer-token-env-var MCP_AUTH_TOKEN
codex mcp list
```

Set `MCP_AUTH_TOKEN` in the environment that launches Codex. Codex reads the secret from that
variable instead of storing the token directly in its MCP configuration.

</details>

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        🦞 OpenClaw Connection
    </summary>

These examples configure the URL and transport. Also configure OpenClaw to send
`Authorization: Bearer <YOUR_MCP_AUTH_TOKEN>` using the header or secret settings supported by
your installed version. A URL-only configuration receives `401` when authentication is enabled.

Run this command:
```bash
openclaw mcp set discord-mcp '{"url":"http://localhost:8085/mcp","transport":"streamable-http"}'
openclaw mcp list
```

OR

Pasting the following configuration into your OpenClaw `~/.openclaw/config.json` file:
```json
{
  "mcp": {
    "servers": {
      "discord-mcp": {
        "url": "http://localhost:8085/mcp",
        "transport": "streamable-http"
      }
    }
  }
}
```

</details>

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        🖲 Cursor Connection
    </summary>

Go to: `Settings` -> `Cursor Settings` -> `MCP` -> `Add new global MCP server`

Pasting the following configuration into your Cursor `~/.cursor/mcp.json` file is the recommended approach. You may also install in a specific project by creating `.cursor/mcp.json` in your project folder. See [Cursor MCP docs](https://docs.cursor.com/context/model-context-protocol) for more info.
```json
{
  "mcpServers": {
    "discord-mcp": {
      "url": "http://localhost:8085/mcp",
      "headers": {
        "Authorization": "Bearer <YOUR_MCP_AUTH_TOKEN>"
      }
    }
  }
}
```

</details>

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        🚀 n8n Connection
    </summary>

#### Connect in n8n
1. Open n8n and add an **MCP Client** node.
2. Choose **HTTP** or **Streamable HTTP** transport (depending on your n8n version/node options).
3. Set the server URL to: `http://localhost:8085/mcp`
4. Configure Header Auth with `Authorization` set to `Bearer <YOUR_MCP_AUTH_TOKEN>`.
5. Save the node and test the connection.
6. After connecting, you can use the available Discord tools exposed by `discord-mcp` inside your workflow.

#### Notes
- If n8n is running in Docker, `localhost` may point to the n8n container itself, not your host machine.
- In that case, use the Docker service name or another reachable host, for example: `http://discord-mcp-auth:8085/mcp`

</details>

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        🖥 Claude Desktop Connection
    </summary>



STDIO local config (Default, legacy):
> Past the following configuration into your Claude Desktop `claude_desktop_config.json` file.
```json
{
  "mcpServers": {
    "discord-mcp": {
      "command": "docker",
      "args": [
        "run",
        "--rm",
        "-i",
        "-e",
        "DISCORD_TOKEN=<YOUR_DISCORD_BOT_TOKEN>",
        "-e",
        "DISCORD_GUILD_ID=<OPTIONAL_DEFAULT_SERVER_ID>",
        "nmbgeek/discord-mcp-auth:latest"
      ]
    }
  }
}
```

Remote MCP Connector:
For a connector that requires OAuth, enable [OAuth browser login](#oauth-browser-login-and-persistent-authorization)
and expose the server through your public HTTPS origin.

1. Open Claude Desktop and go to `Settings` -> `Connectors`.
2. Add a custom connector and set MCP URL to your server endpoint (for example `https://<PUBLIC_HOST>/mcp`).
3. Choose OAuth and complete the instance's browser login and consent flow, or configure a bearer token if supported.

> Claude Desktop remote connectors are managed via Connectors UI (not `claude_desktop_config.json`).
> `http://localhost:8085/mcp` is reachable only from your machine. For Claude Desktop remote connectors, expose the endpoint with public HTTPS (for example tunnel/reverse proxy).

</details>


## 🛠️ Available Tools

#### Server Information
- [`get_server_info`](): Get detailed discord server information

#### User Management
- [`get_user_id_by_name`](): Get a Discord user's ID by username in a guild for ping usage `<@id>`
- [`send_private_message`](): Send a private message to a specific user
- [`edit_private_message`](): Edit a private message from a specific user
- [`delete_private_message`](): Delete a private message from a specific user
- [`read_private_messages`](): Read private message history from a specific user (includes attachment metadata, supports `count` 1-100 and optional cursor: `before` or `after` or `around`)

#### Message Management
- [`send_message`](): Send a message to a specific channel
- [`edit_message`](): Edit a message from a specific channel
- [`delete_message`](): Delete a message from a specific channel
- [`read_messages`](): Read message history from a specific channel (includes attachment metadata, supports `count` 1-100 and optional cursor: `before` or `after` or `around`)
- [`add_reaction`](): Add a reaction (emoji) to a specific message
- [`remove_reaction`](): Remove a specified reaction (emoji) from a message

#### Channel Management
- [`create_text_channel`](): Create a new text channel
- [`edit_text_channel`](): Edit settings of a text channel (name, topic, nsfw, slowmode, category, position)
- [`delete_channel`](): Delete a channel
- [`find_channel`](): Find a channel type and ID using name and server ID
- [`list_channels`](): List of all channels
- [`get_channel_info`](): Get detailed information about a channel
- [`move_channel`](): Move a channel to another category and/or change its position

#### Category Management
- [`create_category`](): Create a new category for channels
- [`edit_category`](): Edit a category (rename or move position)
- [`delete_category`](): Delete a category
- [`find_category`](): Find a category ID using name and server ID
- [`list_channels_in_category`](): List of channels in a specific category

#### Webhook Management
- [`create_webhook`](): Create a new webhook on a specific channel
- [`delete_webhook`](): Delete a webhook
- [`list_webhooks`](): List of webhooks on a specific channel
- [`send_webhook_message`](): Send a message via webhook

#### Role Management
- [`list_roles`](): Get a list of all roles on the server with their details
- [`create_role`](): Create a new role on the server
- [`edit_role`](): Modify an existing role's settings
- [`delete_role`](): Permanently delete a role from the server
- [`assign_role`](): Assign a role to a user
- [`remove_role`](): Remove a role from a user

#### Moderation and User Management
- [`kick_member`](): Kicks a member from the server
- [`ban_member`](): Bans a user from the server
- [`unban_member`](): Removes a ban from a user
- [`timeout_member`](): Disables communication for a member for a specified duration
- [`remove_timeout`](): Removes a timeout (unmute) from a member before it expires
- [`set_nickname`](): Changes a member's nickname on the server
- [`get_bans`](): Returns a list of banned users on the server with ban reasons

#### Voice & Stage Channel Management
- [`create_voice_channel`](): Create a new voice channel in a guild
- [`create_stage_channel`](): Create a new stage channel for audio events
- [`edit_voice_channel`](): Edit settings of a voice or stage channel (name, bitrate, user limit, region)
- [`move_member`](): Move a member to another voice channel
- [`disconnect_member`](): Disconnect a member from their current voice channel
- [`modify_voice_state`](): Server mute or deafen a member in voice channels

#### Scheduled Events Management
- [`create_guild_scheduled_event`](): Schedule a new event on the server (voice, stage, or external)
- [`edit_guild_scheduled_event`](): Modify event details or change its status (start, complete, cancel)
- [`delete_guild_scheduled_event`](): Permanently delete a scheduled event
- [`list_guild_scheduled_events`](): List all active and scheduled events on the server
- [`get_guild_scheduled_event_users`](): Get list of users interested in a scheduled event

#### Channel Permission Overwrites
- [`list_channel_permission_overwrites`](): List all permission overwrites for a channel with role/member breakdown
- [`upsert_role_channel_permissions`](): Create or update permission overwrite for a role on a channel
- [`upsert_member_channel_permissions`](): Create or update permission overwrite for a member on a channel
- [`delete_channel_permission_overwrite`](): Delete a permission overwrite for a role or member from a channel

#### Invite Management
- [`create_invite`](): Create a new invite link for a specific channel
- [`list_invites`](): List all active invites on the server with their statistics
- [`delete_invite`](): Delete (revoke) an invite so the link stops working
- [`get_invite_details`](): Get details about a specific invite (works for any public invite)

#### Forum Management
- [`create_forum_channel`](): Create a new forum channel
- [`edit_forum_channel`](): Edit settings of a forum channel (name, topic, nsfw, slowmode, category, position, default sort, default layout)
- [`list_forum_channels`](): List all forum channels in the server
- [`get_forum_channel_info`](): Get detailed information about a forum channel including tags and settings
- [`list_forum_tags`](): List all available tags in a forum channel
- [`create_forum_post`](): Create a new forum post (thread) with an initial message in a forum channel
- [`list_forum_posts`](): List active posts (threads) in a forum channel
- [`modify_forum_post`](): Modify a forum post: lock/unlock, archive/unarchive, pin/unpin, or change applied tags

#### Emoji Management
- [`list_emojis`](): List all custom emojis on the server
- [`get_emoji_details`](): Get detailed information about a specific custom emoji
- [`create_emoji`](): Upload a base64 PNG, JPEG, or GIF (max 256 KiB and 1024 pixels per side)
- [`edit_emoji`](): Edit an existing emoji's name or role restrictions
- [`delete_emoji`](): Permanently delete a custom emoji from the server

Emoji uploads accept raw base64 or a `data:image/png;base64,...`, `data:image/jpeg;base64,...`,
or `data:image/gif;base64,...` URI in `image`. The legacy `imageUrl` parameter is rejected.
Download images in your client and pass their bytes as base64. The server never fetches an
emoji URL, preventing access to internal services, cloud metadata, or local files through this tool.
Encoded size, decoded size, image type, and dimensions are checked before uploading to Discord.

>If `DISCORD_GUILD_ID` is set, the `guildId` parameter becomes optional for all tools above.

<hr>

A more detailed examples can be found in the [Wiki](https://github.com/SaseQ/discord-mcp/wiki).
