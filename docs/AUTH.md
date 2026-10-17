# Obtaining and diagnosing a VK user API token

## Choose the right application/token

You need a **user access token for VK's social API**, belonging to the personal wall owner, with the permissions and application eligibility required by the methods you use. An app/service key, community token, client secret, authorization code, ID token, or successful VK ID sign-in is not proof that `wall.get` / `wall.delete` will work. Do not paste an entire redirect URL into `VK_ACCESS_TOKEN`.

The Mini App documentation you linked concerns a VK application registered in VK's developer dashboard. A website at web.com can host its HTTPS frontend, but does not itself issue VK tokens. If you meant vk.com, use your own VK application registration there. No client secret is needed in this helper.

## Mini App route supplied here

1. In VK's developer dashboard create/configure your own VK Mini App and note its numeric **application ID**. Configure the HTTPS frontend URL in its platform/settings section. Use your own app, not an app ID copied from a tutorial.
2. Prepare the helper locally (Node/npm required only for this helper, not Kotlin):

   ```powershell
   cd auth-helper
   npm install
   npm run prepare-bridge
   ```

   This pins the official `@vkontakte/vk-bridge` package to 3.0.2 and copies its browser bundle locally. Upload **index.html and vk-bridge.min.js** together to your HTTPS frontend. Keep the page free of analytics and unrelated scripts: any script running there could read the token. Don't upload node_modules.
3. Open your Mini App **inside VK** through its registered launch link, signed in as the owner of the wall. Opening the HTTPS page directly does not provide the VK Bridge host context. Ensure your account can launch the app in its current development/publication state.
4. Click “Request user API token.” The helper calls `VKWebAppInit`, then `VKWebAppGetAuthToken` with your app ID and `wall,photos,video,docs`. Review VK's consent screen. Application policy/platform restrictions can still prevent scopes or methods; do not assume approval is guaranteed.
5. Copy only the returned token into the local PowerShell `Read-Host -MaskInput` prompt in README.md. Clear the helper field afterwards. The helper neither stores the token nor sends it to a custom backend.
6. Run `doctor`. Check that its printed user ID is yours and that `wall.get` succeeds. Inspect failures individually. Test `archive` without deletion and inspect the files before enabling destructive mode.

Do not request `audio` in this Bridge helper: the official Bridge personal scope union includes wall/photos/video/docs but omits audio. The Kotlin tool tries `audio.get` and direct audio attachments, reports denial/missing URLs, and keeps affected posts. If audio is essential and your app is not entitled to access it, this tool cannot promise a complete export or safe deletion of those posts.

## Diagnose failures

| Result | Meaning / next step |
| --- | --- |
| `users.get` fails / VK error 5 | Expired, revoked, malformed or inappropriate token. Obtain a fresh user API token through your app. Copy its value only; no quotes, whitespace, URL fragment, or `access_token=` prefix. |
| Wrong user ID | Stop. Sign into the intended account and authorize again. The destructive command must use this same positive user ID. |
| `account.getAppPermissions` succeeds | Its numeric mask is a diagnostic, not a guarantee that every API method is permitted for the app. Read probes remain necessary. |
| Error 7 or 15 | Permission or object access denied. Check the requested/granted scopes, app eligibility and ownership; adding scope text locally cannot grant access. |
| Error 27 or 28 | Token type does not fit the method (for example, community or service token). Obtain a user token. |
| Wall read succeeds, deletion denied | Read and deletion authorization differ. The tool does not test deletion on a dummy or real post. Check the app's eligibility for `wall.delete`; archived data remains available. |
| Audio denied / no direct media URL | API/app/media restriction. A player URL, thumbnail or metadata does not count as media backup; the post stays on VK. |
| Error 14 or 17 | Complete CAPTCHA/account validation in VK, then retry. The tool does not automatically follow validation URLs or bypass challenges. |
| Error 6 / 9 / 29 | Rate limiting. Calls are paced; error 6 is retried for reads only. Wait before retrying. |
| Bridge failure / no token | Check the numeric app ID, registered launch URL, running inside VK, allowed scopes, development access, and current VK app requirements. The helper deliberately avoids logging full error objects. |

An expiring token may interrupt a long archive; obtain a fresh one and rerun into a new run directory. Never publish tokens, place them in CLI arguments or commit them. Remove `VK_ACCESS_TOKEN` from your shell after use; clearing a field/environment variable does not revoke a token in VK.

## Primary sources and verification boundary

Checked 2026-10-17. The developer documentation URLs below could not be rendered by the research browser in this session, so implementation details were cross-checked against VK's official repositories. These sources describe interfaces, not a guarantee that a newly registered app receives every permission. Live app approval, granted scopes, token lifetime and actual method access must be checked in your account.

* Your app settings reference: https://dev.vk.ru/ru/mini-apps/settings/general/information
* Your API reference: https://dev.vk.ru/ru/reference
* Official Bridge scopes: https://github.com/VKCOM/vk-bridge/blob/master/packages/core/src/types/data.ts
* Official Bridge package: https://github.com/VKCOM/vk-bridge/blob/master/packages/core/package.json
* Wall methods (including user-token requirement for deletion): https://github.com/VKCOM/vk-api-schema/blob/master/wall/methods.json
* API schema version and structure: https://github.com/VKCOM/vk-api-schema
* Video object/files schema: https://github.com/VKCOM/vk-api-schema/blob/master/video/objects.json
