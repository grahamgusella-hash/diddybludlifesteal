# Diddyblud Lifesteal — Level Shop (Paper 0.8.57)

## What it does
- Every **30 minutes online** awards **1 spendable level** (configurable).
- Players can deliberately exchange **$10,000,000 in-game for 1 level** using `/levels buy` (configurable). Money is deducted when they use the command, not automatically.
- Players spend levels on **rank labels** or **in-game money/shard rewards** on the website. After the one-time in-game verification code and level balance are validated, the Paper plugin **automatically grants ranks**, without staff approval. This is **not a real-money payment system**.
- The website shows global top-ten **money, playtime, and hearts leaderboards**, including offline players. Leaderboards follow the game's existing /leaderboard reset baselines.
- Player level balances and partial minutes are persisted to `plugins/DiddybludLifesteal/levels.yml`.
- A one-time code generated **in Minecraft** protects website purchases from username impersonation. Code expires in **5 minutes** and must be used while online.

## Installation
1. Download the latest successful **Build Paper plugin** artifact from the GitHub Actions page.
2. Unzip the JAR, remove the previous DiddybludLifesteal JAR from FalixNodes `plugins/` and upload `DiddybludLifesteal-0.8.57-paper.jar`.
3. Keep your previous `server-data.yml` and other plugin data; do not delete the plugin folder.
4. In the **private server** `plugins/DiddybludLifesteal/config.yml`, add the **leveling** section from `paper/src/main/resources/config.yml` below. Existing config files are not automatically overwritten when updating the JAR.
5. Keep `community-bridge.enabled: true` and the same private `community-bridge.secret` as `BRIDGE_SECRET` in Render. Do not commit or share it.
6. Restart the Minecraft server. Your Render web service should automatically redeploy from changes to the website repository if auto-deploy is enabled.
7. Verify `/levels`, `/levels buy 1`, `/levels code`, then submit the code and reward using the website.

## Configuration
```yaml
leveling:
  playtime-minutes-per-level: 30
  money-per-level: 10000000
  offers:
    starter:
      name: Starter Rank
      type: rank
      value: starter
      cost: 10
    champion:
      name: Champion Rank
      type: rank
      value: champion
      cost: 30
    legend:
      name: Legend Rank
      type: rank
      value: legend
      cost: 60
    bonusmoney:
      name: 500,000 in-game dollars
      type: money
      value: "500000"
      cost: 2
    bonusshards:
      name: 100 Shards
      type: shards
      value: "100"
      cost: 3
```

Edit these costs, add or remove offers, and restart Minecraft. For `rank` rewards, `value` must match a rank ID. Starter, Champion, Legend cosmetic ranks are created if absent; operators can customize rank labels with `/rank create`. These are **plugin rank labels**, not Discord roles or permission groups. The website displays the offers received from the server, not a separate hardcoded catalog.

## Player commands
- `/levels`: view spendable levels and rates
- `/levels buy`: spend in-game money to acquire one level
- `/levels buy 5`: spend 5 times the configured rate to acquire five levels
- `/levels code`: generate a short-lived web redemption code

## Limitations
- Code is in the repositories but must be **built and installed** to work; neither a deployment nor a successful Minecraft test has been independently confirmed yet.
- The existing Render service uses a JSON data file and in-memory workflow. It may lose queued requests on restart if hosted without persistent storage. The bridge now requires acknowledgments before dequeueing; for a production economy, use durable storage and a transaction log.
- Level-shop requests are queued for Minecraft and the website reports **queued**, not **completed**. Check in-game confirmation; an invalid/expired code or insufficient levels rejects the request without deducting levels.
- This version awards levels for playtime and manual money conversion, not passively for having money.
- No real-currency checkout, Discord-role granting, or permission elevation is implemented. If real payments are wanted, integrate a payment provider with verified webhooks and a real database first.
